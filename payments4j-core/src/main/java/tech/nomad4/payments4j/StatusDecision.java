package tech.nomad4.payments4j;

/** What to do with an order that receives a new payment status; see {@link StatusRules#decide}. */
public enum StatusDecision {
    /** Move the order to the new status. */
    APPLY,
    /** Same status: leave the order as it is (updating the provider reference is fine). */
    UNCHANGED,
    /** The status is {@link PaymentStatus#UNKNOWN}: the event is not about a payment. */
    IGNORE_UNKNOWN,
    /** The event arrived late: the order is already in a status that is not left backwards. */
    IGNORE_STALE
}
