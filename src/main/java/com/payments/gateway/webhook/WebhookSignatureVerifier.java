package com.payments.gateway.webhook;

import com.payments.gateway.error.Exceptions.InvalidSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * HMAC-SHA256 webhook verification with replay protection.
 *
 * <p>Header: {@code t=<unix-seconds>,v1=<hex>[,v1=<hex>]}. Signed payload: {@code t + "." + rawBody}.
 * Verify the raw bytes as received, before parsing. Several {@code v1} values are accepted so the
 * secret can be rotated without downtime.
 */
@Component
public class WebhookSignatureVerifier {

    public static final String HEADER = "X-Gateway-Signature";
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;
    private final Clock clock;
    private final long toleranceSeconds;

    public WebhookSignatureVerifier(@Value("${payments.webhook.secret}") String secret,
                                    @Value("${payments.webhook.tolerance-seconds}") long toleranceSeconds,
                                    Clock clock) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("payments.webhook.secret must be configured");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.toleranceSeconds = toleranceSeconds;
        this.clock = clock;
    }

    public void verify(String rawBody, String header) {
        if (header == null || header.isBlank()) {
            throw new InvalidSignature("missing " + HEADER + " header");
        }
        String timestamp = null;
        List<String> candidates = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                throw new InvalidSignature("malformed header");
            }
            if ("t".equals(kv[0])) {
                timestamp = kv[1];
            } else if ("v1".equals(kv[0])) {
                candidates.add(kv[1].toLowerCase(Locale.ROOT));
            }
        }
        if (timestamp == null || candidates.isEmpty()) {
            throw new InvalidSignature("header must carry t= and v1=");
        }

        long signedAt;
        try {
            signedAt = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new InvalidSignature("timestamp is not numeric");
        }
        if (Math.abs(clock.instant().getEpochSecond() - signedAt) > toleranceSeconds) {
            throw new InvalidSignature("timestamp outside tolerance window");
        }

        byte[] expected = hmacHex(timestamp + "." + rawBody).getBytes(StandardCharsets.UTF_8);
        for (String candidate : candidates) {
            // MessageDigest.isEqual does not short-circuit, so timing does not reveal the match position.
            if (MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.UTF_8))) {
                return;
            }
        }
        throw new InvalidSignature("signature does not match");
    }

    /** Builds a header value for a body at the given time. Used by tests and sender simulators. */
    public String sign(String rawBody, long epochSeconds) {
        return "t=" + epochSeconds + ",v1=" + hmacHex(epochSeconds + "." + rawBody);
    }

    private String hmacHex(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }
}
