package tech.nomad4.payments4j.paddle;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.nomad4.payments4j.CheckoutRequest;
import tech.nomad4.payments4j.CheckoutSession;
import tech.nomad4.payments4j.PaymentInfo;
import tech.nomad4.payments4j.PaymentProvider;
import tech.nomad4.payments4j.PaymentProviderException;
import tech.nomad4.payments4j.PaymentStatus;
import tech.nomad4.payments4j.WebhookEvent;
import tech.nomad4.payments4j.WebhookParseException;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tech.nomad4.payments4j.paddle.PaddleTestSupport.*;

class PaddleProviderTest {

    private MockWebServer server;
    private PaymentProvider provider;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
        provider = PaddleProviders.create(config().withBaseUrl(server.url("/").toString().replaceAll("/$", "")),
                new OkHttpClient(), CLOCK);
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private static CheckoutRequest request(String productRef) {
        return new CheckoutRequest("order-1", "Pro", productRef, new BigDecimal("20"), "USD", null, null, null);
    }

    // ---- checkout ----

    @Test
    void checkoutUsesTheRequestedPriceAndTagsTheOrder() throws Exception {
        server.enqueue(new MockResponse().setBody("""
                {"data":{"id":"txn_1","currency_code":"USD","checkout":{"url":"https://pay.example/?_ptxn=txn_1"},
                 "details":{"totals":{"total":"999"}}}}"""));

        CheckoutSession session = provider.createCheckout(request("pri_pro"));

        RecordedRequest sent = server.takeRequest();
        assertThat(sent.getPath()).isEqualTo("/transactions");
        assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer pdl_sdbx_apikey_test");
        assertThat(sent.getBody().readUtf8()).contains("\"price_id\":\"pri_pro\"").contains("\"order_id\":\"order-1\"");
        assertThat(session.providerRef()).isEqualTo("txn_1");
        assertThat(session.redirectUrl()).isEqualTo("https://pay.example/?_ptxn=txn_1");
        assertThat(session.amount()).isEqualByComparingTo("9.99");
        assertThat(session.currency()).isEqualTo("USD");
    }

