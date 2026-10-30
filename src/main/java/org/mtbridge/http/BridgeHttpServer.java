package org.mtbridge.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.mtr.core.simulation.Simulator;
import org.mtbridge.api.BridgeResult;
import org.mtbridge.core.BridgeCommand;
import org.mtbridge.core.BridgeHost;
import org.mtbridge.core.BridgeService;
import org.mtbridge.core.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * HTTP surface, protocol v1 (semantics 2), served by the JDK server.
 *
 * <p>Changes from the first integration run: constant-time token comparison, a larger worker
 * pool so state polling cannot starve commands, an honest {@code loading} flag instead of a
 * bare 503, dimension ids in {@code capabilities} (the live world loads overworld - the_end -
 * the_nether, so a bare index is not enough), and ETag support on {@code state}.</p>
 */
public final class BridgeHttpServer implements AutoCloseable {

	private static final long RESPONSE_TIMEOUT_MILLIS = 2500;

	private final BridgeService service;
	private final BridgeHost host;
	private final byte[] tokenBytes;
	private final HttpServer server;
	private final java.util.function.Supplier<Map<String, Object>> settings;

	public BridgeHttpServer(int port, String bindAddress, String token, int threads, BridgeService service, BridgeHost host,
							java.util.function.Supplier<Map<String, Object>> settings) throws IOException {
		this.service = service;
		this.host = host;
		this.settings = settings;
		this.tokenBytes = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
		this.server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
		this.server.setExecutor(Executors.newFixedThreadPool(Math.max(4, threads), runnable -> {
			final Thread thread = new Thread(runnable, "mtbridge-http");
			thread.setDaemon(true);
			return thread;
		}));
		this.server.createContext("/dispatch/v1/", this::handle);
	}

	public void start() {
		server.start();
	}

	@Override
	public void close() {
		server.stop(0);
	}

	private void handle(HttpExchange exchange) {
		try {
			if (!authorized(exchange)) {
				respond(exchange, 401, error("unauthorized", "missing or invalid bearer token"));
				return;
			}
			final String path = exchange.getRequestURI().getPath();
			switch (path) {
				case "/dispatch/v1/capabilities" -> respond(exchange, 200, capabilities());
				case "/dispatch/v1/health" -> respond(exchange, 200, health());
				case "/dispatch/v1/state" -> handleState(exchange);
				case "/dispatch/v1/command" -> handleCommand(exchange);
				default -> respond(exchange, 404, error("not_found", path));
			}
		} catch (Exception exception) {
			host.error(exception.getClass().getSimpleName() + ": " + exception.getMessage());
			try {
				respond(exchange, 500, error("internal_error", String.valueOf(exception.getMessage())));
			} catch (IOException ignored) {
				// response already committed
			}
		}
	}

	private boolean authorized(HttpExchange exchange) {
		if (tokenBytes.length == 0) {
			return true;
		}
		final String header = exchange.getRequestHeaders().getFirst("Authorization");
		if (header == null || !header.startsWith("Bearer ")) {
			return false;
		}
		final byte[] presented = header.substring(7).trim().getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(tokenBytes, presented);
	}

	private Map<String, Object> capabilities() {
		final Map<String, Object> body = new LinkedHashMap<>();
		body.put("protocol", 1);
		body.put("semantics", 2);
		body.put("features", List.of("set_platform", "deploy_train", "return_to_depot", "dwell_control"));
		body.put("telemetry", List.of("vehicles", "platforms", "routes", "depots"));
		final List<Object> dimensions = new ArrayList<>();
		final List<String> ids = host.dimensionIds();
		for (int index = 0; index < host.dimensionCount(); index++) {
			final Map<String, Object> item = new LinkedHashMap<>();
			item.put("index", index);
			item.put("id", index < ids.size() ? ids.get(index) : "");
			dimensions.add(item);
		}
		body.put("dimensions", dimensions);
		body.put("ready", host.ready());
		body.put("loading", !host.ready());
		body.put("minPollIntervalMs", 250);
		return body;
	}

	private Map<String, Object> health() {
		final Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", host.ready());
		body.put("ready", host.ready());
		body.put("loading", !host.ready());
		body.put("dimensions", host.dimensionCount());
		body.put("executed", service.executed());
		body.put("failed", service.failed());
		body.put("deferred", service.deferred());
		body.put("autoReleased", service.autoReleased());
		body.put("tickFailures", service.tickFailures());
		body.put("lastTickError", service.lastTickError());
		body.put("routeDiag", service.routeDiagnostics());
		body.put("lastPersist", service.lastPersist());
		body.put("persistFailures", service.persistFailures());
		body.put("events", service.events());
		body.put("pathRecoveryNeeded", service.pathRecoveryNeeded());
		body.put("lastRecovery", service.lastRecovery());
		body.put("recovering", service.recovering());
		body.put("bootId", service.bootId());
		body.put("sessionStartedAt", service.startedAt());
		// What the process actually uses, next to what the file says - the BOM incident happened
		// because those two could silently disagree.
		body.put("settings", settings.get());
		body.put("pendingIntents", service.pendingIntents());
		body.put("intents", service.intents());
		body.put("holds", service.holds(host.simulator(0)));
		body.put("lastError", service.lastError());
		return body;
	}

