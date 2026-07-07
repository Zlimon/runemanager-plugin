package com.zlimon.runemanager.push;

import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

/**
 * The local player's current position as a small payload map — attached to
 * feed-producing pushes (loot, collection log, combat achievements, notable
 * events, quests) so the website can show where each event happened. Instanced
 * areas resolve to their overworld point, and the human-readable area name
 * reuses the same region table as the status line.
 */
@Singleton
public class PlayerLocation
{
	@Inject
	private Client client;

	/**
	 * {"x", "y", "plane", "location"} for the local player, or null when no
	 * position is available (e.g. between login states).
	 */
	public Map<String, Object> current()
	{
		Player local = client.getLocalPlayer();
		if (local == null || local.getLocalLocation() == null)
		{
			return null;
		}

		WorldPoint point = WorldPoint.fromLocalInstance(client, local.getLocalLocation());

		Map<String, Object> position = new HashMap<>();
		position.put("x", point.getX());
		position.put("y", point.getY());
		position.put("plane", point.getPlane());
		position.put("location", DiscordRegions.nameForRegion(point.getRegionID()));

		return position;
	}
}
