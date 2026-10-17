package tech.nomad4.payments4j.nowpayments;

import tech.nomad4.payments4j.ProviderEnvironment;
import tech.nomad4.payments4j.WebhookRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class NowPaymentsTestSupport {

    static final String IPN_SECRET = "test-ipn-secret";

    private NowPaymentsTestSupport() {
    }

    static NowPaymentsConfig config() {
        return NowPaymentsConfig.of(ProviderEnvironment.SANDBOX, "api-key", IPN_SECRET);
    }

    static byte[] fixture() {
        try (var in = NowPaymentsTestSupport.class.getResourceAsStream("/ipn/payment-finished.json")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static WebhookRequest signed(byte[] body) {
        return new WebhookRequest(body, Map.of("x-nowpayments-sig", IpnSignature.sign(IPN_SECRET, body)));
    }

    static WebhookRequest signed(String json) {
        return signed(json.getBytes(StandardCharsets.UTF_8));
    }
}
