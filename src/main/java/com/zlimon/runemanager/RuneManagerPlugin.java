package com.zlimon.runemanager;

import com.google.inject.Provides;
import com.zlimon.runemanager.push.BankPushService;
import com.zlimon.runemanager.push.EquipmentPushService;
import com.zlimon.runemanager.push.InventoryPushService;
import com.zlimon.runemanager.push.LootingBagPushService;
import com.zlimon.runemanager.push.QuestPushService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

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

		attemptLoginIfNeeded();
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
