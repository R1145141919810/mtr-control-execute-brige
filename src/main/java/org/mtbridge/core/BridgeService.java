package org.mtbridge.core;

import org.mtr.core.data.Depot;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Route;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Station;
import org.mtr.core.data.Vehicle;
import org.mtr.core.simulation.Simulator;
import org.mtbridge.api.BridgeEvent;
import org.mtbridge.api.BridgeListener;
import org.mtbridge.api.BridgeResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Execution core shared by every entry point.
 *
 * <p>Revised after a live-server integration run. The changes that matter:</p>
 * <ul>
 *   <li>only <b>terminal</b> outcomes are cached for idempotency - caching a {@code DEFERRED}
 *       made a later successful application invisible to the caller;</li>
 *   <li>every intent has a per-command TTL (default 120 s) instead of a 30 minute blanket,
 *       and pending intents are listed in {@code /dispatch/v1/health};</li>
 *   <li>{@code return_to_depot} honours its target: a train can only be held at its own
 *       siding, and asking for another one is refused instead of silently ignored;</li>
 *   <li>indefinite dwell holds are capped by {@code maxHoldSeconds} so a crashed dispatcher
 *       cannot park a train forever.</li>
 * </ul>
 */
public final class BridgeService {

	public enum Source {
		HTTP, COMMAND, API
	}

	private record Intent(BridgeCommand command, long createdAt, long deadline, Source source, long sidingId,
						  long notBefore) {
		Intent withNotBefore(long value) {
			return new Intent(command, createdAt, deadline, source, sidingId, value);
		}
	}

	private record Hold(long releaseAt, long requestedAt) {
	}

	private record CachedResult(BridgeResult result, long at) {
	}

	/** A departure that was started but not yet confirmed by telemetry. */
	private record Verification(String vehicleId, long departAt, long deadline, BridgeCommand command, Source source) {
	}

	private record EventRecord(long seq, long at, String kind, String target, String outcome, String message) {
	}

	/** One in-flight path regeneration: which strategy is running and when to escalate. */
	private record RouteAttempt(long sidingId, String platformHex, String vehicleId, int strategy,
								long startedAt, long nextAt, long deadline, Source source,
								GameActions.PathSnapshot snapshot) {
	}

	private final BridgeSettings settings;
	private final Map<String, CachedResult> idempotency = new ConcurrentHashMap<>();
	private final Map<String, Intent> intents = new ConcurrentHashMap<>();
	private final Map<Long, Hold> dwellHolds = new ConcurrentHashMap<>();
	private final Map<Long, Long> serviceStart = new ConcurrentHashMap<>();
	private final List<BridgeListener> listeners = new CopyOnWriteArrayList<>();

	private final AtomicLong executed = new AtomicLong();
	private final AtomicLong failed = new AtomicLong();
	private final AtomicLong deferred = new AtomicLong();
	private final AtomicLong autoReleased = new AtomicLong();
	private final AtomicLong tickFailures = new AtomicLong();
	private final Map<Long, RouteAttempt> routeAttempts = new ConcurrentHashMap<>();
	private final java.util.Set<String> tickErrorKinds = ConcurrentHashMap.newKeySet();
	private final java.util.Queue<String> pendingTickErrors = new java.util.concurrent.ConcurrentLinkedQueue<>();
	private volatile String lastTickError = "";
	private final Map<String, Verification> verifications = new ConcurrentHashMap<>();
	private final java.util.Deque<EventRecord> events = new java.util.concurrent.ConcurrentLinkedDeque<>();
	private final AtomicLong persistFailures = new AtomicLong();
	private volatile Map<String, Object> lastPersist = Map.of();
	private final AtomicLong eventSeq = new AtomicLong();
	private final long startedAt = System.currentTimeMillis();
	private final String bootId = java.util.UUID.randomUUID().toString().substring(0, 8);
	private volatile Map<String, Object> lastRecovery = Map.of();
	private final java.util.Set<Long> autoRecovered = ConcurrentHashMap.newKeySet();
	private volatile List<Map<String, Object>> pathRecoveryNeeded = List.of();
	/** Depot id -> wall-clock deadline while a rebuild is in flight (regeneration is async). */
	private final Map<Long, Long> recoveringDepots = new ConcurrentHashMap<>();
	private static final long RECOVERY_WINDOW_MILLIS = 20_000L;

	/** A re-created train needs a few ticks before it accepts startUp; same-tick start is dropped. */
	private static final long DEPARTURE_DELAY_MILLIS = 1500L;
	private static final long VERIFY_AFTER_MILLIS = 600L;
	private final AtomicLong revisionCounter = new AtomicLong();
	private final Map<String, Long> revisionHashes = new ConcurrentHashMap<>();
	private volatile String lastError = "";

	public BridgeService() {
		this(BridgeSettings.defaults());
	}

	public BridgeService(BridgeSettings settings) {
		this.settings = settings == null ? BridgeSettings.defaults() : settings;
	}

	public void addListener(BridgeListener listener) {
		if (listener != null && !listeners.contains(listener)) {
			listeners.add(listener);
		}
	}

	public void removeListener(BridgeListener listener) {
		listeners.remove(listener);
	}

	public long executed() {
		return executed.get();
	}

	public long failed() {
		return failed.get();
	}

	public long deferred() {
		return deferred.get();
	}

	public long autoReleased() {
		return autoReleased.get();
	}

	public long tickFailures() {
		return tickFailures.get();
	}

	public String lastTickError() {
		return lastTickError;
	}

	/** Errors not yet logged by the platform layer; each distinct message is returned once. */
	public List<String> drainTickErrors() {
		final List<String> drained = new ArrayList<>();
		String next;
		while ((next = pendingTickErrors.poll()) != null) {
			drained.add(next);
		}
		return drained;
	}

	private void recordTickError(String message) {
		tickFailures.incrementAndGet();
		lastTickError = message;
		lastError = message;
		if (tickErrorKinds.add(message)) {
			pendingTickErrors.add(message);
		}
	}

	/** Pending path regenerations, exposed so "accepted but never applied" is visible. */
	public List<Map<String, Object>> routeDiagnostics() {
		final List<Map<String, Object>> result = new ArrayList<>();
		routeAttempts.forEach((sidingId, attempt) -> {
			final Map<String, Object> item = new LinkedHashMap<>();
			item.put("siding", GameActions.hex(sidingId));
			item.put("platform", attempt.platformHex());
			item.put("strategy", attempt.strategy());
			item.put("strategyName", GameActions.strategyName(attempt.strategy()));
			item.put("startedAt", attempt.startedAt());
			result.add(item);
		});
		return result;
	}

