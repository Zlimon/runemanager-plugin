package com.zlimon.runemanager.push;

import com.zlimon.runemanager.RuneManagerApi;
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
	private static final String GROUP = "resourcepacks";
	private static final String KEY_MODE = "resourcePack";
	private static final String KEY_HUB_PACK = "selectedHubPack";

	@Inject
	private ConfigManager configManager;

	@Inject
	private RuneManagerApi api;

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!GROUP.equals(event.getGroup()))
		{
			return;
		}

		if (!KEY_MODE.equals(event.getKey()) && !KEY_HUB_PACK.equals(event.getKey()))
		{
			return;
		}

		pushCurrent();
	}

	/**
	 * Read the active hub pack from the community plugin's config and push it.
	 * Exposed for {@code RuneManagerPlugin.startUp()} so plugins enabled after
	 * RuneLite has already loaded still sync once.
	 */
	public void pushCurrent()
	{
		String mode = configManager.getConfiguration(GROUP, KEY_MODE);

		if (!"HUB".equals(mode))
		{
			log.debug("RuneManager: RL Resource Packs plugin not in HUB mode (got {}), skipping pack push", mode);
			return;
		}

		String hubPack = configManager.getConfiguration(GROUP, KEY_HUB_PACK);

		if (hubPack == null || hubPack.isEmpty())
		{
			log.debug("RuneManager: HUB mode but no selectedHubPack, skipping pack push");
			return;
		}

		api.putUserScoped("/api/plugin/resource-pack", Collections.singletonMap("name", hubPack));
	}
}
