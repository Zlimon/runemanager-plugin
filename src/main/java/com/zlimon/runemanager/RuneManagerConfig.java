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
		name = "Data sync",
		description = "Choose which in-game data is uploaded to your RuneManager website",
		position = 2
	)
	String dataSyncSection = "dataSync";

	@ConfigSection(
		name = "Screenshots",
		description = "Attach a screenshot of the moment to your live-feed events",
		position = 3
	)
	String screenshotsSection = "screenshots";

	@ConfigSection(
		name = "Theme sync",
		description = "Mirror your in-game resource pack to your RuneManager website",
		position = 4
	)
	String themeSyncSection = "themeSync";

	@ConfigSection(
		name = "Live Map",
		description = "Share your in-game location on your RuneManager website's Live Map",
		position = 5
	)
	String liveMapSection = "liveMap";

	@ConfigItem(
		keyName = "captureScreenshots",
		name = "Attach screenshots to feed",
		description = "Capture a screenshot when you get a notable drop, combat achievement, or collection log slot, and attach it to that event on your RuneManager live feed.",
		warning = "This uploads in-game screenshots to your RuneManager website, where they appear on the public feed.",
		section = screenshotsSection,
		position = 0
	)
	default boolean captureScreenshots()
	{
		return false;
	}

	@ConfigItem(
		keyName = "screenshotLootValue",
		name = "Screenshot loot value (gp)",
		description = "Only screenshot loot drops worth at least this much. Combat achievements and collection log slots are always screenshotted when enabled.",
		section = screenshotsSection,
		position = 1
	)
	default int screenshotLootValue()
	{
		return 1_000_000;
	}

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

	// --- Data sync toggles ----------------------------------------------------
	// Each gates the matching plugin → backend push (see RuneManagerApi). All
	// default on, so a fresh install syncs everything; flip one off to keep that
	// data off your RuneManager website.

	@ConfigItem(
		keyName = "syncInventory",
		name = "Inventory",
		description = "Upload your inventory contents",
		section = dataSyncSection,
		position = 0
	)
	default boolean syncInventory()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncBank",
		name = "Bank",
		description = "Upload your bank contents (and the shared group bank in Group Ironman)",
		section = dataSyncSection,
		position = 1
	)
	default boolean syncBank()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncEquipment",
		name = "Equipment",
		description = "Upload your worn equipment",
		section = dataSyncSection,
		position = 2
	)
	default boolean syncEquipment()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncLootingBag",
		name = "Looting bag",
		description = "Upload your looting bag contents",
		section = dataSyncSection,
		position = 3
	)
	default boolean syncLootingBag()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncLoot",
		name = "Loot",
		description = "Upload loot drops (NPC kills, chests, etc.) to your loot log",
		section = dataSyncSection,
		position = 4
	)
	default boolean syncLoot()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncQuests",
		name = "Quests",
		description = "Upload your quest completion state",
		section = dataSyncSection,
		position = 5
	)
	default boolean syncQuests()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncDiaries",
		name = "Achievement diaries",
		description = "Upload your Achievement Diary completion",
		section = dataSyncSection,
		position = 6
	)
	default boolean syncDiaries()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncCombatAchievements",
		name = "Combat achievements",
		description = "Upload your Combat Achievement points/tiers and post task unlocks to the live feed",
		section = dataSyncSection,
		position = 7
	)
	default boolean syncCombatAchievements()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncCollectionLog",
		name = "Collection log",
		description = "Post collection-log slot unlocks to the live feed",
		section = dataSyncSection,
		position = 8
	)
	default boolean syncCollectionLog()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncNotableEvents",
		name = "Notable events",
		description = "Post pets, deaths, and reward-chest openings to the live feed",
		section = dataSyncSection,
		position = 9
	)
	default boolean syncNotableEvents()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncVitals",
		name = "Vitals",
		description = "Upload live HP/prayer/run/special values for the status orbs",
		section = dataSyncSection,
		position = 10
	)
	default boolean syncVitals()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncActivity",
		name = "Activity",
		description = "Upload your current in-game activity/area",
		section = dataSyncSection,
		position = 11
	)
	default boolean syncActivity()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncAvatar",
		name = "Avatar",
		description = "Upload a snapshot of your character model (on login and when your equipment changes)",
		section = dataSyncSection,
		position = 12
	)
	default boolean syncAvatar()
	{
		return true;
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

	@ConfigItem(
		keyName = "shareLocation",
		name = "Share my location",
		description = "Send your in-game position to your RuneManager website so your account shows on the Live Map. Off by default.",
		warning = "This continuously submits your in-game location to your RuneManager instance while enabled",
		section = liveMapSection,
		position = 0
	)
	default boolean shareLocation()
	{
		return false;
	}
}
