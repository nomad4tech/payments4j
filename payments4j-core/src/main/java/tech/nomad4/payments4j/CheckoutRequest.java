package tech.nomad4.payments4j;

import java.math.BigDecimal;

/**
 * @param orderId     our order id, the provider echoes it back in webhooks
 * @param description payment description, may be null
 * @param productRef  what is sold in the provider's catalog (Paddle: price id {@code pri_...}); null means the
 *                    provider falls back to its configured default or does not use it
 * @param amount      amount in the price currency; required by providers where the request sets the price
 *                    (NOWPayments), ignored by providers with catalog prices (Paddle)
 * @param currency    ISO currency code, e.g. "USD"; required together with amount
 * @param notifyUrl   public URL that receives webhooks, may be null
 * @param successUrl  where to send the customer after payment, may be null
 * @param cancelUrl   where to send the customer on cancellation, may be null
 */
public record CheckoutRequest(
        String orderId,
        String description,
        String productRef,
        BigDecimal amount,
        String currency,
        String notifyUrl,
        String successUrl,
        String cancelUrl) {

    public CheckoutRequest {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId must not be blank");
        }
    }
}
