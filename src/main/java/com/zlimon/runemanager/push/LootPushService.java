package com.zlimon.runemanager.push;

import com.zlimon.runemanager.RuneManagerApi;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.util.Text;

/**
 * Mirrors RuneLite's official Loot Tracker to the backend.
 *
 * The Loot Tracker plugin posts a single {@link LootReceived} for every drop it
 * records — NPC kills, PvP, and special encounters (Barrows, raids, pickpocket,
 * farming contracts, …). Because only the Loot Tracker posts this event, simply
 * subscribing to it means we mirror exactly what the user already tracks, and
 * nothing when that plugin is disabled.
 *
 * The Loot Tracker's "Ignored items" / "Ignored Loot" config lists aren't
 * applied to the event itself (they only hide entries in its panel), so we read
 * those CSV lists straight from the {@code loottracker} config group and apply
 * them here: an ignored source skips the whole drop, and ignored items are
 * stripped from the remaining ones.
 */
@Slf4j
@Singleton
public class LootPushService
{
	private static final String LOOT_TRACKER_GROUP = "loottracker";

	@Inject
	private RuneManagerApi api;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ConfigManager configManager;

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		if (ignored("ignoredEvents").contains(event.getName()))
		{
			return;
		}

		Set<String> ignoredItems = ignored("ignoredItems");
		List<Map<String, Integer>> items = new ArrayList<>();
		long totalValue = 0;

		for (ItemStack stack : event.getItems())
		{
			ItemComposition composition = itemManager.getItemComposition(stack.getId());
			if (ignoredItems.contains(composition.getMembersName()))
			{
				continue;
			}

			Map<String, Integer> item = new HashMap<>();
			item.put("id", stack.getId());
			item.put("quantity", stack.getQuantity());
			items.add(item);

			totalValue += (long) itemManager.getItemPrice(stack.getId()) * stack.getQuantity();
		}

		if (items.isEmpty())
		{
			return;
		}

		Map<String, Object> entry = new HashMap<>();
		entry.put("source", event.getName());
		entry.put("items", items);
		entry.put("total_value", totalValue);
		entry.put("killed_at", Instant.now().toString());

		Map<String, Object> body = new HashMap<>();
		body.put("loot", List.of(entry));

		api.post("/api/plugin/loot", body);
	}

	/**
	 * The Loot Tracker stores its ignore lists as CSV under the {@code loottracker}
	 * config group; mirror its parsing so our filtering matches the panel exactly.
	 */
	private Set<String> ignored(String key)
	{
		String csv = configManager.getConfiguration(LOOT_TRACKER_GROUP, key);
		if (csv == null || csv.isEmpty())
		{
			return Set.of();
		}

		return Set.copyOf(Text.fromCSV(csv));
	}
}
