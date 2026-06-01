package com.zlimon.runemanager.push;

import com.zlimon.runemanager.RuneManagerApi;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;

@Slf4j
@Singleton
public class EquipmentPushService
{
	/** OSRS KitType slot indices that have a backing column on the server. */
	private static final int[] WORN_SLOTS = {0, 1, 2, 3, 4, 5, 7, 9, 10, 12, 13};

	@Inject
	private RuneManagerApi api;

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.WORN)
		{
			return;
		}

		api.put("/api/plugin/equipment", buildPayload(event.getItemContainer()));
	}

	/**
	 * The server's EquipmentController reads a sparse map keyed by KitType index. We only
	 * emit the slots it persists (gaps 6, 8, 11 are skipped). -1 in slot 0 = empty.
	 */
	private Map<String, Object> buildPayload(ItemContainer container)
	{
		Item[] items = container.getItems();
		Map<Integer, int[]> equipment = new LinkedHashMap<>();

		for (int slot : WORN_SLOTS)
		{
			if (slot < items.length)
			{
				Item item = items[slot];
				equipment.put(slot, new int[]{item.getId(), item.getQuantity()});
			}
			else
			{
				equipment.put(slot, new int[]{-1, 0});
			}
		}

		Map<String, Object> body = new HashMap<>();
		body.put("equipment", equipment);
		return body;
	}
}