	private void handleState(HttpExchange exchange) throws IOException {
		final int dimension = queryInt(exchange, "dimension", 0);
		final Simulator simulator = host.simulator(dimension);
		if (simulator == null) {
			respond(exchange, 503, loading("dimension " + dimension + " is not loaded"));
			return;
		}
		try {
			final BridgeService.Payload payload = host.onServer(() -> service.telemetry(simulator))
				.get(RESPONSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
			final String etag = "\"v" + payload.version() + "\"";
			final String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
			final long since = queryInt(exchange, "since", 0);
			if ((since > 0 && payload.version() <= since) || etag.equals(ifNoneMatch)) {
				exchange.getResponseHeaders().set("ETag", etag);
				exchange.sendResponseHeaders(304, -1);
				return;
			}
			exchange.getResponseHeaders().set("ETag", etag);
			respondText(exchange, 200, payload.body());
		} catch (Exception exception) {
			respond(exchange, 503, loading("server did not answer in time"));
		}
	}

	private void handleCommand(HttpExchange exchange) throws IOException {
		final Map<String, Object> body = Json.object(Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
		final BridgeCommand.Kind kind = BridgeCommand.Kind.fromWire(Json.string(body, "kind", ""));
		if (kind == null) {
			respond(exchange, 400, error("bad_request", "unknown kind"));
			return;
		}
		final String trainId = Json.string(body, "vehicleId", Json.string(body, "trainId", ""));
		final Map<String, Object> args = Json.object(body.get("args"));
		final String target = switch (kind) {
			case SET_PLATFORM -> Json.string(args, "platformId", "");
			case DEPLOY -> Json.string(args, "platformId", "");
			case RETURN_TO_DEPOT -> Json.string(args, "sidingId", "");
			case DWELL -> "";
		};
		final int seconds = (int) Json.number(args, "seconds", 0);
		final int ttlSeconds = (int) Json.number(body, "ttlSeconds", (int) Json.number(args, "ttlSeconds", -1));
		final BridgeCommand command = new BridgeCommand(kind, trainId, target, seconds, Json.string(body, "key", ""), ttlSeconds);

		final int dimension = queryInt(exchange, "dimension", 0);
		final Simulator simulator = host.simulator(dimension);
		if (simulator == null) {
			respond(exchange, 503, loading("dimension " + dimension + " is not loaded"));
			return;
		}

		final CompletableFuture<BridgeResult> future = host.onServer(() -> service.execute(simulator, command, BridgeService.Source.HTTP));
		try {
			final BridgeResult result = future.get(RESPONSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
			final Map<String, Object> response = result.toJson();
			response.put("ok", result.success());
			response.put("applied", result.outcome() == BridgeResult.Outcome.APPLIED);
			response.put("retryable", result.outcome() == BridgeResult.Outcome.UNAVAILABLE);
			response.put("detail", result.message());
			respond(exchange, 200, response);
		} catch (Exception exception) {
			respond(exchange, 202, Map.of("ok", true, "applied", false, "retryable", true, "detail", "queued; server busy"));
		}
	}

	private static Map<String, Object> loading(String message) {
		final Map<String, Object> body = error("loading", message);
		body.put("loading", true);
		return body;
	}

	private static int queryInt(HttpExchange exchange, String name, int fallback) {
		final String query = exchange.getRequestURI().getQuery();
		if (query == null) {
			return fallback;
		}
		for (final String pair : query.split("&")) {
			final String[] parts = pair.split("=", 2);
			if (parts.length == 2 && parts[0].equals(name)) {
				try {
					return Integer.parseInt(parts[1]);
				} catch (NumberFormatException ignored) {
					return fallback;
				}
			}
		}
		return fallback;
	}

	private static Map<String, Object> error(String code, String message) {
		final Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", false);
		body.put("error", code);
		body.put("message", message);
		return body;
	}

	private static void respond(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
		respondText(exchange, status, Json.write(body));
	}

	private static void respondText(HttpExchange exchange, int status, String body) throws IOException {
		final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, payload.length);
		try (OutputStream output = exchange.getResponseBody()) {
			output.write(payload);
		}
	}
}
