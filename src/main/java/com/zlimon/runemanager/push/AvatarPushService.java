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
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;

/**
 * Captures the local player's 3D model and uploads it to RuneManager as the
 * account avatar — no third-party export plugin required.
 *
 * Re-captures automatically when worn equipment changes (so the avatar tracks
 * gear swaps), coalescing bursts via a short debounce and waiting for an idle
 * pose so the model isn't frozen mid-animation. The geometry is read on the
 * client thread (RuneLite reuses those arrays) and serialised + uploaded
 * off-thread. A manual config button reuses the same path with no debounce.
 */
@Slf4j
@Singleton
public class AvatarPushService
{
	/** Coalesce gear-swap bursts into a single capture once things settle. */
	private static final long DEBOUNCE_MS = 3000L;
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

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.WORN)
		{
			return;
		}

		// Equipment changed — schedule a debounced re-capture.
		capturePending = true;
		captureNotBefore = System.currentTimeMillis() + DEBOUNCE_MS;
	}

	/**
	 * Manual trigger (config button): capture at the next idle tick, skipping
	 * the gear-swap debounce.
	 */
	public void requestImmediateCapture()
	{
		capturePending = true;
		captureNotBefore = 0L;
		log.info("RuneManager: avatar capture requested — will capture on the next idle game tick");
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

		// Hold off (staying pending across ticks) until the player stands idle,
		// so the captured pose isn't a swing/cast frame.
		if (player.getAnimation() != IDLE_ANIMATION)
		{
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
