# Failure scenarios

Verified on 2026-10-11 in the sandboxes of both providers. Column "Check": **live** - triggered for real and
the result reached the application; **unit** - only mapping or signature tests; **not verified**.

## Matrix

| Scenario | Provider | How triggered | Provider signal | Status in the library | Check |
|---|---|---|---|---|---|
| Partial payment | NOWPayments | `POST /payment` with `"case":"partially_paid"` | IPN `partially_paid` | `PARTIALLY_PAID` | live |
| Payment failed | NOWPayments | `"case":"failed"` | IPN `failed` | `FAILED` | live |
| Payment expired | NOWPayments | impossible (the sandbox does not support `case: expired`, the term is 7 days) | per the documentation **no IPN** after `expired` | `EXPIRED` only from an IPN that may never come | unit (mapping) |
| Card declined | Paddle | card `4000 0000 0000 0002` | `transaction.payment_failed`, payment `error/declined`, the transaction stays `ready` | `FAILED` | live |
| Retry after a decline | Paddle | a valid card in the same transaction | `transaction.paid`, `transaction.completed` | `FAILED` -> `PAID` | live |
| Customer closed the checkout / never paid | both | close the overlay (Paddle), do not pay (NOWPayments) | **no event** | stays `PENDING`; your timeout makes it `EXPIRED` | live |
| Transaction canceled | Paddle | `PATCH /transactions/{id}` `{"status":"canceled"}` (draft/ready) | `transaction.canceled` | `CANCELED` | live |
| Refund | Paddle | `POST /adjustments` `{action:refund,type:full,...}` | `adjustment.created` with `pending_approval`; about 7 minutes later the sandbox approves it itself: `adjustment.updated` `approved` | `PAID` until approved, then `REFUNDED` | live (webhook and API lookup) |
| Chargeback | Paddle | - | `adjustment.*` with `action=chargeback` | `CHARGEBACK` | unit |
| Forged webhook | both | wrong signature | - | event returned with `signatureValid=false` | live |
| Same event delivered again | both | `POST /notifications/{id}/replay` (Paddle), IPN retries | same `event_id` / `payment_id:status` | same `eventId` (your dedupe turns it into a no-op) | live |
| Receiver down | Paddle | application stopped | notification `queued_for_retry`, retries after about 20 s and 65 s, delivered, signature valid | applies after the receiver is back | live |
| Event for an unknown order | both | an order with another `order_id` | - | your lookup finds nothing | live |
| Lost webhook | both | order statuses rolled back to `PENDING` in the database | - | `fetchPayment` returns `PAID`/`PARTIALLY_PAID`/`FAILED`/`REFUNDED` | live |
| Stale signature (replay attack) | Paddle | `ts` older than the tolerance | - | `signatureValid=false` | unit |

## What an application should do with each

These are recommendations; the library only supplies the signals.

- **`PARTIALLY_PAID`.** Do not grant the goods automatically. Ask for the rest (NOWPayments has `purchase_id` for
  that) or refund by hand. `StatusRules` lets the order move on to `PAID` if `finished` arrives later.
- **`FAILED`.** NOWPayments: the payment is over, a new checkout (a new order) is needed. Paddle: not terminal,
  the customer may retry in the same overlay: do not close access for good and do not create a duplicate order
  before your timeout.
- **`PENDING` for long.** An abandoned checkout and an expired payment send no events. Put a timeout on `PENDING`
  orders and mark them `EXPIRED`, softly: if the payment does arrive later (webhook or reconciliation), the order
  becomes `PAID`. Grant access on `PAID` only.
- **`REFUNDED` / `CHARGEBACK`.** Revoke access and record it. A `PAID` order changes only through these two;
  `StatusRules` ignores every other late event.
- **Forged event.** Answer 401, do not process, keep the event for investigation. A burst of them is worth an alert.
- **Unknown order.** Answer 200 so the provider does not retry forever, but log it loudly: a payment event with no
  order is an incident.
- **Receiver unavailable.** Answer 5xx until the event is stored: the provider retries. Store and apply in one
  transaction so a failure does not lose the event.

## Reconciliation

A webhook stays the main path and reconciliation backs it up (see [INTEGRATION.md](INTEGRATION.md)):

1. Orders in an open status (`PENDING`, `PROCESSING`, `PARTIALLY_PAID`, `FAILED`, `EXPIRED`) that were not checked
   for a while are asked from the provider with `fetchPayment`: NOWPayments `GET /payment/{id}`, Paddle
   `GET /transactions/{id}`.
2. Paid orders inside a refund window are re-checked now and then: for Paddle `fetchPayment` also looks at approved
   `adjustments` (a refund or chargeback that came without a webhook).
3. The result goes through the same `StatusRules` as the webhook.

Limits:
- NOWPayments reconciles by `payment_id`. With `NowPaymentsConfig.payCurrency` set the payment is created with the
  invoice, so the id is known before the first IPN. Verified: with the application stopped an IPN was lost (0
  events for the order) and reconciliation moved the order back to `PAID`. Without `payCurrency` the reference is
  the invoice id, which `fetchPayment` cannot find (listing payments by invoice needs a dashboard JWT, see the
  [guide](providers/nowpayments.md)).
- The library does not know how much the customer really paid (`actually_paid` is always 0 in the NOWPayments
  sandbox). In production compare the received amount separately.

## Sandbox limits

- NOWPayments: payments complete on their own after ~30-40 s, `expired` and `refunded` cannot be simulated,
  `actually_paid` is always 0, rapid calls give `429`.
- Paddle: a sandbox refund is approved by itself after about 7 minutes; it cannot be approved by hand.
