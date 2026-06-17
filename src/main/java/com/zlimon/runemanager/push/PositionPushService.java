package com.zlimon.runemanager.push;

import com.zlimon.runemanager.RuneManagerApi;
import com.zlimon.runemanager.RuneManagerConfig;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

/**
 * Pushes the player's position to the RuneManager Live Map while the opt-in
 * "Share my location" setting is enabled (off by default). The website moves
 * the account's marker in real time over websockets.
 *
 * Throttled so a moving player sends at most a few times a second, with a slower
 * keepalive while standing still so the marker stays "on the map" (the server
 * drops positions that go stale).
 */
@Slf4j
@Singleton
public class PositionPushService
{
	/** Don't send more often than this, even while running. */
	private static final long MIN_INTERVAL_MS = 1500;

	/** Resend an unchanged position at least this often so it doesn't go stale. */
	private static final long KEEPALIVE_MS = 30_000;

	@Inject
	private RuneManagerApi api;

	@Inject
	private RuneManagerConfig config;

	@Inject
	private Client client;

	private WorldPoint lastSent;
	private long lastSentMs;

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!config.shareLocation())
		{
			return;
		}

		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}

		// In a player instance the world coordinates are a copy in dead space that
		// renders white on the map — skip the update so the marker keeps the last
		// real position (where they were before entering the instance).
		if (client.isInInstancedRegion())
		{
			return;
		}

		WorldPoint position = local.getWorldLocation();
		if (position == null)
		{
			return;
		}

		long now = System.currentTimeMillis();
		boolean moved = !position.equals(lastSent);
		boolean dueForKeepalive = now - lastSentMs >= KEEPALIVE_MS;

		if (!((moved && now - lastSentMs >= MIN_INTERVAL_MS) || dueForKeepalive))
		{
			return;
		}

		Map<String, Object> body = new HashMap<>();
		body.put("x", position.getX());
		body.put("y", position.getY());
		body.put("plane", position.getPlane());
		api.put("/api/plugin/position", body);

		lastSent = position;
		lastSentMs = now;
	}
}