	public int pendingIntents() {
		return intents.size();
	}

	public String lastError() {
		return lastError;
	}

	/** Pending intents, so a caller can tell "still queued" from "silently dropped". */
	public List<Map<String, Object>> intents() {
		final long now = System.currentTimeMillis() / 1000;
		final List<Map<String, Object>> result = new ArrayList<>();
		intents.forEach((vehicle, intent) -> {
			final Map<String, Object> item = new LinkedHashMap<>();
			item.put("vehicle", vehicle);
			item.put("kind", intent.command().kind().wire);
			item.put("target", intent.command().target());
			item.put("source", intent.source().name().toLowerCase(java.util.Locale.ROOT));
			item.put("createdAt", intent.createdAt());
			item.put("deadline", intent.deadline());
			result.add(item);
		});
		return result;
	}

	// ---------------------------------------------------------------- execution

	public BridgeResult execute(Simulator simulator, BridgeCommand command, Source source) {
		final long now = simulator == null ? System.currentTimeMillis() : simulator.getCurrentMillis();
		if (simulator == null) {
			return BridgeResult.unavailable("simulator not available", now);
		}

		final String key = command.idempotencyKey().isBlank()
			? command.kind().wire + "|" + command.trainId() + "|" + command.target()
			: command.idempotencyKey();
		final CachedResult cached = idempotency.get(key);
		if (cached != null && isTerminal(cached.result()) && now - cached.at() < 600_000L) {
			return cached.result();
		}

		BridgeResult result;
		try {
			result = settings.dryRun() ? dryRun(command, now) : dispatch(simulator, command, source, now);
		} catch (Throwable throwable) {
			lastError = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
			result = BridgeResult.failed(command.trainId(), command.target(), lastError, now);
		}

		if (isTerminal(result)) {
			idempotency.put(key, new CachedResult(result, now));
		}
		switch (result.outcome()) {
			case APPLIED -> executed.incrementAndGet();
			case DEFERRED -> deferred.incrementAndGet();
			case REJECTED, FAILED, UNAVAILABLE -> failed.incrementAndGet();
		}
		if (!result.success()) {
			lastError = result.message();
		}
		fire(source, command, result);
		return result;
	}

	private static boolean isTerminal(BridgeResult result) {
		return result.outcome() != BridgeResult.Outcome.DEFERRED;
	}

	private BridgeResult dryRun(BridgeCommand command, long now) {
		return BridgeResult.applied(command.trainId(), command.target(), "dry-run: state untouched", now);
	}

	private BridgeResult dispatch(Simulator simulator, BridgeCommand command, Source source, long now) {
		if (command.kind() == BridgeCommand.Kind.DWELL) {
			return applyDwell(simulator, command, now);
		}

		final Vehicle vehicle = GameActions.findVehicle(simulator, command.trainId());
		if (vehicle == null) {
			return BridgeResult.rejected(command.trainId(), command.target(), "no such train", now);
		}
		final long ttlMillis = ttlSeconds(command) * 1000L;

		return switch (command.kind()) {
			case SET_PLATFORM -> {
				final Platform platform = GameActions.platform(simulator, command.target());
				if (platform == null) {
					yield BridgeResult.rejected(command.trainId(), command.target(), "no such platform", now);
				}
				if (vehicle.getIsOnRoute()) {
					queue(command, now, now + ttlMillis, source);
					yield BridgeResult.deferred(command.trainId(), command.target(),
						"train is running; path is frozen, queued for its next siding visit (ttl " + ttlSeconds(command) + "s)",
						now, 0);
				}
				final Siding ownedSiding = GameActions.owningSiding(simulator, vehicle);
				final GameActions.PathSnapshot rollback = GameActions.snapshotPaths(ownedSiding);
				final BridgeResult started = GameActions.regenerateTo(simulator, ownedSiding, platform, now, vehicle);
				if (started.success() && ownedSiding != null) {
					trackRoute(ownedSiding, platform, vehicle, now, source, rollback);
				}
				yield started;
			}
			case DEPLOY -> {
				if (vehicle.getIsOnRoute()) {
					yield BridgeResult.rejected(command.trainId(), command.target(), "train is already running", now);
				}
				final Siding siding = GameActions.owningSiding(simulator, vehicle);
				if (!command.target().isBlank()) {
					final Platform platform = GameActions.platform(simulator, command.target());
					if (platform == null) {
						yield BridgeResult.rejected(command.trainId(), command.target(), "no such platform", now);
					}
					final GameActions.PathSnapshot rollback = GameActions.snapshotPaths(siding);
					final BridgeResult regeneration = GameActions.regenerateTo(simulator, siding, platform, now, vehicle);
					if (!regeneration.success()) {
						yield regeneration;
					}
					trackRoute(siding, platform, vehicle, now, source, rollback);
					// Regeneration re-creates the train with a new id, so the departure is queued
					// against the siding, not against the old vehicle id.
					if (siding != null) {
						queueSiding(siding.getId(), command, now, now + 15_000L, source);
						yield BridgeResult.deferred(command.trainId(), command.target(),
							"path regenerated; departure queued on siding " + GameActions.hex(siding.getId())
								+ " (the train is re-created, its id may change)", now, now + 2500);
					}
				}
				queue(command, now, Math.min(now + ttlMillis, now + 10_000L), source);
				yield BridgeResult.deferred(command.trainId(), command.target(), "departure queued", now, now + 2000);
			}
			case RETURN_TO_DEPOT -> {
				final Siding own = GameActions.owningSiding(simulator, vehicle);
				if (!command.target().isBlank()) {
					final long requested = GameActions.parseId(command.target());
					if (requested != 0 && (own == null || own.getId() != requested)) {
						yield BridgeResult.rejected(command.trainId(), command.target(),
							"a train can only be held at its own siding" + (own == null ? "" : " " + GameActions.hex(own.getId())), now);
					}
				}
				if (!vehicle.getIsOnRoute()) {
					yield GameActions.parkAndHold(vehicle, now);
				}
				queue(command, now, now + Math.max(ttlMillis, 900_000L), source);
				yield BridgeResult.deferred(command.trainId(), own == null ? "" : GameActions.hex(own.getId()),
					"train will be held at siding " + (own == null ? "(unknown)" : GameActions.hex(own.getId()))
						+ " when the current run ends (ttl " + Math.max(ttlSeconds(command), 900) + "s)", now, 0);
			}
			default -> BridgeResult.rejected(command.trainId(), command.target(), "unsupported command", now);
		};
	}

