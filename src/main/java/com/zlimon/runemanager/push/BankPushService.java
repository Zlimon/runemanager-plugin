package com.zlimon.runemanager.push;

import com.zlimon.runemanager.RuneManagerApi;
import java.util.HashMap;
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
public class BankPushService
{
	@Inject
	private RuneManagerApi api;

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.BANK)
		{
			return;
		}

		api.put("/api/plugin/bank", buildPayload(event.getItemContainer()));
	}

	/**
	 * The OSRS bank is a flat ItemContainer; tab boundaries are tracked in separate varbits.
	 * Until we parse those out, ship the whole bank as a single tab — the server's schema
	 * is {@code bank: [tab, tab, …]} where each tab is {@code [[itemId, qty], …]}.
	 */
	private Map<String, Object> buildPayload(ItemContainer container)
	{
		Item[] items = container.getItems();
		int[][] tab = new int[items.length][2];
		for (int i = 0; i < items.length; i++)
		{
			tab[i][0] = items[i].getId();
			tab[i][1] = items[i].getQuantity();
		}

		Map<String, Object> body = new HashMap<>();
		body.put("bank", new int[][][]{tab});
		return body;
	}
}
