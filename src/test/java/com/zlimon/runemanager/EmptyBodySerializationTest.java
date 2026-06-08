package com.zlimon.runemanager;

import com.google.gson.Gson;
import java.util.HashMap;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Guards the empty-request-body serialization used by the heartbeat and
 * announcement-ack pushes. {@code RuneManagerApi#send} serialises the body with
 * {@code gson.toJson(Object)}, which resolves the runtime type — and
 * {@code Collections.emptyMap()} is a JDK-internal class Gson can only read
 * reflectively, throwing {@code InaccessibleObjectException} on JDK 16+ and
 * silently dropping the request. A plain HashMap has no such problem.
 */
public class EmptyBodySerializationTest
{
	@Test
	public void emptyHashMapSerialisesToEmptyObject()
	{
		// Serialised as Object (how RuneManagerApi#send receives the body), so the
		// runtime-type path is exercised exactly as in production.
		Object body = new HashMap<String, Object>();
		assertEquals("{}", new Gson().toJson(body));
	}
}
