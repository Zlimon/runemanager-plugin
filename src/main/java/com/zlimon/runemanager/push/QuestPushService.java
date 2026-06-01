package com.zlimon.runemanager.push;

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
import net.runelite.client.eventbus.Subscribe;

/**
 * Pushes the player's full quest list once per login.
 *
 * Quests change rarely and quest-completion events are awkward to detect cleanly,
 * so we settle for "scan everything when the player logs in". The server treats
 * it as a snapshot upsert so re-running is harmless.
 */
@Slf4j
@Singleton
public class QuestPushService
{
	@Inject
	private Client client;

	@Inject
	private RuneManagerApi api;

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		api.put("/api/plugin/quests", buildPayload());
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
