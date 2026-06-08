package com.zlimon.runemanager.push;

import com.zlimon.runemanager.RuneManagerApi;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Model;
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

		Model model = player.getModel();
		if (model == null || model.getVerticesX() == null || model.getFaceIndices1() == null)
		{
			// Model not built yet this tick — try again next tick.
			return;
		}

		short[] faceColors = readFaceColors(model);
		if (faceColors == null)
		{
			// Geometry is here but we can't read colours — stop retrying so we
			// don't silently spin every tick. Surfaced as a warning to diagnose.
			log.warn("RuneManager: avatar capture skipped — player model has no readable face colours");
			capturePending = false;
			return;
		}

		// Clone on the client thread — the API reuses these backing arrays.
		float[] verticesX = model.getVerticesX().clone();
		float[] verticesY = model.getVerticesY().clone();
		float[] verticesZ = model.getVerticesZ().clone();
		int vertexCount = model.getVerticesCount();
		int[] faceIndicesA = model.getFaceIndices1().clone();
		int[] faceIndicesB = model.getFaceIndices2().clone();
		int[] faceIndicesC = model.getFaceIndices3().clone();
		int faceCount = model.getFaceCount();

		capturePending = false;
		lastCapturedAnimation = player.getAnimation();
		lastUploadMs = System.currentTimeMillis();

		executor.submit(() -> serializeAndUpload(
			verticesX, verticesY, verticesZ, vertexCount,
			faceIndicesA, faceIndicesB, faceIndicesC, faceColors, faceCount));
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

	private void serializeAndUpload(
		float[] verticesX, float[] verticesY, float[] verticesZ, int vertexCount,
		int[] faceIndicesA, int[] faceIndicesB, int[] faceIndicesC,
		short[] faceColors, int faceCount)
	{
		try
		{
			ObjModelSerializer.Result result = ObjModelSerializer.serialize(
				verticesX, verticesY, verticesZ, vertexCount,
				faceIndicesA, faceIndicesB, faceIndicesC, faceColors, faceCount);

			List<RuneManagerApi.Part> parts = new ArrayList<>(2);
			parts.add(new RuneManagerApi.Part("model", "avatar.obj", result.obj()));
			parts.add(new RuneManagerApi.Part("material", "avatar.mtl", result.mtl()));

			log.info("RuneManager: uploading player avatar ({} faces)", faceCount);
			api.postParts("/api/plugin/avatar", parts);
		}
		catch (RuntimeException e)
		{
			log.warn("RuneManager: avatar serialisation failed: {}", e.getMessage());
		}
	}
}
