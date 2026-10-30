package org.mtbridge.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Java API exposed to other mods.
 *
 * <p>Every mutating call returns a future that completes on the server thread with the real
 * outcome. Calls made before a world is loaded, or after the server starts stopping, complete
 * with {@link BridgeResult.Outcome#UNAVAILABLE} rather than throwing.</p>
 */
public interface BridgeApi {

	boolean isAvailable();

	BridgeStatus status();

	List<TrainState> trains();

	Optional<TrainState> findTrain(String trainId);

	/**
	 * Changes the platform a train will call at.
	 *
	 * <p>While the train is still at a siding the path is regenerated immediately. Once it is
	 * running the path is frozen by the simulator, so the instruction is accepted as a pending
	 * intent and applied at the next platform stop where a rebuild is allowed.</p>
	 */
	CompletableFuture<BridgeResult> setTrainPlatform(String trainId, String platformId);

	/** Resolves the train a player is riding and changes its next platform. */
	CompletableFuture<BridgeResult> setTrainPlatform(UUID ridingEntity, String platformId);

	/** Forces a train parked at a siding to depart, optionally towards a specific platform. */
	CompletableFuture<BridgeResult> deployTrain(String trainId, String platformId);

	/** Sends a train back to a siding after the current stop; {@code sidingId} may be null. */
	CompletableFuture<BridgeResult> returnToDepot(String trainId, String sidingId);

	/**
	 * Overrides dwell behaviour at the next stop.
	 *
	 * @param seconds {@code > 0} force a minimum dwell, {@code -1} hold indefinitely,
	 *                {@code 0} release a previous hold
	 */
	CompletableFuture<BridgeResult> setDwell(String trainId, int seconds);

	void addListener(BridgeListener listener);

	void removeListener(BridgeListener listener);
}
