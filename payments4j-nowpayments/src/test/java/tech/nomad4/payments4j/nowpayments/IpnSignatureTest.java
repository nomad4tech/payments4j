package tech.nomad4.payments4j.nowpayments;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static tech.nomad4.payments4j.nowpayments.NowPaymentsTestSupport.fixture;

class IpnSignatureTest {

    private static final String SECRET = "test-ipn-secret";

    /** Reference computed with Node.js from the official NOWPayments example (sortObject + JSON.stringify). */
    private static final String NODE_SIGNATURE =
            "e1ec89a58035e1685eeae52c96c7dc85f97388f761d092db12fe148998d50b470281dd738bf14b82bba756a08daca66003296bafcdca72bebb81265a4b8cc7bd";

    @Test
    void signatureMatchesNodeReference() {
        assertThat(IpnSignature.sign(SECRET, fixture())).isEqualTo(NODE_SIGNATURE);
    }

    @Test
    void verifyAcceptsValidAndRejectsTampered() {
        byte[] body = fixture();
        assertThat(IpnSignature.verify(SECRET, body, NODE_SIGNATURE)).isTrue();
        assertThat(IpnSignature.verify(SECRET, body, NODE_SIGNATURE.toUpperCase())).isTrue();

        byte[] tampered = new String(body, StandardCharsets.UTF_8)
                .replace("\"finished\"", "\"waiting\"").getBytes(StandardCharsets.UTF_8);
        assertThat(IpnSignature.verify(SECRET, tampered, NODE_SIGNATURE)).isFalse();
        assertThat(IpnSignature.verify("other-secret", body, NODE_SIGNATURE)).isFalse();
        assertThat(IpnSignature.verify(SECRET, body, null)).isFalse();
        assertThat(IpnSignature.verify(SECRET, body, "")).isFalse();
    }

    @Test
    void numbersFollowJsFormatting() {
        assertThat(IpnSignature.jsNumber(new BigDecimal("20.0"))).isEqualTo("20");
        assertThat(IpnSignature.jsNumber(new BigDecimal("0.00"))).isEqualTo("0");
        assertThat(IpnSignature.jsNumber(new BigDecimal("19.957377"))).isEqualTo("19.957377");
        assertThat(IpnSignature.jsNumber(new BigDecimal("0.0000001"))).isEqualTo("1e-7");
        assertThat(IpnSignature.jsNumber(new BigDecimal("0.000001"))).isEqualTo("0.000001");
        assertThat(IpnSignature.jsNumber(new BigDecimal("1e21"))).isEqualTo("1e+21");
        assertThat(IpnSignature.jsNumber(new BigDecimal("1e20"))).isEqualTo("100000000000000000000");
        assertThat(IpnSignature.jsNumber(new BigDecimal("-1.50"))).isEqualTo("-1.5");
        assertThat(IpnSignature.jsNumber(new BigDecimal("1.5e25"))).isEqualTo("1.5e+25");
    }
}
