package com.zlimon.runemanager.push;

import com.zlimon.runemanager.RuneManagerApi;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.Subscribe;

/**
 * SPEC §5.2/§7.1/§8.1 — Combat Achievements.
 *
 * Two jobs:
 * <ul>
 *   <li><b>Snapshot</b> — total points (CA_POINTS) + completed-task count per
 *       tier (CA_TOTAL_TASKS_COMPLETED_*) pushed on the login snapshot and
 *       whenever one of those varbits changes. These are the in-game overview's
 *       per-tier counts (e.g. 27/41); the denominators live on the website.</li>
 *   <li><b>Live unlock</b> — a freshly completed task posted to the live feed.
 *       Mirrors the official Screenshot plugin: when the in-game popup is
 *       disabled (CA_TASK_POPUP == 1) the completion arrives as a chat message
 *       carrying the tier + task; when enabled (== 0, the default) it arrives as
 *       a notification popup carrying only the task name (tier omitted).</li>
 * </ul>
 */
@Slf4j
@Singleton
public class CombatAchievementPushService
{
	/** Chat-message form, identical to the Screenshot plugin's pattern. */
	private static final Pattern CHAT_PATTERN = Pattern.compile(
		"Congratulations, you've completed an? (?<tier>\\w+) combat task: <col=[0-9a-f]+>(?<task>.+)</col>");

	private static final String POPUP_TITLE = "Combat Task Completed!";

	/** Tier slug → its completed-task-count varbit, in ascending order. */
	private static final Map<String, Integer> TIER_VARBITS = buildTierVarbits();

	@Inject
	private Client client;

	@Inject
	private RuneManagerApi api;

	@Inject
	private ScreenshotPushService screenshot;

	private boolean notificationStarted = false;

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		int id = event.getVarbitId();
		if (id == VarbitID.CA_POINTS || TIER_VARBITS.containsValue(id))
		{
			pushCurrent();
		}
	}

	/** Push the full points + per-tier status snapshot (full-account snapshot). */
	public void pushCurrent()
	{
		api.put("/api/plugin/combat-achievements", buildPayload());
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}

		// Chat carries the completion only when the on-screen popup is disabled;
		// the popup path handles the other case (avoids double-posting).
		if (client.getVarbitValue(VarbitID.CA_TASK_POPUP) != 1)
		{
			return;
		}

		Matcher matcher = CHAT_PATTERN.matcher(event.getMessage());
		if (matcher.find())
		{
			postUnlock(matcher.group("task"), matcher.group("tier"));
		}
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		switch (event.getScriptId())
		{
			case ScriptID.NOTIFICATION_START:
				notificationStarted = true;
				break;
			case ScriptID.NOTIFICATION_DELAY:
				if (notificationStarted)
				{
					handleNotification();
					notificationStarted = false;
				}
				break;
		}
	}

	/**
	 * Read the just-shown notification popup; if it's a completed combat task
	 * (and the popup is the active delivery method) post the unlock. The popup
	 * exposes the task name but not its tier.
	 */
	private void handleNotification()
	{
		if (client.getVarbitValue(VarbitID.CA_TASK_POPUP) != 0)
		{
			return;
		}

		String title = client.getVarcStrValue(VarClientID.NOTIFICATION_TITLE);
		if (title == null || !title.equalsIgnoreCase(POPUP_TITLE))
		{
			return;
		}

		String body = client.getVarcStrValue(VarClientID.NOTIFICATION_MAIN);
		if (body == null)
		{
			return;
		}

		// Body is "...<col=..>Task Name</col>..."; the task is the first tagged
		// segment, matching the Screenshot plugin's parse.
		String[] segments = body.split("<.*?>");
		if (segments.length > 1 && !segments[1].isEmpty())
		{
			postUnlock(segments[1], null);
		}
	}

	private void postUnlock(String task, String tier)
	{
		Map<String, Object> body = new HashMap<>();
		body.put("task", task);
		if (tier != null && !tier.isEmpty())
		{
			body.put("tier", tier.toLowerCase());
		}

		log.debug("RuneManager: combat task unlocked: {} ({})", task, tier);
		api.post("/api/plugin/combat-achievements/unlock", body);
		screenshot.capture("combat_achievement");
	}

	private Map<String, Object> buildPayload()
	{
		Map<String, Integer> tiers = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : TIER_VARBITS.entrySet())
		{
			tiers.put(entry.getKey(), client.getVarbitValue(entry.getValue()));
		}

		Map<String, Object> body = new HashMap<>();
		body.put("points", client.getVarbitValue(VarbitID.CA_POINTS));
		body.put("tiers", tiers);
		return body;
	}

	private static Map<String, Integer> buildTierVarbits()
	{
		Map<String, Integer> tiers = new LinkedHashMap<>();
		tiers.put("easy", VarbitID.CA_TOTAL_TASKS_COMPLETED_EASY);
		tiers.put("medium", VarbitID.CA_TOTAL_TASKS_COMPLETED_MEDIUM);
		tiers.put("hard", VarbitID.CA_TOTAL_TASKS_COMPLETED_HARD);
		tiers.put("elite", VarbitID.CA_TOTAL_TASKS_COMPLETED_ELITE);
		tiers.put("master", VarbitID.CA_TOTAL_TASKS_COMPLETED_MASTER);
		tiers.put("grandmaster", VarbitID.CA_TOTAL_TASKS_COMPLETED_GRANDMASTER);
		return tiers;
	}
}
