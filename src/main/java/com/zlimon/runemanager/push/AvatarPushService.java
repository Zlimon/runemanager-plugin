package com.zlimon.runemanager.push;

import com.zlimon.runemanager.RuneManagerApi;
import com.zlimon.runemanager.RuneManagerConfig;
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
 * Captures the local player's 3D model in its default standing pose and uploads
 * it to RuneManager as the account avatar — no third-party export plugin
 * required.
 *
 * A capture is requested on login (via {@link AccountSnapshotService}) and on
 * every equipment change. The actual capture waits for an idle, standing frame
 * (no action animation, default pose) so the avatar is always the clean default
 * pose rather than a mid-swing or mid-walk frame. Geometry is read on the client
 * thread (RuneLite reuses those arrays) and serialised + uploaded off-thread.
 */
@Slf4j
@Singleton
public class AvatarPushService
{
	/** Actor.getAnimation() sentinel for "no active animation". */
	private static final int IDLE_ANIMATION = -1;

	@Inject
	private Client client;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private RuneManagerApi api;

	@Inject
	private RuneManagerConfig config;

	private volatile boolean capturePending = false;

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.WORN)
		{
			requestCapture();
		}
	}

	/** Request a capture on the next idle, standing frame (login + gear swaps). */
	public void requestCapture()
	{
		if (config.syncAvatar())
		{
			capturePending = true;
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!capturePending)
		{
			return;
		}

		Player player = client.getLocalPlayer();
		if (player == null || !isDefaultPose(player))
		{
			// Not in a world yet, or mid-action/moving — wait for a clean frame.
			return;
		}

		CapturedModel playerModel = capture(player.getModel());
		if (playerModel == null)
		{
			// Model/colours not built yet this tick — try again next tick.
			return;
		}

		capturePending = false;
		executor.submit(() -> serializeAndUpload(playerModel));
	}

	/** A still, default-pose frame: no action animation and the idle standing pose. */
	private static boolean isDefaultPose(Player player)
	{
		return player.getAnimation() == IDLE_ANIMATION
			&& player.getPoseAnimation() == player.getIdlePoseAnimation();
	}

	/**
	 * Clone the player's posed model geometry on the client thread (RuneLite
	 * reuses those backing arrays), or null if it isn't renderable this tick.
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

	/** Cloned geometry for the player, snapshotted off the reused client arrays. */
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

	private void serializeAndUpload(CapturedModel player)
	{
		try
		{
			ObjModelSerializer.Result playerObj = ObjModelSerializer.serialize(
				player.verticesX, player.verticesY, player.verticesZ, player.vertexCount,
				player.faceIndicesA, player.faceIndicesB, player.faceIndicesC,
				player.faceColors, player.faceCount);

			List<RuneManagerApi.Part> parts = new ArrayList<>(2);
			parts.add(new RuneManagerApi.Part("model", "avatar.obj", playerObj.obj()));
			parts.add(new RuneManagerApi.Part("material", "avatar.mtl", playerObj.mtl()));

			log.info("RuneManager: uploading player avatar ({} faces)", player.faceCount);
			api.postParts("/api/plugin/avatar", parts);
		}
		catch (RuntimeException e)
		{
			log.warn("RuneManager: avatar serialisation failed: {}", e.getMessage());
		}
	}
}
