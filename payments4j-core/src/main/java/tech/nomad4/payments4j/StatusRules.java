package tech.nomad4.payments4j;

/**
 * The single place for order status transition rules. Both the webhook handler and reconciliation must use it,
 * otherwise two paths to the same order drift apart. Storing the result stays with the application.
 */
public final class StatusRules {

    private StatusRules() {
    }

    /**
     * The rules:
     * <ul>
     *   <li>the same status changes nothing;</li>
     *   <li>{@code UNKNOWN} (events not about a payment) never changes an order;</li>
     *   <li>a paid order is not rolled back, except by a refund or a chargeback (events arrive out of order);</li>
     *   <li>after a refund the order does not go back either, except to a chargeback;</li>
     *   <li>a chargeback is final.</li>
     * </ul>
     */
    public static StatusDecision decide(PaymentStatus current, PaymentStatus incoming) {
        if (incoming == PaymentStatus.UNKNOWN) {
            return StatusDecision.IGNORE_UNKNOWN;
        }
        if (current == incoming) {
            return StatusDecision.UNCHANGED;
        }
        boolean reversal = incoming == PaymentStatus.REFUNDED || incoming == PaymentStatus.CHARGEBACK;
        boolean stale = switch (current) {
            case PAID -> !reversal;
            case REFUNDED -> incoming != PaymentStatus.CHARGEBACK;
            case CHARGEBACK -> true;
            default -> false;
        };
        return stale ? StatusDecision.IGNORE_STALE : StatusDecision.APPLY;
    }
}
