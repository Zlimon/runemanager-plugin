package com.zlimon.runemanager;

import com.google.inject.Provides;
import com.zlimon.runemanager.push.AccountSnapshotService;
import com.zlimon.runemanager.push.AnnouncementService;
import com.zlimon.runemanager.push.AvatarPushService;
import com.zlimon.runemanager.push.BankPushService;
import com.zlimon.runemanager.push.ClanPushService;
import com.zlimon.runemanager.push.CollectionLogPushService;
import com.zlimon.runemanager.push.CombatAchievementPushService;
import com.zlimon.runemanager.push.DiaryPushService;
import com.zlimon.runemanager.push.EquipmentPushService;
import com.zlimon.runemanager.push.GroupBankPushService;
import com.zlimon.runemanager.push.HeartbeatService;
import com.zlimon.runemanager.push.InventoryPushService;
import com.zlimon.runemanager.push.NotableEventPushService;
import com.zlimon.runemanager.push.LootPushService;
import com.zlimon.runemanager.push.LootingBagPushService;
import com.zlimon.runemanager.push.PositionPushService;
import com.zlimon.runemanager.push.StatusPushService;
import com.zlimon.runemanager.push.VitalsPushService;
import com.zlimon.runemanager.push.QuestPushService;
import com.zlimon.runemanager.push.ResourcePackPushService;
import java.time.temporal.ChronoUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;

@Slf4j
@PluginDescriptor(
	name = "RuneManager",
	description = "Sync your OSRS account data with a RuneManager instance.",
	tags = {"runemanager", "hiscores", "sync"}
)
public class RuneManagerPlugin extends Plugin
{
	@Inject
	private RuneManagerConfig config;

	@Inject
	private RuneManagerAuthService auth;

	@Inject
	private EventBus eventBus;

	@Inject
	private PluginAccountState accountState;

	@Inject
	private InventoryPushService inventoryPushService;

	@Inject
	private BankPushService bankPushService;

	@Inject
	private GroupBankPushService groupBankPushService;

	@Inject
	private EquipmentPushService equipmentPushService;

	@Inject
	private QuestPushService questPushService;

	@Inject
	private DiaryPushService diaryPushService;

	@Inject
	private CombatAchievementPushService combatAchievementPushService;

	@Inject
	private CollectionLogPushService collectionLogPushService;

	@Inject
	private NotableEventPushService notableEventPushService;

	@Inject
	private LootingBagPushService lootingBagPushService;

	@Inject
	private LootPushService lootPushService;

	@Inject
	private PositionPushService positionPushService;

	@Inject
	private VitalsPushService vitalsPushService;

	@Inject
	private StatusPushService statusPushService;

	@Inject
	private ClanPushService clanPushService;

	@Inject
	private AccountSnapshotService accountSnapshotService;

	@Inject
	private ResourcePackPushService resourcePackPushService;

	@Inject
	private AvatarPushService avatarPushService;

	@Inject
	private HeartbeatService heartbeatService;

	@Inject
	private AnnouncementService announcementService;

	@Override
	protected void startUp()
	{
		log.debug("RuneManager started");

		// Plugins are auto-registered with the EventBus, but our helper singletons aren't.
		eventBus.register(accountState);
		eventBus.register(inventoryPushService);
		eventBus.register(bankPushService);
		eventBus.register(groupBankPushService);
		eventBus.register(equipmentPushService);
		eventBus.register(questPushService);
		eventBus.register(diaryPushService);
		eventBus.register(combatAchievementPushService);
		eventBus.register(collectionLogPushService);
		eventBus.register(notableEventPushService);
		eventBus.register(lootingBagPushService);
		eventBus.register(lootPushService);
		eventBus.register(positionPushService);
		eventBus.register(vitalsPushService);
		eventBus.register(statusPushService);
		eventBus.register(clanPushService);
		eventBus.register(accountSnapshotService);
		eventBus.register(resourcePackPushService);
		eventBus.register(avatarPushService);

		attemptLoginIfNeeded();

		// If RuneManager was enabled mid-session (player already logged in),
		// no GameStateChanged event will fire — capture the hash now instead
		// of waiting for the next login transition. QuestPushService doesn't
		// need a similar hook: it retries on GameTick until the push lands.
		accountState.captureIfLoggedIn();

		// Sync the currently-configured resource pack once on startup so users who
		// enabled RuneManager after RuneLite was already running still get mirrored.
		resourcePackPushService.pushCurrent();
	}

	@Override
	protected void shutDown()
	{
		eventBus.unregister(accountState);
		eventBus.unregister(inventoryPushService);
		eventBus.unregister(bankPushService);
		eventBus.unregister(groupBankPushService);
		eventBus.unregister(equipmentPushService);
		eventBus.unregister(questPushService);
		eventBus.unregister(diaryPushService);
		eventBus.unregister(combatAchievementPushService);
		eventBus.unregister(collectionLogPushService);
		eventBus.unregister(notableEventPushService);
		eventBus.unregister(lootingBagPushService);
		eventBus.unregister(lootPushService);
		eventBus.unregister(positionPushService);
		eventBus.unregister(vitalsPushService);
		eventBus.unregister(statusPushService);
		eventBus.unregister(clanPushService);
		eventBus.unregister(accountSnapshotService);
		eventBus.unregister(resourcePackPushService);
		eventBus.unregister(avatarPushService);

		log.debug("RuneManager stopped");
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!RuneManagerConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		// User edited their credentials or base URL — try logging in again.
		if ("email".equals(event.getKey()) || "password".equals(event.getKey()) || "baseUrl".equals(event.getKey()))
		{
			attemptLoginIfNeeded();
		}

		// Token was rejected by the backend and cleared (see RuneManagerApi) — if
		// credentials are still saved, re-log in immediately instead of leaving the
		// user stuck with no token until they touch the config themselves.
		if ("token".equals(event.getKey()) && (event.getNewValue() == null || event.getNewValue().isEmpty()))
		{
			attemptLoginIfNeeded();
		}

		// Token was just (re)issued — push the full account snapshot so a freshly
		// linked account uploads everything without waiting for an in-game change.
		if ("token".equals(event.getKey()) && event.getNewValue() != null && !event.getNewValue().isEmpty())
		{
			accountSnapshotService.requestResnapshot();
		}
	}

	/**
	 * Presence heartbeat — fires regardless of game state, but the API call
	 * no-ops unless the player is logged in and the account state is ready.
	 */
	@Schedule(period = 60, unit = ChronoUnit.SECONDS)
	public void heartbeat()
	{
		heartbeatService.ping();
	}

	/**
	 * Pull and display any new announcements (SPEC §9.2). No-ops unless logged
	 * in and ready.
	 */
	@Schedule(period = 120, unit = ChronoUnit.SECONDS)
	public void pollAnnouncements()
	{
		announcementService.poll();
	}

	private void attemptLoginIfNeeded()
	{
		String email = config.email();
		String password = config.password();

		if (email.isEmpty() || password.isEmpty())
		{
			log.debug("RuneManager: credentials not set, skipping login");
			return;
		}

		if (!config.token().isEmpty())
		{
			log.debug("RuneManager: token already present, skipping login (clear the token config to force a re-login)");
			return;
		}

		auth.login(config.baseUrl(), email, password);
	}

	@Provides
	RuneManagerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RuneManagerConfig.class);
	}
}
