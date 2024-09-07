package com.zlimon.marketplace.sounds;

import com.zlimon.RuneManagerConfig;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import static com.zlimon.marketplace.MarketplaceConstants.*;

@Slf4j
public class SoundManager {
	private final Client client;
	private final RuneManagerConfig config;

	private Instant globalLastPlayedAt;
	private ConcurrentHashMap<Integer, Instant> uniqueLastPlayedAtLookup = new ConcurrentHashMap<>();

	public SoundManager(Client client, RuneManagerConfig config)
	{
		this.client = client;
		this.config = config;
	}

	public void playSound(Integer soundId)
	{

		// guard: check if the marketplace sounds are enabled
		if (!config.marketplaceSoundsEnabled())
		{
			return;
		}

		// guard: make sure the sound is valid
		if (soundId == null || soundId < 0)
		{
			return;
		}

		Instant now = Instant.now();
		Instant uniqueLastPlayedAt = uniqueLastPlayedAtLookup.get(soundId);
		boolean hasUniqueBeenPlayedRecently = uniqueLastPlayedAt != null && now.minusMillis(UNIQUE_PLAY_SOUND_THROTTLE_MS).isBefore(uniqueLastPlayedAt);
		boolean hasGlobalBeenPlayedRecently = globalLastPlayedAt != null && now.minusMillis(GLOBAL_PLAY_SOUND_THROTTLE_MS).isBefore(globalLastPlayedAt);

		// guard: check if this sound is allowed to be played once again
		// this is to prevent many of the same sounds to trigger at once
		if (hasUniqueBeenPlayedRecently || hasGlobalBeenPlayedRecently)
		{
			log.debug("Skipping sound "+ soundId +", because it has been played recently. Unique: "+ hasUniqueBeenPlayedRecently + ", global: "+ hasGlobalBeenPlayedRecently);
			return;
		}

		// update the last played ats
		globalLastPlayedAt = now;
		uniqueLastPlayedAtLookup.put(soundId, now);

		client.playSoundEffect(soundId);
	}
}
