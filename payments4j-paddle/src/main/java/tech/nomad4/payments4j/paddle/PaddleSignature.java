package tech.nomad4.payments4j.paddle;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Header {@code Paddle-Signature: ts=<unix>;h1=<hex>}. The signed string is {@code ts + ":" + raw body},
 * HMAC-SHA256, keyed with the destination secret as is. A rotated secret yields several {@code h1} values:
 * any of them may match.
 */
final class PaddleSignature {

    private PaddleSignature() {
    }

    static boolean verify(String secret, byte[] body, String header, long toleranceSeconds, Instant now) {
        if (header == null || header.isBlank() || secret == null || secret.isBlank()) {
            return false;
        }
        Long ts = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("ts")) {
                try {
                    ts = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("h1")) {
                signatures.add(kv[1].toLowerCase());
            }
        }
        if (ts == null || signatures.isEmpty()) {
            return false;
        }
        if (Math.abs(now.getEpochSecond() - ts) > toleranceSeconds) {
            return false;
        }
        byte[] expected = sign(secret, ts, body).getBytes(StandardCharsets.US_ASCII);
        for (String candidate : signatures) {
            if (MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }

    static String sign(String secret, long ts, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((ts + ":").getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
