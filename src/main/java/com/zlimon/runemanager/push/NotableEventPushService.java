package com.zlimon.runemanager.push;

import com.zlimon.runemanager.RuneManagerApi;
import com.zlimon.runemanager.RuneManagerConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.eventbus.Subscribe;

/**
 * SPEC §8.1 — notable in-game events that become live-feed entries, detected the
 * same way as the official Screenshot plugin: a pet drop (chat), the local
 * player's death ({@link ActorDeath}), and opening a reward screen
 * ({@link WidgetLoaded}). Each pushes a feed event and, when enabled, a
 * screenshot. (Levels, valuable drops, collection log and combat achievements
 * are handled by their own services.)
 */
@Slf4j
@Singleton
public class NotableEventPushService
{
	private static final List<String> PET_MESSAGES = List.of(
		"You have a funny feeling like you're being followed",
		"You feel something weird sneaking into your backpack",
		"You have a funny feeling like you would have been followed");

	/** Level-up chat line, matching the official Screenshot plugin's pattern. */
	private static final Pattern LEVEL_UP_PATTERN = Pattern.compile(
		"Congratulations, you've (?:just advanced your (?<skill>[a-zA-Z]+) level\\. You are now level (?<level>\\d+)"
			+ "|reached the highest possible (?<skill99>[a-zA-Z]+) level of (?<level99>\\d+))\\.");

	/** Reward interface group id → human source name. */
	private static final Map<Integer, String> REWARD_SOURCES = buildRewardSources();

	@Inject
	private Client client;

	@Inject
	private RuneManagerApi api;

	@Inject
	private PlayerLocation playerLocation;

	@Inject
	private RuneManagerConfig config;

	@Inject
	private ScreenshotPushService screenshot;

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}

		String message = event.getMessage();
		if (PET_MESSAGES.stream().anyMatch(message::contains))
		{
			push("pet", null);
			return;
		}

		Matcher levelUp = LEVEL_UP_PATTERN.matcher(message);
		if (levelUp.matches())
		{
			String skill = levelUp.group("skill") != null ? levelUp.group("skill") : levelUp.group("skill99");
			String level = levelUp.group("level") != null ? levelUp.group("level") : levelUp.group("level99");
			pushLevelUp(skill.toLowerCase(), Integer.parseInt(level));
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (event.getActor() == client.getLocalPlayer())
		{
			push("death", null);
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		String source = REWARD_SOURCES.get(event.getGroupId());
		if (source != null)
		{
			push("reward", source);
		}
	}

	private void push(String type, String source)
	{
		if (!config.syncNotableEvents())
		{
			return;
		}

		Map<String, Object> body = new HashMap<>();
		body.put("type", type);
		body.put("position", playerLocation.current());
		if (source != null)
		{
			body.put("source", source);
		}

		log.debug("RuneManager: notable event: {} ({})", type, source);
		api.post("/api/plugin/feed", body);
		screenshot.capture(type);
	}

	private void pushLevelUp(String skill, int level)
	{
		if (!config.syncNotableEvents())
		{
			return;
		}

		Map<String, Object> body = new HashMap<>();
		body.put("type", "level_up");
		body.put("position", playerLocation.current());
		body.put("skill", skill);
		body.put("level", level);

		log.debug("RuneManager: level up: {} {}", skill, level);
		api.post("/api/plugin/feed", body);
		screenshot.capture("level_up");
	}

	private static Map<Integer, String> buildRewardSources()
	{
		Map<Integer, String> sources = new HashMap<>();
		sources.put(InterfaceID.TRAIL_REWARDSCREEN, "Clue scroll");
		sources.put(InterfaceID.RAIDS_REWARDS, "Chambers of Xeric");
		sources.put(InterfaceID.TOB_CHESTS, "Theatre of Blood");
		sources.put(InterfaceID.TOA_CHESTS, "Tombs of Amascut");
		sources.put(InterfaceID.BARROWS_REWARD, "Barrows");
		return sources;
	}
}
