package com.zlimon.runemanager;

import com.google.inject.Provides;
import com.zlimon.runemanager.push.AnnouncementService;
import com.zlimon.runemanager.push.AvatarPushService;
import com.zlimon.runemanager.push.BankPushService;
import com.zlimon.runemanager.push.EquipmentPushService;
import com.zlimon.runemanager.push.HeartbeatService;
import com.zlimon.runemanager.push.InventoryPushService;
import com.zlimon.runemanager.push.LootPushService;
import com.zlimon.runemanager.push.LootingBagPushService;
import com.zlimon.runemanager.push.PositionPushService;
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
	private EquipmentPushService equipmentPushService;

	@Inject
	private QuestPushService questPushService;

	@Inject
	private LootingBagPushService lootingBagPushService;

	@Inject
	private LootPushService lootPushService;

	@Inject
	private PositionPushService positionPushService;

	@Inject
	private VitalsPushService vitalsPushService;

	@Inject
	private ResourcePackPushService resourcePackPushService;

	@Inject
	private AvatarPushService avatarPushService;

	@Inject
	private HeartbeatService heartbeatService;

	@Inject
	private AnnouncementService announcementService;

	@Inject
	private ConfigManager configManager;

	@Override
	protected void startUp()
	{
		log.debug("RuneManager started");

		// Plugins are auto-registered with the EventBus, but our helper singletons aren't.
		eventBus.register(accountState);
		eventBus.register(inventoryPushService);
		eventBus.register(bankPushService);
		eventBus.register(equipmentPushService);
		eventBus.register(questPushService);
		eventBus.register(lootingBagPushService);
		eventBus.register(lootPushService);
		eventBus.register(positionPushService);
		eventBus.register(vitalsPushService);
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
		eventBus.unregister(equipmentPushService);
		eventBus.unregister(questPushService);
		eventBus.unregister(lootingBagPushService);
		eventBus.unregister(lootPushService);
		eventBus.unregister(positionPushService);
		eventBus.unregister(vitalsPushService);
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

		// One-shot manual avatar capture+upload. Auto-resets; the actual capture
		// happens on the next idle tick (see AvatarPushService).
		if ("uploadAvatar".equals(event.getKey()) && "true".equals(event.getNewValue()))
		{
			avatarPushService.requestImmediateCapture();
			configManager.setConfiguration(RuneManagerConfig.GROUP, "uploadAvatar", false);
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
