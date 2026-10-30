package org.mtbridge.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Static entry point for other mods: {@code DispatchBridgeAPI.getInstance().setTrainPlatform(...)}.
 *
 * <p>The holder is installed by the bridge mod during construction. A mod that loads before the
 * bridge (or without it) still gets a non-null instance whose calls complete with
 * {@link BridgeResult.Outcome#UNAVAILABLE}, so optional integration needs no null checks.</p>
 */
public final class DispatchBridgeAPI {

	private static volatile BridgeApi instance = new UnavailableApi();

	private DispatchBridgeAPI() {
	}

	public static BridgeApi getInstance() {
		return instance;
	}

	public static boolean isInstalled() {
		return !(instance instanceof UnavailableApi);
	}

	/** Called by the bridge mod only. */
	public static void install(BridgeApi api) {
		instance = api == null ? new UnavailableApi() : api;
	}

	private static final class UnavailableApi implements BridgeApi {

		@Override
		public boolean isAvailable() {
			return false;
		}

		@Override
		public BridgeStatus status() {
			return BridgeStatus.unavailable("dispatch bridge not installed");
		}

		@Override
		public List<TrainState> trains() {
			return List.of();
		}

		@Override
		public Optional<TrainState> findTrain(String trainId) {
			return Optional.empty();
		}

		@Override
		public CompletableFuture<BridgeResult> setTrainPlatform(String trainId, String platformId) {
			return CompletableFuture.completedFuture(BridgeResult.unavailable("dispatch bridge not installed", System.currentTimeMillis()));
		}

		@Override
		public CompletableFuture<BridgeResult> setTrainPlatform(UUID ridingEntity, String platformId) {
			return setTrainPlatform("", platformId);
		}

		@Override
		public CompletableFuture<BridgeResult> deployTrain(String trainId, String platformId) {
			return setTrainPlatform(trainId, platformId);
		}

		@Override
		public CompletableFuture<BridgeResult> returnToDepot(String trainId, String sidingId) {
			return setTrainPlatform(trainId, sidingId);
		}

		@Override
		public CompletableFuture<BridgeResult> setDwell(String trainId, int seconds) {
			return setTrainPlatform(trainId, "");
		}

		@Override
		public void addListener(BridgeListener listener) {
			// no bridge, nowhere to deliver events
		}

		@Override
		public void removeListener(BridgeListener listener) {
		}
	}
}
