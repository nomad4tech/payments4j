package tech.nomad4.payments4j;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * An incoming webhook as received.
 *
 * @param body    raw body bytes: signatures are computed over them, before any parsing
 * @param headers request headers; names are lower-cased on construction, so lookups are case-insensitive
 */
public record WebhookRequest(byte[] body, Map<String, String> headers) {

    public WebhookRequest {
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        Map<String, String> normalized = new HashMap<>();
        if (headers != null) {
            headers.forEach((name, value) -> normalized.put(name.toLowerCase(Locale.ROOT), value));
        }
        headers = Map.copyOf(normalized);
    }
}
