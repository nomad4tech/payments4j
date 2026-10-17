# Going to production

Status: the code was checked for any binding to the sandbox. Production itself has not been exercised against real
provider accounts.

## Environment selection

The environment is configuration, never code:

| What | Where | Effect |
|---|---|---|
| Paddle | `PaddleConfig.environment` | API base URL |
| NOWPayments | `NowPaymentsConfig.environment` | API base URL |
| Explicit URL (rare) | `withBaseUrl(...)` on either config | overrides the URL of the environment |

`ProviderEnvironment.parse("sandbox" | "production")` turns a configuration string into the enum and refuses
anything else. Take the value from your configuration with no default that silently means production.

What the configs refuse at construction:
- a blank API key, a blank webhook secret (Paddle) or IPN secret (NOWPayments): every webhook would be rejected;
- a production Paddle config with a `pdl_sdbx_` API key, and a sandbox one with a `pdl_live_` key.

What stays your job, because the library cannot know it:
- NOWPayments keys carry no marker, so a swapped sandbox/production pair is not caught;
- the Paddle client-side token (`test_...` / `live_...`) lives on your payment page, not in the library;
- a production webhook URL must be `https://` and public (no tunnels, no localhost).

## What differs in production

| Topic | Paddle | NOWPayments |
|---|---|---|
| Account | a separate production account, business and website verification | a separate production account, a payout wallet |
| Keys | `pdl_live_apikey_...`, client token `live_...`, its own destination secret | its own API key and IPN secret |
| Catalog | product and price are created again, a new price id | - |
| Payment page | default payment link on an approved domain, with Paddle.js on it | the hosted NOWPayments page |
| Webhook | destination `https://<domain>/<your path>` in the production dashboard | `ipn_callback_url` passed with every invoice |
| Behaviour | refunds are approved by Paddle (in the sandbox automatically after ~7 minutes), real bank declines | a payment waits for real network confirmations; underpayments, overpayments, `expired` after 7 days with no IPN |
| Amounts | real taxes and fees | in the sandbox `actually_paid` is always 0, in production compare the amount separately |

## Checklist

1. Production accounts, keys, prices, an approved domain (Paddle), a wallet (NOWPayments).
2. Production secrets for both webhook verifiers; webhook URL over HTTPS.
3. Paddle: the default payment link domain is approved and Paddle.js on it uses the production client token.
4. Idempotency tested by delivering the same event twice.
5. Access is granted on `PAID` only - not on `PROCESSING` or `PARTIALLY_PAID`.
6. Logs contain no secrets and no full payment details (the configs mask secrets in `toString()`).
7. The failure scenarios of [NEGATIVE_SCENARIOS.md](NEGATIVE_SCENARIOS.md) were walked through: declined card,
   underpayment, refund, lost webhook.
8. Reconciliation is on, and its timeout for `PENDING` fits the provider's payment lifetime (NOWPayments: 7 days).
9. Alerting on the things that must never happen quietly: a payment without an order, a burst of forged
   signatures, a refund or chargeback, a failing reconciliation.
10. NOWPayments: `payCurrency` is set, otherwise the payment created with the invoice is not findable by
    reconciliation.
11. A first real payment of the smallest amount with your own card or wallet, then a refund.