	private int ttlSeconds(BridgeCommand command) {
		return command.ttlSeconds() > 0 ? command.ttlSeconds() : settings.intentTtlSeconds();
	}

	private BridgeResult applyDwell(Simulator simulator, BridgeCommand command, long now) {
		final Vehicle vehicle = GameActions.findVehicle(simulator, command.trainId());
		if (vehicle == null) {
			return BridgeResult.rejected(command.trainId(), "", "no such train", now);
		}
		if (command.seconds() == 0) {
			dwellHolds.remove(vehicle.getId());
			return BridgeResult.applied(command.trainId(), "release", "dwell hold released", now);
		}
		final long requested = command.seconds() < 0 ? Long.MAX_VALUE : now + command.seconds() * 1000L;
		final long cap = now + Math.max(1, settings.maxHoldSeconds()) * 1000L;
		final long releaseAt = Math.min(requested, cap);
		dwellHolds.put(vehicle.getId(), new Hold(releaseAt, now));
		final boolean capped = releaseAt < requested;
		return BridgeResult.applied(command.trainId(), String.valueOf(command.seconds()),
			capped ? "dwell hold capped to " + settings.maxHoldSeconds() + "s" : "dwell hold set", now);
	}

	/**
	 * Watches a regeneration and escalates strategies until a path appears.
	 *
	 * <p>Live testing showed strategy 0 can produce no path at all while reporting success, so
	 * "deferred" must mean "attempt started", never "route ready". Escalation is time based and
	 * stops at {@code ROUTE_STRATEGIES} or at the depot-regeneration step when that switch is
	 * off.</p>
	 */
	private void trackRoute(Siding siding, Platform platform, Vehicle vehicle, long now, Source source,
							GameActions.PathSnapshot rollback) {
		if (siding == null || platform == null) {
			lastError = "route attempt not tracked: " + (siding == null ? "no siding" : "no platform");
			return;
		}
		routeAttempts.put(siding.getId(), new RouteAttempt(siding.getId(), GameActions.hex(platform.getId()),
			vehicle == null ? "" : GameActions.hex(vehicle.getId()), 0, now, now + 3000L, now + 60_000L, source, rollback));
	}

	private void queue(BridgeCommand command, long now, long deadline, Source source) {
		intents.put(command.trainId(), new Intent(command, now, deadline, source, 0, 0));
	}

	private void queueSiding(long sidingId, BridgeCommand command, long now, long deadline, Source source) {
		intents.put("siding:" + sidingId, new Intent(command, now, deadline, source, sidingId, 0));
	}

	/** Dwell holds currently in force, for {@code /health} - a held train with no visible reason is a support ticket. */
	public List<Map<String, Object>> holds(Simulator simulator) {
		final long now = simulator == null ? 0 : simulator.getCurrentMillis();
		final List<Map<String, Object>> result = new ArrayList<>();
		dwellHolds.forEach((vehicle, hold) -> {
			final Map<String, Object> item = new LinkedHashMap<>();
			item.put("vehicle", vehicle);
			item.put("releaseAt", hold.releaseAt());
			item.put("remainingSeconds", hold.releaseAt() == Long.MAX_VALUE ? -1 : Math.max(0, (hold.releaseAt() - now) / 1000));
			result.add(item);
		});
		return result;
	}

	// ---------------------------------------------------------------- tick

