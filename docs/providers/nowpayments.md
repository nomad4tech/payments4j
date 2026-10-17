# NOWPayments guide

Status: verified on 2026-10-11 against the official Postman collection and with live sandbox calls. Whatever was
not verified is marked explicitly.

Links:
- Sandbox dashboard: https://account-sandbox.nowpayments.io/dashboard
- Official documentation (Postman): https://documenter.getpostman.com/view/7907941/2s93JusNJt (the page is rendered
  by JavaScript; the whole collection is served by
  `https://documenter.gw.postman.com/api/collections/7907941/2s93JusNJt?segregateAuth=true&versionTag=latest`)

## 1. Environments

| | API URL | Payment page |
|---|---|---|
| Sandbox | `https://api-sandbox.nowpayments.io/v1` | `https://sandbox.nowpayments.io/payment/?iid=<invoice_id>` |
| Production | `https://api.nowpayments.io/v1` | `https://nowpayments.io/payment/?iid=<invoice_id>` (not verified) |

Sandbox and production keys differ, and so do the dashboards. In code the environment is `ProviderEnvironment`
in `NowPaymentsConfig`. NOWPayments keys carry no marker of their environment, so unlike Paddle the library cannot
catch a swapped key: double-check the pair when going live.

## 2. Keys

| What | Meaning | Used for |
|---|---|---|
| API key | `x-api-key` header, `NowPaymentsConfig.apiKey` | every API call |
| IPN secret | `NowPaymentsConfig.ipnSecret` | verifying `x-nowpayments-sig` |
| Public key | dashboard public key | not used by the library |

The IPN secret is shown in full only when it is created: save it right away.

## 3. Flows

Two ways to accept a payment:

1. **Invoice** `POST /invoice` returns `invoice_url`; the customer is sent there (a hosted page, where they choose
   the coin if `pay_currency` is not set). This is what the library uses: no addresses and memos to handle.
2. **Payment** `POST /payment` returns `pay_address` and `pay_amount`, and you draw the payment UI yourself. It
   needs `pay_currency`, and some coins need a memo (`payin_extra_id`). Not supported by the library.

Minimal invoice request:

```json
{"price_amount": 20, "price_currency": "usd", "order_id": "<your id>",
 "ipn_callback_url": "https://.../webhooks/nowpayments",
 "success_url": "...", "cancel_url": "..."}
```

The answer holds `id` (invoice id) and `invoice_url`. A payment's status: `GET /payment/{payment_id}`.

**Payment created together with the invoice:** `POST /invoice-payment` with
`{"iid":"<invoice id>","pay_currency":"usdttrc20"}` returns `payment_id`, `pay_address` and `pay_amount`. Verified:
`ipn_callback_url` and `order_id` are inherited from the invoice, and `GET /payment/{payment_id}` shows
`invoice_id`. Without this step the `payment_id` of an invoice payment first appears in the IPN, and until then the
payment cannot be found through the API.

The library does this when `NowPaymentsConfig.payCurrency` is set: `CheckoutSession.providerRef()` is then the
`payment_id` from the very start, and a lost IPN can be recovered by reconciliation. If creating the payment fails,
`createCheckout` throws `PaymentProviderException` rather than quietly returning the invoice id: an invoice id is
not found as a payment, so such an order could never be reconciled. The invoice that was already created stays
unpaid and expires on its own. Leave `payCurrency` unset to let the customer pick the coin on the invoice page
(then the payment id appears only in the IPN).

### Payment statuses (per the documentation)
`waiting` -> `confirming` -> `confirmed` -> `sending` -> `finished`; branches: `partially_paid`, `failed`,
`expired` (7 days without payment). Mapping: `waiting` -> `PENDING`; `confirming`/`confirmed`/`sending` ->
`PROCESSING`; `finished` -> `PAID`; `partially_paid` -> `PARTIALLY_PAID`; `failed` -> `FAILED`; `refunded` -> `REFUNDED` (from the documentation, not observable in the sandbox);
`expired` -> `EXPIRED`. Do not grant the goods on `partially_paid` when the full amount matters.

## 4. IPN (webhooks)

- POST to the `ipn_callback_url` given when the invoice was created (a single URL in the dashboard settings is
  also possible: not verified).
