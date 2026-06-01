package com.zlimon.runemanager;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import javax.inject.Inject;
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
import okhttp3.ResponseBody;

@Slf4j
public class RuneManagerAuthService
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	@Inject
	private OkHttpClient httpClient;

	@Inject
	private Gson gson;

	@Inject
	private ConfigManager configManager;

	/**
	 * Exchange the configured email + password for an API token via {@code POST /api/login}.
	 * On success the token is persisted to the plugin config under the {@code token} key.
	 * Runs asynchronously on the OkHttp thread pool — must not be called from the client thread
	 * if you need its result, but fire-and-forget from {@code startUp()} is fine.
	 */
	public void login(String baseUrl, String email, String password)
	{
		HttpUrl url = HttpUrl.parse(normaliseBaseUrl(baseUrl) + "/api/login");
		if (url == null)
		{
			log.warn("RuneManager: invalid base URL '{}', skipping login", baseUrl);
			return;
		}

		JsonObject payload = new JsonObject();
		payload.addProperty("email", email);
		payload.addProperty("password", password);

		Request request = new Request.Builder()
			.url(url)
			.header("Accept", "application/json")
			.post(RequestBody.create(JSON, gson.toJson(payload)))
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("RuneManager: login request failed: {}", e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response; ResponseBody body = r.body())
				{
					String raw = body != null ? body.string() : "";

					if (!r.isSuccessful())
					{
						log.warn("RuneManager: login failed ({}): {}", r.code(), raw);
						return;
					}

					JsonObject json;
					try
					{
						json = gson.fromJson(raw, JsonObject.class);
					}
					catch (JsonSyntaxException e)
					{
						log.warn("RuneManager: login response was not valid JSON: {}", raw);
						return;
					}

					if (json == null || !json.has("access_token"))
					{
						log.warn("RuneManager: login response missing access_token: {}", raw);
						return;
					}

					String token = json.get("access_token").getAsString();
					configManager.setConfiguration(RuneManagerConfig.GROUP, "token", token);
					log.debug("RuneManager: logged in, token stored");
				}
				catch (IOException e)
				{
					log.warn("RuneManager: failed to read login response: {}", e.getMessage());
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
