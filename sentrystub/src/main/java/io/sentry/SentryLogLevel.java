package io.sentry;

/**
 * Stub mirror of io.sentry.SentryLogLevel (real Sentry 8.x).
 * Keeps the app compile- and runnable when the sentrystub module is used
 * instead of the real Sentry SDK (keystore-defined builds).
 */
public enum SentryLogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    FATAL
}
