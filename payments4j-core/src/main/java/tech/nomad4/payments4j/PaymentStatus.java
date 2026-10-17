package tech.nomad4.payments4j;

/** Provider-independent payment status. */
public enum PaymentStatus {
    /** Waiting for the customer to pay. */
    PENDING,
    /** Funds received, confirmations or settlement in progress. */
    PROCESSING,
    /** Paid in full: safe to grant the goods. */
    PAID,
    /** Paid only in part. Do not grant the goods automatically. */
    PARTIALLY_PAID,
    FAILED,
    EXPIRED,
    CANCELED,
    /** Refund confirmed by the provider: revoke access granted for the order. */
    REFUNDED,
    /** Dispute lost: revoke access, the bank has already taken the money back. */
    CHARGEBACK,
    /** An event or status that is not about the payment itself (customers, addresses) or is not mapped. */
    UNKNOWN
}
