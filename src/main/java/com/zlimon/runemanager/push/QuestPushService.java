package com.zlimon.runemanager.push;

import com.zlimon.runemanager.PluginAccountState;
import com.zlimon.runemanager.RuneManagerApi;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

/**
 * Pushes the player's full quest snapshot once per session.
 *
 * Quests change rarely and quest-completion events are awkward to detect
 * cleanly, so we settle for "scan everything once after login". The push
 * is gated on {@link PluginAccountState#isReady()} and retried on each
 * GameTick until it succeeds — this absorbs two real-world cases:
 *   1. Plugin enabled mid-session, no GameStateChanged ever fires.
 *   2. GameStateChanged → LOGGED_IN fires before the local player object
 *      is fully loaded, so the first push attempt finds account state
 *      not ready yet.
 *
 * The server treats the payload as a snapshot upsert so re-running is
 * harmless on its own — the gate just keeps us from spamming a push
 * every tick for the whole session.
 */
@Slf4j
@Singleton
public class QuestPushService
{
	@Inject
	private Client client;

	@Inject
	private RuneManagerApi api;

	@Inject
	private PluginAccountState accountState;

	private boolean pushedThisSession = false;

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING || state == GameState.CONNECTION_LOST)
		{
			pushedThisSession = false;
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (pushedThisSession || !accountState.isReady())
		{
			return;
		}

		api.put("/api/plugin/quests", buildPayload());
		pushedThisSession = true;
	}

	private Map<String, Object> buildPayload()
	{
		List<Object[]> quests = new ArrayList<>(Quest.values().length);

		for (Quest quest : Quest.values())
		{
			QuestState state = quest.getState(client);
			int code;
			switch (state)
			{
				case FINISHED:
					code = 2;
					break;
				case IN_PROGRESS:
					code = 1;
					break;
				default:
					code = 0;
			}
			quests.add(new Object[]{quest.getName(), code});
		}

		Map<String, Object> body = new HashMap<>();
		body.put("quests", quests);
		return body;
	}
}
