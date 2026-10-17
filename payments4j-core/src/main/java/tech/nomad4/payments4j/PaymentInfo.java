package tech.nomad4.payments4j;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What a provider reports about a payment, in a webhook event and in the answer of
 * {@link PaymentProvider#fetchPayment}. Optional fields are {@code null} when the provider does not supply them.
 *
 * @param status         normalized status
 * @param providerRef    payment or transaction id on the provider side
 * @param orderId        our order id as the provider echoes it
 * @param subscriptionId subscription id for recurring payments; null for one-off payments
 * @param amount         payment amount in the price currency, not the amount actually received:
 *                       an underpayment is reported by {@link PaymentStatus#PARTIALLY_PAID}
 * @param currency       ISO currency code of the amount; null together with amount
 * @param periodEnd      end of the paid subscription period; null for one-off payments
 */
public record PaymentInfo(
        PaymentStatus status,
        String providerRef,
        String orderId,
        String subscriptionId,
        BigDecimal amount,
        String currency,
        Instant periodEnd) {

    /** An event that is not about a payment (customer, address): it must not change any order. */
    public static PaymentInfo unrelated() {
        return new PaymentInfo(PaymentStatus.UNKNOWN, null, null, null, null, null, null);
    }
}
