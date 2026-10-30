package org.mtbridge.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Result of one bridge instruction.
 *
 * <p>{@link Outcome#DEFERRED} is a first-class outcome, not an error: MTR freezes a train's path
 * when it departs, so an instruction that changes where a train goes can only be honoured at
 * the next moment the path may be rebuilt (a platform stop, or back at the siding). The bridge
 * accepts the instruction, keeps it as a pending intent and reports exactly when it will take
 * effect instead of pretending it already did.</p>
 */
public record BridgeResult(boolean success, Outcome outcome, String message, String trainId,
						   String target, long requestedAt, long effectiveAt) {

	public enum Outcome {
		/** Applied immediately. */
		APPLIED,
		/** Accepted, will be applied at {@link #effectiveAt()} (or at the next opportunity). */
		DEFERRED,
		/** Refused because of a precondition (unknown train, platform not allowed, no path). */
		REJECTED,
		/** Attempted and failed inside the simulator. */
		FAILED,
		/** No bridge (server not running, simulator not reachable, bridge disabled). */
		UNAVAILABLE
	}

	public static BridgeResult applied(String trainId, String target, String message, long now) {
		return new BridgeResult(true, Outcome.APPLIED, message, trainId, target, now, now);
	}

	public static BridgeResult deferred(String trainId, String target, String message, long now, long effectiveAt) {
		return new BridgeResult(true, Outcome.DEFERRED, message, trainId, target, now, effectiveAt);
	}

	public static BridgeResult rejected(String trainId, String target, String message, long now) {
		return new BridgeResult(false, Outcome.REJECTED, message, trainId, target, now, 0);
	}

	public static BridgeResult failed(String trainId, String target, String message, long now) {
		return new BridgeResult(false, Outcome.FAILED, message, trainId, target, now, 0);
	}

	public static BridgeResult unavailable(String message, long now) {
		return new BridgeResult(false, Outcome.UNAVAILABLE, message, "", "", now, 0);
	}

	public Map<String, Object> toJson() {
		final Map<String, Object> json = new LinkedHashMap<>();
		json.put("ok", success);
		json.put("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT));
		json.put("message", message);
		json.put("trainId", trainId);
		json.put("target", target);
		json.put("requestedAt", requestedAt);
		json.put("effectiveAt", effectiveAt);
		return json;
	}
}
