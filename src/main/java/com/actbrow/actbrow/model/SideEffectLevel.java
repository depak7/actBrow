package com.actbrow.actbrow.model;

/**
 * How consequential a tool's action is. Drives policy: writes/destructive actions require stricter
 * validation and post-action verification than reads (Phase 6).
 */
public enum SideEffectLevel {
	/** Only reads data; safe to retry freely. */
	READ,
	/** Creates or mutates state. */
	WRITE,
	/** Irreversible or high-blast-radius change (delete, payment, etc.). */
	DESTRUCTIVE;

	public static SideEffectLevel fromMetadata(Object value) {
		if (value == null) {
			return READ;
		}
		try {
			return valueOf(value.toString().trim().toUpperCase(java.util.Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			return READ;
		}
	}
}
