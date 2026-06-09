package com.zlimon.runemanager.push;

import com.zlimon.runemanager.PluginAccountState;
import com.zlimon.runemanager.RuneManagerApi;
import com.zlimon.runemanager.RuneManagerConfig;
import java.util.HashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

/**
 * Pushes the full account snapshot (inventory, equipment, bank, looting bag,
 * quests, avatar) once per login — and again whenever the API token is (re)set —
 * so a freshly linked account uploads everything without waiting for an in-game
 * change to trigger each per-container push.
 *
 * Runs on the client thread via GameTick (safe to read item containers) and
 * retries until the account state + token are ready. Bank and looting bag are
 * only available once opened in-session, so they push only when loaded.
 */
@Slf4j
@Singleton
public class AccountSnapshotService
{
	@Inject
	private PluginAccountState accountState;

	@Inject
	private RuneManagerConfig config;

	@Inject
	private RuneManagerApi api;

	@Inject
	private HeartbeatService heartbeat;

	@Inject
	private InventoryPushService inventory;

	@Inject
	private EquipmentPushService equipment;

	@Inject
	private BankPushService bank;

	@Inject
	private LootingBagPushService lootingBag;

	@Inject
	private QuestPushService quests;

	@Inject
	private AvatarPushService avatar;

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
		if (pushedThisSession || !accountState.isReady() || config.token().isEmpty())
		{
			return;
		}

		pushAll();
		pushedThisSession = true;
	}

	/** Force a fresh snapshot on the next ready tick (e.g. after the token is set). */
	public void requestResnapshot()
	{
		pushedThisSession = false;
	}

	private void pushAll()
	{
		log.debug("RuneManager: pushing full account snapshot");
		inventory.pushCurrent();
		equipment.pushCurrent();
		bank.pushCurrent();
		lootingBag.pushCurrent();
		quests.pushCurrent();
		avatar.requestImmediateCapture();

		// Stats (skills/bosses/clues) — ask the server to refresh from the OSRS
		// hiscores. Online status — stamp last_seen now rather than waiting for
		// the 60s heartbeat schedule.
		api.put("/api/plugin/hiscores", new HashMap<>());
		heartbeat.ping();
	}
}
