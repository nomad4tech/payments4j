package tech.nomad4.payments4j.paddle;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Currency;
import java.util.Optional;

class PaddleProvider implements PaymentProvider {

    static final String ID = "paddle";
    static final String SIGNATURE_HEADER = "paddle-signature";

    private static final MediaType JSON = MediaType.get("application/json");
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final int ERROR_BODY_LIMIT = 500;

    private final PaddleConfig config;
    private final OkHttpClient http;
    private final Clock clock;

    PaddleProvider(PaddleConfig config, OkHttpClient http, Clock clock) {
        this.config = config;
        this.http = http;
        this.clock = clock;
    }

    @Override
    public String id() {
        return ID;
    }

    /**
     * Creates a transaction for a price from the Paddle catalog. The amount and currency of the request are
     * ignored: Paddle takes them from the price. Notify, success and cancel URLs are not sent either: the webhook
     * destination and the checkout page (default payment link) are set in the Paddle dashboard.
     */
    @Override
    public CheckoutSession createCheckout(CheckoutRequest request) {
        String priceId = request.productRef() != null && !request.productRef().isBlank()
                ? request.productRef() : config.defaultPriceId();
        if (priceId == null) {
            throw new IllegalArgumentException(
                    "Paddle needs a price id: set CheckoutRequest.productRef or PaddleConfig.defaultPriceId");
        }
        ObjectNode body = MAPPER.createObjectNode();
        ObjectNode item = body.putArray("items").addObject();
        item.put("price_id", priceId);
        item.put("quantity", 1);
        // custom_data comes back in webhooks: this is how we find our order
        body.putObject("custom_data").put("order_id", request.orderId());

        Request httpRequest = new Request.Builder()
                .url(config.baseUrl() + "/transactions")
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Paddle-Version", "1")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = http.newCall(httpRequest).execute()) {
            String text = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new PaymentProviderException(
                        "Paddle transaction creation failed: HTTP " + response.code() + " " + abbreviate(text));
            }
            JsonNode data = readJson(text).path("data");
            String id = data.path("id").asString(null);
            String url = data.path("checkout").path("url").asString(null);
            if (id == null || url == null) {
                throw new PaymentProviderException("Paddle response has no id/checkout.url: " + abbreviate(text));
            }
            String currency = data.path("currency_code").asString(null);
            BigDecimal amount = toAmount(data.path("details").path("totals").path("total").asString(null), currency);
            return amount == null
                    ? new CheckoutSession(id, url)
                    : new CheckoutSession(id, url, amount, currency);
        } catch (IOException e) {
            throw new PaymentProviderException("Paddle is unreachable", e);
        }
    }

    @Override
    public WebhookEvent parseWebhook(WebhookRequest request) {
        JsonNode json;
        try {
            json = MAPPER.readTree(request.body());
        } catch (JacksonException e) {
            throw new WebhookParseException("Paddle webhook body is not valid JSON", e);
        }
        if (json == null || !json.isObject()) {
            throw new WebhookParseException("Paddle webhook body is not a JSON object");
        }
        String eventId = json.path("event_id").asString(null);
        String eventType = json.path("event_type").asString(null);
        if (eventId == null || eventType == null) {
            throw new WebhookParseException("Paddle webhook has no event_id/event_type");
        }

        boolean valid = PaddleSignature.verify(
                config.webhookSecret(), request.body(), request.headers().get(SIGNATURE_HEADER),
                config.toleranceSeconds(), clock.instant());

        try {
            return new WebhookEvent(
                    ID,
                    eventId,
                    eventType,
                    instant(json.path("occurred_at")),
                    valid,
                    new String(request.body(), StandardCharsets.UTF_8),
                    paymentInfo(eventType, json.path("data")));
        } catch (DateTimeParseException e) {
            throw new WebhookParseException("Paddle webhook has a malformed timestamp", e);
        }
    }

    @Override
    public Optional<PaymentInfo> fetchPayment(String providerRef) {
        if (providerRef == null || !providerRef.startsWith("txn_")) {
            return Optional.empty();
        }
        try {
            JsonNode txn = get("/transactions/" + providerRef);
            if (txn == null) {
                return Optional.empty();
            }
            PaymentInfo info = paymentInfo("transaction.updated", txn.path("data"));
            // a refund and a chargeback are separate objects: look at approved adjustments
            if (info.status() == PaymentStatus.PAID) {
                JsonNode adjustments = get("/adjustments?transaction_id=" + providerRef + "&status=approved");
                if (adjustments != null) {
                    PaymentStatus status = info.status();
                    for (JsonNode adjustment : adjustments.path("data")) {
                        PaymentStatus adj = mapStatus("adjustment.updated", "approved",
                                adjustment.path("action").asString(null));
                        if (adj == PaymentStatus.CHARGEBACK || adj == PaymentStatus.REFUNDED && status != PaymentStatus.CHARGEBACK) {
                            status = adj;
                        }
                    }
                    info = new PaymentInfo(status, info.providerRef(), info.orderId(), info.subscriptionId(),
                            info.amount(), info.currency(), info.periodEnd());
                }
            }
            return Optional.of(info);
        } catch (IOException e) {
            throw new PaymentProviderException("Paddle is unreachable", e);
        } catch (DateTimeParseException e) {
            throw new PaymentProviderException("Paddle returned a malformed timestamp", e);
        }
    }

    /**
     * Transaction events and approved adjustments carry the payment status. Subscription events carry no status
     * ({@code UNKNOWN}) but tie the subscription to our order and report the current period end. Customers,
     * addresses and the rest are {@link PaymentInfo#unrelated()}.
     */
    private static PaymentInfo paymentInfo(String eventType, JsonNode data) {
        if (eventType.startsWith("transaction.")) {
            String currency = data.path("currency_code").asString(null);
            BigDecimal amount = toAmount(data.path("details").path("totals").path("total").asString(null), currency);
            return new PaymentInfo(
                    mapStatus(eventType, data.path("status").asString(null), null),
                    data.path("id").asString(null),
                    data.path("custom_data").path("order_id").asString(null),
                    data.path("subscription_id").asString(null),
                    amount,
                    amount == null ? null : currency,
                    instant(data.path("billing_period").path("ends_at")));
        }
        if (eventType.startsWith("adjustment.")) {
            // an adjustment has its own id adj_...; the payment it belongs to is in transaction_id
            return new PaymentInfo(
                    mapStatus(eventType, data.path("status").asString(null), data.path("action").asString(null)),
                    data.path("transaction_id").asString(null), null, null, null, null, null);
        }
        if (eventType.startsWith("subscription.")) {
            return new PaymentInfo(
                    PaymentStatus.UNKNOWN, null,
                    data.path("custom_data").path("order_id").asString(null),
                    data.path("id").asString(null),
                    null, null,
                    instant(data.path("current_billing_period").path("ends_at")));
        }
        return PaymentInfo.unrelated();
    }

    /**
     * An order is changed by transaction events and approved adjustments (refund, chargeback). Subscriptions,
     * customers and the rest give UNKNOWN: the consumer does not apply such events to an order.
     */
    static PaymentStatus mapStatus(String eventType, String dataStatus, String action) {
        if (eventType.startsWith("adjustment.")) {
            // a refund is created as pending_approval and changes the order only once approved
            if (!"approved".equals(dataStatus) || action == null) {
                return PaymentStatus.UNKNOWN;
            }
            return switch (action) {
                case "refund", "credit" -> PaymentStatus.REFUNDED;
                case "chargeback", "chargeback_warning", "chargeback_reverse" -> PaymentStatus.CHARGEBACK;
                default -> PaymentStatus.UNKNOWN;
            };
        }
        if (eventType.equals("transaction.payment_failed")) {
            return PaymentStatus.FAILED;
        }
        if (!eventType.startsWith("transaction.") || dataStatus == null) {
            return PaymentStatus.UNKNOWN;
        }
        return switch (dataStatus) {
            case "draft", "ready", "billed" -> PaymentStatus.PENDING;
            case "paid" -> PaymentStatus.PROCESSING;
            case "completed" -> PaymentStatus.PAID;
            case "canceled" -> PaymentStatus.CANCELED;
            case "past_due" -> PaymentStatus.FAILED;
            default -> PaymentStatus.UNKNOWN;
        };
    }

    /** GET against the Paddle API. null means 404; other errors become {@link PaymentProviderException}. */
    private JsonNode get(String path) throws IOException {
        Request httpRequest = new Request.Builder()
                .url(config.baseUrl() + path)
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Paddle-Version", "1")
                .get()
                .build();
        try (Response response = http.newCall(httpRequest).execute()) {
            String text = response.body() != null ? response.body().string() : "";
            if (response.code() == 404) {
                return null;
            }
            if (!response.isSuccessful()) {
                throw new PaymentProviderException(
                        "Paddle lookup failed: HTTP " + response.code() + " " + abbreviate(text));
            }
            return readJson(text);
        }
    }

    private static JsonNode readJson(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JacksonException e) {
            throw new PaymentProviderException("Paddle returned a body that is not valid JSON", e);
        }
    }

    /** Null for a missing field; throws {@link DateTimeParseException} for a malformed one. */
    private static Instant instant(JsonNode node) {
        String text = node.asString(null);
        return text == null ? null : OffsetDateTime.parse(text).toInstant();
    }

    /**
     * Paddle amounts are strings in the currency's minor units. The number of decimals comes from the currency
     * (USD 2, JPY 0). Null when the amount or the currency is missing or unknown.
     */
    static BigDecimal toAmount(String minorUnits, String currencyCode) {
        if (minorUnits == null || currencyCode == null) {
            return null;
        }
        try {
            int digits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
            return new BigDecimal(minorUnits).movePointLeft(Math.max(digits, 0));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= ERROR_BODY_LIMIT ? text : text.substring(0, ERROR_BODY_LIMIT) + "...";
    }
}
