package tech.nomad4.payments4j;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookRequestTest {

    @Test
    void headerNamesAreLowerCased() {
        WebhookRequest request = new WebhookRequest(new byte[0], Map.of("Paddle-Signature", "ts=1;h1=ab"));
        assertThat(request.headers()).containsEntry("paddle-signature", "ts=1;h1=ab");
    }

    @Test
    void missingHeadersBecomeEmpty() {
        assertThat(new WebhookRequest(new byte[0], null).headers()).isEmpty();
    }

    @Test
    void bodyIsRequired() {
        assertThatThrownBy(() -> new WebhookRequest(null, Map.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
