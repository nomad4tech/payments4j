package tech.nomad4.payments4j;

import java.util.Optional;

/**
 * Port of a payment provider. The implementation lives in a {@code payments4j-<provider>} module and sits behind
 * a factory: the consumer only sees this interface.
 *
 * <p>The contract that {@code payments4j-test-kit} verifies:
 * <ul>
 *   <li>{@link #parseWebhook} does not throw on a bad signature: it returns the event with
 *       {@code signatureValid = false}, and the caller decides (401, log, store for debugging);</li>
 *   <li>an event that is not about a payment (customer, address) gets {@link PaymentStatus#UNKNOWN} and
 *       must not change any order;</li>
 *   <li>{@link #fetchPayment} returns empty for a reference the provider does not know, it does not throw.</li>
 * </ul>
 */
public interface PaymentProvider {

    /** Stable provider id such as "paddle" or "nowpayments". Handy as a webhook URL segment. */
    String id();

    /**
     * Creates a payment session and returns where to send the customer.
     *
     * @throws IllegalArgumentException the request lacks something this provider requires
     * @throws PaymentProviderException the provider is unreachable or answered with an error
     */
    CheckoutSession createCheckout(CheckoutRequest request);

    /**
     * Parses an incoming webhook. The signature is verified here, over the raw bytes.
     *
     * @throws WebhookParseException the body could not be parsed at all
     */
    WebhookEvent parseWebhook(WebhookRequest request);

    /**
     * Asks the provider for the current state of a payment. Meant for reconciliation: a webhook may be lost,
     * and "abandoned payment" or "refund approved" do not always arrive as events.
     *
     * @return empty if the provider does not know the reference (for example it is still an invoice id)
     *         or does not support the lookup
     * @throws PaymentProviderException the provider is unreachable or answered with an error
     */
    default Optional<PaymentInfo> fetchPayment(String providerRef) {
        return Optional.empty();
    }
}
