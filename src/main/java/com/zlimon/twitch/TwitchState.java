package com.zlimon.twitch;

import com.google.gson.*;
import com.zlimon.RuneManagerConfig;
import com.zlimon.RuneManagerPlugin;
import com.zlimon.ui.CanvasListener;
import com.zlimon.utilities.AccountType;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Item;

import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static com.zlimon.RuneManagerConfig.*;

/**
 * In-memory state of all the data that is synced
 * to the Twitch Extension. All data is stored here
 * to allow each data type to be synced at different rates
 * and mechanisms (e.g. polling and event driven).
 *
 * Due to the fact we only have one entry ("broadcaster") to store
 * the data in the Twitch Configuration Service upon update of one
 * data type we need to send all the others as well. For this
 * an in-memory state is useful. Looks like this does not give significant
 * overhead.
 *
 * The Configuration and PubSub Service state do have limitations: it can only be a maximum of 5KB.
 *
 * Configuration Service documentation:
 * https://dev.twitch.tv/docs/extensions/reference/#set-extension-configuration-segment
 */
@Slf4j
public class TwitchState {

	private final RuneManagerPlugin plugin;
	private final RuneManagerConfig config;
	private final CanvasListener canvasListener;
	private final Gson gson;
	private final TwitchApi twitchApi;

	/**
	 * Enable this if you want to test the state with all its limits
	 * where we will fill the combat stats, item quantities, etc. with maxed out integers.
	 * This will help us gain insights in how many bank items and combat fights we can allow.
	 */
	public final static boolean STATE_STRESS_TEST_ENABLED = false && RuneManagerPlugin.IN_DEVELOPMENT;
	public final static int MAX_ITEM_QUANTITY = 8000;
	public final static int MAX_FIGHT_STATISTIC_VALUE = 700;
	public final static int MAX_SKILL_EXPERIENCE = 200000000;
	public final static int MAX_SKILL_LEVEL = 126;

	/**
	 * The current state that is queued to be sent out.
	 */
	private JsonObject currentState = new JsonObject();

	/**
	 * An additional cyclic state that cannot be sent out at once
	 * due to Twitch limitations, currently the bank and the collection log
	 * are sent in smaller parts via this state.
	 * NOTE: keep a minimum of 20% reserved for combat fights.
	 */
	private final static int MAX_BANK_ITEMS_PER_SLICE = 250;
	private JsonObject cyclicState = new JsonObject();
	@Getter
	private TwitchStateEntry currentCyclicEntry = TwitchStateEntry.BANK_TABBED_ITEMS;
	private int currentCyclicSliceIndex = 0;

	/**
	 * Additional state variables not synced to the client but can determine syncing behaviour
	 */
	private final static int WAS_IN_TOA_DEBOUNCE = 20 * 1000; // ms
	private Instant lastWasInToA;

	public TwitchState(RuneManagerPlugin plugin, RuneManagerConfig config, CanvasListener canvasListener, Gson gson, TwitchApi twitchApi)
	{
		this.plugin = plugin;
		this.config = config;
		this.canvasListener = canvasListener;
		this.gson = gson;
        this.twitchApi = twitchApi;

        // initialize the states that are not directly synced with events
		setOverlayTopPosition(config.overlayTopPosition());
		setVirtualLevelsEnabled(config.virtualLevelsEnabled());
		setTwitchTheme(config.twitchTheme());
		setTwitchVisibility(config.twitchVisibility());

		// set initial items as no events are triggered when they are empty
		setInventoryItems(new Item[0], 0);
		setEquipmentItems(new Item[0], 0);
		setWeight(0);
	}

	public JsonObject getState()
	{
		return currentState;
	}

	public void setPlayerName(String playerName)
	{
		currentState.addProperty(TwitchStateEntry.PLAYER_NAME.getKey(), playerName);
	}

