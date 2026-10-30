package org.mtbridge.api;

import java.util.LinkedHashMap;
import java.util.Map;

/** Snapshot of the bridge itself, suitable for {@code /mtbridge status} and the HTTP health endpoint. */
public record BridgeStatus(boolean available, boolean serverRunning, boolean httpRunning, int httpPort,
						   long executed, long failed, long deferred, int pendingIntents, String lastError) {

	public static BridgeStatus unavailable(String reason) {
		return new BridgeStatus(false, false, false, 0, 0, 0, 0, 0, reason);
	}

	public Map<String, Object> toJson() {
		final Map<String, Object> json = new LinkedHashMap<>();
		json.put("available", available);
		json.put("serverRunning", serverRunning);
		json.put("httpRunning", httpRunning);
		json.put("httpPort", httpPort);
		json.put("executed", executed);
		json.put("failed", failed);
		json.put("deferred", deferred);
		json.put("pendingIntents", pendingIntents);
		json.put("lastError", lastError);
		return json;
	}
}
