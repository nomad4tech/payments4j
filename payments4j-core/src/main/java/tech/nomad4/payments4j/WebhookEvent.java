package tech.nomad4.payments4j;

import java.time.Instant;

/**
 * A normalized webhook event.
 *
 * @param provider       provider id
 * @param eventId        idempotency key: the provider's own event id when it has one, otherwise a composite
 *                       (see the provider guide). Treat it as unique only among events with a valid signature,
 *                       so a forged event cannot block the genuine one
 * @param type           event type in the provider's terms (for debugging)
 * @param occurredAt     when the event happened according to the provider; null if the provider gives no time
 *                       (the application knows the receive time itself)
 * @param signatureValid result of the signature check
 * @param rawBody        the body as a string, for storing
 * @param payment        payment data; {@link PaymentInfo#unrelated()} for events that are not about a payment
 */
public record WebhookEvent(
        String provider,
        String eventId,
        String type,
        Instant occurredAt,
        boolean signatureValid,
        String rawBody,
        PaymentInfo payment) {
}