	public void setAccountHash(Long accountHash)
	{
		currentState.addProperty(TwitchStateEntry.ACCOUNT_HASH.getKey(), accountHash);
	}

	public void setAccountType(AccountType accountType)
	{

		// guard: skip when account type is not valid
		// this happens mainly when the client is booting up
		if (accountType == null)
		{
			return;
		}

		currentState.addProperty(TwitchStateEntry.ACCOUNT_TYPE.getKey(), accountType.getKey());
	}

	public void setRegionId(int regionId)
	{
		currentState.addProperty(TwitchStateEntry.REGION_ID.getKey(), regionId);
	}

	public void setOverlayTopPosition(int overlayTopPosition)
	{
		if (overlayTopPosition < MIN_OVERLAY_TOP_POSITION)
		{
			overlayTopPosition = MIN_OVERLAY_TOP_POSITION;
		}
		else if (overlayTopPosition > MAX_OVERLAY_TOP_POSITION)
		{
			overlayTopPosition = MAX_OVERLAY_TOP_POSITION;
		}

		currentState.addProperty(TwitchStateEntry.TOP_POSITION.getKey(), overlayTopPosition);
	}

	public void setTwitchTheme(TwitchThemeEntry twitchTheme)
	{
		currentState.addProperty(TwitchStateEntry.THEME_TYPE.getKey(), twitchTheme.getKey());
	}

	public void setTwitchVisibility(TwitchVisibilityEntry twitchVisibility)
	{
		currentState.addProperty(TwitchStateEntry.VISIBILITY_TYPE.getKey(), twitchVisibility.getKey());
	}

	public void setWeight(Integer weight)
	{
		currentState.addProperty(TwitchStateEntry.WEIGHT.getKey(), weight);
	}

	public void setSkillExperiences(int[] skillExperiences)
	{
		currentState.add(TwitchStateEntry.SKILL_EXPERIENCES.getKey(), convertToJson(skillExperiences, MAX_SKILL_EXPERIENCE));
	}

	public void setBoostedSkillLevels(int[] boostedSkillLevels)
	{
		currentState.add(TwitchStateEntry.BOOSTED_SKILL_LEVELS.getKey(), convertToJson(boostedSkillLevels, MAX_SKILL_LEVEL));
	}

	public void setVirtualLevelsEnabled(boolean virtualLevelsEnabled)
	{
		currentState.addProperty(TwitchStateEntry.VIRTUAL_LEVELS_ENABLED.getKey(), virtualLevelsEnabled);
	}

	public void setFightStatistics(JsonObject fightStatistics)
	{
		currentState.add(TwitchStateEntry.FIGHT_STATISTICS.getKey(), fightStatistics);
	}

	public void setInventoryItems(Item[] items, long totalPrice)
	{
		final JsonObject payload = new JsonObject();

		payload.add("inventory", convertToJson(items));

		twitchApi.sendAsyncRequest("/inventory/update", payload);
	}

	public void setEquipmentItems(Item[] items, long totalPrice)
	{
		final JsonObject payload = new JsonObject();

		payload.add("equipment", convertToJson(items));

		twitchApi.sendAsyncRequest("/equipment/update", payload);
	}

	public void setLootingBagItems(Item[] items, long totalPrice)
	{
		final JsonObject payload = new JsonObject();

		payload.add("looting_bag", convertToJson(items));

		twitchApi.sendAsyncRequest("/looting-bag/update", payload);
	}

	private void setItems(String itemsKey, Item[] items)
	{
		setItems(itemsKey, convertToJson(items));
	}

	private void setItems(String itemsKey, JsonArray items)
	{
		currentState.add(itemsKey, items);
	}

	private void setItemsPrice(String priceKey, String totalPrice)
	{
		setItemsPrice(priceKey, Long.parseLong(totalPrice));
	}

	private void setItemsPrice(String priceKey, long totalPrice)
	{
		currentState.addProperty(priceKey, totalPrice);
	}

