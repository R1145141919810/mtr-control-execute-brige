package org.mtbridge.api;

/** Delivered to registered listeners after every instruction, whatever its outcome. */
public record BridgeEvent(Source source, String kind, String trainId, String target, BridgeResult result) {

	public enum Source {
		/** External dispatch service over HTTP. */
		HTTP,
		/** In-game {@code /mtbridge} command. */
		COMMAND,
		/** Another mod through the Java API. */
		API
	}
}
