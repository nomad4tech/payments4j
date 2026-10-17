package tech.nomad4.payments4j.nowpayments;

import tools.jackson.core.JacksonException;
import tools.jackson.core.io.JsonStringEncoder;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * NOWPayments IPN signature: JSON body, keys sorted recursively, compact {@code JSON.stringify}, HMAC-SHA512
 * keyed with the IPN secret, hex.
 *
 * <p>The canonical string must match the JavaScript output byte for byte, so the JSON is written by hand:
 * Jackson prints {@code 20.0} where JavaScript prints {@code 20}.
 */
final class IpnSignature {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private IpnSignature() {
    }

    static boolean verify(String secret, byte[] body, String receivedHex) {
        if (receivedHex == null || receivedHex.isBlank()) {
            return false;
        }
        String expected = sign(secret, body);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                receivedHex.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }

    static String sign(String secret, byte[] body) {
        try {
            JsonNode tree = MAPPER.readTree(body);
            String canonical = canonicalJson(tree);
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (JacksonException | GeneralSecurityException e) {
            throw new IllegalArgumentException("Cannot compute IPN signature", e);
        }
    }

    static String canonicalJson(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        write(node, sb);
        return sb.toString();
    }

    private static void write(JsonNode node, StringBuilder sb) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>(node.propertyNames());
            // String.compareTo compares by UTF-16 code units, like Array.prototype.sort in JavaScript
            Collections.sort(names);
            sb.append('{');
            boolean first = true;
            for (String name : names) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(name, sb);
                sb.append(':');
                write(node.get(name), sb);
            }
            sb.append('}');
        } else if (node.isArray()) {
            sb.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(node.get(i), sb);
            }
            sb.append(']');
        } else if (node.isString()) {
            quote(node.stringValue(), sb);
        } else if (node.isNumber()) {
            sb.append(jsNumber(node.decimalValue()));
        } else if (node.isBoolean()) {
            sb.append(node.booleanValue());
        } else {
            sb.append("null");
        }
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        JsonStringEncoder.getInstance().quoteAsString(s, sb);
        sb.append('"');
    }

    /** ECMAScript Number::toString: through double, shortest representation. */
    static String jsNumber(BigDecimal value) {
        double d = value.doubleValue();
        if (d == 0) {
            return "0";
        }
        boolean negative = d < 0;
        BigDecimal s = new BigDecimal(Double.toString(Math.abs(d))).stripTrailingZeros();
        String digits = s.unscaledValue().toString();
        int k = digits.length();
        int n = k - s.scale();

        StringBuilder out = new StringBuilder();
        if (negative) {
            out.append('-');
        }
        if (k <= n && n <= 21) {
            out.append(digits).append("0".repeat(n - k));
        } else if (0 < n && n <= 21) {
            out.append(digits, 0, n).append('.').append(digits, n, k);
        } else if (-6 < n && n <= 0) {
            out.append("0.").append("0".repeat(-n)).append(digits);
        } else {
            int e = n - 1;
            out.append(digits.charAt(0));
            if (k > 1) {
                out.append('.').append(digits, 1, k);
            }
            out.append('e').append(e < 0 ? '-' : '+').append(Math.abs(e));
        }
        return out.toString();
    }
}