	public void setCurrentProductCooldowns(ConcurrentHashMap<String, Instant> cooldownsUntil)
	{
		Instant now = Instant.now();
		JsonObject currentProductCooldowns = new JsonObject();

		// add each one of them
		cooldownsUntil.forEach((streamerProductId, cooldownUntil) -> {

			// guard: don't include cooldowns that have already passed
			// this ensures no old cooldowns are sent to the extension which might have
			// a new cooldown initialized via the PubSub messaging, which is faster than this one!
			if (now.isAfter(cooldownUntil))
			{
				return;
			}
			currentProductCooldowns.addProperty(streamerProductId, cooldownUntil.toString());
		});

		currentState.add(TwitchStateEntry.CURRENT_PRODUCT_COOLDOWNS.getKey(), currentProductCooldowns);
	}

	public void setCurrentSharedCooldown(Instant sharedCooldownUntil)
	{
		Instant now = Instant.now();

		// guard: don't include a cooldown that has passed
		if (sharedCooldownUntil == null || now.isAfter(sharedCooldownUntil))
		{
			return;
		}

		currentState.addProperty(TwitchStateEntry.CURRENT_SHARED_COOLDOWN.getKey(), sharedCooldownUntil.toString());
	}

	public void setInvocations(JsonArray invocations)
	{
		cyclicState.add(TwitchStateEntry.INVOCATIONS.getKey(), invocations);
		plugin.setConfiguration(INVOCATIONS_CONFIG_KEY, invocations);
	}

	public void setInvocationsRaidLevel(String raidLevel)
	{
		try {
			int parsedRaidLevel = Integer.parseInt(raidLevel);
			cyclicState.addProperty(TwitchStateEntry.INVOCATIONS_RAID_LEVEL.getKey(), parsedRaidLevel);
			plugin.setConfiguration(INVOCATIONS_RAID_LEVEL_CONFIG_KEY, parsedRaidLevel);
		} catch (Exception exception) {
			plugin.logSupport("Could not set invocations raid level due to the following error:", exception);
		}
	}

	public void setBankItems(Item[] items, int[] tabAmounts)
	{
		JsonArray tabbedBankItems = new JsonArray();
		int currentItemAmount = 0;

		// convert the client item structure to a nested array with the items
		for (final int tabAmount : tabAmounts) {
			final Item[] tabItems = Arrays.copyOfRange(items, currentItemAmount, currentItemAmount + tabAmount);

			tabbedBankItems.add(convertToJson(tabItems));
			currentItemAmount += tabAmount;
		}

		// get the remaining / zero tab and prepend it
		final Item[] zeroTabItems = Arrays.copyOfRange(items, currentItemAmount, items.length);
		JsonArray prependedTabbedBankItems = new JsonArray();
		prependedTabbedBankItems.add(convertToJson(zeroTabItems));
		prependedTabbedBankItems.addAll(tabbedBankItems);

		setBankItems(prependedTabbedBankItems);
	}

	public void setBankItems(JsonArray tabbedBankItems)
	{
		final JsonObject payload = new JsonObject();

		payload.add("bank", tabbedBankItems);

		twitchApi.sendAsyncRequest("/bank/update", payload);
	}

	public void setBankItemsPrice(long totalPrice)
	{
		cyclicState.addProperty(TwitchStateEntry.BANK_PRICE.getKey(), totalPrice);
		plugin.setConfiguration(BANK_PRICE_CONFIG_KEY, totalPrice);
	}

	public void setQuests(JsonArray quests)
	{
		final JsonObject payload = new JsonObject();

		payload.add("quests", quests);

		twitchApi.sendAsyncRequest("/quests/update", payload);
	}

	public void setSeasonalItems(JsonArray seasonalItems)
	{
		currentState.add(TwitchStateEntry.SEASONAL_ITEMS.getKey(), seasonalItems);
	}

