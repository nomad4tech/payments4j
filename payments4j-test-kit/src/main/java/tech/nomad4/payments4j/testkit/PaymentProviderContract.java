package tech.nomad4.payments4j.testkit;

import org.junit.jupiter.api.Test;
import tech.nomad4.payments4j.PaymentProvider;
import tech.nomad4.payments4j.PaymentStatus;
import tech.nomad4.payments4j.WebhookEvent;
import tech.nomad4.payments4j.WebhookParseException;
import tech.nomad4.payments4j.WebhookRequest;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behaviour every {@link PaymentProvider} must show. A provider module extends this class in its tests and
 * supplies the provider and sample webhooks; the checks themselves are shared, so a new provider cannot
 * quietly break the contract the applications rely on.
 *
 * <p>See {@code docs/ADDING_A_PROVIDER.md} for how to wire a new provider in.
 */
public abstract class PaymentProviderContract {

    /** The provider under test, configured with the secrets the sample webhooks were signed with. */
    protected abstract PaymentProvider provider();

    /** A correctly signed webhook about a successful payment. */
    protected abstract WebhookRequest signedPaidWebhook();

    /** The same webhook as {@link #signedPaidWebhook()} with the signature invalid. */
    protected abstract WebhookRequest forgedPaidWebhook();

    /** A correctly signed webhook that is not about a payment (customer, address, subscription bookkeeping). */
    protected abstract WebhookRequest signedUnrelatedWebhook();

    /** Make the provider's API answer "not found" for {@link #unknownReference()} (a stub server, for example). */
    protected abstract void stubUnknownReference();

    /** A payment reference the provider does not know. */
    protected abstract String unknownReference();

    @Test
    void idIsAStableLowerCaseToken() {
        assertThat(provider().id()).isNotBlank().isEqualTo(provider().id().toLowerCase()).doesNotContain(" ", "/");
    }

    @Test
    void validWebhookIsAcceptedAndNormalized() {
        WebhookEvent event = provider().parseWebhook(signedPaidWebhook());

        assertThat(event.signatureValid()).isTrue();
        assertThat(event.provider()).isEqualTo(provider().id());
        assertThat(event.eventId()).isNotBlank();
        assertThat(event.type()).isNotBlank();
        assertThat(event.rawBody()).isNotBlank();
        assertThat(event.payment().status()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void forgedWebhookIsReportedNotThrown() {
        assertThatCode(() -> provider().parseWebhook(forgedPaidWebhook())).doesNotThrowAnyException();
        assertThat(provider().parseWebhook(forgedPaidWebhook()).signatureValid()).isFalse();
    }

    @Test
    void sameDeliveryHasTheSameEventId() {
        assertThat(provider().parseWebhook(signedPaidWebhook()).eventId())
                .isEqualTo(provider().parseWebhook(signedPaidWebhook()).eventId());
    }

    @Test
    void unrelatedEventNeverChangesAnOrder() {
        WebhookEvent event = provider().parseWebhook(signedUnrelatedWebhook());

        assertThat(event.signatureValid()).isTrue();
        assertThat(event.payment().status()).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    void unparsableBodyIsAParseError() {
        WebhookRequest garbage = new WebhookRequest("not json".getBytes(StandardCharsets.UTF_8), Map.of());

        assertThatThrownBy(() -> provider().parseWebhook(garbage)).isInstanceOf(WebhookParseException.class);
    }

    @Test
    void unknownReferenceIsEmptyNotAnError() {
        stubUnknownReference();

        assertThat(provider().fetchPayment(unknownReference())).isEmpty();
    }
}
