package com.zlimon.runemanager;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Varbits;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * Tracks the OSRS account behind the locally logged-in player.
 *
 * The values are captured from the {@link Client} on the LOGGED_IN game state, since
 * {@code accountHash} and the local player are only meaningful after login. Stored as
 * Strings to match the headers the server reads (X-Account-Hash, X-Account-Username).
 *
 * Cleared on LOGIN_SCREEN / HOPPING / CONNECTION_LOST so push services know to back off.
 */
@Slf4j
@Singleton
public class PluginAccountState
{
	/**
	 * Worlds with a separate, fresh-start save (Leagues, Deadman, tournament,
	 * fresh-start, beta, quest-speedrunning). Account data on these does not
	 * belong to the player's main save, so we must not upload it.
	 */
	private static final Set<WorldType> NON_STANDARD_WORLDS = EnumSet.of(
		WorldType.SEASONAL,
		WorldType.DEADMAN,
		WorldType.TOURNAMENT_WORLD,
		WorldType.FRESH_START_WORLD,
		WorldType.NOSAVE_MODE,
		WorldType.BETA_WORLD,
		WorldType.QUEST_SPEEDRUNNING);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	private volatile String accountHash;
	private volatile String username;
	private volatile String accountType;

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();

		if (state == GameState.LOGGED_IN)
		{
			refreshFromClient();
		}
		else if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING || state == GameState.CONNECTION_LOST)
		{
			clear();
		}
	}

	/**
	 * Called by {@code RuneManagerPlugin.startUp()} so a plugin enabled after the
	 * player is already in-game still captures the hash — without waiting for a
	 * LOGGED_IN transition that won't fire if no state change happens. Marshalled
	 * through {@link ClientThread} because {@code getLocalPlayer()} must run on
	 * the client thread.
	 */
	public void captureIfLoggedIn()
	{
		clientThread.invoke(() -> {
			GameState state = client.getGameState();
			log.debug("RuneManager: captureIfLoggedIn ran — gameState={}", state);
			if (state == GameState.LOGGED_IN)
			{
				refreshFromClient();
			}
		});
	}

	public boolean isReady()
	{
		if ((accountHash == null || username == null) && client.getGameState() == GameState.LOGGED_IN)
		{
			// Lazy capture for the mid-session plugin-enable case where no
			// LOGGED_IN GameStateChanged event fires. Push services dispatch
			// through @Subscribe (client thread), so calling refreshFromClient
			// inline here is safe — getLocalPlayer() is on the right thread.
			refreshFromClient();
		}
		return accountHash != null && username != null && isStandardWorld();
	}

	/**
	 * Whether the current world saves to the player's main account. Leagues,
	 * Deadman, etc. share the account hash but have a separate save, so uploading
	 * their data would clobber the real account — we skip all pushes there.
	 */
	public boolean isStandardWorld()
	{
		EnumSet<WorldType> worldType = client.getWorldType();
		return worldType == null || Collections.disjoint(worldType, NON_STANDARD_WORLDS);
	}

	public String accountHash()
	{
		return accountHash;
	}

	public String username()
	{
		return username;
	}

	/**
	 * The in-game account type ("normal", "ironman", "group_ironman", …) read
	 * from the ACCOUNT_TYPE varbit. The server uses it to set the account type
	 * and, in GROUP mode, to confirm the account is a Group Ironman.
	 */
	public String accountType()
	{
		return accountType;
	}

	private void refreshFromClient()
	{
		long hash = client.getAccountHash();
		Player player = client.getLocalPlayer();

		if (hash == -1L || player == null || player.getName() == null)
		{
			log.debug("RuneManager: refreshFromClient bailed — hash={} player={} name={}",
				hash,
				player == null ? "null" : "present",
				player == null ? "n/a" : player.getName());
			return;
		}

		this.accountHash = String.valueOf(hash);
		this.username = player.getName();
		this.accountType = mapAccountType(client.getVarbitValue(Varbits.ACCOUNT_TYPE));
		log.debug("RuneManager: captured account hash={} username={} type={}", accountHash, username, accountType);
	}

	/**
	 * Map the ACCOUNT_TYPE varbit to the website's account-type strings. The
	 * three Group Ironman variants (group, hardcore group, unranked group) all
	 * collapse to "group_ironman".
	 */
	private static String mapAccountType(int varbit)
	{
		switch (varbit)
		{
			case 1:
				return "ironman";
			case 2:
				return "ultimate_ironman";
			case 3:
				return "hardcore_ironman";
			case 4:
			case 5:
			case 6:
				return "group_ironman";
			default:
				return "normal";
		}
	}

	private void clear()
	{
		this.accountHash = null;
		this.username = null;
		this.accountType = null;
	}
}