	public JsonArray getTabbedBankItems()
	{
		return cyclicState.getAsJsonArray(TwitchStateEntry.BANK_TABBED_ITEMS.getKey());
	}

	public JsonObject getFilteredState()
	{
		JsonObject filteredState = getState().deepCopy();

		// add the state that is too big to sync at once
		filteredState = addCyclicState(filteredState);

		// add a unique content ID so the front-end knows
		// this is a message containing information for the tabs.
		// if it is only a connection status all of this is filtered
		// out in a later step.
		filteredState = addContentId(filteredState);

		// verify whether we can sync this RL window, based on the
		// anti multi-logging settings
		filteredState = verifyClientActivityStatus(filteredState);

		// always add a connection status, even when this RL window is not syncing
		// this gives the user proper feedback whether the client is connected in the
		// configuration view when installing the extension
		filteredState = addConnectionStatus(filteredState);

		// remove any states that are disabled in the settings
		filteredState = removeDisabledState(filteredState);

		return filteredState;
	}

	public JsonObject addSeasonalState(JsonObject state)
	{

		// guard: skip when not seasonal world
		if (!plugin.isSeasonal()) {
			return state;
		}



		return state;
	}

	public JsonObject addCyclicState(JsonObject state)
	{

		// add the bank items when in this mode
		if (currentCyclicEntry == TwitchStateEntry.BANK_TABBED_ITEMS)
		{
			final String bankTabbedItemsKey = TwitchStateEntry.BANK_TABBED_ITEMS.getKey();
			final String bankPriceKey = TwitchStateEntry.BANK_PRICE.getKey();

			if (!cyclicState.has(bankTabbedItemsKey) || !cyclicState.has(bankPriceKey))
			{
				return state;
			}

			AtomicInteger currentItemAmount = new AtomicInteger();
			final JsonArray allTabbedBankItems = cyclicState.getAsJsonArray(bankTabbedItemsKey);
			final JsonArray slicedTabbedBankItems = new JsonArray();
			final JsonArray emptyItem = new JsonArray();
			emptyItem.add(-1); // item ID
			emptyItem.add(-1); // item quantity

			// loop all the bank items until we find the item where we need to start syncing
			for (int tabIndex = 0; tabIndex < allTabbedBankItems.size(); tabIndex++)
			{
				JsonArray tabItems = allTabbedBankItems.get(tabIndex).getAsJsonArray();
				JsonArray slicedTabItems = new JsonArray();

				for (int itemIndex = 0; itemIndex < tabItems.size(); itemIndex++)
				{
					final boolean afterSliceStart = currentItemAmount.get() >= currentCyclicSliceIndex;
					final boolean beforeSliceEnd = currentItemAmount.get() < (currentCyclicSliceIndex + MAX_BANK_ITEMS_PER_SLICE);
					currentItemAmount.addAndGet(1);

					// guard: add empty item when we are not in range
					if (!afterSliceStart || !beforeSliceEnd) {
						slicedTabItems.add(emptyItem);
						continue;
					}

					slicedTabItems.add(tabItems.get(itemIndex));
				}

				// add each sliced tab
				slicedTabbedBankItems.add(slicedTabItems);
			}

			state.add(bankTabbedItemsKey, slicedTabbedBankItems);
			state.addProperty(bankPriceKey, cyclicState.get(bankPriceKey).getAsLong());

			return state;
		}

		if (currentCyclicEntry == TwitchStateEntry.QUESTS)
		{
			// add all the quests in one go
			JsonArray quests = cyclicState.getAsJsonArray(TwitchStateEntry.QUESTS.getKey());
			state.add(TwitchStateEntry.QUESTS.getKey(), quests);
		}

		if (currentCyclicEntry == TwitchStateEntry.INVOCATIONS)
		{
			// add all the invocations in one go
			JsonArray invocations = cyclicState.getAsJsonArray(TwitchStateEntry.INVOCATIONS.getKey());
			JsonElement invocationsRaidLevelElement = cyclicState.get(TwitchStateEntry.INVOCATIONS_RAID_LEVEL.getKey());
			Integer invocationsRaidLevel = (invocationsRaidLevelElement == null ? null : invocationsRaidLevelElement.getAsInt());
			state.add(TwitchStateEntry.INVOCATIONS.getKey(), invocations);
			state.addProperty(TwitchStateEntry.INVOCATIONS_RAID_LEVEL.getKey(), invocationsRaidLevel);
		}

		return state;
	}

