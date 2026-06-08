package com.zlimon.runemanager.push;

import com.zlimon.runemanager.RuneManagerApi;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;

/**
 * Captures the local player's 3D model and uploads it to RuneManager as the
 * account avatar — no third-party export plugin required.
 *
 * The snapshot is taken mid-activity: when the player starts an action animation
 * (chopping, attacking, casting, …) we capture the posed model a tick in, so the
 * avatar freezes mid-swing rather than standing idle. It re-captures on gear
 * swaps and settles back to an idle pose shortly after the action stops.
 * Re-captures are throttled so combat/skilling don't spam uploads. Geometry is
 * read on the client thread (RuneLite reuses those arrays) and serialised +
 * uploaded off-thread.
 */
@Slf4j
@Singleton
public class AvatarPushService
{
	/** Coalesce gear-swap bursts into a single capture once things settle. */
	private static final long DEBOUNCE_MS = 3000L;
	/** Wait ~1 tick into an action so the captured frame is mid-animation. */
	private static final long ACTION_DELAY_MS = 600L;
	/** Let an idle pose settle before capturing it after an action stops. */
	private static final long IDLE_SETTLE_MS = 1200L;
	/** Floor between uploads so repeated attacks/skilling don't spam the API. */
	private static final long MIN_UPLOAD_INTERVAL_MS = 15000L;
	/** Actor.getAnimation() sentinel for "no active animation". */
	private static final int IDLE_ANIMATION = -1;

	@Inject
	private Client client;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private RuneManagerApi api;

