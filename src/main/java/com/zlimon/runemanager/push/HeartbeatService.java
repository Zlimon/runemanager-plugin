package com.zlimon.runemanager.push;

import com.zlimon.runemanager.RuneManagerApi;
import java.util.Collections;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Presence ping. Sent on a fixed schedule while logged in so the website can
 * show the account as online; {@link RuneManagerApi#put} no-ops when the player
 * isn't logged in / the account state isn't ready, so this is cheap to call
 * unconditionally.
 */
@Slf4j
@Singleton
public class HeartbeatService
{
	@Inject
	private RuneManagerApi api;

	public void ping()
	{
		api.put("/api/plugin/heartbeat", Collections.emptyMap());
	}
}
