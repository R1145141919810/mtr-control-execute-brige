package org.mtbridge.config;

import net.minecraftforge.fml.loading.FMLPaths;
import org.mtbridge.core.BridgeSettings;
import org.mtbridge.core.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads {@code config/mtbridge.json}; writes a documented default on first start. */
public final class BridgeConfig {

	private static final String DEFAULT_JSON = """
		{
		  "_comment": "MTR Dispatch Bridge. token empty = no auth, loopback only.",
		  "_comment2": "Non-loopback bind without a token refuses to start (fail closed).",
		  "_comment3": "allowDepotRegeneration drops and rebuilds every train of a depot; opt-in only.",
		  "enabled": true,
		  "port": 8889,
		  "bindAddress": "127.0.0.1",
		  "token": "",
		  "maxHoldSeconds": 600,
		  "intentTtlSeconds": 120,
		  "dryRun": false,
		  "httpThreads": 8,
		  "allowDepotRegeneration": false
		}
		""";

	public final boolean enabled;
	public final int port;
	public final String bindAddress;
	public final String token;
	public final int maxHoldSeconds;
	public final int intentTtlSeconds;
	public final boolean dryRun;
	public final int httpThreads;
	public final boolean allowDepotRegeneration;
	public final String configError;

	private BridgeConfig(boolean enabled, int port, String bindAddress, String token, int maxHoldSeconds,
						 int intentTtlSeconds, boolean dryRun, int httpThreads, boolean allowDepotRegeneration,
						 String configError) {
		this.configError = configError;
		this.enabled = enabled;
		this.port = port;
		this.bindAddress = bindAddress;
		this.token = token;
		this.maxHoldSeconds = maxHoldSeconds;
		this.intentTtlSeconds = intentTtlSeconds;
		this.dryRun = dryRun;
		this.httpThreads = httpThreads;
		this.allowDepotRegeneration = allowDepotRegeneration;
	}

	public static BridgeConfig load() {
		final Path path = FMLPaths.CONFIGDIR.get().resolve("mtbridge.json");
		try {
			if (!Files.exists(path)) {
				Files.createDirectories(path.getParent());
				Files.writeString(path, DEFAULT_JSON, StandardCharsets.UTF_8);
			}
			String text = Files.readString(path, StandardCharsets.UTF_8);
			// Windows editors love a UTF-8 BOM; the parser does not, and the old code silently fell
			// back to defaults - which quietly dropped the token and the depot-regeneration switch.
			if (!text.isEmpty() && text.charAt(0) == 0xFEFF) {
				text = text.substring(1);
			}
			final Map<String, Object> json = Json.object(Json.parse(text));
			return new BridgeConfig(
				!"false".equalsIgnoreCase(Json.string(json, "enabled", "true")),
				(int) Json.number(json, "port", 8889),
				Json.string(json, "bindAddress", "127.0.0.1"),
				Json.string(json, "token", ""),
				(int) Json.number(json, "maxHoldSeconds", 600),
				(int) Json.number(json, "intentTtlSeconds", 120),
				"true".equalsIgnoreCase(Json.string(json, "dryRun", "false")),
				(int) Json.number(json, "httpThreads", 8),
				"true".equalsIgnoreCase(Json.string(json, "allowDepotRegeneration", "false")),
				"");
		} catch (Exception exception) {
			// Never fail silently again: the caller surfaces this through /health.configError.
			return new BridgeConfig(true, 8889, "127.0.0.1", "", 600, 120, false, 8, false,
				"config unreadable, using defaults: " + exception.getMessage());
		}
	}

	/** Fail-closed check: exposing the bridge beyond loopback without a token is refused. */
	public boolean unsafeWithoutToken() {
		if (!token.isEmpty()) {
			return false;
		}
		return !(bindAddress.equals("127.0.0.1") || bindAddress.equals("localhost") || bindAddress.equals("::1"));
	}

	public BridgeSettings settings() {
		return new BridgeSettings(intentTtlSeconds, maxHoldSeconds, dryRun, allowDepotRegeneration);
	}

	public Map<String, Object> describe() {
		final Map<String, Object> map = new LinkedHashMap<>();
		map.put("enabled", enabled);
		map.put("port", port);
		map.put("bindAddress", bindAddress);
		map.put("token", token.isEmpty() ? "" : "***");
		map.put("maxHoldSeconds", maxHoldSeconds);
		map.put("intentTtlSeconds", intentTtlSeconds);
		map.put("dryRun", dryRun);
		map.put("allowDepotRegeneration", allowDepotRegeneration);
		map.put("httpThreads", httpThreads);
		map.put("configError", configError);
		return map;
	}
}
