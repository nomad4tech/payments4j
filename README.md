# payments4j

[![Build](https://github.com/nomad4tech/payments4j/actions/workflows/ci.yml/badge.svg)](https://github.com/nomad4tech/payments4j/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A small, framework-agnostic Java library that puts payment providers behind one port: create a checkout, verify
and normalize webhooks, ask the provider what really happened to a payment. Paddle (cards, subscriptions,
Merchant of Record) and NOWPayments (crypto) are included; adding another provider means implementing one
interface and passing a shared contract test.

No Spring dependency - build a provider with a factory call and wrap it in a `@Bean` (or equivalent) yourself.
Storage, retries and scheduling stay in your application on purpose.

## Why

Every provider invents its own statuses, its own webhook signature and its own way to say "the customer paid".
The parts that hurt are the same each time, and they are what this library carries:

- **One port, normalized events** - `PaymentProvider` returns a `WebhookEvent` with a `PaymentStatus`, a
  `PaymentInfo` (payment reference, your order id, subscription id, amount, end of the paid period) and an
  idempotency key, whatever the provider sent.
- **Signatures over the raw bytes** - verification happens before any parsing. A forged event is returned with
  `signatureValid = false` instead of an exception, so you can store it for debugging and answer 401.
- **Status rules in one place** - `StatusRules.decide(current, incoming)` encodes the rules that keep late and
  out-of-order events from corrupting an order: a paid order is not rolled back, a refund is not undone, an event
  that is not about a payment never changes anything.
- **Reconciliation hook** - `fetchPayment(ref)` asks the provider for the real state, because webhooks get lost
  and some things (an abandoned checkout, an approved refund) never arrive as events.
- **Configuration that fails fast** - the constructor of a provider config refuses a production setup with a
  sandbox key, a blank webhook secret, and masks secrets in `toString()`.
- **A contract test kit** - `payments4j-test-kit` holds the behaviour every provider must show, so a new
  provider cannot quietly break what applications rely on.

## Installation

Pick the provider modules you need; `payments4j-core` comes with them.

```xml
<dependency>
    <groupId>tech.nomad4</groupId>
    <artifactId>payments4j-paddle</artifactId>
    <version>0.1.0</version>
</dependency>
<dependency>
    <groupId>tech.nomad4</groupId>
    <artifactId>payments4j-nowpayments</artifactId>
    <version>0.1.0</version>
</dependency>
```

> Published to GitHub Packages, which needs a token. No token needed via [JitPack](https://jitpack.io) instead -
> see [Package registry setup](#package-registry-setup) below.

Java 17 or newer. The only runtime dependencies of a provider module are OkHttp and Jackson 3
(`tools.jackson`); Jackson 3 lives in its own package, so it coexists with Jackson 2 in your application.

## Quick start

```java
// 1. Build the provider once. Settings come from your configuration, not from the code.
PaymentProvider paddle = PaddleProviders.create(
        PaddleConfig.of(ProviderEnvironment.parse(env("PADDLE_ENVIRONMENT")), env("PADDLE_API_KEY"), env("PADDLE_WEBHOOK_SECRET")));

// 2. Start a payment and send the customer to the returned page.
CheckoutSession session = paddle.createCheckout(new CheckoutRequest(
        order.id(), "Pro plan, 1 month", "pri_01abc...", null, null, null, null, null));
order.attach(session.providerRef());
redirect(session.redirectUrl());

// 3. In the webhook endpoint: pass the RAW bytes and headers, never a parsed body.
WebhookEvent event = paddle.parseWebhook(new WebhookRequest(rawBody, headers));
if (!event.signatureValid()) {
    store(event);                 // keep it for debugging
    return status(401);
}
if (alreadyProcessed(event.provider(), event.eventId())) {
    return status(200);           // the provider redelivered the same event
}
Order order = findOrder(event.payment());      // by payment().orderId(), else by payment().providerRef()
if (StatusRules.decide(order.status(), event.payment().status()) == StatusDecision.APPLY) {
    order.setStatus(event.payment().status());
    if (event.payment().status() == PaymentStatus.PAID) {
        grantAccess(order, event.payment().periodEnd());   // null for one-off payments
    }
}
```

A fuller walk-through of a webhook endpoint, storage, idempotency and reconciliation is in
[docs/INTEGRATION.md](docs/INTEGRATION.md).

## Core concepts

- **`PaymentProvider`** - the port: `id()`, `createCheckout`, `parseWebhook`, `fetchPayment`.
- **`PaymentStatus`** - `PENDING`, `PROCESSING`, `PAID`, `PARTIALLY_PAID`, `FAILED`, `EXPIRED`, `CANCELED`,
  `REFUNDED`, `CHARGEBACK`, `UNKNOWN`. Grant the goods on `PAID` only.
- **`PaymentInfo`** - what a provider says about a payment; the same type in webhook events and in `fetchPayment`.
- **`StatusRules`** - the order status transition rules, shared by the webhook path and reconciliation.
- **Provider modules** - `PaddleProviders.create(PaddleConfig)` and `NowPaymentsProviders.create(NowPaymentsConfig)`
  are the only public entry points; everything else is package-private. Pass your own `OkHttpClient` (and, for
  Paddle, a `Clock`) to the second overload when you need shared timeouts, a proxy or deterministic tests.

| | Paddle | NOWPayments |
|---|---|---|
| Kind | cards, PayPal, subscriptions | crypto (USDT and others) |
| Merchant of Record | yes | no |
| Price set by | the catalog in the Paddle dashboard (`productRef` = price id) | the request (`amount` + `currency`) |
| Idempotency key | Paddle `event_id` | `payment_id:payment_status` |
| Signature | HMAC-SHA256 over `ts:body`, 5 s tolerance | HMAC-SHA512 over key-sorted JSON |

More: [docs/COMPARISON.md](docs/COMPARISON.md), per-provider guides in [docs/providers](docs/providers), failure
scenarios in [docs/NEGATIVE_SCENARIOS.md](docs/NEGATIVE_SCENARIOS.md), going live in
[docs/GOING_TO_PRODUCTION.md](docs/GOING_TO_PRODUCTION.md), and how to add a provider in
[docs/ADDING_A_PROVIDER.md](docs/ADDING_A_PROVIDER.md).

## Known limitations

- **Sandbox only.** Both providers were exercised end to end in their sandboxes (payment, failures, refund,
  lost webhooks, reconciliation). Nothing has run against a production account yet; read
  [docs/GOING_TO_PRODUCTION.md](docs/GOING_TO_PRODUCTION.md) before the first real payment.
- **Subscription renewals are not verified live.** `PaymentInfo.subscriptionId` and `periodEnd` are read from the
  documented Paddle fields and covered by fixture tests, but a renewal transaction has not been observed in the
  sandbox. In particular, whether a renewal carries your `order_id` in `custom_data` is unconfirmed: link
  renewals to your order through `subscriptionId` (the first `transaction.*` or `subscription.*` event carries both).
- **No storage, scheduling or retries.** Deduplicating events, persisting orders, running reconciliation and
  alerting are the application's job; `docs/INTEGRATION.md` describes a working shape.
- **Paddle amounts in minor units.** Conversion uses the currency's standard number of decimals; a currency the JDK
  does not know yields `null` amount and currency instead of a wrong number.
- **NOWPayments amounts are the invoice price**, not what the customer actually sent: in the sandbox
  `actually_paid` is always 0. An underpayment is reported by the `PARTIALLY_PAID` status.
- **NOWPayments `expired` cannot be simulated** in the sandbox, and the provider sends no IPN after expiry; use
  reconciliation or a timeout on `PENDING` orders.
- **Paddle checkout page is yours.** Paddle needs a "default payment link" pointing at a page of yours that loads
  Paddle.js; the library creates the transaction, it does not render the page.

## Package registry setup

**GitHub Packages** (needs a personal access token with `read:packages`). In `~/.m2/settings.xml`:

```xml
<servers>
    <server>
        <id>github</id>
        <username>YOUR_GITHUB_USERNAME</username>
        <password>YOUR_TOKEN</password>
    </server>
</servers>
```

and in the `pom.xml` of your project:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/nomad4tech/payments4j</url>
    </repository>
</repositories>
```

**JitPack** (no token). Use the JitPack coordinates, for example:

```xml
<dependency>
    <groupId>com.github.nomad4tech.payments4j</groupId>
    <artifactId>payments4j-paddle</artifactId>
    <version>v0.1.0</version>
</dependency>
```

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

## License

MIT - see [LICENSE](LICENSE) in repository root.
