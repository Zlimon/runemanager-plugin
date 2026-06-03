package com.zlimon.runemanager;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
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
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	private volatile String accountHash;
	private volatile String username;

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
		return accountHash != null && username != null;
	}

	public String accountHash()
	{
		return accountHash;
	}

	public String username()
	{
		return username;
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
		log.debug("RuneManager: captured account hash={} username={}", accountHash, username);
	}

	private void clear()
	{
		this.accountHash = null;
		this.username = null;
	}
}
