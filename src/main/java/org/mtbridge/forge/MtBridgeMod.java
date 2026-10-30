package org.mtbridge.forge;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mtr.core.simulation.Simulator;
import org.mtbridge.api.DispatchBridgeAPI;
import org.mtbridge.config.BridgeConfig;
import org.mtbridge.core.BridgeService;
import org.mtbridge.http.BridgeHttpServer;

/**
 * Mod entry point.
 *
 * <p>The first live-server run exposed an ordering race: this mod's server-started listener can
 * run before the host mod creates its simulator container, leaving the bridge dead for the whole
 * session. Three defences are applied together - lowest event priority, a bounded retry from the
 * server tick, and a visible counter instead of a silent {@code ready=false}.</p>
 */
@Mod("mtbridge")
public final class MtBridgeMod {

	public static final String MOD_ID = "mtbridge";
	private static final Logger LOGGER = LogManager.getLogger("mtbridge");
	private static final int MAX_LOCATE_LOGS = 12;
	private static final long LOCATE_RETRY_MILLIS = 5000L;

	private final BridgeConfig config = BridgeConfig.load();
	private final BridgeService service = new BridgeService(config.settings());
	private final ForgeHost host = new ForgeHost();
	private BridgeHttpServer http;
	private int locateLogs;
	private long nextLocateAt;
	private int recoveryCounter;
	private long nextRecoveryLogAt;

	public MtBridgeMod() {
		DispatchBridgeAPI.install(new ForgeBridgeApi(service, host));
		MinecraftForge.EVENT_BUS.register(this);
		if (!config.configError.isEmpty()) {
			// The BOM incident shipped a config that silently fell back to defaults; the console is
			// the first place an operator looks, so the failure has to be said out loud here.
			LOGGER.error("bridge config error: {} (running on defaults)", config.configError);
		}
		LOGGER.info("dispatch bridge loaded ({}:{}, token {}, dryRun {})", config.bindAddress, config.port,
			config.token.isEmpty() ? "disabled" : "enabled", config.dryRun);
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onServerStarted(ServerStartedEvent event) {
		final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
		host.tryAttach(server);
		LOGGER.info("server started; simulators located: {} ({})", host.dimensionCount(), host.locator());
		if (!config.enabled) {
			LOGGER.warn("bridge disabled by config; no HTTP control channel");
			return;
		}
		if (config.unsafeWithoutToken()) {
			LOGGER.error("refusing to start: bindAddress {} is not loopback and token is empty", config.bindAddress);
			return;
		}
		try {
			http = new BridgeHttpServer(config.port, config.bindAddress, config.token, config.httpThreads, service, host,
				config::describe);
			http.start();
			LOGGER.info("bridge listening on {}:{}", config.bindAddress, config.port);
		} catch (Exception exception) {
			LOGGER.error("bridge HTTP server failed to start on port {}: {}", config.port, exception.getMessage());
			http = null;
		}
	}

	@SubscribeEvent
	public void onServerStopping(ServerStoppingEvent event) {
		if (http != null) {
			http.close();
			http = null;
		}
		host.detach();
		LOGGER.info("bridge stopped; host native behaviour restored");
	}

	@SubscribeEvent
	public void onRegisterCommands(RegisterCommandsEvent event) {
		BridgeCommands.register(event.getDispatcher(), service, host);
	}

	@SubscribeEvent
	public void onServerTick(TickEvent.ServerTickEvent event) {
		if (event.phase != TickEvent.Phase.END) {
			return;
		}
		try {
			retryLocate();
			for (int dimension = 0; dimension < host.dimensionCount(); dimension++) {
				final Simulator simulator = host.simulator(dimension);
				if (simulator != null) {
					service.tick(simulator);
				}
			}
			auditDepots();
			// Each distinct swallowed error is logged once, so tickFailures is actionable.
			for (final String message : service.drainTickErrors()) {
				LOGGER.warn("bridge tick error: {}", message);
			}
		} catch (Throwable throwable) {
			// Last line of defence: a bridge failure must never take the server tick down.
			LOGGER.error("bridge tick failed; continuing without applying intents: {}", throwable.toString());
		}
	}

	/**
	 * Depot health pass (every ~10 s). Path loss is depot scoped, so repair is depot scoped too.
	 *
	 * <p>Auto-repair only runs when the operator enabled depot regeneration; otherwise the
	 * finding is logged once a minute and exposed in {@code /health.pathRecoveryNeeded}, and
	 * {@code /mtbridge recover} performs it on demand.</p>
	 */
	private void auditDepots() {
		if (host.dimensionCount() == 0) {
			return;
		}
		// While a rebuild is in flight the advisory is recomputed every tick, so it converges the
		// moment the paths appear instead of one sampling period later.
		final boolean urgent = service.hasRecoveringDepots();
		if (!urgent && ++recoveryCounter % 200 != 0) {
			return;
		}
		final java.util.List<Simulator> simulators = new java.util.ArrayList<>();
		for (int dimension = 0; dimension < host.dimensionCount(); dimension++) {
			final Simulator simulator = host.simulator(dimension);
			if (simulator != null) {
				simulators.add(simulator);
			}
		}
		if (simulators.isEmpty()) {
			return;
		}
		// One pass over every dimension, then one publish - auditing per dimension used to
		// overwrite the shared report with the (empty) result of the next dimension.
		final java.util.List<java.util.Map<String, Object>> broken = service.auditAll(simulators);
		if (broken.isEmpty()) {
			return;
		}
		final int recovered = service.autoRecover(simulators);
		if (recovered > 0) {
			// Repair happened after the audit published its result; refresh so the advisory and
			// the state it describes cannot disagree for a whole cycle.
			service.auditAll(simulators);
		}
		final long now = System.currentTimeMillis();
		if (now >= nextRecoveryLogAt) {
			nextRecoveryLogAt = now + 60_000L;
			LOGGER.warn("depots with missing paths: {} (auto-recovered {}, allowDepotRegeneration={}); "
					+ "run '/mtbridge recover' to rebuild them",
				broken, recovered, config.allowDepotRegeneration);
		}
	}

	/** Bounded retry: the host may initialise after our listener. Gives up loudly, never silently. */
	private void retryLocate() {
		if (host.ready() || locateLogs >= MAX_LOCATE_LOGS) {
			return;
		}
		final long now = System.currentTimeMillis();
		if (now < nextLocateAt) {
			return;
		}
		nextLocateAt = now + LOCATE_RETRY_MILLIS;
		host.tryAttach(ServerLifecycleHooks.getCurrentServer());
		if (host.ready()) {
			LOGGER.info("simulators located after retry: {} ({})", host.dimensionCount(), host.locator());
		} else {
			locateLogs++;
			LOGGER.info("waiting for host simulator {}/{} (locator={})", locateLogs, MAX_LOCATE_LOGS, host.locator());
			if (locateLogs >= MAX_LOCATE_LOGS) {
				LOGGER.warn("host simulator still not found; bridge stays unavailable for this session "
					+ "(check that the host mod is present and started)");
			}
		}
	}
}
