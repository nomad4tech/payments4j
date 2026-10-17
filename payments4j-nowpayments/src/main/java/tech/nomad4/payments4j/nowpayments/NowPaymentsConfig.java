package tech.nomad4.payments4j.nowpayments;

import tech.nomad4.payments4j.ProviderEnvironment;

/**
 * NOWPayments settings. Everything environment-dependent comes from here: nothing in the library assumes sandbox.
 *
 * <p>The constructor refuses a blank API key and a blank IPN secret (every webhook would be rejected).
 *
 * @param environment sandbox or production; selects the API base URL
 * @param baseUrl     explicit API base URL overriding the environment's one (proxy, tests); null for the default
 * @param apiKey      sent in the {@code x-api-key} header
 * @param ipnSecret   secret for verifying IPN signatures
 * @param payCurrency coin of the payment created together with the invoice (for example {@code usdttrc20}). When
 *                    set, the payment id is known right after checkout, which reconciliation by API needs.
 *                    When null, the customer picks the coin on the invoice page and the payment id appears only
 *                    in the first IPN
 */
public record NowPaymentsConfig(
        ProviderEnvironment environment,
        String baseUrl,
        String apiKey,
        String ipnSecret,
        String payCurrency) {

    public static final String SANDBOX_BASE_URL = "https://api-sandbox.nowpayments.io/v1";
    public static final String PRODUCTION_BASE_URL = "https://api.nowpayments.io/v1";

    public NowPaymentsConfig {
        if (environment == null) {
            throw new IllegalArgumentException("NOWPayments environment is required");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("NOWPayments API key is blank");
        }
        if (ipnSecret == null || ipnSecret.isBlank()) {
            throw new IllegalArgumentException("NOWPayments IPN secret is blank: every webhook would be rejected");
        }
        baseUrl = baseUrl == null || baseUrl.isBlank()
                ? (environment == ProviderEnvironment.PRODUCTION ? PRODUCTION_BASE_URL : SANDBOX_BASE_URL)
                : baseUrl;
        payCurrency = payCurrency == null || payCurrency.isBlank() ? null : payCurrency;
    }

    public static NowPaymentsConfig of(ProviderEnvironment environment, String apiKey, String ipnSecret) {
        return new NowPaymentsConfig(environment, null, apiKey, ipnSecret, null);
    }

    public NowPaymentsConfig withBaseUrl(String baseUrl) {
        return new NowPaymentsConfig(environment, baseUrl, apiKey, ipnSecret, payCurrency);
    }

    public NowPaymentsConfig withPayCurrency(String payCurrency) {
        return new NowPaymentsConfig(environment, baseUrl, apiKey, ipnSecret, payCurrency);
    }

    /** Secrets are masked: a logged config must not leak keys. */
    @Override
    public String toString() {
        return "NowPaymentsConfig[environment=" + environment + ", baseUrl=" + baseUrl
                + ", apiKey=***, ipnSecret=***, payCurrency=" + payCurrency + "]";
    }
}
