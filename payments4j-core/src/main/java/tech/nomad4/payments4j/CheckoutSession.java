package tech.nomad4.payments4j;

import java.math.BigDecimal;

/**
 * @param providerRef session, invoice or transaction id on the provider side
 * @param redirectUrl payment page to send the customer to
 * @param amount      the amount actually charged, if the provider sets the price itself (Paddle takes it
 *                    from the catalog, not from the request); null means the amount of the request
 * @param currency    currency of the charged amount; null together with amount
 */
public record CheckoutSession(String providerRef, String redirectUrl, BigDecimal amount, String currency) {

    public CheckoutSession(String providerRef, String redirectUrl) {
        this(providerRef, redirectUrl, null, null);
    }
}
