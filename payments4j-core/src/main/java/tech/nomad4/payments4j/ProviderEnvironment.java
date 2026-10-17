package tech.nomad4.payments4j;

import java.util.Locale;

/** Provider environment. Comes from configuration only: application code must not hardcode sandbox. */
public enum ProviderEnvironment {
    SANDBOX,
    PRODUCTION;

    /**
     * @param value {@code sandbox} or {@code production}, case-insensitive
     * @throws IllegalArgumentException any other value, including null and blank
     */
    public static ProviderEnvironment parse(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "sandbox" -> SANDBOX;
            case "production" -> PRODUCTION;
            default -> throw new IllegalArgumentException(
                    "Unknown environment '" + value + "', expected sandbox or production");
        };
    }
}
