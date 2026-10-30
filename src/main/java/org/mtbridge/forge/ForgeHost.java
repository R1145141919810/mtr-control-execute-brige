package org.mtbridge.forge;

import net.minecraft.server.MinecraftServer;
import org.mtr.core.simulation.Simulator;
import org.mtbridge.core.BridgeHost;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Locates the live simulators without linking against the host mod's internal classes.
 *
 * <p>The lookup ladder is deliberate: an accessor first, then a public field, then the declared
 * field, and every step reports which one worked. If the host mod reorganises its bootstrap the
 * bridge degrades to "unavailable" with a readable message instead of failing to load or, worse,
 * silently operating on a second simulator.</p>
 */
public final class ForgeHost implements BridgeHost {

	private static final String HOST_INIT = "org.mtr.mod.Init";
	private static final String MAIN_CLASS = "org.mtr.core.Main";

	private volatile MinecraftServer server;
	private volatile List<Simulator> simulators = List.of();
	private volatile String locator = "none";
	private volatile String lastError = "";

	private volatile int locateAttempts;

	/**
	 * Retries the lookup while the host is still initialising.
	 *
	 * <p>Server-started listeners have no ordering guarantee: the host mod creates its simulator
	 * container inside its own listener, so a bridge registered earlier observes {@code null}
	 * once and, without this retry, would stay dead for the whole session.</p>
	 */
	public boolean tryAttach(MinecraftServer minecraftServer) {
		if (ready() || minecraftServer == null) {
			return ready();
		}
		this.server = minecraftServer;
		this.simulators = locate();
		locateAttempts++;
		return ready();
	}

	public int locateAttempts() {
		return locateAttempts;
	}

	public java.util.List<String> dimensionIds() {
		final java.util.List<String> ids = new java.util.ArrayList<>();
		for (final Simulator simulator : simulators) {
			ids.add(simulator.dimension);
		}
		return ids;
	}

	public void attach(MinecraftServer minecraftServer) {
		this.server = minecraftServer;
		this.simulators = locate();
	}

	public void detach() {
		this.server = null;
		this.simulators = List.of();
	}

	public String locator() {
		return locator;
	}

	@Override
	public boolean ready() {
		return server != null && !simulators.isEmpty();
	}

	@Override
	public Simulator simulator(int dimension) {
		final List<Simulator> current = simulators;
		if (dimension < 0 || dimension >= current.size()) {
			return null;
		}
		return current.get(dimension);
	}

	@Override
	public int dimensionCount() {
		return simulators.size();
	}

	@Override
	public <T> CompletableFuture<T> onServer(Supplier<T> task) {
		final CompletableFuture<T> future = new CompletableFuture<>();
		final MinecraftServer current = server;
		if (current == null) {
			future.completeExceptionally(new IllegalStateException("server not running"));
			return future;
		}
		current.execute(() -> {
			try {
				future.complete(task.get());
			} catch (Throwable throwable) {
				future.completeExceptionally(throwable);
			}
		});
		return future;
	}

	@Override
	public void error(String message) {
		lastError = message;
	}

	public String lastError() {
		return lastError;
	}

	private List<Simulator> locate() {
		try {
			final Class<?> init = Class.forName(HOST_INIT);
			Object main = null;
			for (final Field field : init.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()) && field.getType().getName().equals(MAIN_CLASS)) {
					field.setAccessible(true);
					main = field.get(null);
					locator = "field:" + HOST_INIT + "#" + field.getName();
					break;
				}
			}
			if (main == null) {
				for (final Method method : init.getDeclaredMethods()) {
					if (Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0
						&& method.getReturnType().getName().equals(MAIN_CLASS)) {
						method.setAccessible(true);
						main = method.invoke(null);
						locator = "method:" + HOST_INIT + "#" + method.getName();
						break;
					}
				}
			}
			if (main == null) {
				locator = "none";
				return List.of();
			}
			for (final Field field : main.getClass().getDeclaredFields()) {
				if (!Iterable.class.isAssignableFrom(field.getType())) {
					continue;
				}
				field.setAccessible(true);
				final Object value = field.get(main);
				if (value instanceof Iterable<?> iterable) {
					final List<Simulator> found = new ArrayList<>();
					for (final Object element : iterable) {
						if (element instanceof Simulator simulator) {
							found.add(simulator);
						}
					}
					if (!found.isEmpty()) {
						locator = locator + " -> " + field.getName() + "[" + found.size() + "]";
						return List.copyOf(found);
					}
				}
			}
			locator = "none";
			return List.of();
		} catch (Throwable throwable) {
			locator = "failed:" + throwable.getClass().getSimpleName();
			lastError = throwable.getMessage();
			return List.of();
		}
	}
}
