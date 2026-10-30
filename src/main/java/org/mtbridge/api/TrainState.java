package org.mtbridge.api;

import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only view of one train, safe to hand to other mods and to the telemetry endpoint. */
public record TrainState(String id, String name, String routeId, String nextPlatformId, String stationId,
						 double speedMps, double railProgress, long dwellElapsedMillis, boolean onRoute,
						 boolean inDepot, long departureIndex) {

	public Map<String, Object> toJson() {
		final Map<String, Object> json = new LinkedHashMap<>();
		json.put("id", id);
		json.put("name", name);
		json.put("routeId", routeId);
		json.put("nextPlatformId", nextPlatformId);
		json.put("stationId", stationId);
		json.put("speed", speedMps);
		json.put("railProgress", railProgress);
		json.put("dwellElapsed", dwellElapsedMillis);
		json.put("onRoute", onRoute);
		json.put("inDepot", inDepot);
		json.put("departureIndex", departureIndex);
		return json;
	}
}
