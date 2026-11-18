package com.example.Project1.Model.Entity;

/**
 * Every state a WhatsApp conversation can be in.
 *
 * <p>The state is persisted by name in {@link WhatsAppSession#getCurrentState()}, so
 * multi-turn flows survive webhook calls, application restarts and multiple instances.
 * Using an enum (instead of raw strings) makes invalid transitions a compile-time error
 * and keeps every state in one place.</p>
 */
public enum SessionState {
    // ---- Patient intake flow ----
    INITIAL,
    AWAITING_PATIENT_INFO,
    COMPLETED,

    // ---- Doctor flow ----
    DOC_IDLE,

    // ---- Doctor confirming a date for a patient who started the chat ----
    DOC_AWAITING_PATIENT_DATE,

    // ---- Doctor update-appointment flow ----
    DOC_UPD_SEARCHING,
    DOC_UPD_SELECTING_PATIENT
}
