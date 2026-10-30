package org.mtbridge.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** One instruction from the external service, the command system or another mod. */
public record BridgeCommand(Kind kind, String trainId, String target, int seconds, String idempotencyKey, int ttlSeconds) {

	public enum Kind {
		SET_PLATFORM("set_platform"),
		DEPLOY("deploy_train"),
		RETURN_TO_DEPOT("return_to_depot"),
		DWELL("dwell_control");

		public final String wire;

		Kind(String wire) {
			this.wire = wire;
		}

		public static Kind fromWire(String wire) {
			for (final Kind kind : values()) {
				if (kind.wire.equalsIgnoreCase(wire)) {
					return kind;
				}
			}
			return null;
		}
	}

	public static BridgeCommand of(Kind kind, String trainId, String target, int seconds) {
		return new BridgeCommand(kind, trainId == null ? "" : trainId, target == null ? "" : target, seconds, "", -1);
	}

	public static BridgeCommand of(Kind kind, String trainId, String target, int seconds, int ttlSeconds) {
		return new BridgeCommand(kind, trainId == null ? "" : trainId, target == null ? "" : target, seconds, "", ttlSeconds);
	}

	public Map<String, Object> toJson() {
		final Map<String, Object> json = new LinkedHashMap<>();
		json.put("kind", kind.wire);
		json.put("trainId", trainId);
		json.put("target", target);
		json.put("seconds", seconds);
		json.put("key", idempotencyKey);
		json.put("ttlSeconds", ttlSeconds);
		return json;
	}
}
