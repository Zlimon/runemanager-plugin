package com.zlimon.runemanager;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
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
 * Two variants:
 * <ul>
 *   <li>{@link #put(String, Object)} — for OSRS-account-scoped data; requires the
 *       account state to be ready and sends X-Account-Hash + X-Account-Username.</li>
 *   <li>{@link #putUserScoped(String, Object)} — for plain user-scoped data (e.g.
 *       resource pack preference); only needs the API token.</li>
 * </ul>
 */
@Slf4j
@Singleton
public class RuneManagerApi
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

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
	 * Fire-and-forget PUT for OSRS-account-scoped data. Skipped silently if
	 * the plugin isn't ready (no token, no account state, or invalid base URL).
	 */
	public void put(String path, Object body)
	{
		if (!syncEnabled(path))
		{
			log.debug("RuneManager: skipping {} — disabled in Data sync settings", path);
			return;
		}

		if (!accountState.isReady())
		{
			log.debug("RuneManager: skipping {} — account state not ready", path);
			return;
		}

		send(path, body, true, "PUT");
	}

	/**
	 * Fire-and-forget POST for OSRS-account-scoped data. Used by append-only
	 * endpoints (e.g. loot) where each push adds a row rather than replacing a
	 * snapshot. Skipped silently if the plugin isn't ready.
	 */
	public void post(String path, Object body)
	{
		if (!syncEnabled(path))
		{
			log.debug("RuneManager: skipping {} — disabled in Data sync settings", path);
			return;
		}

		if (!accountState.isReady())
		{
			log.debug("RuneManager: skipping {} — account state not ready", path);
			return;
		}

		send(path, body, true, "POST");
	}

	/**
	 * Fire-and-forget PUT for user-scoped data (no OSRS account context needed).
	 * Only requires an API token to be set.
	 */
	public void putUserScoped(String path, Object body)
	{
		send(path, body, false, "PUT");
	}

	/**
	 * OSRS-account-scoped GET. On a 2xx the (string) response body is handed to
	 * {@code onBody} on OkHttp's thread; callers must hop to the client thread
	 * themselves for any game interaction. Skipped if the account state isn't
	 * ready or there's no token.
	 */
	public void get(String path, Consumer<String> onBody)
	{
		if (!accountState.isReady())
		{
			log.debug("RuneManager: skipping GET {} — account state not ready", path);
			return;
		}

		String token = config.token();
		if (token == null || token.isEmpty())
		{
			log.debug("RuneManager: skipping GET {} — no API token", path);
			return;
		}

		HttpUrl url = HttpUrl.parse(normaliseBaseUrl(config.baseUrl()) + path);
		if (url == null)
		{
			log.warn("RuneManager: invalid base URL '{}', skipping GET {}", config.baseUrl(), path);
			return;
		}

		Request request = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + token)
			.header("Accept", "application/json")
			.header("X-Account-Hash", accountState.accountHash())
			.header("X-Account-Username", accountState.username())
			.header("X-Account-Type", accountTypeHeader())
			.get()
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("RuneManager: GET {} failed: {}", path, e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.isSuccessful() && r.body() != null)
					{
						onBody.accept(r.body().string());
						return;
					}

					if (r.code() == 401)
					{
						log.warn("RuneManager: GET {} rejected our token (401); clearing it", path);
						configManager.setConfiguration(RuneManagerConfig.GROUP, "token", "");
						return;
					}

					log.warn("RuneManager: GET {} returned {}", path, r.code());
				}
				catch (IOException e)
				{
					log.warn("RuneManager: failed to read GET response from {}: {}", path, e.getMessage());
				}
			}
		});
	}

	/**
	 * A single multipart file part built from in-memory bytes (e.g. an avatar
	 * model serialised on the fly rather than read from disk). The filename's
	 * extension matters — the backend validates uploads by extension.
	 */
	public static final class Part
	{
		private final String field;
		private final String filename;
		private final byte[] bytes;

		public Part(String field, String filename, byte[] bytes)
		{
			this.field = field;
			this.filename = filename;
			this.bytes = bytes;
		}
	}

	/**
	 * Fire-and-forget multipart POST for OSRS-account-scoped uploads built from
	 * in-memory bytes (e.g. the serialised avatar OBJ/MTL). Requires the account
	 * state to be ready, like {@link #put(String, Object)}.
	 */
	public void postParts(String path, List<Part> parts)
	{
		if (!syncEnabled(path))
		{
			log.debug("RuneManager: skipping {} — disabled in Data sync settings", path);
			return;
		}

		if (parts.isEmpty())
		{
			log.warn("RuneManager: skipping {} — no parts to upload", path);
			return;
		}

		MultipartBody.Builder bodyBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM);
		for (Part part : parts)
		{
			bodyBuilder.addFormDataPart(part.field, part.filename, RequestBody.create(OCTET_STREAM, part.bytes));
		}

		sendMultipart(path, bodyBuilder.build());
	}

	private void sendMultipart(String path, MultipartBody body)
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
			.header("X-Account-Type", accountTypeHeader())
			.post(body)
			.build();

		httpClient.newCall(request).enqueue(responseCallback(path));
	}

	private void send(String path, Object body, boolean includeAccountHeaders, String method)
	{
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

		Request.Builder builder = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + token)
			.header("Accept", "application/json")
			.method(method, RequestBody.create(JSON, gson.toJson(body)));

		if (includeAccountHeaders)
		{
			builder.header("X-Account-Hash", accountState.accountHash());
			builder.header("X-Account-Username", accountState.username());
			builder.header("X-Account-Type", accountTypeHeader());
		}

		httpClient.newCall(builder.build()).enqueue(responseCallback(path));
	}

	/** Account type for the X-Account-Type header, defaulting to normal if unknown. */
	private String accountTypeHeader()
	{
		String type = accountState.accountType();
		return type != null ? type : "normal";
	}

	/**
	 * Shared fire-and-forget response handler: logs success, clears a rejected
	 * token on 401, and logs other failures. Used by every request variant.
	 */
	private Callback responseCallback(String path)
	{
		return new Callback()
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
						// Token was rejected — clear it, which triggers an automatic re-login (see
						// RuneManagerPlugin#onConfigChanged) as long as credentials are still saved.
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
		};
	}

	/**
	 * Central gate for the "Data sync" toggles: maps a push endpoint to its
	 * config switch so a single check covers every push (snapshot + event-driven)
	 * without each service repeating the guard. Unmapped paths (heartbeat,
	 * hiscores, clan, position, resource-pack) are always allowed — those have
	 * their own gating or are core presence.
	 */
	private boolean syncEnabled(String path)
	{
		if (path.startsWith("/api/plugin/inventory"))
		{
			return config.syncInventory();
		}
		if (path.startsWith("/api/plugin/group-bank"))
		{
			return config.syncBank();
		}
		if (path.startsWith("/api/plugin/bank"))
		{
			return config.syncBank();
		}
		if (path.startsWith("/api/plugin/equipment"))
		{
			return config.syncEquipment();
		}
		if (path.startsWith("/api/plugin/looting-bag"))
		{
			return config.syncLootingBag();
		}
		if (path.startsWith("/api/plugin/loot"))
		{
			return config.syncLoot();
		}
		if (path.startsWith("/api/plugin/quests"))
		{
			return config.syncQuests();
		}
		if (path.startsWith("/api/plugin/diaries"))
		{
			return config.syncDiaries();
		}
		if (path.startsWith("/api/plugin/combat-achievements"))
		{
			return config.syncCombatAchievements();
		}
		if (path.startsWith("/api/plugin/collection-log"))
		{
			return config.syncCollectionLog();
		}
		if (path.startsWith("/api/plugin/feed/screenshot"))
		{
			return config.captureScreenshots();
		}
		if (path.startsWith("/api/plugin/feed"))
		{
			return config.syncNotableEvents();
		}
		if (path.startsWith("/api/plugin/avatar"))
		{
			return config.syncAvatar();
		}
		if (path.startsWith("/api/plugin/vitals"))
		{
			return config.syncVitals();
		}
		if (path.startsWith("/api/plugin/status"))
		{
			return config.syncActivity();
		}

		return true;
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