- Header `x-nowpayments-sig`. The body resembles the answer of `GET /payment/{id}`.
- **Signature:** body JSON -> sort keys recursively -> compact `JSON.stringify` -> HMAC-SHA512 with the IPN
  secret -> hex -> compare with the header.
- A callback to localhost is impossible: a public URL is needed (a tunnel such as ngrok).
- After `expired` no more callbacks come, even if money arrives later.
- An IPN has no event id of its own; the library's idempotency key is `payment_id:payment_status`.
- A firewall or Cloudflare in front of the endpoint must let NOWPayments through (their IP list is available on
  request from their support).
- `WebhookEvent.occurredAt()` is the IPN's `updated_at`.

## 5. Test data / sandbox

- The sandbox dashboard is separate, with 100 coins; USDT is available as `usdttrc20`, `usdterc20`, `usdtbsc` and
  `usdtsol` (verified with `GET /currencies`).
- **The sandbox completes any payment on its own after about 30-40 seconds** (verified 3 times: an invoice through
  the hosted page and `POST /payment` directly). Nothing needs to be paid or clicked. A payment goes `waiting` ->
  `finished`; there are no intermediate IPNs (`confirming`, `sending`), a single IPN arrives with the final status.
- **The `case` parameter of `POST /payment` (sandbox only) controls the outcome:** `common` (default), `success`,
  `failed`, `partially_paid`. Verified: `failed` and `partially_paid` arrive as real IPNs with a valid signature.
  Other values (`expired`, `refunded`) are rejected: `case must be one of [common, success, failed, partially_paid]`.
  The parameter was not tried with `POST /invoice`.
- Rapid repeated calls hit `429 Too Many Requests` from nginx (an HTML answer, not JSON).
- A real IPN reached an ngrok URL and the signature matched this implementation (2026-10-11).
- Without a public URL, test the receiving side locally: sign a body with the same algorithm (a Node script after
  the official example) and POST it with curl to your endpoint.

## 6. Pitfalls

- **Minimum amount.** `GET /min-amount?currency_from=usdttrc20&currency_to=usdttrc20` returned 11.26 (2026-10-11,
  sandbox). A test payment of 5 USD is not created. Use 20 or more, and check the minimum for the coin you offer
  before pricing anything below about 13 USD.
- **`payment_id` has different types:** a string in the answer of `POST /payment`, a number in `GET /payment/{id}`
  and in the IPN. The library reads both.
- **Numbers in the signature.** The signature is computed over the JavaScript representation of numbers (`20.0`
  becomes `20`, `1e-7` stays). Jackson prints `20.0` by default and the signature would not match, so
  `IpnSignature` writes the canonical JSON itself (covered by tests against a Node reference).
- **Nested objects** are sorted recursively (the documentation's `sortObject` example).
- `GET /payment/` (the list, including by `invoiceId`) and payouts require a JWT from `POST /auth` (valid for 5
  minutes), that is the dashboard login and password. **An account created through Google sign-in has no
  password**: the change form asks for the "Current password", and the sandbox password reset page shows reCAPTCHA
  `Invalid site key` (verified 2026-10-11). That is why the library never uses the JWT and takes the `payment_id`
  from `POST /invoice-payment`.
- **Arrays in the signature: the official examples disagree.** The Node example turns an array into an object with
  keys "0", "1", ...; the PHP and Python ones keep the array. Normal IPNs have no arrays and the library keeps an
  array as an array. If an array ever appears in an IPN, verify against a live event.
- **Types in an IPN differ from the API:** `updated_at` is a number (epoch milliseconds) in a live IPN and an ISO
  string in the answer of `GET /payment/{id}`; `payment_id` is a number in an IPN. The library reads both forms of
  `updated_at`. `actually_paid` is always 0 in the sandbox even for `finished`, so sandbox amounts must not be
  trusted: test the logic by status.
- **`ipn_callback_url` of an invoice applies to the payment the customer creates on the hosted page** (verified:
  the IPN arrived at the URL passed to `POST /invoice`).
- **Repeated IPNs on errors.** A payment flagged with an error sends a series of repeated notifications (their
  number and interval are in Payment Settings). The handler must be idempotent.
- The documentation has typos in places (`api.nowpayments.ioo` in the use cases). Trust the main section.
