package com.zlimon.runemanager;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(RuneManagerConfig.GROUP)
public interface RuneManagerConfig extends Config
{
	String GROUP = "runemanager";

	@ConfigSection(
		name = "Connection",
		description = "RuneManager instance to sync with",
		position = 0
	)
	String connectionSection = "connection";

	@ConfigSection(
		name = "Credentials",
		description = "Used once to obtain an API token; the password can be cleared after",
		position = 1
	)
	String credentialsSection = "credentials";

	@ConfigSection(
		name = "Theme sync",
		description = "Mirror your in-game resource pack to your RuneManager website",
		position = 2
	)
	String themeSyncSection = "themeSync";

	@ConfigItem(
		keyName = "baseUrl",
		name = "Base URL",
		description = "The URL of your RuneManager instance (no trailing slash)",
		section = connectionSection,
		position = 0
	)
	default String baseUrl()
	{
		return "http://localhost";
	}

	@ConfigItem(
		keyName = "email",
		name = "Email",
		description = "Your RuneManager account email",
		section = credentialsSection,
		position = 0
	)
	default String email()
	{
		return "";
	}

	@ConfigItem(
		keyName = "password",
		name = "Password",
		description = "Used once to obtain an API token; clear after login succeeds",
		secret = true,
		section = credentialsSection,
		position = 1
	)
	default String password()
	{
		return "";
	}

	@ConfigItem(
		keyName = "token",
		name = "API token",
		description = "Issued by the RuneManager API after login. Managed by the plugin.",
		hidden = true
	)
	default String token()
	{
		return "";
	}

	@ConfigItem(
		keyName = "applyThemeToWebsite",
		name = "Apply RuneLite theme to website",
		description = "When the community Resource Packs plugin is in HUB mode, push the active pack name to your RuneManager website so it renders in the same theme.",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		section = themeSyncSection,
		position = 0
	)
	default boolean applyThemeToWebsite()
	{
		return false;
	}
}
