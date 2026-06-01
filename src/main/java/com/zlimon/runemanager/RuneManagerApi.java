package com.zlimon.runemanager;

import com.google.gson.Gson;
import java.io.IOException;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Generic plugin → backend PUT helper.
 *
 * Reads the auth token from config and the OSRS account identity (hash + username)
 * from {@link PluginAccountState}, attaches them as the three standard headers, and
 * fires the request on OkHttp's thread pool. Per the AGENTS.md guidelines no blocking
 * IO happens on the client thread.
 *
 * Push services hand off here so they don't have to repeat the auth wiring.
 */
@Slf4j
@Singleton
public class RuneManagerApi
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	@Inject
	private OkHttpClient httpClient;

	@Inject
	private Gson gson;

	@Inject
	private RuneManagerConfig config;

	@Inject
	private PluginAccountState accountState;

	@Inject
	private ConfigManager configManager;

	/**
	 * Fire-and-forget PUT. Skipped silently if the plugin isn't ready
	 * (not logged in, no token, or invalid base URL).
	 *
	 * @param path absolute path on the API (e.g. "/api/plugin/inventory")
	 * @param body any Object — Gson serialises it
	 */
	public void put(String path, Object body)
	{
		if (!accountState.isReady())
		{
			log.debug("RuneManager: skipping {} — account state not ready", path);
			return;
		}

		String token = config.token();
		if (token == null || token.isEmpty())
		{
			log.debug("RuneManager: skipping {} — no API token, log in via the plugin config", path);
			return;
		}

		HttpUrl url = HttpUrl.parse(normaliseBaseUrl(config.baseUrl()) + path);
		if (url == null)
		{
			log.warn("RuneManager: invalid base URL '{}', skipping {}", config.baseUrl(), path);
			return;
		}

		Request request = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + token)
			.header("Accept", "application/json")
			.header("X-Account-Hash", accountState.accountHash())
			.header("X-Account-Username", accountState.username())
			.put(RequestBody.create(JSON, gson.toJson(body)))
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("RuneManager: {} failed: {}", path, e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.isSuccessful())
					{
						log.debug("RuneManager: {} ok ({})", path, r.code());
						return;
					}

					if (r.code() == 401)
					{
						// Token was rejected — clear it so the plugin will re-log in on next config edit / startup.
						log.warn("RuneManager: {} rejected our token (401); clearing it", path);
						configManager.setConfiguration(RuneManagerConfig.GROUP, "token", "");
						return;
					}

					log.warn("RuneManager: {} returned {}: {}", path, r.code(), r.body() != null ? r.body().string() : "");
				}
				catch (IOException e)
				{
					log.warn("RuneManager: failed to read response from {}: {}", path, e.getMessage());
				}
			}
		});
	}

	private static String normaliseBaseUrl(String url)
	{
		if (url == null)
		{
			return "";
		}
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}
}
