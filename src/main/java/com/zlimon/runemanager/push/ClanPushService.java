package com.zlimon.runemanager.push;

import com.zlimon.runemanager.RuneManagerApi;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
import net.runelite.api.clan.ClanMember;
import net.runelite.api.clan.ClanRank;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.clan.ClanTitle;
import net.runelite.api.events.ClanChannelChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * Pushes clan data to the website (SPEC §5.2):
 *   - the local player's own rank/title, so the backend keeps their website
 *     role in sync with an in-game promotion;
 *   - and, when the local player is the clan owner, the full clan roster, so the
 *     backend can pre-create the unclaimed accounts members later link to.
 *
 * Both are sent only when something changes — clan membership rarely shifts, so
 * this is near-silent after the initial login push.
 */
@Slf4j
@Singleton
public class ClanPushService
{
	@Inject
	private RuneManagerApi api;

	@Inject
	private Client client;

	private Integer lastRank;
	private String lastTitle;
	private String lastRosterSignature;
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

		pushOwnRank(local);
		pushRosterIfOwner(local);
	}

	/** The local player's own rank + title (drives their website role). */
	private void pushOwnRank(Player local)
	{
		Integer rank = null;
		String title = null;

		ClanChannel channel = client.getClanChannel();
		if (channel != null)
		{
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

		if (!dirty && Objects.equals(rank, lastRank) && Objects.equals(title, lastTitle))
		{
			return;
		}

		Map<String, Object> body = new HashMap<>();
		body.put("clan_rank", rank);
		body.put("clan_title", title);
		api.put("/api/plugin/clan", body);

		lastRank = rank;
		lastTitle = title;
		dirty = false;
	}

	/**
	 * The full clan roster, pushed only by the owner. ClanSettings carries every
	 * member (not just those online), so a single owner login seeds the site.
	 */
	private void pushRosterIfOwner(Player local)
	{
		ClanSettings settings = client.getClanSettings();
		if (settings == null)
		{
			return;
		}

		ClanMember me = settings.findMember(local.getName());
		if (me == null || me.getRank() == null || me.getRank().getRank() != ClanRank.OWNER.getRank())
		{
			return;
		}

		List<ClanMember> members = settings.getMembers();
		if (members == null || members.isEmpty())
		{
			return;
		}

		List<Map<String, Object>> roster = new ArrayList<>(members.size());
		StringBuilder signature = new StringBuilder();

		for (ClanMember member : members)
		{
			if (member.getName() == null)
			{
				continue;
			}

			Integer rank = member.getRank() != null ? member.getRank().getRank() : null;
			String title = null;
			if (member.getRank() != null)
			{
				ClanTitle clanTitle = settings.titleForRank(member.getRank());
				if (clanTitle != null)
				{
					title = clanTitle.getName();
				}
			}

			Map<String, Object> entry = new HashMap<>();
			entry.put("username", member.getName());
			entry.put("rank", rank);
			entry.put("title", title);
			roster.add(entry);

			signature.append(member.getName()).append('#').append(rank).append(';');
		}

		String rosterSignature = signature.toString();
		if (rosterSignature.equals(lastRosterSignature))
		{
			return;
		}

		Map<String, Object> body = new HashMap<>();
		body.put("clan_name", settings.getName());
		body.put("members", roster);
		api.post("/api/plugin/clan/roster", body);

		lastRosterSignature = rosterSignature;
	}

	private void reset()
	{
		lastRank = null;
		lastTitle = null;
		lastRosterSignature = null;
		dirty = true;
	}
}
