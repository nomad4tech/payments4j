package tech.nomad4.payments4j.nowpayments;

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
import static tech.nomad4.payments4j.nowpayments.NowPaymentsTestSupport.*;

class NowPaymentsProviderTest {

    private static final String INVOICE = "{\"id\":\"inv-1\",\"invoice_url\":\"https://pay.example/inv-1\"}";

    private MockWebServer server;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private PaymentProvider provider(String payCurrency) {
        return NowPaymentsProviders.create(
                config().withBaseUrl(server.url("/").toString().replaceAll("/$", "")).withPayCurrency(payCurrency),
                new OkHttpClient());
    }

    private static CheckoutRequest request() {
        return new CheckoutRequest("o-1", "Demo", null, new BigDecimal("20"), "USD", "https://app/ipn", null, null);
    }

    // ---- checkout ----

    @Test
    void invoiceCarriesPriceOrderAndCallback() throws Exception {
        server.enqueue(new MockResponse().setBody(INVOICE));

        CheckoutSession session = provider(null).createCheckout(request());

        RecordedRequest sent = server.takeRequest();
        assertThat(sent.getPath()).isEqualTo("/invoice");
        assertThat(sent.getHeader("x-api-key")).isEqualTo("api-key");
        assertThat(sent.getBody().readUtf8()).contains("\"price_amount\":20", "\"price_currency\":\"usd\"",
                "\"order_id\":\"o-1\"", "\"ipn_callback_url\":\"https://app/ipn\"");
        assertThat(session.providerRef()).isEqualTo("inv-1");
        assertThat(session.redirectUrl()).isEqualTo("https://pay.example/inv-1");
        assertThat(session.amount()).isNull();
    }

    @Test
    void paymentIdBecomesTheReferenceWhenPayCurrencyIsSet() throws Exception {
        server.enqueue(new MockResponse().setBody(INVOICE));
        server.enqueue(new MockResponse().setBody("{\"payment_id\":\"555\"}"));

        CheckoutSession session = provider("usdttrc20").createCheckout(request());

        assertThat(session.providerRef()).isEqualTo("555");
        server.takeRequest();
        assertThat(server.takeRequest().getBody().readUtf8()).contains("\"iid\":\"inv-1\"", "\"pay_currency\":\"usdttrc20\"");
    }

    @Test
    void failedPaymentCreationIsReportedNotHidden() {
        server.enqueue(new MockResponse().setBody(INVOICE));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{}"));

        assertThatThrownBy(() -> provider("usdttrc20").createCheckout(request()))
                .isInstanceOf(PaymentProviderException.class).hasMessageContaining("payment creation failed");
    }

    @Test
    void invoiceFailureIsReported() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"message\":\"bad\"}"));

        assertThatThrownBy(() -> provider(null).createCheckout(request()))
                .isInstanceOf(PaymentProviderException.class).hasMessageContaining("HTTP 400");
    }

    @Test
    void checkoutWithoutAmountIsRefusedBeforeCallingTheApi() {
        CheckoutRequest noAmount = new CheckoutRequest("o-1", null, "pri_x", null, null, null, null, null);

        assertThatThrownBy(() -> provider(null).createCheckout(noAmount)).isInstanceOf(IllegalArgumentException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    // ---- webhooks ----

    @Test
    void finishedIpnIsPaidWithFiatPriceAndTime() {
        WebhookEvent event = provider(null).parseWebhook(signed(fixture()));

        assertThat(event.eventId()).isEqualTo("5077125051:finished");
        assertThat(event.type()).isEqualTo("payment.finished");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-11T02:12:01.100Z"));
        assertThat(event.payment()).isEqualTo(new PaymentInfo(PaymentStatus.PAID, "5077125051", "order-ü-1", null,
                new BigDecimal("20.0"), "USD", null));
    }

    @Test
    void ipnTimestampInEpochMillisIsRead() {
        WebhookEvent event = provider(null).parseWebhook(signed(
                "{\"payment_id\":1,\"payment_status\":\"waiting\",\"updated_at\":1791684721100}"));

        assertThat(event.occurredAt()).isEqualTo(Instant.ofEpochMilli(1791684721100L));
    }

    @Test
    void ipnWithMalformedTimestampIsAParseError() {
        assertThatThrownBy(() -> provider(null).parseWebhook(signed(
                "{\"payment_id\":1,\"payment_status\":\"waiting\",\"updated_at\":\"yesterday\"}")))
                .isInstanceOf(WebhookParseException.class);
    }

    @Test
    void ipnWithoutPaymentIdIsAParseError() {
        assertThatThrownBy(() -> provider(null).parseWebhook(signed("{\"payment_status\":\"finished\"}")))
                .isInstanceOf(WebhookParseException.class);
    }

    @Test
    void mapsStatuses() {
        assertThat(NowPaymentsProvider.mapStatus("waiting")).isEqualTo(PaymentStatus.PENDING);
        assertThat(NowPaymentsProvider.mapStatus("confirming")).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(NowPaymentsProvider.mapStatus("partially_paid")).isEqualTo(PaymentStatus.PARTIALLY_PAID);
        assertThat(NowPaymentsProvider.mapStatus("failed")).isEqualTo(PaymentStatus.FAILED);
        assertThat(NowPaymentsProvider.mapStatus("refunded")).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(NowPaymentsProvider.mapStatus("expired")).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(NowPaymentsProvider.mapStatus("whatever")).isEqualTo(PaymentStatus.UNKNOWN);
    }

    // ---- reconciliation ----

    @Test
    void finishedPaymentIsPaid() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "{\"payment_id\":777,\"payment_status\":\"finished\",\"order_id\":\"o-1\",\"price_amount\":20,\"price_currency\":\"usd\"}"));

        PaymentInfo info = provider(null).fetchPayment("777").orElseThrow();

        assertThat(server.takeRequest().getPath()).isEqualTo("/payment/777");
        assertThat(info.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(info.orderId()).isEqualTo("o-1");
        assertThat(info.providerRef()).isEqualTo("777");
        assertThat(info.amount()).isEqualByComparingTo("20");
        assertThat(info.currency()).isEqualTo("USD");
    }

    @Test
    void partiallyPaidAndFailedAreMapped() {
        server.enqueue(new MockResponse().setBody("{\"payment_id\":1,\"payment_status\":\"partially_paid\"}"));
        server.enqueue(new MockResponse().setBody("{\"payment_id\":2,\"payment_status\":\"failed\"}"));

        assertThat(provider(null).fetchPayment("1").orElseThrow().status()).isEqualTo(PaymentStatus.PARTIALLY_PAID);
        assertThat(provider(null).fetchPayment("2").orElseThrow().status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void referenceThatIsNotAPaymentIdIsSkippedWithoutACall() {
        assertThat(provider(null).fetchPayment("not-a-number")).isEmpty();
        assertThat(provider(null).fetchPayment(null)).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void serverErrorOnLookupIsReported() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{}"));

        assertThatThrownBy(() -> provider(null).fetchPayment("9")).isInstanceOf(PaymentProviderException.class);
    }
}
