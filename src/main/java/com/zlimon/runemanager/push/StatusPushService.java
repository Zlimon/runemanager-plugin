package com.zlimon.runemanager.push;

import com.zlimon.runemanager.RuneManagerApi;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * Pushes the character's current activity, area and world — modelled on the
 * official RuneLite Discord plugin so the status is stable rather than flipping
 * every tick.
 *
 * Each skill that gains XP becomes a timed event; the displayed activity is the
 * highest-priority recent skill (Hitpoints is de-prioritised so melee shows the
 * attack style, Slayer/Fishing win ties). Events expire after a short idle so
 * the status settles to just the area. The area is resolved via the instance
 * template region, so instanced content still reports the right place, and the
 * world is sent alongside.
 */
@Slf4j
@Singleton
public class StatusPushService
{
	/** Clear a skill event after this long without XP, like Discord's timeout. */
	private static final long IDLE_TIMEOUT_MS = 15_000L;

	/** Keep "Fighting X" through the gaps between attacks so it doesn't flicker. */
	private static final long COMBAT_STICKY_MS = 6_000L;

	private static final long MIN_INTERVAL_MS = 1500L;

	@Inject
	private RuneManagerApi api;

	@Inject
	private Client client;

	/** Last XP per skill, to detect genuine gains (not the login-time sync). */
	private final Map<Skill, Integer> skillXp = new EnumMap<>(Skill.class);

	/** When each skill last gained XP. */
	private final Map<Skill, Long> skillActiveAt = new EnumMap<>(Skill.class);

	/** The NPC the player is currently fighting, and when last seen, for stability. */
	private String combatTarget;
	private long combatTargetAt = Long.MIN_VALUE;

	private String lastActivity;
	private String lastIcon;
	private String lastLocation;
	private int lastWorld = -1;
	private long lastSentMs;

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		Integer previous = skillXp.put(skill, event.getXp());

		// Ignore the initial sync (previous == null) and non-increases.
		if (previous != null && event.getXp() > previous)
		{
			skillActiveAt.put(skill, System.currentTimeMillis());
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			// Logged out / loading — leave the last status in place so it persists.
			return;
		}

		long now = System.currentTimeMillis();
		skillActiveAt.values().removeIf(at -> now - at > IDLE_TIMEOUT_MS);
		trackCombatTarget(local, now);

		boolean fighting = combatTarget != null && now - combatTargetAt <= COMBAT_STICKY_MS;
		String activity;
		String icon;

		if (fighting)
		{
			// "Fighting {NPC} using {Melee|Ranged|Magic}" with the style's icon.
			Skill style = topCombatSkill();
			activity = "Fighting " + combatTarget + (style != null ? " using " + styleName(style) : "");
			icon = style != null ? styleIcon(style) : "attack";
		}
		else
		{
			Skill top = topSkill();
			activity = top == null ? null : top.getName();
			icon = top == null ? null : top.getName().toLowerCase();
		}

		String location = currentArea(local);
		int world = client.getWorld();

		boolean changed = !Objects.equals(activity, lastActivity)
			|| !Objects.equals(icon, lastIcon)
			|| !Objects.equals(location, lastLocation)
			|| world != lastWorld;
		if (!changed || now - lastSentMs < MIN_INTERVAL_MS)
		{
			return;
		}

		Map<String, Object> body = new HashMap<>();
		body.put("activity", activity);
		body.put("activity_icon", icon);
		body.put("location", location);
		body.put("world", world);
		api.put("/api/plugin/status", body);

		lastActivity = activity;
		lastIcon = icon;
		lastLocation = location;
		lastWorld = world;
		lastSentMs = now;
	}

	/** Remember the NPC the player is fighting (has a health bar) for the sticky window. */
	private void trackCombatTarget(Player local, long now)
	{
		Actor interacting = local.getInteracting();
		if (interacting instanceof NPC && interacting.getName() != null && interacting.getHealthScale() > 0)
		{
			combatTarget = interacting.getName();
			combatTargetAt = now;
		}
	}

	private static final EnumSet<Skill> COMBAT_SKILLS = EnumSet.of(
		Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.RANGED, Skill.MAGIC, Skill.HITPOINTS);

	/** The combat style being trained, for the "using {style}" label. */
	private static String styleName(Skill skill)
	{
		if (skill == Skill.RANGED)
		{
			return "Ranged";
		}
		if (skill == Skill.MAGIC)
		{
			return "Magic";
		}
		return "Melee";
	}

	/** Skill-icon slug for the combat style — the exact skill trained (Attack/Strength/Defence/Ranged/Magic). */
	private static String styleIcon(Skill skill)
	{
		return skill.getName().toLowerCase();
	}

	/** Highest-priority recently-trained skill (any), or null when idle. */
	private Skill topSkill()
	{
		return topSkill(false);
	}

	/** Highest-priority recently-trained combat skill, or null. */
	private Skill topCombatSkill()
	{
		return topSkill(true);
	}

	private Skill topSkill(boolean combatOnly)
	{
		Skill best = null;
		int bestPriority = Integer.MIN_VALUE;
		long bestAt = Long.MIN_VALUE;

		for (Map.Entry<Skill, Long> entry : skillActiveAt.entrySet())
		{
			if (combatOnly && !COMBAT_SKILLS.contains(entry.getKey()))
			{
				continue;
			}

			int priority = priorityOf(entry.getKey());
			if (priority > bestPriority || (priority == bestPriority && entry.getValue() > bestAt))
			{
				best = entry.getKey();
				bestPriority = priority;
				bestAt = entry.getValue();
			}
		}

		return best;
	}

	/** Mirror Discord's priorities: Hitpoints loses to the attack style; Slayer/Fishing win ties. */
	private static int priorityOf(Skill skill)
	{
		if (skill == Skill.HITPOINTS)
		{
			return -1;
		}
		if (skill == Skill.SLAYER || skill == Skill.FISHING)
		{
			return 1;
		}
		return 0;
	}

	/**
	 * The named OSRS area the player is in, resolved through the instance
	 * template region so instanced content (raids, etc.) still names correctly.
	 */
	private String currentArea(Player local)
	{
		if (local.getLocalLocation() == null)
		{
			return null;
		}

		int regionId = WorldPoint.fromLocalInstance(client, local.getLocalLocation()).getRegionID();
		return DiscordRegions.nameForRegion(regionId);
	}
}
