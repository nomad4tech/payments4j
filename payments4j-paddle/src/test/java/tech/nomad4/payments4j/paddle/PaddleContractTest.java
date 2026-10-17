package tech.nomad4.payments4j.paddle;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import tech.nomad4.payments4j.PaymentProvider;
import tech.nomad4.payments4j.WebhookRequest;
import tech.nomad4.payments4j.testkit.PaymentProviderContract;

import java.io.IOException;
import java.util.Map;

import static tech.nomad4.payments4j.paddle.PaddleTestSupport.*;

class PaddleContractTest extends PaymentProviderContract {

    private MockWebServer server;
    private PaymentProvider provider;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(404).setBody("{}");
            }
        });
        server.start();
        provider = PaddleProviders.create(config().withBaseUrl(server.url("/").toString().replaceAll("/$", "")),
                new OkHttpClient(), CLOCK);
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    @Override
    protected PaymentProvider provider() {
        return provider;
    }

    @Override
    protected WebhookRequest signedPaidWebhook() {
        return signed(fixture("transaction-completed.json"));
    }

    @Override
    protected WebhookRequest forgedPaidWebhook() {
        return new WebhookRequest(fixture("transaction-completed.json"),
                Map.of("paddle-signature", "ts=" + TS + ";h1=" + "0".repeat(64)));
    }

    @Override
    protected WebhookRequest signedUnrelatedWebhook() {
        return signed("{\"event_id\":\"evt_c\",\"event_type\":\"customer.created\",\"occurred_at\":"
                + "\"2026-10-11T02:30:00.000000Z\",\"data\":{\"id\":\"ctm_1\",\"email\":\"a@example.com\"}}");
    }

    @Override
    protected void stubUnknownReference() {
        // the dispatcher answers 404 to everything
    }

    @Override
    protected String unknownReference() {
        return "txn_missing";
    }
}