    @Test
    void checkoutFallsBackToTheDefaultPrice() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "{\"data\":{\"id\":\"txn_1\",\"checkout\":{\"url\":\"https://pay.example/\"}}}"));

        CheckoutSession session = provider.createCheckout(request(null));

        assertThat(server.takeRequest().getBody().readUtf8()).contains("\"price_id\":\"pri_default\"");
        assertThat(session.amount()).isNull();
    }

    @Test
    void checkoutWithoutAnyPriceIsRefusedBeforeCallingPaddle() {
        PaymentProvider noDefault = PaddleProviders.create(config().withDefaultPriceId(null), new OkHttpClient(), CLOCK);

        assertThatThrownBy(() -> noDefault.createCheckout(request(null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("price id");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void checkoutErrorsBecomeProviderExceptions() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"bad\"}"));
        assertThatThrownBy(() -> provider.createCheckout(request("pri_pro")))
                .isInstanceOf(PaymentProviderException.class).hasMessageContaining("HTTP 400");

        server.enqueue(new MockResponse().setBody("{\"data\":{}}"));
        assertThatThrownBy(() -> provider.createCheckout(request("pri_pro")))
                .isInstanceOf(PaymentProviderException.class).hasMessageContaining("no id/checkout.url");
    }

    // ---- webhooks ----

    @Test
    void completedTransactionCarriesSubscriptionAmountAndPeriodEnd() {
        WebhookEvent event = provider.parseWebhook(signed("""
                {"event_id":"evt_1","event_type":"transaction.completed","occurred_at":"2026-10-11T02:30:00.000000Z",
                 "data":{"id":"txn_1","status":"completed","subscription_id":"sub_1","currency_code":"USD",
                  "custom_data":{"order_id":"order-1"},"details":{"totals":{"total":"999"}},
                  "billing_period":{"starts_at":"2026-10-11T02:30:00Z","ends_at":"2026-11-11T02:30:00Z"}}}"""));

        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-11T02:30:00Z"));
        assertThat(event.payment()).isEqualTo(new PaymentInfo(PaymentStatus.PAID, "txn_1", "order-1", "sub_1",
                new BigDecimal("9.99"), "USD", Instant.parse("2026-11-11T02:30:00Z")));
    }

    @Test
    void renewalWithoutOurCustomDataStillTiesToTheSubscription() {
        WebhookEvent event = provider.parseWebhook(signed("""
                {"event_id":"evt_2","event_type":"transaction.completed","occurred_at":"2026-11-11T02:30:00Z",
                 "data":{"id":"txn_2","status":"completed","subscription_id":"sub_1","currency_code":"USD",
                  "details":{"totals":{"total":"999"}},"billing_period":{"ends_at":"2026-12-11T02:30:00Z"}}}"""));

        assertThat(event.payment().orderId()).isNull();
        assertThat(event.payment().subscriptionId()).isEqualTo("sub_1");
        assertThat(event.payment().periodEnd()).isEqualTo(Instant.parse("2026-12-11T02:30:00Z"));
    }

    @Test
    void subscriptionEventTiesTheSubscriptionToTheOrderWithoutChangingStatus() {
        WebhookEvent event = provider.parseWebhook(signed("""
                {"event_id":"evt_3","event_type":"subscription.activated","occurred_at":"2026-10-11T02:31:00Z",
                 "data":{"id":"sub_1","status":"active","custom_data":{"order_id":"order-1"},
                  "current_billing_period":{"ends_at":"2026-11-11T02:30:00Z"}}}"""));

        assertThat(event.payment().status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(event.payment().orderId()).isEqualTo("order-1");
        assertThat(event.payment().subscriptionId()).isEqualTo("sub_1");
        assertThat(event.payment().periodEnd()).isEqualTo(Instant.parse("2026-11-11T02:30:00Z"));
    }

    @Test
    void approvedRefundPointsAtTheOriginalTransaction() {
        WebhookEvent event = provider.parseWebhook(signed("""
                {"event_id":"evt_4","event_type":"adjustment.updated","occurred_at":"2026-10-11T03:00:00Z",
                 "data":{"id":"adj_1","status":"approved","action":"refund","transaction_id":"txn_1"}}"""));

        assertThat(event.payment().status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(event.payment().providerRef()).isEqualTo("txn_1");
        assertThat(event.payment().orderId()).isNull();
    }

    @Test
    void pendingApprovalRefundDoesNotChangeAnything() {
        WebhookEvent event = provider.parseWebhook(signed("""
                {"event_id":"evt_5","event_type":"adjustment.created","occurred_at":"2026-10-11T03:00:00Z",
                 "data":{"id":"adj_1","status":"pending_approval","action":"refund","transaction_id":"txn_1"}}"""));

        assertThat(event.payment().status()).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    void webhookFromTheFutureOrPastBeyondToleranceIsInvalid() {
        PaymentProvider late = PaddleProviders.create(config(), new OkHttpClient(),
                java.time.Clock.fixed(Instant.ofEpochSecond(TS + 60), java.time.ZoneOffset.UTC));

        assertThat(late.parseWebhook(signed(fixture("transaction-completed.json"))).signatureValid()).isFalse();
    }

    @Test
    void webhookWithoutEventIdIsAParseError() {
        assertThatThrownBy(() -> provider.parseWebhook(signed("{\"event_type\":\"x\"}")))
                .isInstanceOf(WebhookParseException.class);
    }

    @Test
    void malformedTimestampIsAParseError() {
        assertThatThrownBy(() -> provider.parseWebhook(signed(
                "{\"event_id\":\"e\",\"event_type\":\"customer.created\",\"occurred_at\":\"yesterday\",\"data\":{}}")))
                .isInstanceOf(WebhookParseException.class);
    }

    // ---- status mapping and money ----

    @Test
    void mapsEventsToStatuses() {
        assertThat(PaddleProvider.mapStatus("transaction.completed", "completed", null)).isEqualTo(PaymentStatus.PAID);
        assertThat(PaddleProvider.mapStatus("transaction.paid", "paid", null)).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(PaddleProvider.mapStatus("transaction.payment_failed", "ready", null)).isEqualTo(PaymentStatus.FAILED);
        assertThat(PaddleProvider.mapStatus("transaction.updated", "past_due", null)).isEqualTo(PaymentStatus.FAILED);
        assertThat(PaddleProvider.mapStatus("transaction.updated", "canceled", null)).isEqualTo(PaymentStatus.CANCELED);
        assertThat(PaddleProvider.mapStatus("subscription.activated", "active", null)).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(PaddleProvider.mapStatus("adjustment.updated", "approved", "chargeback")).isEqualTo(PaymentStatus.CHARGEBACK);
    }

    @Test
    void amountUsesTheCurrencyDecimals() {
        assertThat(PaddleProvider.toAmount("999", "USD")).isEqualByComparingTo("9.99");
        assertThat(PaddleProvider.toAmount("1500", "JPY")).isEqualByComparingTo("1500");
        assertThat(PaddleProvider.toAmount("999", "XXX-NOPE")).isNull();
        assertThat(PaddleProvider.toAmount(null, "USD")).isNull();
    }

    // ---- reconciliation ----

    private static String txn(String status) {
        return "{\"data\":{\"id\":\"txn_1\",\"status\":\"" + status + "\",\"subscription_id\":\"sub_1\","
                + "\"custom_data\":{\"order_id\":\"o-1\"}}}";
    }

    @Test
    void completedTransactionIsPaid() throws Exception {
        server.enqueue(new MockResponse().setBody(txn("completed")));
        server.enqueue(new MockResponse().setBody("{\"data\":[]}"));

        PaymentInfo info = provider.fetchPayment("txn_1").orElseThrow();

        assertThat(info.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(info.orderId()).isEqualTo("o-1");
        assertThat(info.subscriptionId()).isEqualTo("sub_1");
        assertThat(server.takeRequest().getPath()).isEqualTo("/transactions/txn_1");
        assertThat(server.takeRequest().getPath()).isEqualTo("/adjustments?transaction_id=txn_1&status=approved");
    }

    @Test
    void approvedRefundTurnsPaidIntoRefunded() {
        server.enqueue(new MockResponse().setBody(txn("completed")));
        server.enqueue(new MockResponse().setBody("{\"data\":[{\"id\":\"adj_1\",\"action\":\"refund\",\"status\":\"approved\"}]}"));

        assertThat(provider.fetchPayment("txn_1").orElseThrow().status()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void chargebackWinsOverRefund() {
        server.enqueue(new MockResponse().setBody(txn("completed")));
        server.enqueue(new MockResponse().setBody("{\"data\":[{\"action\":\"chargeback\"},{\"action\":\"refund\"}]}"));

        assertThat(provider.fetchPayment("txn_1").orElseThrow().status()).isEqualTo(PaymentStatus.CHARGEBACK);
    }

    @Test
    void readyTransactionIsPendingAndNeedsNoAdjustmentLookup() {
        server.enqueue(new MockResponse().setBody(txn("ready")));

        assertThat(provider.fetchPayment("txn_1").orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void foreignReferenceIsSkippedWithoutACall() {
        assertThat(provider.fetchPayment("5271104369")).isEmpty();
        assertThat(provider.fetchPayment(null)).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void serverErrorOnLookupIsReported() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{}"));

        assertThatThrownBy(() -> provider.fetchPayment("txn_1")).isInstanceOf(PaymentProviderException.class);
    }
}
