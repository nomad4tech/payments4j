package tech.nomad4.payments4j.paddle;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static tech.nomad4.payments4j.paddle.PaddleTestSupport.fixture;

class PaddleSignatureTest {

    private static final String SECRET = "pdl_ntfset_test_secret";
    private static final long TS = 1791686000L;
    private static final byte[] BODY = fixture("transaction-completed.json");
    private static final Instant NOW = Instant.ofEpochSecond(TS + 2);

    /** Reference computed with Node.js: HMAC-SHA256 over "ts:body" as the Paddle documentation describes. */
    private static final String NODE_SIGNATURE = "ce0488f4bb0f3ffbf9a1e04f30849f8740af68ebabf9163fc6be8cc9c52915f3";

    @Test
    void signatureMatchesNodeReference() {
        assertThat(PaddleSignature.sign(SECRET, TS, BODY)).isEqualTo(NODE_SIGNATURE);
    }

    @Test
    void acceptsValidSignature() {
        assertThat(PaddleSignature.verify(SECRET, BODY, "ts=" + TS + ";h1=" + NODE_SIGNATURE, 5, NOW)).isTrue();
    }

    @Test
    void acceptsAnyOfSeveralH1AfterSecretRotation() {
        assertThat(PaddleSignature.verify(SECRET, BODY, "ts=" + TS + ";h1=deadbeef;h1=" + NODE_SIGNATURE, 5, NOW)).isTrue();
    }

    @Test
    void rejectsTamperedBodyWrongSecretAndMalformedHeader() {
        String header = "ts=" + TS + ";h1=" + NODE_SIGNATURE;
        byte[] tampered = new String(BODY, StandardCharsets.UTF_8).replace("completed", "canceled")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(PaddleSignature.verify(SECRET, tampered, header, 5, NOW)).isFalse();
        assertThat(PaddleSignature.verify("other", BODY, header, 5, NOW)).isFalse();
        assertThat(PaddleSignature.verify(SECRET, BODY, null, 5, NOW)).isFalse();
        assertThat(PaddleSignature.verify(SECRET, BODY, "h1=" + NODE_SIGNATURE, 5, NOW)).isFalse();
        assertThat(PaddleSignature.verify(SECRET, BODY, "ts=abc;h1=" + NODE_SIGNATURE, 5, NOW)).isFalse();
    }

    @Test
    void rejectsTimestampBeyondTolerance() {
        String header = "ts=" + TS + ";h1=" + NODE_SIGNATURE;
        assertThat(PaddleSignature.verify(SECRET, BODY, header, 5, Instant.ofEpochSecond(TS + 6))).isFalse();
        assertThat(PaddleSignature.verify(SECRET, BODY, header, 5, Instant.ofEpochSecond(TS + 5))).isTrue();
        assertThat(PaddleSignature.verify(SECRET, BODY, header, 5, Instant.ofEpochSecond(TS - 6))).isFalse();
    }
}