	private volatile boolean capturePending = false;
	private volatile long captureNotBefore = 0L;
	private int lastCapturedAnimation = Integer.MIN_VALUE;
	private long lastUploadMs = 0L;

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.WORN)
		{
			return;
		}

		// Equipment changed — always re-capture (current pose) after a short debounce.
		scheduleCapture(DEBOUNCE_MS);
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		if (event.getActor() != client.getLocalPlayer())
		{
			return;
		}

		int animation = client.getLocalPlayer().getAnimation();
		if (animation == lastCapturedAnimation)
		{
			return; // Already showing this pose (e.g. the same repeating attack).
		}

		if (System.currentTimeMillis() - lastUploadMs < MIN_UPLOAD_INTERVAL_MS)
		{
			return; // Throttle: keep the current avatar until the cooldown passes.
		}

		// Mid-action poses capture quickly; an idle pose waits a touch longer so a
		// brief gap between actions doesn't flip the avatar back to standing.
		scheduleCapture(animation == IDLE_ANIMATION ? IDLE_SETTLE_MS : ACTION_DELAY_MS);
	}

	private void scheduleCapture(long delayMs)
	{
		capturePending = true;
		captureNotBefore = System.currentTimeMillis() + delayMs;
	}

	/**
	 * Manual trigger (config button): capture at the next tick, skipping the
	 * debounce and the upload throttle.
	 */
	public void requestImmediateCapture()
	{
		capturePending = true;
		captureNotBefore = 0L;
		lastUploadMs = 0L;
		log.info("RuneManager: avatar capture requested — will capture on the next game tick");
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!capturePending || System.currentTimeMillis() < captureNotBefore)
		{
			return;
		}

		Player player = client.getLocalPlayer();
		if (player == null)
		{
			// Not in a world yet — keep waiting (no game ticks fire at the login screen).
			return;
		}

		CapturedModel playerModel = capture(player.getModel());
		if (playerModel == null)
		{
			// Model/colours not built yet this tick — try again next tick.
			return;
		}

		// In a fight (the target shows a health bar) — grab the opponent's posed
		// model too so the avatar is a combat tableau. Skipped for skilling NPCs
		// like fishing spots, which have no health bar.
		CapturedModel npcModel = null;
		Actor target = player.getInteracting();
		if (target instanceof NPC && target.getHealthScale() > 0)
		{
			npcModel = capture(target.getModel());
		}

		capturePending = false;
		lastCapturedAnimation = player.getAnimation();
		lastUploadMs = System.currentTimeMillis();

		CapturedModel npc = npcModel;
		executor.submit(() -> serializeAndUpload(playerModel, npc));
	}

	/**
	 * Clone an actor's posed model geometry on the client thread (RuneLite reuses
	 * those backing arrays), or null if it isn't renderable this tick.
	 */
	private static CapturedModel capture(Model model)
	{
		if (model == null || model.getVerticesX() == null || model.getFaceIndices1() == null)
		{
			return null;
		}

		short[] faceColors = readFaceColors(model);
		if (faceColors == null)
		{
			return null;
		}

		CapturedModel captured = new CapturedModel();
		captured.verticesX = model.getVerticesX().clone();
		captured.verticesY = model.getVerticesY().clone();
		captured.verticesZ = model.getVerticesZ().clone();
		captured.vertexCount = model.getVerticesCount();
		captured.faceIndicesA = model.getFaceIndices1().clone();
		captured.faceIndicesB = model.getFaceIndices2().clone();
		captured.faceIndicesC = model.getFaceIndices3().clone();
		captured.faceColors = faceColors;
		captured.faceCount = model.getFaceCount();
		return captured;
	}

	/** Cloned geometry for one actor, snapshotted off the reused client arrays. */
	private static final class CapturedModel
	{
		private float[] verticesX;
		private float[] verticesY;
		private float[] verticesZ;
		private int vertexCount;
		private int[] faceIndicesA;
		private int[] faceIndicesB;
		private int[] faceIndicesC;
		private short[] faceColors;
		private int faceCount;
	}

	/**
	 * Prefer the unlit per-face colours; fall back to the lit per-vertex colours
	 * (always populated on a rendered model) when a model doesn't retain unlit
	 * data. Both are Jagex-packed 16-bit HSL — the lit ints are narrowed to
	 * short, which {@link ObjModelSerializer} unpacks with the same masks.
	 */
	private static short[] readFaceColors(Model model)
	{
		short[] unlit = model.getUnlitFaceColors();
		if (unlit != null)
		{
			return unlit.clone();
		}

		int[] lit = model.getFaceColors1();
		if (lit == null)
		{
			return null;
		}

		short[] colors = new short[lit.length];
		for (int i = 0; i < lit.length; i++)
		{
			colors[i] = (short) lit[i];
		}
		return colors;
	}

	private void serializeAndUpload(CapturedModel player, CapturedModel npc)
	{
		try
		{
			ObjModelSerializer.Result playerObj = serialize(player);

			List<RuneManagerApi.Part> parts = new ArrayList<>(4);
			parts.add(new RuneManagerApi.Part("model", "avatar.obj", playerObj.obj()));
			parts.add(new RuneManagerApi.Part("material", "avatar.mtl", playerObj.mtl()));

			if (npc != null)
			{
				ObjModelSerializer.Result npcObj = serialize(npc);
				parts.add(new RuneManagerApi.Part("npc_model", "avatar_npc.obj", npcObj.obj()));
				parts.add(new RuneManagerApi.Part("npc_material", "avatar_npc.mtl", npcObj.mtl()));
			}

			log.info("RuneManager: uploading player avatar ({} faces{})",
				player.faceCount, npc != null ? " + opponent " + npc.faceCount + " faces" : "");
			api.postParts("/api/plugin/avatar", parts);
		}
		catch (RuntimeException e)
		{
			log.warn("RuneManager: avatar serialisation failed: {}", e.getMessage());
		}
	}

	private static ObjModelSerializer.Result serialize(CapturedModel m)
	{
		return ObjModelSerializer.serialize(
			m.verticesX, m.verticesY, m.verticesZ, m.vertexCount,
			m.faceIndicesA, m.faceIndicesB, m.faceIndicesC, m.faceColors, m.faceCount);
	}
}
