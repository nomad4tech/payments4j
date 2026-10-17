# Provider comparison

Filled in as providers get verified. An empty cell means not verified.

| | Paddle | NOWPayments |
|---|---|---|
| Kind | cards, PayPal, subscriptions | crypto |
| Merchant of Record | yes (takes the sales taxes on itself) | no |
| Subscriptions | yes; first payment, `subscription.*` events and a successful renewal verified; failed renewals and cancellation not | separate recurring API (per the documentation, not verified; the library supports one-off payments only) |
| Price set by | catalog price in the dashboard (`CheckoutRequest.productRef`) | the request (`amount` + `currency`) |
| Idempotency key | `event_id` of the event | `payment_id:payment_status` (no id of its own) |
| Sandbox | yes; full payment with a test card verified | yes; payments complete on their own after ~30-40 s |
| Webhook signature | HMAC-SHA256 over `ts:body`, header `Paddle-Signature`, 5 s tolerance | HMAC-SHA512 over key-sorted JSON, header `x-nowpayments-sig` |
| Refund / chargeback | `adjustment.*` events, approved refunds visible through the API | status `refunded` is mapped to `REFUNDED` (from the documentation; cannot be simulated in the sandbox) |
| Reconciliation lookup | `GET /transactions/{id}` + approved `/adjustments` | `GET /payment/{payment_id}` |
| Status in payments4j | implemented, sandbox-verified | implemented, sandbox-verified |

Candidates for later: Lemon Squeezy, Dodo Payments, Cryptomus. See
[ADDING_A_PROVIDER.md](ADDING_A_PROVIDER.md).