	public JsonObject addContentId(JsonObject state)
	{
		state.addProperty(TwitchStateEntry.CONTENT_ID.getKey(), Long.toString(Instant.now().toEpochMilli()));

		return state;
	}

	public void nextCyclicState()
	{


		// after quests we go to invocations
//		if (currentCyclicEntry == TwitchStateEntry.QUESTS)
//		{
//			currentCyclicEntry = TwitchStateEntry.INVOCATIONS;
//			currentCyclicSliceIndex = 0;
//		}
//
//		// after invocations we go back to the channel point rewards
//		else if (currentCyclicEntry == TwitchStateEntry.INVOCATIONS)
//		{
//			currentCyclicEntry = TwitchStateEntry.CHANNEL_POINT_REWARDS;
//			currentCyclicSliceIndex = 0;
//		}
//
//		// after the channel point rewards go to the bank
//		else if (currentCyclicEntry == TwitchStateEntry.CHANNEL_POINT_REWARDS)
//		{
//			currentCyclicEntry = TwitchStateEntry.BANK_TABBED_ITEMS;
//			currentCyclicSliceIndex = 0;
//		}
	}

	private JsonObject verifyClientActivityStatus(JsonObject state)
	{
		final JsonElement accountHashElement = state.get(TwitchStateEntry.ACCOUNT_HASH.getKey());
		final JsonElement channelPointRewardsElement = state.get(TwitchStateEntry.CHANNEL_POINT_REWARDS.getKey());
		final Long accountHash = (accountHashElement == null ? -1 : accountHashElement.getAsLong());

		// only sync this account when a valid account hash
		if (accountHash == null || accountHash == -1)
		{
			state = new JsonObject();
		}

		// only sync this account when logged in
		if (!plugin.isLoggedIn(true))
		{
			state = new JsonObject();
		}

		// we need to make one exception for the cyclic state of the Channel Point Rewards
		// that should be synced regardless of whether we are logged in or not
		// TODO: look into refactor as this feels a bit weird to place here as one exception...
		if (channelPointRewardsElement != null) {
			state.add(TwitchStateEntry.CHANNEL_POINT_REWARDS.getKey(), channelPointRewardsElement);
		}

		return state;
	}

	private JsonObject addConnectionStatus(JsonObject state)
	{
		final JsonObject connectionStatus = new JsonObject();
		final boolean isLoggedIn = plugin.isLoggedIn(true);

		// for now always true?
		connectionStatus.addProperty("status", true);
		connectionStatus.addProperty("isLoggedIn", isLoggedIn);

		state.add(TwitchStateEntry.CONNECTION_STATUS.getKey(), connectionStatus);
		return state;
	}

