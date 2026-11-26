package com.example.Project1.Service;

/**
 * Shared button-id prefixes for the local-development interactive reminder.
 *
 * Production templates use quick-reply buttons and are correlated through the
 * incoming webhook context id instead of these prefixes.
 */
public final class ReminderButtonIds {

    public static final String CONFIRM_PREFIX = "REMINDER_CONFIRM_";
    public static final String CANCEL_PREFIX = "REMINDER_CANCEL_";

    private ReminderButtonIds() {
    }
}
