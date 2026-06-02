package com.zlimon.runemanager.push;

import com.zlimon.runemanager.RuneManagerApi;
import com.zlimon.runemanager.RuneManagerConfig;
import java.util.Collections;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;

/**
 * Mirrors the currently-active RuneLite resource pack to the RuneManager backend
 * so the website's theme follows the in-client theme.
 *
 * Off by default — gated on {@link RuneManagerConfig#applyThemeToWebsite()}.
 *
 * Reads from the community Resource Packs plugin (melky's) — config group
 * {@code resourcepacks}:
 * <ul>
 *   <li>{@code resourcePack} — enum (FIRST/SECOND/THIRD/HUB). Only HUB has a name
 *       we can match against {@code resource_packs.name} server-side; the local
 *       slots store filesystem paths.</li>
 *   <li>{@code selectedHubPack} — the active hub pack's name (when in HUB mode).</li>
 * </ul>
 *
 * When the user is on a local pack slot we stay silent and let whatever they
 * picked manually on the website persist.
 */
@Slf4j
@Singleton
public class ResourcePackPushService
{
	private static final String RESOURCE_PACKS_GROUP = "resourcepacks";
	private static final String KEY_MODE = "resourcePack";
	private static final String KEY_HUB_PACK = "selectedHubPack";

	private static final String KEY_APPLY_THEME = "applyThemeToWebsite";

	@Inject
	private ConfigManager configManager;

	@Inject
	private RuneManagerConfig config;

	@Inject
	private RuneManagerApi api;

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		// Active pack changed in the community plugin.
		if (RESOURCE_PACKS_GROUP.equals(event.getGroup())
			&& (KEY_MODE.equals(event.getKey()) || KEY_HUB_PACK.equals(event.getKey())))
		{
			pushCurrent();
			return;
		}

		// User flipped our toggle on — push the current state immediately so it
		// reflects without waiting for the next pack change.
		if (RuneManagerConfig.GROUP.equals(event.getGroup())
			&& KEY_APPLY_THEME.equals(event.getKey())
			&& "true".equals(event.getNewValue()))
		{
			pushCurrent();
		}
	}

	/**
	 * Read the active hub pack from the community plugin's config and push it.
	 * Exposed for {@code RuneManagerPlugin.startUp()} so plugins enabled after
	 * RuneLite has already loaded still sync once.
	 *
	 * Short-circuits when the user hasn't opted in to theme sync.
	 */
	public void pushCurrent()
	{
		if (!config.applyThemeToWebsite())
		{
			log.debug("RuneManager: applyThemeToWebsite is off, skipping pack push");
			return;
		}

		String mode = configManager.getConfiguration(RESOURCE_PACKS_GROUP, KEY_MODE);

		if (!"HUB".equals(mode))
		{
			log.debug("RuneManager: RL Resource Packs plugin not in HUB mode (got {}), skipping pack push", mode);
			return;
		}

		String hubPack = configManager.getConfiguration(RESOURCE_PACKS_GROUP, KEY_HUB_PACK);

		if (hubPack == null || hubPack.isEmpty())
		{
			log.debug("RuneManager: HUB mode but no selectedHubPack, skipping pack push");
			return;
		}

		api.putUserScoped("/api/plugin/resource-pack", Collections.singletonMap("name", hubPack));
	}
}
