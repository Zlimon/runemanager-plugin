package com.zlimon.runemanager.push;

import com.zlimon.runemanager.RuneManagerApi;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;

@Slf4j
@Singleton
public class LootingBagPushService
{
	@Inject
	private RuneManagerApi api;

	@Inject
	private Client client;

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.LOOTING_BAG)
		{
			return;
		}

		api.put("/api/plugin/looting-bag", buildPayload(event.getItemContainer()));
	}

	/**
	 * Push the current looting bag on demand (full-account snapshot). Only
	 * available once the bag has been opened this session.
	 */
	public void pushCurrent()
	{
		ItemContainer container = client.getItemContainer(InventoryID.LOOTING_BAG);
		if (container != null)
		{
			api.put("/api/plugin/looting-bag", buildPayload(container));
		}
	}

	private Map<String, Object> buildPayload(ItemContainer container)
	{
		Item[] items = container.getItems();
		int[][] slots = new int[items.length][2];
		for (int i = 0; i < items.length; i++)
		{
			slots[i][0] = items[i].getId();
			slots[i][1] = items[i].getQuantity();
		}

		Map<String, Object> body = new HashMap<>();
		body.put("looting_bag", slots);
		return body;
	}
}
