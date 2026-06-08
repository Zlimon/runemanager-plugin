package com.zlimon.runemanager.push;

import com.zlimon.runemanager.RuneManagerApi;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * Pushes the character's current activity to the website (Discord-plugin style):
 * the most recently trained skill ("Fishing"), or "Fighting {target}" while in
 * combat, falling back to "Idle" after a spell with no XP. Sent only when the
 * activity changes.
 *
 * This is a lightweight take on the RuneLite Discord plugin's status — its full
 * region/boss map is package-private, so we derive a faithful subset from XP
 * drops and the combat target rather than copy hundreds of region IDs.
 */
@Slf4j
@Singleton
public class StatusPushService
{
	private static final Set<Skill> COMBAT_SKILLS = EnumSet.of(
		Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.RANGED, Skill.MAGIC, Skill.HITPOINTS);

	/** Game ticks (~0.6s) of no XP before the status drops back to "Idle". */
	private static final int IDLE_AFTER_TICKS = 25;

	private static final long MIN_INTERVAL_MS = 1500;

	@Inject
	private RuneManagerApi api;

	@Inject
	private Client client;

	private Skill lastSkill;
	private int lastActionTick = Integer.MIN_VALUE;
	private String lastPushed;
	private long lastSentMs;

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		lastSkill = event.getSkill();
		lastActionTick = client.getTickCount();
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}

		String activity = computeActivity(local);
		long now = System.currentTimeMillis();

		if (activity.equals(lastPushed) || now - lastSentMs < MIN_INTERVAL_MS)
		{
			return;
		}

		Map<String, Object> body = new HashMap<>();
		body.put("activity", activity);
		api.put("/api/plugin/status", body);

		lastPushed = activity;
		lastSentMs = now;
	}

	private String computeActivity(Player local)
	{
		boolean recentAction = client.getTickCount() - lastActionTick <= IDLE_AFTER_TICKS;

		if (recentAction && lastSkill != null)
		{
			if (COMBAT_SKILLS.contains(lastSkill))
			{
				Actor target = local.getInteracting();
				if (target instanceof NPC && target.getName() != null)
				{
					return "Fighting " + target.getName();
				}
				return "In combat";
			}

			return lastSkill.getName();
		}

		return "Idle";
	}
}
