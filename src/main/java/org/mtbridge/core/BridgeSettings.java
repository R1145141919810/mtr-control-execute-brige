package org.mtbridge.core;

/** Runtime knobs the platform layer passes into the core (mirrors config/mtbridge.json). */
public record BridgeSettings(int intentTtlSeconds, int maxHoldSeconds, boolean dryRun, boolean allowDepotRegeneration) {

	public static BridgeSettings defaults() {
		return new BridgeSettings(120, 600, false, false);
	}
}
