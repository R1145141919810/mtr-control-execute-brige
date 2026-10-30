package org.mtbridge.api;

/**
 * Listener contract for other mods.
 *
 * <p>Called on the Minecraft server thread after the instruction has been applied, deferred or
 * rejected. Implementations must return quickly and must not throw: the bridge catches and logs
 * exceptions so one broken listener cannot stall dispatch.</p>
 */
@FunctionalInterface
public interface BridgeListener {

	void onBridgeEvent(BridgeEvent event);
}
