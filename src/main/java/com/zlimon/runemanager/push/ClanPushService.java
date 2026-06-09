package com.zlimon.runemanager.push;

import com.zlimon.runemanager.RuneManagerApi;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.clan.ClanChannelMember;
import net.runelite.api.clan.ClanRank;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.clan.ClanTitle;
import net.runelite.api.events.ClanChannelChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * Pushes the local player's in-game clan name + rank to the website (SPEC §5.2).
 * In CLAN mode the backend mirrors the rank onto the website role, so an
 * in-game promotion to Administrator+ grants admin on the site.
 *
 * Sent only when the clan name, rank or title changes — clan membership rarely
 * shifts, so this is near-silent after the initial login push.
 */
@Slf4j
@Singleton
public class ClanPushService
{
	@Inject
	private RuneManagerApi api;

	@Inject
	private Client client;

	private String lastClanName;
	private Integer lastRank;
	private String lastTitle;
	private boolean dirty = true;

	@Subscribe
	public void onClanChannelChanged(ClanChannelChanged event)
	{
		// Fires for both the member and guest channels; recompute from the
		// authoritative main channel on the next tick-safe push.
		push();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			// Forget cached state so the next login re-pushes even if unchanged.
			reset();
		}
		else if (event.getGameState() == GameState.LOGGED_IN)
		{
			push();
		}
	}

	private void push()
	{
		Player local = client.getLocalPlayer();
		if (local == null || local.getName() == null)
		{
			return;
		}

		String clanName = null;
		Integer rank = null;
		String title = null;

		ClanChannel channel = client.getClanChannel();
		if (channel != null)
		{
			clanName = channel.getName();

			ClanChannelMember me = channel.findMember(local.getName());
			if (me != null && me.getRank() != null)
			{
				ClanRank clanRank = me.getRank();
				rank = clanRank.getRank();

				ClanSettings settings = client.getClanSettings();
				if (settings != null)
				{
					ClanTitle clanTitle = settings.titleForRank(clanRank);
					if (clanTitle != null)
					{
						title = clanTitle.getName();
					}
				}
			}
		}

		if (!dirty
			&& Objects.equals(clanName, lastClanName)
			&& Objects.equals(rank, lastRank)
			&& Objects.equals(title, lastTitle))
		{
			return;
		}

		Map<String, Object> body = new HashMap<>();
		body.put("clan_name", clanName);
		body.put("clan_rank", rank);
		body.put("clan_title", title);
		api.put("/api/plugin/clan", body);

		lastClanName = clanName;
		lastRank = rank;
		lastTitle = title;
		dirty = false;
	}

	private void reset()
	{
		lastClanName = null;
		lastRank = null;
		lastTitle = null;
		dirty = true;
	}
}
