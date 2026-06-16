package com.zlimon.runemanager.push;

import com.zlimon.runemanager.RuneManagerApi;
import com.zlimon.runemanager.RuneManagerConfig;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.ui.DrawManager;

/**
 * SPEC §8 — captures a full-frame screenshot when a feed-worthy event happens
 * and uploads it to be attached to that event on the RuneManager live feed.
 *
 * Off by default (uploads images to the public feed). Called by the loot /
 * combat-achievement / collection-log push services at the in-game moment; the
 * backend matches the screenshot to the event it just created.
 *
 * The frame is grabbed via {@link DrawManager} (the fully composited client
 * image — RuneLite has no API to exclude other plugins' overlays), then encoded
 * and uploaded off the client thread.
 */
@Slf4j
@Singleton
public class ScreenshotPushService
{
	@Inject
	private Client client;

	@Inject
	private DrawManager drawManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private RuneManagerApi api;

	@Inject
	private RuneManagerConfig config;

	/** Capture the next frame and upload it for the given feed event type. */
	public void capture(String type)
	{
		if (!config.captureScreenshots() || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		drawManager.requestNextFrameListener(image -> executor.submit(() -> upload(type, image)));
	}

	private void upload(String type, Image image)
	{
		try
		{
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(toBufferedImage(image), "png", out);

			List<RuneManagerApi.Part> parts = new ArrayList<>(1);
			parts.add(new RuneManagerApi.Part("image", "screenshot.png", out.toByteArray()));

			api.postParts("/api/plugin/feed/screenshot?type=" + type, parts);
		}
		catch (IOException e)
		{
			log.warn("RuneManager: screenshot encode failed: {}", e.getMessage());
		}
	}

	private static BufferedImage toBufferedImage(Image image)
	{
		if (image instanceof BufferedImage)
		{
			return (BufferedImage) image;
		}

		BufferedImage buffered = new BufferedImage(
			image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = buffered.createGraphics();
		graphics.drawImage(image, 0, 0, null);
		graphics.dispose();
		return buffered;
	}
}