	private JsonObject removeDisabledState(JsonObject state)
	{

		// clear everything when sync is not enabled to clear everything for all viewers
		if (!config.syncEnabled())
		{

			// set null for all keys to make sure all viewers have their state cleared as well
			for (TwitchStateEntry stateEntry : TwitchStateEntry.values())
			{
				if (!stateEntry.isNullable())
				{
					continue;
				}

				state.add(stateEntry.getKey(), null);
			}
		}

		if (!config.playerInfoEnabled())
		{
			state.add(TwitchStateEntry.PLAYER_NAME.getKey(), null);
		}

		if (!config.inventoryEnabled())
		{
			state.add(TwitchStateEntry.INVENTORY_ITEMS.getKey(), null);
			state.add(TwitchStateEntry.INVENTORY_PRICE.getKey(), null);
		}

		if (!config.equipmentEnabled())
		{
			state.add(TwitchStateEntry.EQUIPMENT_ITEMS.getKey(), null);
			state.add(TwitchStateEntry.EQUIPMENT_PRICE.getKey(), null);
		}

		if (!config.lootingBagEnabled())
		{
			state.add(TwitchStateEntry.LOOTING_BAG_ITEMS.getKey(), null);
			state.add(TwitchStateEntry.LOOTING_BAG_PRICE.getKey(), null);
		}

		if (!config.bankEnabled())
		{
			state.add(TwitchStateEntry.BANK_TABBED_ITEMS.getKey(), null);
			state.add(TwitchStateEntry.BANK_PRICE.getKey(), null);
		}

		if (!config.bankPriceEnabled())
		{
			state.add(TwitchStateEntry.BANK_PRICE.getKey(), null);
		}

		if (!config.fightStatisticsEnabled())
		{
			state.add(TwitchStateEntry.FIGHT_STATISTICS.getKey(), null);
		}

		if (!config.skillsEnabled())
		{
			state.add(TwitchStateEntry.SKILL_EXPERIENCES.getKey(), null);
			state.add(TwitchStateEntry.BOOSTED_SKILL_LEVELS.getKey(), null);
		}

		if (!config.weightEnabled())
		{
			state.add(TwitchStateEntry.WEIGHT.getKey(), null);
		}

		if (!config.invocationsEnabled())
		{
			state.add(TwitchStateEntry.INVOCATIONS.getKey(), null);
		}

		if (!config.invocationsEnabled() || !config.invocationsRaidLevelEnabled())
		{
			state.add(TwitchStateEntry.INVOCATIONS_RAID_LEVEL.getKey(), null);
		}

		if (!config.questsEnabled())
		{
			state.add(TwitchStateEntry.QUESTS.getKey(), null);
		}

		if (!config.seasonalsEnabled() || !plugin.isSeasonal())
		{
			state.add(TwitchStateEntry.SEASONAL_ITEMS.getKey(), null);
		}

		// reset the invocations for the current viewers when we are not in ToA anymore
		// note that this should only be done for the window that is active long enough
		// otherwise it is possible to have an alt window resetting the invocations for the main window
		if (plugin.isLoggedIn() &&
			config.autoDetectInToaRaidEnabled() &&
			!wasInToaDebounced() &&
			state.has(TwitchStateEntry.INVOCATIONS.getKey()))
		{
			final boolean hasInvocations = state.get(TwitchStateEntry.INVOCATIONS.getKey()).isJsonArray();

			if (hasInvocations)
			{
				state.add(TwitchStateEntry.INVOCATIONS.getKey(), null);
				state.add(TwitchStateEntry.INVOCATIONS_RAID_LEVEL.getKey(), null);
			}
		}

		return state;
	}

	private JsonArray convertToJson(Item[] items)
	{
		JsonArray itemsJson = new JsonArray();

		if (items == null) {
			return itemsJson;
		}

		for (Item item : items) {
			JsonArray itemJson = new JsonArray();
			final int id = item.getId();
			int quantity = item.getQuantity();

			if (TwitchState.STATE_STRESS_TEST_ENABLED)
			{
				quantity = (int) (Math.random() * TwitchState.MAX_ITEM_QUANTITY);
			}

			itemJson.add(id);
			itemJson.add(quantity);

			itemsJson.add(itemJson);
		}

		return itemsJson;
	}

