# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `payments4j-core`: the `PaymentProvider` port, `CheckoutRequest` / `CheckoutSession`, `WebhookRequest` /
  `WebhookEvent`, `PaymentInfo`, `PaymentStatus`, `ProviderEnvironment` and `StatusRules` (order status
  transition rules).
- `payments4j-paddle`: Paddle Billing - transactions, webhook signature verification, subscription and period
  data, reconciliation by API.
- `payments4j-nowpayments`: NOWPayments - invoices with an upfront payment id, IPN signature verification,
  reconciliation by API.
- `payments4j-test-kit`: `PaymentProviderContract`, the tests every provider has to pass.

### Notes

- Verified only against the provider sandboxes. Recurring payments (renewals) are implemented from the Paddle
  documentation and not yet exercised against the live sandbox; see Known limitations in the README.
