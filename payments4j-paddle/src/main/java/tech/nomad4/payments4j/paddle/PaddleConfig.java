package tech.nomad4.payments4j.paddle;

import tech.nomad4.payments4j.ProviderEnvironment;

/**
 * Paddle Billing settings. Everything environment-dependent comes from here: nothing in the library assumes sandbox.
 *
 * <p>The constructor refuses a configuration that would silently misbehave: a blank API key, a blank webhook
 * secret (every webhook would be rejected) and an API key issued for the other environment.
 *
 * @param environment      sandbox or production; selects the API base URL
 * @param baseUrl          explicit API base URL overriding the environment's one (proxy, tests); null for the default
 * @param apiKey           server-side API key, sent as a Bearer token
 * @param webhookSecret    secret of the notification destination ({@code pdl_ntfset_...}); every destination has its own
 * @param defaultPriceId   price used by checkouts that carry no {@code productRef}; null if every checkout names its price
 * @param toleranceSeconds allowed difference between the signature timestamp and the clock (replay protection)
 */
public record PaddleConfig(
        ProviderEnvironment environment,
        String baseUrl,
        String apiKey,
        String webhookSecret,
        String defaultPriceId,
        long toleranceSeconds) {

    public static final String SANDBOX_BASE_URL = "https://sandbox-api.paddle.com";
    public static final String PRODUCTION_BASE_URL = "https://api.paddle.com";
    public static final long DEFAULT_TOLERANCE_SECONDS = 5;

    private static final String SANDBOX_KEY_PREFIX = "pdl_sdbx_";
    private static final String LIVE_KEY_PREFIX = "pdl_live_";

    public PaddleConfig {
        if (environment == null) {
            throw new IllegalArgumentException("Paddle environment is required");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Paddle API key is blank");
        }
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalArgumentException("Paddle webhook secret is blank: every webhook would be rejected");
        }
        if (environment == ProviderEnvironment.PRODUCTION && apiKey.startsWith(SANDBOX_KEY_PREFIX)) {
            throw new IllegalArgumentException("Environment is production but the Paddle API key is a sandbox key");
        }
        if (environment == ProviderEnvironment.SANDBOX && apiKey.startsWith(LIVE_KEY_PREFIX)) {
            throw new IllegalArgumentException("Environment is sandbox but the Paddle API key is a live key");
        }
        if (toleranceSeconds < 0) {
            throw new IllegalArgumentException("Paddle webhook tolerance must not be negative");
        }
        baseUrl = baseUrl == null || baseUrl.isBlank()
                ? (environment == ProviderEnvironment.PRODUCTION ? PRODUCTION_BASE_URL : SANDBOX_BASE_URL)
                : baseUrl;
        defaultPriceId = defaultPriceId == null || defaultPriceId.isBlank() ? null : defaultPriceId;
    }

    public static PaddleConfig of(ProviderEnvironment environment, String apiKey, String webhookSecret) {
        return new PaddleConfig(environment, null, apiKey, webhookSecret, null, DEFAULT_TOLERANCE_SECONDS);
    }

    public PaddleConfig withBaseUrl(String baseUrl) {
        return new PaddleConfig(environment, baseUrl, apiKey, webhookSecret, defaultPriceId, toleranceSeconds);
    }

    public PaddleConfig withDefaultPriceId(String defaultPriceId) {
        return new PaddleConfig(environment, baseUrl, apiKey, webhookSecret, defaultPriceId, toleranceSeconds);
    }

    public PaddleConfig withToleranceSeconds(long toleranceSeconds) {
        return new PaddleConfig(environment, baseUrl, apiKey, webhookSecret, defaultPriceId, toleranceSeconds);
    }

    /** Secrets are masked: a logged config must not leak keys. */
    @Override
    public String toString() {
        return "PaddleConfig[environment=" + environment + ", baseUrl=" + baseUrl + ", apiKey=***, webhookSecret=***, "
                + "defaultPriceId=" + defaultPriceId + ", toleranceSeconds=" + toleranceSeconds + "]";
    }
}
