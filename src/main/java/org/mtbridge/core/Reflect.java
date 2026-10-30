package org.mtbridge.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflective access to simulator fields that are not part of the public API surface.
 *
 * <p>The bridge deliberately avoids mixins: they bind to a mapping and a class layout and break
 * on every platform update. A cached {@link Field} costs one virtual call per instruction and
 * fails loudly with a readable message when a future version renames something.</p>
 */
public final class Reflect {

	private static final ConcurrentHashMap<String, Field> FIELDS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, Method> METHODS = new ConcurrentHashMap<>();

	private Reflect() {
	}

	public static Field field(Class<?> owner, String name) {
		return FIELDS.computeIfAbsent(owner.getName() + "#" + name, key -> {
			Class<?> current = owner;
			while (current != null) {
				try {
					final Field field = current.getDeclaredField(name);
					field.setAccessible(true);
					return field;
				} catch (NoSuchFieldException ignored) {
					current = current.getSuperclass();
				}
			}
			throw new IllegalStateException("no field '" + name + "' on " + owner.getName());
		});
	}

	public static Method method(Class<?> owner, String name, Class<?>... parameters) {
		return METHODS.computeIfAbsent(owner.getName() + "#" + name + "/" + parameters.length, key -> {
			Class<?> current = owner;
			while (current != null) {
				try {
					final Method method = current.getDeclaredMethod(name, parameters);
					method.setAccessible(true);
					return method;
				} catch (NoSuchMethodException ignored) {
					current = current.getSuperclass();
				}
			}
			throw new IllegalStateException("no method '" + name + "' on " + owner.getName());
		});
	}

	/**
	 * Reads a numeric field without assuming its declared type.
	 *
	 * <p>Vehicle state mixes {@code long} and {@code double}: {@code elapsedDwellTime} is a
	 * {@code long}, {@code speed} is a {@code double}. {@code Field.getDouble} on a long field
	 * throws, and on a long field it threw all the way through the server tick and took the
	 * server down. Everything numeric goes through here now.</p>
	 */
	public static double getNumber(Object target, String name) {
		final Field field = field(target.getClass(), name);
		try {
			final Class<?> type = field.getType();
			if (type == long.class) {
				return field.getLong(target);
			}
			if (type == int.class) {
				return field.getInt(target);
			}
			if (type == float.class) {
				return field.getFloat(target);
			}
			if (type == double.class) {
				return field.getDouble(target);
			}
			final Object value = field.get(target);
			return value instanceof Number number ? number.doubleValue() : 0.0;
		} catch (Exception exception) {
			throw new IllegalStateException("cannot read " + name + ": " + exception.getMessage(), exception);
		}
	}

	/** Writes a numeric field using its declared type. */
	public static void setNumber(Object target, String name, double value) {
		final Field field = field(target.getClass(), name);
		try {
			final Class<?> type = field.getType();
			if (type == long.class) {
				field.setLong(target, (long) value);
			} else if (type == int.class) {
				field.setInt(target, (int) value);
			} else if (type == float.class) {
				field.setFloat(target, (float) value);
			} else if (type == double.class) {
				field.setDouble(target, value);
			} else {
				field.set(target, (long) value);
			}
		} catch (Exception exception) {
			throw new IllegalStateException("cannot write " + name + ": " + exception.getMessage(), exception);
		}
	}

	public static double getDouble(Object target, String name) {
		try {
			return field(target.getClass(), name).getDouble(target);
		} catch (Exception exception) {
			throw new IllegalStateException("cannot read " + name + ": " + exception.getMessage(), exception);
		}
	}

	public static long getLong(Object target, String name) {
		try {
			return field(target.getClass(), name).getLong(target);
		} catch (Exception exception) {
			throw new IllegalStateException("cannot read " + name + ": " + exception.getMessage(), exception);
		}
	}

	public static void setDouble(Object target, String name, double value) {
		try {
			field(target.getClass(), name).setDouble(target, value);
		} catch (Exception exception) {
			throw new IllegalStateException("cannot write " + name + ": " + exception.getMessage(), exception);
		}
	}

	public static void setLong(Object target, String name, long value) {
		try {
			field(target.getClass(), name).setLong(target, value);
		} catch (Exception exception) {
			throw new IllegalStateException("cannot write " + name + ": " + exception.getMessage(), exception);
		}
	}

	public static Object get(Object target, String name) {
		try {
			return field(target.getClass(), name).get(target);
		} catch (Exception exception) {
			throw new IllegalStateException("cannot read " + name + ": " + exception.getMessage(), exception);
		}
	}

	/**
	 * Invokes a method by name and arity, coercing numeric arguments.
	 *
	 * <p>fastutil collections expose {@code getLong(int)} rather than {@code get(Integer)}, and
	 * the exact-type lookup used elsewhere would miss them. This variant tolerates primitive
	 * parameters and returns the raw result.</p>
	 */
	public static Object callAny(Object target, String name, Object... arguments) {
		Class<?> current = target.getClass();
		while (current != null) {
			for (final Method method : current.getDeclaredMethods()) {
				if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) {
					continue;
				}
				final Class<?>[] types = method.getParameterTypes();
				boolean compatible = true;
				for (int index = 0; index < types.length; index++) {
					compatible &= isCompatible(types[index], arguments[index]);
				}
				if (!compatible) {
					continue;
				}
				try {
					method.setAccessible(true);
					return method.invoke(target, arguments);
				} catch (Exception exception) {
					throw new IllegalStateException("cannot call " + name + ": " + exception.getMessage(), exception);
				}
			}
			current = current.getSuperclass();
		}
		throw new IllegalStateException("no method '" + name + "' with " + arguments.length + " args on " + target.getClass().getName());
	}

	private static boolean isCompatible(Class<?> type, Object argument) {
		if (argument == null) {
			return !type.isPrimitive();
		}
		if (type.isInstance(argument)) {
			return true;
		}
		return (type == long.class || type == int.class || type == double.class || type == float.class)
			&& argument instanceof Number;
	}

	public static Object call(Object target, String name, Object... arguments) {
		final Class<?>[] types = new Class<?>[arguments.length];
		for (int index = 0; index < arguments.length; index++) {
			types[index] = arguments[index] == null ? Object.class : arguments[index].getClass();
		}
		try {
			return method(target.getClass(), name, types).invoke(target, arguments);
		} catch (Exception exception) {
			throw new IllegalStateException("cannot call " + name + ": " + exception.getMessage(), exception);
		}
	}
}
