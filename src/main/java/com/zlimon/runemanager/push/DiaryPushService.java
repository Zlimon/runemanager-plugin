package com.zlimon.runemanager.push;

import com.zlimon.runemanager.RuneManagerApi;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Varbits;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * Pushes Achievement Diary completion (SPEC §5.2): 12 areas × 4 tiers read from
 * their completion varbits. A tier is complete when its varbit reaches the
 * "complete" value — 1 for all diaries except Karamja easy/medium/hard, which
 * use 2 (matching the RuneLite/wiki convention). Pushed on the login snapshot
 * and whenever a diary varbit changes.
 */
@Slf4j
@Singleton
public class DiaryPushService
{
	private static final class Tier
	{
		final String area;
		final String tier;
		final int varbit;
		final int completeValue;

		Tier(String area, String tier, int varbit, int completeValue)
		{
			this.area = area;
			this.tier = tier;
			this.varbit = varbit;
			this.completeValue = completeValue;
		}
	}

	private static final List<Tier> TIERS = buildTiers();
	private static final Set<Integer> DIARY_VARBITS = collectVarbits();

	@Inject
	private RuneManagerApi api;

	@Inject
	private Client client;

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (DIARY_VARBITS.contains(event.getVarbitId()))
		{
			pushCurrent();
		}
	}

	/** Push the full diary completion snapshot (full-account snapshot). */
	public void pushCurrent()
	{
		api.put("/api/plugin/diaries", buildPayload());
	}

	private Map<String, Object> buildPayload()
	{
		Map<String, Map<String, Object>> diaries = new LinkedHashMap<>();

		for (Tier tier : TIERS)
		{
			boolean done = client.getVarbitValue(tier.varbit) >= tier.completeValue;
			diaries.computeIfAbsent(tier.area, key -> new LinkedHashMap<>()).put(tier.tier, done);
		}

		Map<String, Object> body = new HashMap<>();
		body.put("diaries", diaries);
		return body;
	}

	private static Set<Integer> collectVarbits()
	{
		Set<Integer> ids = new HashSet<>();
		for (Tier tier : TIERS)
		{
			ids.add(tier.varbit);
		}
		return ids;
	}

	private static List<Tier> buildTiers()
	{
		List<Tier> tiers = new ArrayList<>(48);

		standard(tiers, "Ardougne",
			Varbits.DIARY_ARDOUGNE_EASY, Varbits.DIARY_ARDOUGNE_MEDIUM, Varbits.DIARY_ARDOUGNE_HARD, Varbits.DIARY_ARDOUGNE_ELITE);
		standard(tiers, "Desert",
			Varbits.DIARY_DESERT_EASY, Varbits.DIARY_DESERT_MEDIUM, Varbits.DIARY_DESERT_HARD, Varbits.DIARY_DESERT_ELITE);
		standard(tiers, "Falador",
			Varbits.DIARY_FALADOR_EASY, Varbits.DIARY_FALADOR_MEDIUM, Varbits.DIARY_FALADOR_HARD, Varbits.DIARY_FALADOR_ELITE);
		standard(tiers, "Fremennik",
			Varbits.DIARY_FREMENNIK_EASY, Varbits.DIARY_FREMENNIK_MEDIUM, Varbits.DIARY_FREMENNIK_HARD, Varbits.DIARY_FREMENNIK_ELITE);
		standard(tiers, "Kandarin",
			Varbits.DIARY_KANDARIN_EASY, Varbits.DIARY_KANDARIN_MEDIUM, Varbits.DIARY_KANDARIN_HARD, Varbits.DIARY_KANDARIN_ELITE);

		// Karamja easy/medium/hard report 2 when fully complete; elite uses 1.
		tiers.add(new Tier("Karamja", "Easy", Varbits.DIARY_KARAMJA_EASY, 2));
		tiers.add(new Tier("Karamja", "Medium", Varbits.DIARY_KARAMJA_MEDIUM, 2));
		tiers.add(new Tier("Karamja", "Hard", Varbits.DIARY_KARAMJA_HARD, 2));
		tiers.add(new Tier("Karamja", "Elite", Varbits.DIARY_KARAMJA_ELITE, 1));

		standard(tiers, "Kourend",
			Varbits.DIARY_KOUREND_EASY, Varbits.DIARY_KOUREND_MEDIUM, Varbits.DIARY_KOUREND_HARD, Varbits.DIARY_KOUREND_ELITE);
		standard(tiers, "Lumbridge",
			Varbits.DIARY_LUMBRIDGE_EASY, Varbits.DIARY_LUMBRIDGE_MEDIUM, Varbits.DIARY_LUMBRIDGE_HARD, Varbits.DIARY_LUMBRIDGE_ELITE);
		standard(tiers, "Morytania",
			Varbits.DIARY_MORYTANIA_EASY, Varbits.DIARY_MORYTANIA_MEDIUM, Varbits.DIARY_MORYTANIA_HARD, Varbits.DIARY_MORYTANIA_ELITE);
		standard(tiers, "Varrock",
			Varbits.DIARY_VARROCK_EASY, Varbits.DIARY_VARROCK_MEDIUM, Varbits.DIARY_VARROCK_HARD, Varbits.DIARY_VARROCK_ELITE);
		standard(tiers, "Western",
			Varbits.DIARY_WESTERN_EASY, Varbits.DIARY_WESTERN_MEDIUM, Varbits.DIARY_WESTERN_HARD, Varbits.DIARY_WESTERN_ELITE);
		standard(tiers, "Wilderness",
			Varbits.DIARY_WILDERNESS_EASY, Varbits.DIARY_WILDERNESS_MEDIUM, Varbits.DIARY_WILDERNESS_HARD, Varbits.DIARY_WILDERNESS_ELITE);

		return tiers;
	}

	private static void standard(List<Tier> tiers, String area, int easy, int medium, int hard, int elite)
	{
		tiers.add(new Tier(area, "Easy", easy, 1));
		tiers.add(new Tier(area, "Medium", medium, 1));
		tiers.add(new Tier(area, "Hard", hard, 1));
		tiers.add(new Tier(area, "Elite", elite, 1));
	}
}
