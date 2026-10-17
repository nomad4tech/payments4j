package tech.nomad4.payments4j.paddle;

import tech.nomad4.payments4j.ProviderEnvironment;
import tech.nomad4.payments4j.WebhookRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

/** Shared fixtures: a fixed clock, a config and a way to sign bodies like Paddle does. */
final class PaddleTestSupport {

    static final String WEBHOOK_SECRET = "pdl_ntfset_test_secret";
    static final long TS = 1791686000L;
    static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(TS + 2), ZoneOffset.UTC);

    private PaddleTestSupport() {
    }

    static PaddleConfig config() {
        return PaddleConfig.of(ProviderEnvironment.SANDBOX, "pdl_sdbx_apikey_test", WEBHOOK_SECRET)
                .withDefaultPriceId("pri_default");
    }

    static byte[] fixture(String name) {
        try (var in = PaddleTestSupport.class.getResourceAsStream("/webhook/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static WebhookRequest signed(byte[] body) {
        return new WebhookRequest(body,
                Map.of("Paddle-Signature", "ts=" + TS + ";h1=" + PaddleSignature.sign(WEBHOOK_SECRET, TS, body)));
    }

    static WebhookRequest signed(String json) {
        return signed(json.getBytes(StandardCharsets.UTF_8));
    }
}
