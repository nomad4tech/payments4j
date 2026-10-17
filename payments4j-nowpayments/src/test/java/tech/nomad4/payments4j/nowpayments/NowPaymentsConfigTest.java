package tech.nomad4.payments4j.nowpayments;

import org.junit.jupiter.api.Test;
import tech.nomad4.payments4j.ProviderEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NowPaymentsConfigTest {

    @Test
    void environmentSelectsBaseUrl() {
        assertThat(NowPaymentsConfig.of(ProviderEnvironment.SANDBOX, "k", "s").baseUrl())
                .isEqualTo(NowPaymentsConfig.SANDBOX_BASE_URL);
        assertThat(NowPaymentsConfig.of(ProviderEnvironment.PRODUCTION, "k", "s").baseUrl())
                .isEqualTo(NowPaymentsConfig.PRODUCTION_BASE_URL);
    }

    @Test
    void blankPayCurrencyMeansTheCustomerPicks() {
        assertThat(NowPaymentsConfig.of(ProviderEnvironment.SANDBOX, "k", "s").withPayCurrency(" ").payCurrency()).isNull();
    }

    @Test
    void blankKeyAndBlankIpnSecretAreRefused() {
        assertThatThrownBy(() -> NowPaymentsConfig.of(ProviderEnvironment.SANDBOX, "", "s"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NowPaymentsConfig.of(ProviderEnvironment.SANDBOX, "k", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("IPN secret");
    }

    @Test
    void toStringNeverContainsSecrets() {
        assertThat(NowPaymentsConfig.of(ProviderEnvironment.SANDBOX, "KEY-TOPSECRET", "IPN-HIDDEN").toString())
                .doesNotContain("TOPSECRET", "HIDDEN");
    }
}
