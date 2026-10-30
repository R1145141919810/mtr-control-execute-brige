package org.mtbridge.forge;

import org.mtr.core.data.Vehicle;
import org.mtr.core.simulation.Simulator;
import org.mtbridge.api.BridgeApi;
import org.mtbridge.api.BridgeListener;
import org.mtbridge.api.BridgeResult;
import org.mtbridge.api.BridgeStatus;
import org.mtbridge.api.TrainState;
import org.mtbridge.core.BridgeCommand;
import org.mtbridge.core.BridgeService;
import org.mtbridge.core.GameActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Bridges the mod-facing API onto the shared execution core. */
public final class ForgeBridgeApi implements BridgeApi {

	private final BridgeService service;
	private final ForgeHost host;

	public ForgeBridgeApi(BridgeService service, ForgeHost host) {
		this.service = service;
		this.host = host;
	}

	@Override
	public boolean isAvailable() {
		return host.ready();
	}

	@Override
	public BridgeStatus status() {
		return new BridgeStatus(host.ready(), host.ready(), host.ready(), 0,
			service.executed(), service.failed(), service.deferred(), service.pendingIntents(),
			host.lastError().isEmpty() ? service.lastError() : host.lastError());
	}

	@Override
	public List<TrainState> trains() {
		try {
			return host.onServer(() -> {
				final Simulator simulator = host.simulator(0);
				final List<TrainState> result = new ArrayList<>();
				if (simulator != null) {
					for (final org.mtr.core.data.Siding siding : simulator.sidingIdMap.values()) {
						for (final Vehicle vehicle : GameActions.vehicles(siding)) {
							result.add(state(vehicle));
						}
					}
				}
				return result;
			}).get(500, TimeUnit.MILLISECONDS);
		} catch (Exception exception) {
			return List.of();
		}
	}

	@Override
	public Optional<TrainState> findTrain(String trainId) {
		try {
			return Optional.ofNullable(host.onServer(() -> {
				final Simulator simulator = host.simulator(0);
				final Vehicle vehicle = simulator == null ? null : GameActions.findVehicle(simulator, trainId);
				return vehicle == null ? null : state(vehicle);
			}).get(500, TimeUnit.MILLISECONDS));
		} catch (Exception exception) {
			return Optional.empty();
		}
	}

	@Override
	public CompletableFuture<BridgeResult> setTrainPlatform(String trainId, String platformId) {
		return submit(BridgeCommand.of(BridgeCommand.Kind.SET_PLATFORM, trainId, platformId, 0));
	}

	@Override
	public CompletableFuture<BridgeResult> setTrainPlatform(UUID ridingEntity, String platformId) {
		return host.onServer(() -> {
			final Simulator simulator = host.simulator(0);
			final Vehicle vehicle = simulator == null ? null : GameActions.findByRidingEntity(simulator, ridingEntity);
			if (vehicle == null) {
				return BridgeResult.rejected("", platformId, "no train carries that entity", System.currentTimeMillis());
			}
			return service.execute(simulator, BridgeCommand.of(BridgeCommand.Kind.SET_PLATFORM, String.valueOf(vehicle.getId()), platformId, 0),
				BridgeService.Source.API);
		});
	}

	@Override
	public CompletableFuture<BridgeResult> deployTrain(String trainId, String platformId) {
		return submit(BridgeCommand.of(BridgeCommand.Kind.DEPLOY, trainId, platformId, 0));
	}

	@Override
	public CompletableFuture<BridgeResult> returnToDepot(String trainId, String sidingId) {
		return submit(BridgeCommand.of(BridgeCommand.Kind.RETURN_TO_DEPOT, trainId, sidingId, 0));
	}

	@Override
	public CompletableFuture<BridgeResult> setDwell(String trainId, int seconds) {
		return submit(BridgeCommand.of(BridgeCommand.Kind.DWELL, trainId, "", seconds));
	}

	@Override
	public void addListener(BridgeListener listener) {
		service.addListener(listener);
	}

	@Override
	public void removeListener(BridgeListener listener) {
		service.removeListener(listener);
	}

	private CompletableFuture<BridgeResult> submit(BridgeCommand command) {
		return host.onServer(() -> {
			final Simulator simulator = host.simulator(0);
			return service.execute(simulator, command, BridgeService.Source.API);
		});
	}

	private static TrainState state(Vehicle vehicle) {
		return new TrainState(
			Long.toString(vehicle.getId()),
			String.valueOf(vehicle.getId()),
			Long.toString(vehicle.vehicleExtraData.getThisRouteId()),
			GameActions.hex(vehicle.vehicleExtraData.getNextPlatformId()),
			GameActions.hex(vehicle.vehicleExtraData.getThisStationId()),
			GameActions.speed(vehicle) * 1000.0,
			GameActions.railProgress(vehicle),
			GameActions.dwellElapsed(vehicle),
			vehicle.getIsOnRoute(),
			!vehicle.getIsOnRoute(),
			vehicle.vehicleExtraData == null ? -1 : 0);
	}
}
