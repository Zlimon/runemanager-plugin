package com.zlimon.runemanager;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
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

	@Override
	protected void startUp()
	{
		log.debug("RuneManager started");
		attemptLoginIfNeeded();
	}

	@Override
	protected void shutDown()
	{
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