	private JsonArray convertToJson(int[] array, int maxValue)
	{
		final int length = array.length;
		final int[] copiedArray = new int[length];

		// Prevent the original array to be mutated with testing mode
		// as it can be an array that is used for rendering.
		System.arraycopy(array, 0, copiedArray, 0, length);

		if (STATE_STRESS_TEST_ENABLED && maxValue > 0) {
			for (int i = 0; i < array.length; i++) {
				copiedArray[i] = (int) (Math.random() * maxValue);
			}
		}

		JsonArray json = gson.toJsonTree(copiedArray).getAsJsonArray();
		return json;
	}

	private int getBankItemAmount()
	{
		final JsonArray tabbedBankItems = getTabbedBankItems();
		int amount = 0;

		if (tabbedBankItems == null) {
			return amount;
		}

		for (int tabIndex = 0; tabIndex < tabbedBankItems.size(); tabIndex++)
		{
			JsonArray tabItems = tabbedBankItems.get(tabIndex).getAsJsonArray();
			amount += tabItems.size();
		}

		return amount;
	}

	public void onAccountChanged()
	{
		reloadConfiguration();
	}

	private void reloadConfiguration()
	{
		// when another account logs in the cache should be updated to that account
		// first we reset the data and after that check the cach
		cyclicState.remove(TwitchStateEntry.BANK_TABBED_ITEMS.getKey());
		cyclicState.remove(TwitchStateEntry.BANK_PRICE.getKey());
		cyclicState.remove(TwitchStateEntry.QUESTS.getKey());
		cyclicState.remove(TwitchStateEntry.INVOCATIONS.getKey());
		cyclicState.remove(TwitchStateEntry.INVOCATIONS_RAID_LEVEL.getKey());

		currentState.add(TwitchStateEntry.LOOTING_BAG_ITEMS.getKey(), null);
		currentState.addProperty(TwitchStateEntry.LOOTING_BAG_PRICE.getKey(), 0);

		plugin.loadFromConfiguration(BANK_TABBED_ITEMS_CONFIG_KEY, (String rawItems) -> {
			JsonArray parsedTabbedItems = new JsonParser().parse(rawItems).getAsJsonArray();
			setBankItems(parsedTabbedItems);
		});

		plugin.loadFromConfiguration(BANK_PRICE_CONFIG_KEY, (String price) -> {
			setBankItemsPrice(Long.parseLong(price));
		});

		plugin.loadFromConfiguration(QUESTS_CONFIG_KEY, (String rawQuests) -> {
			JsonArray parsedQuests = new JsonParser().parse(rawQuests).getAsJsonArray();
			setQuests(parsedQuests);
		});

		plugin.loadFromConfiguration(LOOTING_BAG_ITEMS_CONFIG_KEY, (String rawItems) -> {
			JsonArray parsedItems = new JsonParser().parse(rawItems).getAsJsonArray();
			setItems(TwitchStateEntry.LOOTING_BAG_ITEMS.getKey(), parsedItems);
		});

		plugin.loadFromConfiguration(LOOTING_BAG_PRICE_CONFIG_KEY, (String price) -> {
			setItemsPrice(TwitchStateEntry.LOOTING_BAG_PRICE.getKey(), price);
		});

		plugin.loadFromConfiguration(INVOCATIONS_CONFIG_KEY, (String rawInvocations) -> {
			JsonArray parsedInvocations = new JsonParser().parse(rawInvocations).getAsJsonArray();
			setInvocations(parsedInvocations);
		});

		plugin.loadFromConfiguration(INVOCATIONS_RAID_LEVEL_CONFIG_KEY, (String raidLevel) -> {
			setInvocationsRaidLevel(raidLevel);
		});
	}

	public void resetState()
	{
		currentState = new JsonObject();
		cyclicState = new JsonObject();
	}

	public void setInToA(boolean isInToA)
	{
		if (isInToA)
		{
			lastWasInToA = Instant.now();
		}
	}

	private boolean wasInToaDebounced()
	{
		if (lastWasInToA == null)
		{
			return false;
		}

		return Instant.now().minusMillis(WAS_IN_TOA_DEBOUNCE).isBefore(lastWasInToA);
	}
}
