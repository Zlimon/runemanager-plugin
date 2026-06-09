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

/**
 * Pushes the shared Group Ironman group bank (SPEC §5.2). Any group member who
 * opens the group storage submits its full contents; the backend stores it as a
 * single overwritten document, so the website's shared bank view stays current.
 * The backend ignores the push outside GROUP mode.
 */
@Slf4j
@Singleton
public class GroupBankPushService
{
	/** Shared Group Ironman group storage (gameval INV_GROUP_TEMP = 659). */
	private static final int GROUP_STORAGE = InventoryID.INV_GROUP_TEMP;

	@Inject
	private RuneManagerApi api;

	@Inject
	private Client client;

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != GROUP_STORAGE)
		{
			return;
		}

		api.put("/api/plugin/group-bank", buildPayload(event.getItemContainer()));
	}

	/**
	 * Push the current group bank on demand (full-account snapshot). Only
	 * available once the group storage has been opened this session.
	 */
	public void pushCurrent()
	{
		ItemContainer container = client.getItemContainer(GROUP_STORAGE);
		if (container != null)
		{
			api.put("/api/plugin/group-bank", buildPayload(container));
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
		body.put("group_bank", slots);
		return body;
	}
}
