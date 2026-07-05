package com.zlimon.runemanager.push;

import com.zlimon.runemanager.RuneManagerApi;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * SPEC §8.1 — posts collection-log slot unlocks to the live feed. Mirrors the
 * official Screenshot plugin: OPTION_COLLECTION_NEW_ITEM is 0 = no
 * notification, 1 = chat only, 2 = popup only, 3 = chat + popup. The chat path
 * fires only in chat-only mode (1); the popup path fires whenever the popup
 * actually appears (2 and 3) with no varbit check, so the two paths never
 * double-post. Either way we extract the item name and push it (the full log
 * is pulled from TempleOSRS). At 0 the game shows nothing to parse.
 */
@Slf4j
@Singleton
public class CollectionLogPushService
{
	private static final String CHAT_PREFIX = "New item added to your collection log: ";

	private static final String POPUP_TITLE = "Collection log";

	private static final String POPUP_PREFIX = "New item:";

	@Inject
	private Client client;

	@Inject
	private RuneManagerApi api;

	@Inject
	private ScreenshotPushService screenshot;

	private boolean notificationStarted = false;

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}

		// Chat-only mode (1); in chat+popup mode (3) the popup path posts instead.
		if (client.getVarbitValue(VarbitID.OPTION_COLLECTION_NEW_ITEM) != 1)
		{
			return;
		}

		String message = Text.removeTags(event.getMessage());
		if (message.startsWith(CHAT_PREFIX))
		{
			postUnlock(message.substring(CHAT_PREFIX.length()));
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

	private void handleNotification()
	{
		String title = client.getVarcStrValue(VarClientID.NOTIFICATION_TITLE);
		if (title == null || !title.equalsIgnoreCase(POPUP_TITLE))
		{
			return;
		}

		String body = Text.removeTags(client.getVarcStrValue(VarClientID.NOTIFICATION_MAIN));
		if (body == null)
		{
			return;
		}

		String item = body.startsWith(POPUP_PREFIX) ? body.substring(POPUP_PREFIX.length()) : body;
		postUnlock(item);
	}

	private void postUnlock(String item)
	{
		item = item.trim();
		if (item.isEmpty())
		{
			return;
		}

		Map<String, Object> body = new HashMap<>();
		body.put("item", item);

		log.debug("RuneManager: collection log slot unlocked: {}", item);
		api.post("/api/plugin/collection-log/unlock", body);
		screenshot.capture("collection_log");
	}
}