	/**
	 * Called once per server tick per dimension, on the server thread.
	 *
	 * <p>Nothing thrown in here may reach the host's tick loop: a bridge that crashes the
	 * server is worse than a bridge that does nothing. Every failure becomes a counter and a
	 * {@code lastError}, and the tick continues.</p>
	 */
	public void tick(Simulator simulator) {
		try {
			tickInternal(simulator);
		} catch (Throwable throwable) {
			recordTickError("tick: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
		}
	}

	private void tickInternal(Simulator simulator) {
		if (simulator == null || settings.dryRun()) {
			return;
		}
		final long now = simulator.getCurrentMillis();

		for (final Siding siding : simulator.sidingIdMap.values()) {
			for (final Vehicle vehicle : GameActions.vehicles(siding)) {
				if (vehicle.getIsOnRoute()) {
					serviceStart.putIfAbsent(vehicle.getId(), now);
				} else {
					serviceStart.remove(vehicle.getId());
				}
			}
		}

		processRouteAttempts(simulator, now);

		for (final Map.Entry<String, Intent> entry : List.copyOf(intents.entrySet())) {
			final Intent intent = entry.getValue();
			Vehicle vehicle;
			if (intent.sidingId() != 0) {
				// Same lesson as the route attempts: never trust a hash lookup to decide
				// "this siding does not exist". A miss just means another dimension owns it.
				final Siding target = findSiding(simulator, intent.sidingId());
				if (target == null) {
					if (now > intent.deadline()) {
						intents.remove(entry.getKey());
						final String reason = "siding " + GameActions.hex(intent.sidingId()) + " vanished; departure abandoned";
						fire(intent.source(), intent.command(), BridgeResult.failed(entry.getKey(), intent.command().target(), reason, now));
						recordTickError("intent: " + reason);
					}
					continue;
				}
				if (!sidingPathReady(target)) {
					if (now > intent.deadline()) {
						intents.remove(entry.getKey());
						final String reason = "path regeneration never settled; departure abandoned for siding "
							+ GameActions.hex(intent.sidingId());
						fire(intent.source(), intent.command(), BridgeResult.failed(entry.getKey(), intent.command().target(), reason, now));
						recordTickError("intent: " + reason);
					}
					continue;
				}
				// Path is ready: wait a couple of ticks, depart, then verify on a later tick.
				completePendingDeparture(simulator, target, now);
				continue;
			} else {
				vehicle = GameActions.findVehicle(simulator, entry.getKey());
			}
			if (vehicle == null) {
				if (now > intent.deadline()) {
					intents.remove(entry.getKey());
				}
				continue;
			}
			if (vehicle.getIsOnRoute()) {
				if (now > intent.deadline()) {
					intents.remove(entry.getKey());
					final String reason = "intent expired before the train returned to a siding: " + intent.command().kind().wire;
					fire(intent.source(), intent.command(), BridgeResult.failed(entry.getKey(), intent.command().target(), reason, now));
					failed.incrementAndGet();
					recordTickError("intent: " + reason);
				}
				continue;
			}
			final BridgeResult result = switch (intent.command().kind()) {
				case SET_PLATFORM -> GameActions.regenerateTo(simulator, GameActions.owningSiding(simulator, vehicle),
					GameActions.platform(simulator, intent.command().target()), now, vehicle);
				case DEPLOY -> GameActions.depart(simulator, vehicle, GameActions.owningSiding(simulator, vehicle), now);
				case RETURN_TO_DEPOT -> GameActions.parkAndHold(vehicle, now);
				default -> BridgeResult.rejected(entry.getKey(), intent.command().target(), "unsupported intent", now);
			};
			if (result.success() || now > intent.deadline()) {
				intents.remove(entry.getKey());
				fire(intent.source(), intent.command(), result);
				switch (result.outcome()) {
					case APPLIED -> executed.incrementAndGet();
					case DEFERRED -> deferred.incrementAndGet();
					default -> failed.incrementAndGet();
				}
			}
		}

		verifyDepartures(simulator, now);

		dwellHolds.forEach((vehicleId, hold) -> {
			try {
				if (now >= hold.releaseAt()) {
					dwellHolds.remove(vehicleId);
					if (hold.requestedAt() >= 0 && now - hold.requestedAt() >= Math.max(1, settings.maxHoldSeconds()) * 1000L) {
						autoReleased.incrementAndGet();
					}
					return;
				}
				final Vehicle vehicle = GameActions.findVehicle(simulator, String.valueOf(vehicleId));
				if (vehicle != null) {
					GameActions.holdDwell(vehicle);
				}
			} catch (Throwable throwable) {
				dwellHolds.remove(vehicleId);
				recordTickError("dwell: " + throwable.getMessage());
			}
		});
	}

	/**
	 * Advances every in-flight regeneration.
	 *
	 * <p>Every removal path is instrumented. The previous version removed an attempt silently
	 * when the siding lookup missed - which is exactly what live testing observed (routeDiag
	 * vanished in 500 ms with no counter moving). The lookup now walks the siding map by id
	 * instead of hashing, and a miss is treated as "not my dimension" rather than "drop it".</p>
	 */
	private void processRouteAttempts(Simulator simulator, long now) {
		for (final Map.Entry<Long, RouteAttempt> entry : List.copyOf(routeAttempts.entrySet())) {
			try {
				final RouteAttempt attempt = entry.getValue();
				final Siding siding = findSiding(simulator, attempt.sidingId());
				if (siding == null) {
					// tick() runs once per dimension, so a miss usually just means "another
					// dimension owns this siding". Only give up at the hard deadline.
					if (now > attempt.deadline()) {
						routeAttempts.remove(entry.getKey());
						final String reason = "siding " + GameActions.hex(attempt.sidingId())
							+ " not found before deadline; regeneration abandoned";
						lastError = reason;
						recordTickError("route: " + reason);
						fire(attempt.source(), command(attempt), BridgeResult.failed(attempt.vehicleId(), attempt.platformHex(), reason, now));
						failed.incrementAndGet();
					}
					continue;
				}
				if (GameActions.routeSegments(siding) > 0) {
					routeAttempts.remove(entry.getKey());
					final boolean persisted = GameActions.persistPath(siding);
					lastPersist = persistReport(siding, persisted);
					if (!persisted) {
						persistFailures.incrementAndGet();
						lastError = "persistPath failed for siding " + GameActions.hex(siding.getId())
							+ " (writePathCache missing or refused)";
						recordTickError("persist: " + lastError);
					}
					fire(attempt.source(), command(attempt), BridgeResult.applied(attempt.vehicleId(), attempt.platformHex(),
						"path ready via " + GameActions.strategyName(attempt.strategy())
							+ (persisted ? ", persisted" : ", NOT persisted"), now));
					executed.incrementAndGet();
					continue;
				}
				if (now < attempt.nextAt()) {
					continue;
				}
				final int nextStrategy = attempt.strategy() + 1;
				final boolean blockedByConfig = nextStrategy >= 2 && !settings.allowDepotRegeneration();
				if (nextStrategy >= GameActions.ROUTE_STRATEGIES || blockedByConfig) {
					routeAttempts.remove(entry.getKey());
					final boolean restored = GameActions.restorePaths(siding, attempt.snapshot());
					final String reason = (blockedByConfig
						? "path generation produced nothing; depot regeneration is disabled (allowDepotRegeneration=false)"
						: "path generation produced nothing after " + GameActions.ROUTE_STRATEGIES + " strategies")
						+ (restored ? "; previous path restored" : "; ROLLBACK FAILED - siding has no outbound path");
					if (!restored) {
						recordTickError("route: rollback failed for siding " + GameActions.hex(attempt.sidingId()));
					}
					fire(attempt.source(), command(attempt), BridgeResult.failed(attempt.vehicleId(), attempt.platformHex(), reason, now));
					failed.incrementAndGet();
					lastError = reason;
					failPendingDeparture(intentFor(siding), reason, now);
					continue;
				}
				final Platform platform = GameActions.platform(simulator, attempt.platformHex());
				GameActions.nextRouteStrategy(simulator, siding, platform, nextStrategy, now);
				routeAttempts.put(entry.getKey(), new RouteAttempt(attempt.sidingId(), attempt.platformHex(),
					attempt.vehicleId(), nextStrategy, attempt.startedAt(), now + 3000L, attempt.deadline(),
					attempt.source(), attempt.snapshot()));
			} catch (Throwable throwable) {
				routeAttempts.remove(entry.getKey());
				recordTickError("route: " + throwable.getMessage());
			}
		}
	}

	/**
	 * Fires a departure that was waiting for the regenerated path.
	 *
	 * <p>Before this, a deploy-with-target queued a siding intent and relied on a later tick to
	 * notice the path; live testing showed the intent never ran (same silent lookup bug) and the
	 * train simply stayed parked. Path readiness is the moment to act, so the departure happens
	 * right here, on the current vehicle instance.</p>
	 */
	private void completePendingDeparture(Simulator simulator, Siding siding, long now) {
		final String key = "siding:" + siding.getId();
		final Intent pending = intents.get(key);
		if (pending == null || pending.command().kind() != BridgeCommand.Kind.DEPLOY) {
			return;
		}
		// Live testing: startUp in the very tick the path becomes ready gets the vehicle dropped
		// by the host. The same departure a couple of ticks later always worked, so the bridge
		// now waits instead of firing immediately.
		final long notBefore = pending.notBefore() == 0 ? now + DEPARTURE_DELAY_MILLIS : pending.notBefore();
		if (now < notBefore) {
			intents.put(key, pending.withNotBefore(notBefore));
			return;
		}
		intents.remove(key);
		final Vehicle vehicle = GameActions.firstVehicle(siding);
		if (vehicle == null) {
			final String reason = "path ready but no train is parked at siding " + GameActions.hex(siding.getId());
			fire(pending.source(), pending.command(), BridgeResult.failed(pending.command().trainId(), pending.command().target(), reason, now));
			failed.incrementAndGet();
			lastError = reason;
			return;
		}
		final BridgeResult result = GameActions.depart(simulator, vehicle, siding, now);
		if (result.success()) {
			// Do not report success yet: the host may still drop the re-created train.
			verifications.put(GameActions.hex(vehicle.getId()), new Verification(GameActions.hex(vehicle.getId()),
				now, now + 6000L, pending.command(), pending.source()));
			return;
		}
		fire(pending.source(), pending.command(), result);
		failed.incrementAndGet();
		lastError = result.message();
	}

	/**
	 * Post-departure check: a departure only counts once telemetry agrees.
	 *
	 * <p>v1.6 reported APPLIED while the host dropped the started vehicle, so the caller saw
	 * success and the train never moved. Verification turns that into an honest failure.</p>
	 */
	private void verifyDepartures(Simulator simulator, long now) {
		for (final Map.Entry<String, Verification> entry : List.copyOf(verifications.entrySet())) {
			try {
				final Verification verification = entry.getValue();
				if (now < verification.departAt() + VERIFY_AFTER_MILLIS) {
					continue;
				}
				final Vehicle vehicle = GameActions.findVehicle(simulator, verification.vehicleId());
				if (vehicle == null) {
					verifications.remove(entry.getKey());
					final String reason = "departed vehicle was dropped by the host (startUp did not stick)";
					fire(verification.source(), verification.command(),
						BridgeResult.failed(verification.command().trainId(), verification.command().target(), reason, now));
					failed.incrementAndGet();
					lastError = reason;
					recordTickError("depart: " + reason + " vehicle=" + verification.vehicleId());
					continue;
				}
				if (vehicle.getIsOnRoute()) {
					verifications.remove(entry.getKey());
					fire(verification.source(), verification.command(),
						BridgeResult.applied(verification.command().trainId(), verification.command().target(),
							"departed and verified on route", now));
					executed.incrementAndGet();
					continue;
				}
				if (now > verification.deadline()) {
					verifications.remove(entry.getKey());
					final String reason = "departure did not take effect within " + (verification.deadline() - verification.departAt()) + "ms";
					fire(verification.source(), verification.command(),
						BridgeResult.failed(verification.command().trainId(), verification.command().target(), reason, now));
					failed.incrementAndGet();
					lastError = reason;
					recordTickError("depart: " + reason);
				}
			} catch (Throwable throwable) {
				verifications.remove(entry.getKey());
				recordTickError("verify: " + throwable.getMessage());
			}
		}
	}

	private void failPendingDeparture(Intent pending, String reason, long now) {
		if (pending != null && pending.command().kind() == BridgeCommand.Kind.DEPLOY) {
			fire(pending.source(), pending.command(), BridgeResult.failed(pending.command().trainId(), pending.command().target(), reason, now));
			failed.incrementAndGet();
		}
	}

	private Intent intentFor(Siding siding) {
		return siding == null ? null : intents.remove("siding:" + siding.getId());
	}

	private Map<String, Object> persistReport(Siding siding, boolean persisted) {
		final Map<String, Object> report = new LinkedHashMap<>();
		report.put("siding", GameActions.hex(siding.getId()));
		report.put("ok", persisted);
		report.put("at", System.currentTimeMillis());
		report.put("method", "writePathCache");
		return report;
	}

	public Map<String, Object> lastPersist() {
		return lastPersist;
	}

	public long persistFailures() {
		return persistFailures.get();
	}

	/**
	 * Audits sidings per depot.
	 *
	 * <p>Live testing showed the damage is depot scoped: regenerating one siding leaves the whole
	 * depot without usable paths after a restart, and a siding nobody touched goes down with it.
	 * A per-siding health check therefore cannot describe (or repair) the problem; this groups by
	 * depot and only reports depots that actually have parked trains to serve.</p>
	 */
	public List<Map<String, Object>> auditDepots(Simulator simulator) {
		final List<Map<String, Object>> broken = collectBroken(simulator);
		pathRecoveryNeeded = broken;
		return broken;
	}

	/**
	 * Audits every dimension and publishes the union.
	 *
	 * <p>Auditing one simulator at a time overwrote the shared field with that dimension's
	 * (usually empty) result, so {@code /health.pathRecoveryNeeded} read as "all healthy" a
	 * moment after a command had just reported a broken depot. One pass, one publish.</p>
	 */
	public List<Map<String, Object>> auditAll(List<Simulator> simulators) {
		final List<Map<String, Object>> broken = new ArrayList<>();
		if (simulators != null) {
			for (final Simulator simulator : simulators) {
				broken.addAll(collectBroken(simulator));
			}
		}
		pathRecoveryNeeded = broken;
		return broken;
	}

	private List<Map<String, Object>> collectBroken(Simulator simulator) {
		final List<Map<String, Object>> broken = new ArrayList<>();
		if (simulator == null) {
			return broken;
		}
		for (final Depot depot : simulator.depotIdMap.values()) {
			if (isRecovering(depot)) {
				// A rebuild is in flight: reporting it as broken would flap every sample, and
				// re-publishing the same result is exactly what v2.0 got wrong.
				continue;
			}
			final List<String> lost = new ArrayList<>();
			int depotVehicles = 0;
			for (final Siding siding : depot.savedRails) {
				if (GameActions.routeSegments(siding) <= 0) {
					lost.add(GameActions.hex(siding.getId()));
				}
				depotVehicles += GameActions.vehicles(siding).size();
			}
			if (!lost.isEmpty() && depotVehicles > 0) {
				final Map<String, Object> item = new LinkedHashMap<>();
				item.put("depot", depot.getName());
				item.put("depotId", GameActions.hex(depot.getId()));
				item.put("sidings", lost);
				item.put("lostSidings", lost.size());
				// Number of vehicles in the depot, not "vehicles affected by the loss".
				item.put("depotVehicles", depotVehicles);
				broken.add(item);
			}
		}
		return broken;
	}

	public List<Map<String, Object>> pathRecoveryNeeded() {
		return pathRecoveryNeeded;
	}

	/** Depots currently being rebuilt: visible, but not counted as "needs action". */
	public List<Map<String, Object>> recovering() {
		final List<Map<String, Object>> result = new ArrayList<>();
		final long now = System.currentTimeMillis();
		recoveringDepots.forEach((depotId, until) -> {
			if (until > now) {
				final Map<String, Object> item = new LinkedHashMap<>();
				item.put("depotId", GameActions.hex(depotId));
				item.put("until", until);
				result.add(item);
			}
		});
		return result;
	}

	/** True while any rebuild is outstanding - the platform layer then audits every tick. */
	public boolean hasRecoveringDepots() {
		return !recoveringDepots.isEmpty();
	}

	private void beginRecovery(Depot depot) {
		if (depot != null) {
			recoveringDepots.put(depot.getId(), System.currentTimeMillis() + RECOVERY_WINDOW_MILLIS);
		}
	}

	/**
	 * Regeneration is asynchronous (several seconds), so a depot is "recovering" until its
	 * paths are back or the window expires. While it is, the depot is neither reported as
	 * broken nor re-published - which removes both the stale advisory and the flapping the
	 * live run showed.
	 */
	private boolean isRecovering(Depot depot) {
		final Long until = recoveringDepots.get(depot.getId());
		if (until == null) {
			return false;
		}
		final long now = System.currentTimeMillis();
		if (now > until) {
			recoveringDepots.remove(depot.getId());
			return false;
		}
		boolean allReady = !depot.savedRails.isEmpty();
		for (final Siding siding : depot.savedRails) {
			if (GameActions.routeSegments(siding) <= 0) {
				allReady = false;
				break;
			}
		}
		if (allReady) {
			recoveringDepots.remove(depot.getId());
			return false;
		}
		return true;
	}

	/** savedRails is a set, not a list - take the first roadside we find. */
	private static Siding firstSiding(Depot depot) {
		for (final Siding siding : depot.savedRails) {
			return siding;
		}
		return null;
	}

	/** Rebuilds every broken depot (or all of them). Returns how many were attempted. */
	public int recoverDepots(List<Simulator> simulators, boolean onlyBroken) {
		final List<Map<String, Object>> targets = onlyBroken ? auditAll(simulators) : List.of();
		int recovered = 0;
		for (final Simulator simulator : simulators) {
			recovered += recoverDepots(simulator, onlyBroken, targets);
		}
		return recovered;
	}

	public int recoverDepots(Simulator simulator, boolean onlyBroken) {
		return recoverDepots(simulator, onlyBroken, onlyBroken ? auditDepots(simulator) : List.of());
	}

	private int recoverDepots(Simulator simulator, boolean onlyBroken, List<Map<String, Object>> targets) {
		if (simulator == null) {
			return 0;
		}
		int recovered = 0;
		if (onlyBroken) {
			for (final Map<String, Object> target : targets) {
				final long depotId = GameActions.parseId(String.valueOf(target.get("depotId")));
				final Depot depot = simulator.depotIdMap.get(depotId);
				if (depot == null || depot.savedRails.isEmpty()) {
					continue;
				}
				beginRecovery(depot);
				if (GameActions.regenerateDepotQuietly(simulator, firstSiding(depot))) {
					recovered++;
					recordRecovery(depot, target, "manual");
				}
			}
		} else {
			for (final Depot depot : simulator.depotIdMap.values()) {
				if (depot.savedRails.isEmpty()) {
					continue;
				}
				// Measured before regeneration: afterwards the paths are gone by design, so any
				// count taken later would describe the wrong world.
				final Map<String, Object> before = new LinkedHashMap<>();
				int lost = 0;
				int vehicles = 0;
				for (final Siding siding : depot.savedRails) {
					if (GameActions.routeSegments(siding) <= 0) {
						lost++;
					}
					vehicles += GameActions.vehicles(siding).size();
				}
				before.put("lostSidings", lost);
				before.put("depotVehicles", vehicles);
				beginRecovery(depot);
				if (GameActions.regenerateDepotQuietly(simulator, firstSiding(depot))) {
					recovered++;
					recordRecovery(depot, before, "manual");
				}
			}
		}
		return recovered;
	}

	/**
	 * Called periodically by the platform layer. Auto-repairs a broken depot only when the
	 * operator enabled depot regeneration, and only once per depot per session - it drops and
	 * re-creates every train of that depot.
	 */
	public int autoRecover(List<Simulator> simulators) {
		if (simulators == null || !settings.allowDepotRegeneration()) {
			return 0;
		}
		int recovered = 0;
		for (final Simulator simulator : simulators) {
			recovered += autoRecover(simulator);
		}
		return recovered;
	}

	public int autoRecover(Simulator simulator) {
		if (simulator == null || !settings.allowDepotRegeneration()) {
			return 0;
		}
		int recovered = 0;
		for (final Map<String, Object> target : collectBroken(simulator)) {
			final long depotId = GameActions.parseId(String.valueOf(target.get("depotId")));
			if (!autoRecovered.add(depotId)) {
				continue;
			}
			final Depot depot = simulator.depotIdMap.get(depotId);
			beginRecovery(depot);
			if (depot != null && !depot.savedRails.isEmpty()
				&& GameActions.regenerateDepotQuietly(simulator, firstSiding(depot))) {
				recovered++;
				lastError = "auto-recovered depot " + depot.getName() + " (paths were missing)";
				recordRecovery(depot, target, "auto");
			}
		}
		return recovered;
	}

	/** Last 20 events, newest first - the text that made earlier rounds hard to diagnose. */
	public List<Map<String, Object>> events() {
		final List<Map<String, Object>> result = new ArrayList<>();
		for (final EventRecord record : events) {
			final Map<String, Object> item = new LinkedHashMap<>();
			item.put("seq", record.seq());
			item.put("at", record.at());
			item.put("kind", record.kind());
			item.put("target", record.target());
			item.put("outcome", record.outcome());
			item.put("message", record.message());
			result.add(item);
		}
		return result;
	}

	private static BridgeCommand command(RouteAttempt attempt) {
		return BridgeCommand.of(BridgeCommand.Kind.SET_PLATFORM, attempt.vehicleId(), attempt.platformHex(), 0);
	}

	/** Linear scan by id: robust against key-space surprises, and cheap for a few hundred sidings. */
	private static Siding findSiding(Simulator simulator, long sidingId) {
		for (final Siding candidate : simulator.sidingIdMap.values()) {
			if (candidate.getId() == sidingId) {
				return candidate;
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- telemetry

	/** A telemetry payload plus the content revision it belongs to. */
	public record Payload(String body, long version) {
	}

	/**
	 * Snapshot with a <em>content</em> revision.
	 *
	 * <p>The first version used the simulation clock as the version, which advances every tick
	 * and therefore made {@code ?since=} and {@code If-None-Match} unreachable by construction.
	 * The revision now increments only when the payload actually changes, so a 304 means "your
	 * copy is current".</p>
	 */
	public Payload telemetry(Simulator simulator) {
		final long now = simulator.getCurrentMillis();
		final Map<String, Object> root = new LinkedHashMap<>();

		final List<Object> stations = new ArrayList<>();
		for (final Station station : simulator.stationIdMap.values()) {
			final Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", station.getHexId());
			item.put("name", station.getName());
			item.put("color", station.getColor());
			stations.add(item);
		}
		root.put("stations", stations);

		final List<Object> platforms = new ArrayList<>();
		for (final Platform platform : simulator.platformIdMap.values()) {
			final Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", platform.getHexId());
			item.put("name", platform.getName());
			item.put("stationId", platform.area == null ? "0" : platform.area.getHexId());
			item.put("dwellMillis", platform.getDwellTime());
			platforms.add(item);
		}
		root.put("platforms", platforms);

		final List<Object> routes = new ArrayList<>();
		for (final Route route : simulator.routeIdMap.values()) {
			final Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", route.getHexId());
			item.put("name", route.getName());
			item.put("number", route.getRouteNumber());
			item.put("color", route.getColor());
			item.put("type", route.getRouteTypeKey());
			item.put("circularState", route.getCircularState().name());
			final List<Object> stops = new ArrayList<>();
			route.getRoutePlatforms().forEach(routePlatform -> {
				if (routePlatform.platform != null && routePlatform.platform.area != null) {
					final Map<String, Object> stop = new LinkedHashMap<>();
					stop.put("stationId", routePlatform.platform.area.getHexId());
					stop.put("name", routePlatform.platform.getName());
					stop.put("x", routePlatform.platform.getMidPosition().getX());
					stop.put("y", routePlatform.platform.getMidPosition().getY());
					stop.put("z", routePlatform.platform.getMidPosition().getZ());
					stop.put("dwellMillis", routePlatform.platform.getDwellTime());
					stops.add(stop);
				}
			});
			item.put("stops", stops);
			item.put("durations", new ArrayList<>(route.durations));
			final List<Object> depotNames = new ArrayList<>();
			route.depots.forEach(depot -> depotNames.add(depot.getName()));
			item.put("depots", depotNames);
			routes.add(item);
		}
		root.put("routes", routes);

		final List<Object> depots = new ArrayList<>();
		for (final Depot depot : simulator.depotIdMap.values()) {
			final Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", depot.getHexId());
			item.put("name", depot.getName());
			final List<Object> routeIds = new ArrayList<>();
			depot.routes.forEach(route -> routeIds.add(route.getHexId()));
			item.put("routeIds", routeIds);
			final List<Object> sidingIds = new ArrayList<>();
			depot.savedRails.forEach(siding -> sidingIds.add(GameActions.hex(siding.getId())));
			item.put("sidingIds", sidingIds);
			depots.add(item);
		}
		root.put("depots", depots);

		final List<Object> vehicles = new ArrayList<>();
		for (final Siding siding : simulator.sidingIdMap.values()) {
			for (final Vehicle vehicle : GameActions.vehicles(siding)) {
				vehicles.add(vehicleJson(simulator, vehicle, siding, now));
			}
		}
		root.put("vehicles", vehicles);

		final String content = Json.write(root);
		final long hash = fnv1a(content);
		final String key = simulator.dimension == null ? "default" : simulator.dimension;
		final Long previous = revisionHashes.put(key, hash);
		final long revision = previous != null && previous == hash ? revisionCounter.get() : revisionCounter.incrementAndGet();

		final Map<String, Object> header = new LinkedHashMap<>();
		header.put("protocol", 1);
		header.put("semantics", 2);
		header.put("serverTime", now);
		header.put("version", revision);
		header.put("source", settings.dryRun() ? "mtbridge-dryrun" : "mtbridge");
		header.putAll(root);
		header.put("routeDiag", routeDiagnostics());
		header.put("lastTickError", lastTickError);
		return new Payload(Json.write(header), revision);
	}

	private static long fnv1a(String value) {
		long hash = 0xcbf29ce484222325L;
		for (int index = 0; index < value.length(); index++) {
			hash ^= value.charAt(index);
			hash *= 0x100000001b3L;
		}
		return hash;
	}

	private Map<String, Object> vehicleJson(Simulator simulator, Vehicle vehicle, Siding siding, long now) {
		final Map<String, Object> item = new LinkedHashMap<>();
		try {
			final String currentStation = GameActions.hex(vehicle.vehicleExtraData.getThisStationId());
			final String nextStation = GameActions.hex(vehicle.vehicleExtraData.getNextStationId());
			final String nextPlatform = GameActions.hex(vehicle.vehicleExtraData.getNextPlatformId());
			final long eta = estimateEta(vehicle, now);
			item.put("id", Long.toString(vehicle.getId()));
			item.put("name", String.valueOf(vehicle.getId()));
			item.put("routeId", Long.toString(vehicle.vehicleExtraData.getThisRouteId()));
			item.put("currentStationId", currentStation);
			item.put("nextStationId", nextStation);
			item.put("nextPlatformId", nextPlatform);
			item.put("stationId", currentStation); // legacy alias, semantics 2
			item.put("deviation", deviation(vehicle));
			item.put("railProgress", GameActions.railProgress(vehicle));
			item.put("speed", GameActions.speed(vehicle) * 1000.0);
			item.put("dwellElapsed", GameActions.dwellElapsed(vehicle));
			item.put("controllable", true);
			item.put("atPlatform", GameActions.isStopped(vehicle));
			item.put("inDepot", !vehicle.getIsOnRoute());
			item.put("depot", siding.area == null ? "" : siding.area.getName());
			item.put("sidingId", GameActions.hex(siding.getId()));
			item.put("eta", eta);
			item.put("departure", eta + nextDwell(vehicle));
			item.put("serviceStart", serviceStart.getOrDefault(vehicle.getId(), 0L));
			item.put("passengers", ridingEntities(vehicle));
			item.put("passengersExact", false);
			item.put("pathReady", pathReady(vehicle));
			final String planned = plannedFirstPlatform(simulator, siding);
			item.put("plannedFirstPlatformId", planned);
			// A parked train with no planned platform means the siding lost its outbound path
			// (regeneration cleared it and nothing rebuilt it). Say so instead of looking idle.
			item.put("pathLost", !vehicle.getIsOnRoute() && "0".equals(planned));
		} catch (Throwable throwable) {
			item.put("error", throwable.getMessage());
		}
		return item;
	}

	private long estimateEta(Vehicle vehicle, long now) {
		final double speed = GameActions.speed(vehicle);
		if (speed <= 0.0001) {
			return now;
		}
		try {
			final Object extra = Reflect.get(vehicle, "vehicleExtraData");
			final Object path = Reflect.get(extra, "immutablePath");
			final long index = Reflect.getLong(vehicle, "nextStoppingIndexAto");
			if (path instanceof java.util.List<?> list && index >= 0 && index < list.size()) {
				final Object pathData = list.get((int) index);
				final double end = ((Number) Reflect.call(pathData, "getEndDistance")).doubleValue();
				final double remaining = Math.max(0, end - GameActions.railProgress(vehicle));
				return now + (long) (remaining / speed);
			}
		} catch (Throwable ignored) {
			// fall through
		}
		return now;
	}

	private long nextDwell(Vehicle vehicle) {
		try {
			final Object path = Reflect.get(vehicle.vehicleExtraData, "immutablePath");
			final long index = Reflect.getLong(vehicle, "nextStoppingIndexAto");
			if (path instanceof java.util.List<?> list && index >= 0 && index < list.size()) {
				return ((Number) Reflect.call(list.get((int) index), "getDwellTime")).longValue();
			}
		} catch (Throwable ignored) {
			// unknown dwell
		}
		return 20_000L;
	}

	/** True when the siding has a generated path towards the main line. */
	private boolean sidingPathReady(Siding siding) {
		try {
			final Object path = Reflect.get(siding, "pathSidingToMainRoute");
			return path instanceof java.util.List<?> list && !list.isEmpty();
		} catch (Throwable ignored) {
			return false;
		}
	}

	/** True when the train carries a usable path and a concrete next stop. */
	private boolean pathReady(Vehicle vehicle) {
		try {
			final Object path = Reflect.get(vehicle.vehicleExtraData, "immutablePath");
			final boolean hasPath = path instanceof java.util.List<?> list && !list.isEmpty();
			return hasPath && vehicle.vehicleExtraData.getNextPlatformId() != 0L;
		} catch (Throwable ignored) {
			return false;
		}
	}

	/**
	 * The platform a parked train would head for, read from the siding's generated path.
	 *
	 * <p>Parked trains have no next platform (the field is 0), which made "did the platform
	 * change actually take effect" impossible to verify from telemetry.</p>
	 */
	private String plannedFirstPlatform(Simulator simulator, Siding siding) {
		try {
			final Object path = Reflect.get(siding, "pathSidingToMainRoute");
			if (path instanceof java.util.List<?> list) {
				for (int index = list.size() - 1; index >= 0; index--) {
					final Object pathData = list.get(index);
					final long savedRailId = ((Number) Reflect.call(pathData, "getSavedRailBaseId")).longValue();
					if (simulator.platformIdMap.containsKey(savedRailId)) {
						return GameActions.hex(savedRailId);
					}
				}
			}
		} catch (Throwable ignored) {
			// path not generated yet
		}
		return "0";
	}

	private long ridingEntities(Vehicle vehicle) {
		final long[] count = {0};
		try {
			vehicle.vehicleExtraData.iterateRidingEntities(entity -> count[0]++);
		} catch (Throwable ignored) {
			return 0L;
		}
		return count[0];
	}

	private long deviation(Vehicle vehicle) {
		try {
			return Reflect.getLong(vehicle, "deviation");
		} catch (Throwable ignored) {
			return 0L;
		}
	}

	// ---------------------------------------------------------------- events

	private void recordRecovery(Depot depot, Map<String, Object> target, String mode) {
		final Map<String, Object> item = new LinkedHashMap<>();
		item.put("depot", depot.getName());
		item.put("depotId", GameActions.hex(depot.getId()));
		item.put("sidings", target.getOrDefault("lostSidings", depot.savedRails.size()));
		item.put("trains", target.getOrDefault("depotVehicles", 0));
		item.put("mode", mode);
		item.put("at", System.currentTimeMillis());
		lastRecovery = item;
		fireRaw(Source.COMMAND, "recover_depot",
			GameActions.hex(depot.getId()),
			BridgeResult.applied("", GameActions.hex(depot.getId()),
				"recovered depot " + depot.getName() + " (" + mode + ")", System.currentTimeMillis()));
	}

	public Map<String, Object> lastRecovery() {
		return lastRecovery;
	}

	public String bootId() {
		return bootId;
	}

	public long startedAt() {
		return startedAt;
	}

	/** Emits an event that is not tied to a {@link BridgeCommand} (recovery, audit findings). */
	private void fireRaw(Source source, String kind, String target, BridgeResult result) {
		events.addFirst(new EventRecord(eventSeq.incrementAndGet(), System.currentTimeMillis(), kind, target,
			result.outcome().name(), result.message()));
		while (events.size() > 20) {
			events.removeLast();
		}
		for (final BridgeListener listener : listeners) {
			try {
				listener.onBridgeEvent(new BridgeEvent(BridgeEvent.Source.COMMAND, kind, "", target, result));
			} catch (Throwable ignored) {
				// listener isolation
			}
		}
	}

	private void fire(Source source, BridgeCommand command, BridgeResult result) {
		events.addFirst(new EventRecord(eventSeq.incrementAndGet(), System.currentTimeMillis(), command.kind().wire,
			command.target(), result.outcome().name(), result.message()));
		while (events.size() > 20) {
			events.removeLast();
		}
		if (listeners.isEmpty()) {
			return;
		}
		final BridgeEvent event = new BridgeEvent(
			source == Source.HTTP ? BridgeEvent.Source.HTTP : source == Source.COMMAND ? BridgeEvent.Source.COMMAND : BridgeEvent.Source.API,
			command.kind().wire, command.trainId(), command.target(), result);
		for (final BridgeListener listener : listeners) {
			try {
				listener.onBridgeEvent(event);
			} catch (Throwable ignored) {
				// a broken listener must never break dispatch
			}
		}
	}
}
