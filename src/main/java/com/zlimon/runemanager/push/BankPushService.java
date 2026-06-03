package com.zlimon.runemanager.push;

import com.zlimon.runemanager.RuneManagerApi;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Varbits;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;

@Slf4j
@Singleton
public class BankPushService
{
	/**
	 * Per-tab item counts. Tab 0 ("Main" / "All items") holds whatever's not
	 * in a numbered tab; only tabs 1-9 have explicit count varbits.
	 */
	private static final int[] NUMBERED_TAB_COUNT_VARBITS = {
		Varbits.BANK_TAB_ONE_COUNT,
		Varbits.BANK_TAB_TWO_COUNT,
		Varbits.BANK_TAB_THREE_COUNT,
		Varbits.BANK_TAB_FOUR_COUNT,
		Varbits.BANK_TAB_FIVE_COUNT,
		Varbits.BANK_TAB_SIX_COUNT,
		Varbits.BANK_TAB_SEVEN_COUNT,
		Varbits.BANK_TAB_EIGHT_COUNT,
		Varbits.BANK_TAB_NINE_COUNT,
	};

	@Inject
	private Client client;

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
	 * The bank ItemContainer is flat; OSRS stores tab boundaries in varbits.
	 * Layout: items in tabs 1-9 come first in the array (in tab order, sized
	 * per the {@code BANK_TAB_*_COUNT} varbits), then the remainder belongs
	 * to tab 0 ("Main").
	 *
	 * Wire shape (matches the server's BankResource — tab 0 first so it lines
	 * up with the "main" placeholder tab in the website's Bank.vue):
	 *   {@code bank: [main, tab1, tab2, …, tabN]}
	 * Trailing empty numbered tabs are trimmed so the payload doesn't carry
	 * empty entries for tabs the player hasn't created.
	 */
	private Map<String, Object> buildPayload(ItemContainer container)
	{
		Item[] items = container.getItems();

		int[] numberedTabSizes = new int[NUMBERED_TAB_COUNT_VARBITS.length];
		int numberedTotal = 0;
		for (int i = 0; i < NUMBERED_TAB_COUNT_VARBITS.length; i++)
		{
			int size = Math.max(0, client.getVarbitValue(NUMBERED_TAB_COUNT_VARBITS[i]));
			numberedTabSizes[i] = size;
			numberedTotal += size;
		}

		// Guard against varbits being unset (e.g. bank never opened this session):
		// if every numbered tab is 0 we just fall through with a single main tab.
		int mainCount = Math.max(0, items.length - numberedTotal);

		List<int[][]> tabs = new ArrayList<>(1 + numberedTabSizes.length);
		// Main / tab 0 — sits AFTER the numbered tabs in the flat array.
		tabs.add(slice(items, numberedTotal, mainCount));

		int offset = 0;
		for (int size : numberedTabSizes)
		{
			tabs.add(slice(items, offset, size));
			offset += size;
		}

		// Trim trailing empty numbered tabs — keep tab 0 even if empty so the
		// server sees a consistent "main tab always present" shape.
		while (tabs.size() > 1 && tabs.get(tabs.size() - 1).length == 0)
		{
			tabs.remove(tabs.size() - 1);
		}

		Map<String, Object> body = new HashMap<>();
		body.put("bank", tabs.toArray(new int[0][][]));
		return body;
	}

	private static int[][] slice(Item[] items, int from, int count)
	{
		if (count <= 0 || from >= items.length)
		{
			return new int[0][2];
		}
		int end = Math.min(items.length, from + count);
		int[][] out = new int[end - from][2];
		for (int i = from; i < end; i++)
		{
			out[i - from][0] = items[i].getId();
			out[i - from][1] = items[i].getQuantity();
		}
		return out;
	}
}
