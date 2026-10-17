package tech.nomad4.payments4j;

/** The webhook body could not be parsed at all (not JSON, required fields missing). */
public class WebhookParseException extends RuntimeException {

    public WebhookParseException(String message) {
        super(message);
    }

    public WebhookParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
