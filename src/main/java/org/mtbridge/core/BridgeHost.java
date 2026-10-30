package org.mtbridge.core;

import org.mtr.core.simulation.Simulator;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * What the platform layer must provide to the bridge core: access to the live simulators and a
 * way to run work on the server thread.
 */
public interface BridgeHost {

	boolean ready();

	Simulator simulator(int dimension);

	int dimensionCount();

	/** Dimension ids in the same order as the indices, e.g. {@code minecraft/overworld}. */
	default java.util.List<String> dimensionIds() {
		return java.util.List.of();
	}

	<T> CompletableFuture<T> onServer(Supplier<T> task);

	void error(String message);
}
