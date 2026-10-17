package tech.nomad4.payments4j.nowpayments;

import okhttp3.OkHttpClient;
import tech.nomad4.payments4j.PaymentProvider;

import java.util.concurrent.TimeUnit;

/** The only public entry point of the module: the provider itself is package-private. */
public final class NowPaymentsProviders {

    private NowPaymentsProviders() {
    }

    /** Uses a client with 30 second timeouts. */
    public static PaymentProvider create(NowPaymentsConfig config) {
        return create(config, new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build());
    }

    /** @param http client shared by the application (timeouts, proxy) */
    public static PaymentProvider create(NowPaymentsConfig config, OkHttpClient http) {
        return new NowPaymentsProvider(config, http);
    }
}
