package tech.nomad4.payments4j.nowpayments;

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

import static tech.nomad4.payments4j.nowpayments.NowPaymentsTestSupport.*;

class NowPaymentsContractTest extends PaymentProviderContract {

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
        provider = NowPaymentsProviders.create(config().withBaseUrl(server.url("/").toString().replaceAll("/$", "")),
                new OkHttpClient());
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
        return signed(fixture());
    }

    @Override
    protected WebhookRequest forgedPaidWebhook() {
        return new WebhookRequest(fixture(), Map.of("x-nowpayments-sig", "0".repeat(128)));
    }

    /** NOWPayments sends only payment IPNs; an IPN of a status we do not map is the closest "not about an order" case. */
    @Override
    protected WebhookRequest signedUnrelatedWebhook() {
        return signed("{\"payment_id\":1,\"payment_status\":\"some_future_status\",\"order_id\":\"o-1\"}");
    }

    @Override
    protected void stubUnknownReference() {
        // the dispatcher answers 404 to everything
    }

    @Override
    protected String unknownReference() {
        return "5271104369";
    }
}
