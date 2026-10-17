package tech.nomad4.payments4j;

/** The provider returned an error or is unreachable on an outgoing call. Retry or give up is up to the caller. */
public class PaymentProviderException extends RuntimeException {

    public PaymentProviderException(String message) {
        super(message);
    }

    public PaymentProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
