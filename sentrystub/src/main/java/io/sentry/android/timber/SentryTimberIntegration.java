package io.sentry.android.timber;

import io.sentry.Integration;
import io.sentry.SentryLevel;
import io.sentry.SentryLogLevel;

public class SentryTimberIntegration implements Integration {
    public SentryTimberIntegration(SentryLevel minEventLevel, SentryLevel minBreadcrumbLevel) {
        // Stub
    }

    public SentryTimberIntegration(SentryLevel minEventLevel, SentryLevel minBreadcrumbLevel,
                                   SentryLogLevel minLogsLevel) {
        // Stub (matches real Sentry 8.x constructor)
    }
}
