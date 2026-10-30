package org.mtbridge.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON codec: the bridge ships no third-party libraries and must stay loadable on a server. */
public final class Json {

	private Json() {
	}

	public static Object parse(String text) {
		final Reader reader = new Reader(text == null ? "" : text);
		reader.skip();
		final Object value = reader.value();
		reader.skip();
		if (!reader.done()) {
			throw new IllegalArgumentException("trailing content at " + reader.position);
		}
		return value;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> object(Object value) {
		return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
	}

	@SuppressWarnings("unchecked")
	public static List<Object> array(Object value) {
		return value instanceof List ? (List<Object>) value : new ArrayList<>();
	}

	public static String string(Map<String, Object> map, String key, String fallback) {
		final Object value = map.get(key);
		return value == null ? fallback : String.valueOf(value);
	}

	public static long number(Map<String, Object> map, String key, long fallback) {
		final Object value = map.get(key);
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value instanceof String text) {
			try {
				return Long.parseLong(text.trim());
			} catch (NumberFormatException ignored) {
				return fallback;
			}
		}
		return fallback;
	}

	public static String write(Object value) {
		final StringBuilder builder = new StringBuilder();
		write(builder, value);
		return builder.toString();
	}

	private static void write(StringBuilder builder, Object value) {
		if (value == null) {
			builder.append("null");
		} else if (value instanceof String text) {
			builder.append('"');
			for (int index = 0; index < text.length(); index++) {
				final char c = text.charAt(index);
				switch (c) {
					case '"' -> builder.append("\\\"");
					case '\\' -> builder.append("\\\\");
					case '\n' -> builder.append("\\n");
					case '\r' -> builder.append("\\r");
					case '\t' -> builder.append("\\t");
					default -> {
						if (c < 0x20) {
							builder.append(String.format("\\u%04x", (int) c));
						} else {
							builder.append(c);
						}
					}
				}
			}
			builder.append('"');
		} else if (value instanceof Map<?, ?> map) {
			builder.append('{');
			boolean first = true;
			for (final Map.Entry<?, ?> entry : map.entrySet()) {
				if (!first) {
					builder.append(',');
				}
				first = false;
				write(builder, String.valueOf(entry.getKey()));
				builder.append(':');
				write(builder, entry.getValue());
			}
			builder.append('}');
		} else if (value instanceof Iterable<?> iterable) {
			builder.append('[');
			boolean first = true;
			for (final Object element : iterable) {
				if (!first) {
					builder.append(',');
				}
				first = false;
				write(builder, element);
			}
			builder.append(']');
		} else if (value instanceof Number number) {
			if (number instanceof Double || number instanceof Float) {
				final double raw = number.doubleValue();
				builder.append(raw == Math.rint(raw) && Math.abs(raw) < 1e15 ? String.valueOf((long) raw) : String.valueOf(raw));
			} else {
				builder.append(number);
			}
		} else if (value instanceof Boolean) {
			builder.append(value);
		} else {
			write(builder, String.valueOf(value));
		}
	}

	private static final class Reader {
		private final String text;
		private int position;

		private Reader(String text) {
			this.text = text;
		}

		private boolean done() {
			return position >= text.length();
		}

		private void skip() {
			while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
				position++;
			}
		}

		private Object value() {
			skip();
			if (done()) {
				throw new IllegalArgumentException("unexpected end of json");
			}
			final char c = text.charAt(position);
			return switch (c) {
				case '{' -> object();
				case '[' -> array();
				case '"' -> string();
				case 't' -> literal("true", Boolean.TRUE);
				case 'f' -> literal("false", Boolean.FALSE);
				case 'n' -> literal("null", null);
				default -> number();
			};
		}

		private Object literal(String literal, Object value) {
			if (!text.startsWith(literal, position)) {
				throw new IllegalArgumentException("bad literal at " + position);
			}
			position += literal.length();
			return value;
		}

		private Map<String, Object> object() {
			position++;
			final Map<String, Object> result = new LinkedHashMap<>();
			skip();
			if (!done() && text.charAt(position) == '}') {
				position++;
				return result;
			}
			while (true) {
				skip();
				final String key = string();
				skip();
				if (text.charAt(position) != ':') {
					throw new IllegalArgumentException("expected ':' at " + position);
				}
				position++;
				result.put(key, value());
				skip();
				final char next = text.charAt(position++);
				if (next == '}') {
					return result;
				}
				if (next != ',') {
					throw new IllegalArgumentException("expected ',' at " + (position - 1));
				}
			}
		}

		private List<Object> array() {
			position++;
			final List<Object> result = new ArrayList<>();
			skip();
			if (!done() && text.charAt(position) == ']') {
				position++;
				return result;
			}
			while (true) {
				result.add(value());
				skip();
				final char next = text.charAt(position++);
				if (next == ']') {
					return result;
				}
				if (next != ',') {
					throw new IllegalArgumentException("expected ',' at " + (position - 1));
				}
			}
		}

		private String string() {
			if (text.charAt(position) != '"') {
				throw new IllegalArgumentException("expected string at " + position);
			}
			position++;
			final StringBuilder builder = new StringBuilder();
			while (true) {
				final char c = text.charAt(position++);
				if (c == '"') {
					return builder.toString();
				}
				if (c != '\\') {
					builder.append(c);
					continue;
				}
				final char escaped = text.charAt(position++);
				switch (escaped) {
					case 'n' -> builder.append('\n');
					case 't' -> builder.append('\t');
					case 'r' -> builder.append('\r');
					case 'b' -> builder.append('\b');
					case 'f' -> builder.append('\f');
					case 'u' -> {
						builder.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
						position += 4;
					}
					default -> builder.append(escaped);
				}
			}
		}

		private Object number() {
			final int start = position;
			while (!done() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) {
				position++;
			}
			final String raw = text.substring(start, position);
			if (raw.isEmpty()) {
				throw new IllegalArgumentException("unexpected character at " + start);
			}
			return raw.contains(".") || raw.contains("e") || raw.contains("E") ? (Object) Double.parseDouble(raw) : (Object) Long.parseLong(raw);
		}
	}
}
