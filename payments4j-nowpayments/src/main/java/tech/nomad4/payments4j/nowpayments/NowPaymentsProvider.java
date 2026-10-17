package tech.nomad4.payments4j.nowpayments;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import tech.nomad4.payments4j.CheckoutRequest;
import tech.nomad4.payments4j.CheckoutSession;
import tech.nomad4.payments4j.PaymentInfo;
import tech.nomad4.payments4j.PaymentProvider;
import tech.nomad4.payments4j.PaymentProviderException;
import tech.nomad4.payments4j.PaymentStatus;
import tech.nomad4.payments4j.WebhookEvent;
import tech.nomad4.payments4j.WebhookParseException;
import tech.nomad4.payments4j.WebhookRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;

class NowPaymentsProvider implements PaymentProvider {

    static final String ID = "nowpayments";
    static final String SIGNATURE_HEADER = "x-nowpayments-sig";

    private static final MediaType JSON = MediaType.get("application/json");
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();
    private static final int ERROR_BODY_LIMIT = 500;

    private final NowPaymentsConfig config;
    private final OkHttpClient http;

    NowPaymentsProvider(NowPaymentsConfig config, OkHttpClient http) {
        this.config = config;
        this.http = http;
    }

    @Override
    public String id() {
        return ID;
    }

    /**
     * Creates an invoice. When {@link NowPaymentsConfig#payCurrency()} is set, a payment is created for the invoice
     * right away so its id is known before the first IPN, and that id becomes the session reference. The request
     * must carry amount and currency: the price is set here, not in a catalog. {@code productRef} is not used.
     */
    @Override
    public CheckoutSession createCheckout(CheckoutRequest request) {
        if (request.amount() == null || request.currency() == null || request.currency().isBlank()) {
            throw new IllegalArgumentException("NOWPayments needs amount and currency in the checkout request");
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("price_amount", request.amount());
        body.put("price_currency", request.currency().toLowerCase(Locale.ROOT));
        body.put("order_id", request.orderId());
        putIfPresent(body, "order_description", request.description());
        putIfPresent(body, "ipn_callback_url", request.notifyUrl());
        putIfPresent(body, "success_url", request.successUrl());
        putIfPresent(body, "cancel_url", request.cancelUrl());

        JsonNode invoice = post("/invoice", body, "invoice creation");
        String id = text(invoice.path("id"));
        String url = text(invoice.path("invoice_url"));
        if (id == null || url == null) {
            throw new PaymentProviderException("NOWPayments invoice response has no id/invoice_url: "
                    + abbreviate(invoice.toString()));
        }
        String paymentId = config.payCurrency() == null
                ? null : createPaymentForInvoice(id, request.description());
        return new CheckoutSession(paymentId != null ? paymentId : id, url);
    }

    /**
     * {@code POST /invoice-payment}. The payment id is needed up front: listing payments by invoice requires a JWT
     * from an account login, and accounts created through Google have no password. A failure is reported to the
     * caller instead of degrading silently: without the id, reconciliation by API cannot work for this payment.
     * The invoice created before stays unpaid and expires on its own.
     */
    private String createPaymentForInvoice(String invoiceId, String description) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("iid", invoiceId);
        body.put("pay_currency", config.payCurrency());
        putIfPresent(body, "order_description", description);
        String paymentId = text(post("/invoice-payment", body, "payment creation").path("payment_id"));
        if (paymentId == null) {
            throw new PaymentProviderException("NOWPayments payment response has no payment_id");
        }
        return paymentId;
    }

    @Override
    public WebhookEvent parseWebhook(WebhookRequest request) {
        JsonNode json;
        try {
            json = MAPPER.readTree(request.body());
        } catch (JacksonException e) {
            throw new WebhookParseException("IPN body is not valid JSON", e);
        }
        if (json == null || !json.isObject()) {
            throw new WebhookParseException("IPN body is not a JSON object");
        }
        String paymentId = text(json.path("payment_id"));
        String status = text(json.path("payment_status"));
        if (paymentId == null || status == null) {
            throw new WebhookParseException("IPN body has no payment_id/payment_status");
        }

        boolean valid;
        try {
            valid = IpnSignature.verify(config.ipnSecret(), request.body(), request.headers().get(SIGNATURE_HEADER));
        } catch (IllegalArgumentException e) {
            valid = false;
        }

        try {
            return new WebhookEvent(
                    ID,
                    // an IPN has no event id of its own: payment plus status is the natural key
                    paymentId + ":" + status,
                    "payment." + status,
                    instant(json.path("updated_at")),
                    valid,
                    new String(request.body(), StandardCharsets.UTF_8),
                    paymentInfo(json));
        } catch (DateTimeParseException e) {
            throw new WebhookParseException("IPN has a malformed timestamp", e);
        }
    }

