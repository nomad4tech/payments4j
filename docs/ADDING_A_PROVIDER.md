# Adding a provider

A provider is one module that implements `PaymentProvider` and passes the shared contract tests. Applications
see only the interface, so a new provider needs no change anywhere else.

## Steps

1. **Research first.** Write `docs/providers/<provider>.md` as you learn: environments, keys, the payment flow,
   the webhook format and signature, sandbox behaviour, pitfalls. Mark what you did not verify. The pitfalls section
   turned out to be the most valuable part of the existing guides.
2. **Create the module** `payments4j-<provider>`, add it to the root `pom.xml` modules, depend on
   `payments4j-core`, OkHttp and Jackson 3 (`tools.jackson`), and on `payments4j-test-kit` with test scope. Copy
   a sibling `pom.xml`.
3. **One public class and a factory.** Package `tech.nomad4.payments4j.<provider>`:
   - `<Provider>Config` - a record; validate in the compact constructor (blank key, blank secret, key of the wrong
     environment), take `ProviderEnvironment`, mask secrets in `toString()`, offer `withBaseUrl(...)` for tests;
   - `<Provider>Providers` - the factory, the only other public type. The implementation class is package-private;
   - the implementation, the signature verification and the status mapping stay package-private.
4. **Rules the implementation must follow:**
   - verify the signature over the raw bytes, with a constant-time comparison, and never throw for a bad one:
     return `signatureValid = false`;
   - throw `WebhookParseException` only when the body cannot be read at all;
   - map provider statuses to `PaymentStatus`; anything not about a payment is `PaymentInfo.unrelated()` /
     `UNKNOWN`. Refund and chargeback map to `REFUNDED` and `CHARGEBACK`, never to `FAILED`;
   - pick an idempotency key (`eventId`): the provider's own event id, or a composite that is stable across
     redeliveries, and document it;
   - wrap network failures in `PaymentProviderException`; reject a request that lacks what the provider needs
     with `IllegalArgumentException` before any call;
   - do not swallow a failure to continue: return it to the caller, who decides on retries. No logging
     framework, no retries and no timeouts of its own beyond what the supplied `OkHttpClient` has;
   - own your `ObjectMapper` internally; never take one from the caller;
   - fill `PaymentInfo` as far as the provider tells you (`subscriptionId`, `amount`, `periodEnd`); leave the rest
     `null`.
5. **Tests.**
   - Extend `PaymentProviderContract` from `payments4j-test-kit` (see `PaddleContractTest`): you supply the
     provider, a signed paid webhook, a forged one, a signed unrelated one, and stub a "not found" lookup.
   - Test the signature against a reference computed in another language (a Node or Python script run against the
     provider's own documentation example), not only against your own implementation.
   - Test checkout and lookup against `MockWebServer`; test the status mapping; test the config validation.
6. **Update docs:** the table in the README, `docs/COMPARISON.md`, the failure scenarios you verified in the
   sandbox, and `CHANGELOG.md`.

## What does not belong in a provider module

Storage, scheduling, retries, alerting, HTTP endpoints. They live in the application; see
[INTEGRATION.md](INTEGRATION.md).
