package tech.nomad4.payments4j;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderEnvironmentTest {

    @Test
    void parsesBothEnvironmentsIgnoringCase() {
        assertThat(ProviderEnvironment.parse("Sandbox")).isEqualTo(ProviderEnvironment.SANDBOX);
        assertThat(ProviderEnvironment.parse(" PRODUCTION ")).isEqualTo(ProviderEnvironment.PRODUCTION);
    }

    @Test
    void rejectsAnythingElse() {
        assertThatThrownBy(() -> ProviderEnvironment.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProviderEnvironment.parse("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProviderEnvironment.parse("prod")).isInstanceOf(IllegalArgumentException.class);
    }
}
