package tech.nomad4.payments4j.paddle;

import org.junit.jupiter.api.Test;
import tech.nomad4.payments4j.ProviderEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaddleConfigTest {

    @Test
    void environmentSelectsBaseUrl() {
        assertThat(PaddleConfig.of(ProviderEnvironment.SANDBOX, "pdl_sdbx_k", "s").baseUrl())
                .isEqualTo(PaddleConfig.SANDBOX_BASE_URL);
        assertThat(PaddleConfig.of(ProviderEnvironment.PRODUCTION, "pdl_live_k", "s").baseUrl())
                .isEqualTo(PaddleConfig.PRODUCTION_BASE_URL);
    }

    @Test
    void explicitBaseUrlWins() {
        assertThat(PaddleConfig.of(ProviderEnvironment.SANDBOX, "pdl_sdbx_k", "s").withBaseUrl("http://localhost:1").baseUrl())
                .isEqualTo("http://localhost:1");
    }

    @Test
    void productionRefusesASandboxKeyAndSandboxRefusesALiveKey() {
        assertThatThrownBy(() -> PaddleConfig.of(ProviderEnvironment.PRODUCTION, "pdl_sdbx_apikey_x", "s"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sandbox key");
        assertThatThrownBy(() -> PaddleConfig.of(ProviderEnvironment.SANDBOX, "pdl_live_apikey_x", "s"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("live key");
    }

    @Test
    void blankKeyAndBlankWebhookSecretAreRefused() {
        assertThatThrownBy(() -> PaddleConfig.of(ProviderEnvironment.SANDBOX, " ", "s"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaddleConfig.of(ProviderEnvironment.SANDBOX, "pdl_sdbx_k", ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("webhook secret");
    }

    @Test
    void toStringNeverContainsSecrets() {
        PaddleConfig config = PaddleConfig.of(ProviderEnvironment.SANDBOX, "pdl_sdbx_apikey_TOPSECRET", "pdl_ntfset_HIDDEN");
        assertThat(config.toString()).doesNotContain("TOPSECRET", "HIDDEN");
    }
}
