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

	/**
	 * One-shot toggle: flipping this to true dumps every HiscoreSkill sprite
	 * (the 25×25 icons the RuneLite hiscore panel uses) into
	 * {@code ~/.runelite/runemanager-hiscore-icons/}. The flag is auto-reset
	 * to false once the dump completes so it doesn't loop on every launch.
	 *
	 * Intended for the website maintainer to bootstrap /public/images/boss/
	 * with the in-game icons; can be removed once the assets are committed.
	 */
	@ConfigItem(
		keyName = "extractHiscoreIcons",
		name = "Extract hiscore icons",
		description = "Dump the RuneLite hiscore-panel sprites to ~/.runelite/runemanager-hiscore-icons/ then auto-clear",
		section = connectionSection,
		position = 50
	)
	default boolean extractHiscoreIcons()
	{
		return false;
	}

	/**
	 * One-shot toggle: captures your current player model and uploads it as the
	 * RuneManager account avatar. The avatar also re-syncs automatically when you
	 * change equipment; this button forces a capture now. Auto-resets to false.
	 */
	@ConfigItem(
		keyName = "uploadAvatar",
		name = "Sync avatar now",
		description = "Capture your current character model and upload it as your RuneManager avatar (also happens automatically on equipment change)",
		section = connectionSection,
		position = 60
	)
	default boolean uploadAvatar()
	{
		return false;
	}
}
