# Paddle (Billing) guide

Status: verified on 2026-10-11 with live sandbox API calls and a full payment with a test card. Whatever was not
verified is marked explicitly.

Links:
- Sandbox dashboard: https://sandbox-vendors.paddle.com
- Production dashboard: https://vendors.paddle.com
- Documentation: https://developer.paddle.com

Paddle is a Merchant of Record: it sells to the customer in its own name, calculates and pays the sales taxes
(VAT and so on) and pays the revenue out to you.

## 1. Environments

| | API | Dashboard |
|---|---|---|
| Sandbox | `https://sandbox-api.paddle.com` | `https://sandbox-vendors.paddle.com` |
| Production | `https://api.paddle.com` | `https://vendors.paddle.com` |

Accounts, keys, prices and notification destinations of sandbox and production are completely separate. API
headers: `Authorization: Bearer <api key>`, `Paddle-Version: 1`.

In code the environment is `ProviderEnvironment` in `PaddleConfig`; the library rejects a production environment
with a `pdl_sdbx_` key and a sandbox environment with a `pdl_live_` key.

## 2. Keys and identifiers

| What | Meaning | Secret |
|---|---|---|
| API key | server-side key (`pdl_sdbx_apikey_...` / `pdl_live_apikey_...`), `PaddleConfig.apiKey` | yes |
| Webhook secret | secret of a notification destination (`pdl_ntfset_...`), `PaddleConfig.webhookSecret`; every destination has its own | yes |
| Client-side token | for Paddle.js on your payment page (`test_...` in sandbox); not used by the library | no |
| Price id | catalog price (`pri_...`), passed as `CheckoutRequest.productRef` or `PaddleConfig.defaultPriceId` | no |

A destination's secret can be read again through `GET /notification-settings/{id}` (`endpoint_secret_key`).

## 3. Product model

- Product -> Price. The price is set in the dashboard (or API), never in the request. Amount and currency of
  `CheckoutRequest` are ignored; the charged amount comes back in `CheckoutSession.amount()`.
- With `tax_mode: location` the price already includes VAT (the checkout shows net plus tax).
- Amounts come as strings in minor units (`"999"` is 9.99 USD). The library converts using the currency's number
  of decimals, so JPY is not divided by 100.

## 4. Payment flow

1. Once, in the dashboard: **Checkout -> Checkout settings -> Default payment link** = the public URL of a page of
   yours where Paddle.js is loaded. Without it `POST /transactions` fails with
   `transaction_default_checkout_url_not_set`. It cannot be set through the API.
2. `createCheckout` calls `POST /transactions` with
   `{"items":[{"price_id":"pri_...","quantity":1}],"custom_data":{"order_id":"<your id>"}}`. The answer holds
   `data.id` (`txn_...`) and `data.checkout.url` = `<default payment link>?_ptxn=txn_...`; they become
   `CheckoutSession.providerRef()` and `redirectUrl()`.
3. Send the customer to `redirectUrl`. The page initializes Paddle.js
   (`Paddle.Environment.set("sandbox"); Paddle.Initialize({token: <client token>})`) and, seeing `_ptxn`, opens the
   overlay checkout on its own.
4. Statuses arrive as webhooks. `custom_data.order_id` comes back in transaction events and in subscription
   events, which is how an event is tied to your order.

An alternative without `POST /transactions` is opening the overlay from JavaScript with `Paddle.Checkout.open(...)`
(not verified). The library only supports the server-side transaction flow.

## 5. Webhooks

- Destination: dashboard (Developer tools -> Notifications) or the `/notification-settings` API. The URL **must
  include the path** of your endpoint. Without a path, events go to the site root and are lost.
- Body: `{"event_id":"evt_...","event_type":"...","occurred_at":"...","notification_id":"ntf_...","data":{...}}`.
  `event_id` is the idempotency key (`WebhookEvent.eventId()`).
- **Signature:** header `Paddle-Signature: ts=<unix>;h1=<hex>`. The signed string is `ts + ":" + raw body`,
  HMAC-SHA256 with the destination secret as is (do not decode it). Constant-time comparison.
- **Time check:** the documentation allows 5 seconds between `ts` and the current time. The library enforces it
  (`PaddleConfig.toleranceSeconds`, default 5), which needs a synchronized server clock.
- The signature covers the exact bytes: never re-parse or re-format the body before verifying.
- After a secret rotation the header holds several `h1` values: any of them may match.

### Event sequence observed for the first subscription payment
`transaction.created` -> `transaction.updated` -> `customer.created`, `address.created` -> `transaction.ready` ->
`transaction.paid` -> `subscription.created`, `subscription.activated` -> `transaction.updated` ->
`transaction.completed`. The order is not guaranteed; do not rely on it.

### How events map to `PaymentInfo`

- `transaction.*` events set the status from `data.status`: `draft`/`ready`/`billed` -> `PENDING`, `paid` ->
  `PROCESSING`, `completed` -> `PAID`, `canceled` -> `CANCELED`, `past_due` and `transaction.payment_failed` ->
  `FAILED`. They also fill `subscriptionId`, `amount`, `currency` and `periodEnd` (`billing_period.ends_at`).
- `adjustment.*` events (refund, chargeback) change the status only once `approved`; `providerRef` is the original
  transaction, `orderId` is null (adjustments carry no `custom_data`).
- `subscription.*` events never change a status (`UNKNOWN`) but tie `subscriptionId` to your `orderId` and report
  the current `periodEnd`.
- Everything else (customers, addresses, ...) is `PaymentInfo.unrelated()`.

## 6. Test data (sandbox)

- Card `4242 4242 4242 4242`, any future expiry (12/30 was used), CVC `100`. Other cards from the Paddle
  documentation: `4000 0566 5566 5556` (debit Visa), `4000 0038 0000 0446` (3D Secure, not tried),
  `4000 0000 0000 0002` (declined, verified), `4000 0027 6000 3184` (succeeds, then fails on the next charge, not
  tried).
- Any email and country.
- The overlay checkout works on `http://localhost:<port>` (verified); no domain approval was needed in sandbox.

## 7. Pitfalls

- **Default payment link is mandatory** for `POST /transactions` and can only be set by hand in the dashboard. In
  production the domain needs approval (dashboard: Website approval, not verified).
- **A destination without a path** silently loses events.
- **Many events per payment** (14 in one run); some have no `custom_data` (customer, address). Events with no
  order of yours must simply be accepted and stored.
- **`custom_data` is present in `subscription.*`**, but they are not payments: do not change an order status from
  them (the library returns `UNKNOWN` for exactly that reason).
- **Renewals are not verified.** The first payment was observed; the automatic charge of the next period (a new
  transaction created by Paddle, and whether it carries your `order_id`) was not. Tie renewals to your order
  through `subscriptionId`.
- **Ngrok free** shows an interstitial page to browsers on first visit; server-to-server webhooks are not affected.
- **The price in the request does not affect Paddle:** do not show the customer your own number, show
  `CheckoutSession.amount()`.
