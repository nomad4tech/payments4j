# Integrating payments4j into an application

The library covers the provider side. What every application still has to build is small and always the same
shape: an order, a webhook endpoint, a table of received events, and a reconciliation loop. This page describes
that shape; the snippets are sketches (Spring is used for illustration, the library does not need it).

## The contract the code relies on

- `parseWebhook` does not throw on a bad signature: it returns the event with `signatureValid = false`. Whether to
  answer 401, log or store it is your decision. It throws `WebhookParseException` only for a body it cannot read.
- `eventId` is the idempotency key. Paddle: its `event_id`. NOWPayments: `payment_id:payment_status`. Treat it as
  unique **only among events with a valid signature**, so a forged event cannot block the genuine one.
- `PaymentStatus.UNKNOWN` must never change an order (customers, addresses, subscription bookkeeping).
- A paid order is not rolled back: events arrive out of order. `StatusRules` encodes this.
- `CheckoutSession.amount()` / `currency()` are non-null when the provider sets the price itself (Paddle): show
  that number to the customer, not the one from your request.
- `fetchPayment(ref)` returns empty for a reference the provider does not know (for example an invoice id) and
  throws `PaymentProviderException` when the provider is unavailable.
- Every status change, from a webhook or from reconciliation, goes through `StatusRules.decide`.

## What to store

An order table and an event table are enough.

```sql
CREATE TABLE orders (
    id            VARCHAR(64) PRIMARY KEY,   -- passed as CheckoutRequest.orderId
    provider      VARCHAR(32)  NOT NULL,
    status        VARCHAR(32)  NOT NULL,     -- PaymentStatus
    provider_ref  VARCHAR(128),              -- CheckoutSession.providerRef, updated from events
    subscription_id VARCHAR(128),            -- PaymentInfo.subscriptionId, for renewals
    paid_until    TIMESTAMPTZ,               -- PaymentInfo.periodEnd (or computed by you for one-off payments)
    last_checked_at TIMESTAMPTZ,             -- reconciliation throttle
    created_at    TIMESTAMPTZ  NOT NULL
);

CREATE TABLE webhook_events (
    id           BIGSERIAL PRIMARY KEY,
    provider     VARCHAR(32)  NOT NULL,
    event_id     VARCHAR(128) NOT NULL,
    event_type   VARCHAR(128),
    raw_body     TEXT         NOT NULL,
    signature_ok BOOLEAN      NOT NULL,
    received_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_webhook_provider_event_valid
    ON webhook_events (provider, event_id) WHERE signature_ok;
```

## The webhook endpoint

1. Read the **raw bytes** of the body. Do not let a framework parse and re-serialize it first, the signature
   covers the exact bytes. Lower-casing the header names is done by `WebhookRequest`.
2. `provider.parseWebhook(new WebhookRequest(body, headers))`.
3. Store the raw event, even with a bad signature (for debugging).
4. Bad signature: answer 401 and stop. A burst of them deserves an alert.
5. Duplicate (`provider` + `eventId` already stored with a valid signature, or the unique index fires on a race):
   answer 200 and stop.
6. Find the order: by `payment().orderId()`, and if it is null (refunds, renewals) by `payment().providerRef()` or
   `payment().subscriptionId()`. No order for a payment event: log it as an error, alert, answer 200 (so the
   provider stops retrying) - never silently drop it. An event with status `UNKNOWN` and no order is normal.
7. `StatusRules.decide(order.status(), payment.status())`; on `APPLY` store the new status and act on it.
8. Do 3-7 in one transaction. If applying fails, roll everything back including the stored event and answer 5xx:
   the provider will redeliver.

```java
@PostMapping("/webhooks/{provider}")
ResponseEntity<Void> receive(@PathVariable String provider, @RequestBody byte[] body, HttpServletRequest http) {
    PaymentProvider p = providers.get(provider);            // your registry: id -> PaymentProvider
    if (p == null) return ResponseEntity.notFound().build();
    Map<String, String> headers = new HashMap<>();
    Collections.list(http.getHeaderNames()).forEach(n -> headers.put(n, http.getHeader(n)));
    try {
        return switch (webhookService.handle(p, new WebhookRequest(body, headers))) {
            case INVALID_SIGNATURE -> ResponseEntity.status(401).build();
            default -> ResponseEntity.ok().build();
        };
    } catch (WebhookParseException e) {
        return ResponseEntity.badRequest().build();
    }
}
```

## Granting access

Grant on `PAID` only, never on `PROCESSING` or `PARTIALLY_PAID`. Revoke on `REFUNDED` and `CHARGEBACK`.

For a subscription, `payment().periodEnd()` is the end of the period that was just paid: set your "access until"
to it on every `PAID` event (a renewal is a new transaction with a new `periodEnd`). For a one-off payment
(`periodEnd == null`, NOWPayments) compute the period yourself. Decide up front what happens when a subscription
payment fails and the provider retries: Paddle keeps retrying for days, so keeping access until the subscription is
actually canceled may be kinder than cutting it at the first `FAILED`.

Renewals are not verified live (see Known limitations): a renewal transaction may arrive without your `order_id`.
Link it to your order through `subscriptionId`, which the first `transaction.*` and `subscription.*` events carry
together with the `orderId`.

## Reconciliation

A scheduled job, for example once a minute:

1. Take orders in an open status (`PENDING`, `PROCESSING`, `PARTIALLY_PAID`, `FAILED`, `EXPIRED`) not checked for a
   few minutes, and paid orders inside your refund window not checked for an hour.
2. `provider.fetchPayment(order.providerRef())`. Skip an empty answer. If the answer carries an `orderId`, ignore it
   when it differs from the order you asked about.
3. Apply through `StatusRules.decide`, the same code as the webhook.
4. A `PENDING` order older than your timeout becomes `EXPIRED`, softly: a late payment still turns it `PAID`.
5. A `PaymentProviderException` on one order is that order's problem: record it as state or alert on it, and go on
   with the rest. Do not retry inside the loop; the next run is the retry.

## Configuration

Take everything from your configuration, with no code that assumes sandbox:

```java
ProviderEnvironment environment = ProviderEnvironment.parse(config.get("PADDLE_ENVIRONMENT"));
PaymentProvider paddle = PaddleProviders.create(
        PaddleConfig.of(environment, config.get("PADDLE_API_KEY"), config.get("PADDLE_WEBHOOK_SECRET")),
        sharedHttpClient, Clock.systemUTC());
```

The config constructor throws `IllegalArgumentException` for a swapped key or a blank secret, so a wrong setup fails
at startup instead of rejecting every webhook later. Going live: [GOING_TO_PRODUCTION.md](GOING_TO_PRODUCTION.md).
