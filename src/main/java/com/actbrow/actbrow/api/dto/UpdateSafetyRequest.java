package com.actbrow.actbrow.api.dto;

/**
 * Partial update of an assistant's runtime safety controls. Boxed booleans on purpose: {@code null}
 * means "leave this control alone", which lets an operator flip one switch without having to restate
 * (and risk clobbering) the other during an incident.
 */
public record UpdateSafetyRequest(Boolean toolsEnabled, Boolean shadowMode) {
}
