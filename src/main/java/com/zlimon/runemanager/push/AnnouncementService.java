package com.zlimon.runemanager.push;

import com.google.gson.Gson;
import com.zlimon.runemanager.RuneManagerApi;
import java.util.HashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;

/**
 * Pulls active announcements the account hasn't seen, shows each as a game chat
 * message, then acknowledges it so it isn't shown again (SPEC §9.2). Polled on a
 * schedule from {@link com.zlimon.runemanager.RuneManagerPlugin}; the API calls
 * no-op unless logged in and ready.
 */
@Slf4j
@Singleton
public class AnnouncementService
{
	/** Shape of an entry in the /api/plugin/announcements response. */
	private static final class Announcement
	{
		long id;
		String title;
		String body;
	}

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private RuneManagerApi api;

	@Inject
	private Gson gson;

	public void poll()
	{
		api.get("/api/plugin/announcements", this::handle);
	}

	private void handle(String json)
	{
		Announcement[] announcements;
		try
		{
			announcements = gson.fromJson(json, Announcement[].class);
		}
		catch (RuntimeException e)
		{
			log.warn("RuneManager: could not parse announcements: {}", e.getMessage());
			return;
		}

		if (announcements == null)
		{
			return;
		}

		for (Announcement announcement : announcements)
		{
			if (announcement == null)
			{
				continue;
			}

			show(announcement);
			// A plain HashMap, not Collections.emptyMap(): Gson serialises the latter
			// (a JDK-internal class) by reflection, which throws on JDK 16+ and would
			// silently drop the ack — re-showing the announcement on every poll.
			api.put("/api/plugin/announcements/" + announcement.id + "/acknowledge", new HashMap<>());
		}
	}

	private void show(Announcement announcement)
	{
		String body = announcement.body == null ? "" : announcement.body.replaceAll("\\s+", " ").trim();
		String message = "[RuneManager] " + announcement.title + (body.isEmpty() ? "" : ": " + body);

		clientThread.invoke(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null));
	}
}