    @Override
    public Optional<PaymentInfo> fetchPayment(String providerRef) {
        if (providerRef == null || !providerRef.matches("\\d+")) {
            return Optional.empty();
        }
        Request httpRequest = new Request.Builder()
                .url(config.baseUrl() + "/payment/" + providerRef)
                .header("x-api-key", config.apiKey())
                .get()
                .build();
        try (Response response = http.newCall(httpRequest).execute()) {
            String text = response.body() != null ? response.body().string() : "";
            // an invoice has no payment yet: an invoice id is not found as a payment id
            if (response.code() == 404 || response.code() == 400) {
                return Optional.empty();
            }
            if (!response.isSuccessful()) {
                throw new PaymentProviderException(
                        "NOWPayments payment lookup failed: HTTP " + response.code() + " " + abbreviate(text));
            }
            JsonNode json = readJson(text);
            if (text(json.path("payment_status")) == null) {
                return Optional.empty();
            }
            return Optional.of(paymentInfo(json));
        } catch (IOException e) {
            throw new PaymentProviderException("NOWPayments is unreachable", e);
        } catch (DateTimeParseException e) {
            throw new PaymentProviderException("NOWPayments returned a malformed timestamp", e);
        }
    }

    /** The amount is the price in the fiat currency of the invoice, not {@code actually_paid}: see {@code PaymentInfo}. */
    private static PaymentInfo paymentInfo(JsonNode json) {
        JsonNode price = json.path("price_amount");
        String currency = text(json.path("price_currency"));
        boolean hasPrice = price.isNumber() && currency != null;
        return new PaymentInfo(
                mapStatus(text(json.path("payment_status"))),
                text(json.path("payment_id")),
                text(json.path("order_id")),
                null,
                hasPrice ? price.decimalValue() : null,
                hasPrice ? currency.toUpperCase(Locale.ROOT) : null,
                null);
    }

    static PaymentStatus mapStatus(String status) {
        if (status == null) {
            return PaymentStatus.UNKNOWN;
        }
        return switch (status) {
            case "waiting" -> PaymentStatus.PENDING;
            case "confirming", "confirmed", "sending" -> PaymentStatus.PROCESSING;
            case "finished" -> PaymentStatus.PAID;
            case "partially_paid" -> PaymentStatus.PARTIALLY_PAID;
            case "failed" -> PaymentStatus.FAILED;
            case "refunded" -> PaymentStatus.REFUNDED;
            case "expired" -> PaymentStatus.EXPIRED;
            default -> PaymentStatus.UNKNOWN;
        };
    }

    private JsonNode post(String path, ObjectNode body, String action) {
        Request httpRequest = new Request.Builder()
                .url(config.baseUrl() + path)
                .header("x-api-key", config.apiKey())
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response response = http.newCall(httpRequest).execute()) {
            String text = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new PaymentProviderException(
                        "NOWPayments " + action + " failed: HTTP " + response.code() + " " + abbreviate(text));
            }
            return readJson(text);
        } catch (IOException e) {
            throw new PaymentProviderException("NOWPayments is unreachable", e);
        }
    }

    private static JsonNode readJson(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JacksonException e) {
            throw new PaymentProviderException("NOWPayments returned a body that is not valid JSON", e);
        }
    }

    /**
     * An IPN carries {@code updated_at} as epoch milliseconds, the payment API as an ISO string: both are read.
     * Null for a missing field; throws {@link DateTimeParseException} for a malformed one.
     */
    private static Instant instant(JsonNode node) {
        if (node.isNumber()) {
            return Instant.ofEpochMilli(node.longValue());
        }
        String text = text(node);
        return text == null ? null : Instant.parse(text);
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    /** Text of a value node; null for a missing field and for a JSON null (Jackson would turn the latter into ""). */
    private static String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asString();
    }

    private static String abbreviate(String text) {
        return text.length() <= ERROR_BODY_LIMIT ? text : text.substring(0, ERROR_BODY_LIMIT) + "...";
    }
}
