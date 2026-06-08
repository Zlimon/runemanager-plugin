package com.zlimon.runemanager.push;

import com.zlimon.runemanager.RuneManagerApi;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.eventbus.Subscribe;

/**
 * Pushes live status-orb values (HP, prayer, run energy, special attack) to the
 * RuneManager website so an open profile's orbs update in real time.
 *
 * Sent only when a value changes, throttled so combat ticks don't spam the API.
 */
@Slf4j
@Singleton
public class VitalsPushService
{
	/** Don't send more often than this even when values keep changing. */
	private static final long MIN_INTERVAL_MS = 1000;

	@Inject
	private RuneManagerApi api;

	@Inject
	private Client client;

	private int[] lastSent;
	private long lastSentMs;

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
		int hpMax = client.getRealSkillLevel(Skill.HITPOINTS);
		int prayer = client.getBoostedSkillLevel(Skill.PRAYER);
		int prayerMax = client.getRealSkillLevel(Skill.PRAYER);
		int runEnergy = client.getEnergy() / 100; // 0-10000 -> 0-100
		int special = client.getVarpValue(VarPlayerID.SA_ENERGY) / 10; // 0-1000 -> 0-100

		int[] current = {hp, hpMax, prayer, prayerMax, runEnergy, special};

		long now = System.currentTimeMillis();
		boolean changed = lastSent == null || !Arrays.equals(current, lastSent);
		if (!changed || now - lastSentMs < MIN_INTERVAL_MS)
		{
			return;
		}

		Map<String, Object> body = new HashMap<>();
		body.put("hitpoints", hp);
		body.put("hitpoints_max", hpMax);
		body.put("prayer", prayer);
		body.put("prayer_max", prayerMax);
		body.put("run_energy", runEnergy);
		body.put("special_attack", special);
		api.put("/api/plugin/vitals", body);

		lastSent = current;
		lastSentMs = now;
	}
}
