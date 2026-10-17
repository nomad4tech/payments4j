package tech.nomad4.payments4j.paddle;

import okhttp3.OkHttpClient;
import tech.nomad4.payments4j.PaymentProvider;

import java.time.Clock;
import java.util.concurrent.TimeUnit;

/** The only public entry point of the module: the provider itself is package-private. */
public final class PaddleProviders {

    private PaddleProviders() {
    }

    /** Uses a client with 30 second timeouts and the system clock. */
    public static PaymentProvider create(PaddleConfig config) {
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        return create(config, http, Clock.systemUTC());
    }

    /** @param http client shared by the application (timeouts, proxy); the library never creates one on its own here */
    public static PaymentProvider create(PaddleConfig config, OkHttpClient http, Clock clock) {
        return new PaddleProvider(config, http, clock);
    }
}
