package com.payments.gateway.unit;

import com.payments.gateway.error.Exceptions.InvalidSignature;
import com.payments.gateway.webhook.WebhookSignatureVerifier;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebhookSignatureVerifierTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final String BODY = "{\"eventId\":\"e1\"}";

    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(
            "test-secret", 300, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void validSignatureIsAccepted() {
        String header = verifier.sign(BODY, NOW.getEpochSecond());
        assertDoesNotThrow(() -> verifier.verify(BODY, header));
    }

    @Test
    void tamperedBodyIsRejected() {
        String header = verifier.sign(BODY, NOW.getEpochSecond());
        assertThrows(InvalidSignature.class, () -> verifier.verify(BODY + " ", header));
    }

    @Test
    void signatureFromAnotherSecretIsRejected() {
        WebhookSignatureVerifier attacker = new WebhookSignatureVerifier(
                "other", 300, Clock.fixed(NOW, ZoneOffset.UTC));
        String header = attacker.sign(BODY, NOW.getEpochSecond());
        assertThrows(InvalidSignature.class, () -> verifier.verify(BODY, header));
    }

    @Test
    void replayOutsideToleranceWindowIsRejectedInBothDirections() {
        String stale = verifier.sign(BODY, NOW.getEpochSecond() - 301);
        String future = verifier.sign(BODY, NOW.getEpochSecond() + 301);
        assertThrows(InvalidSignature.class, () -> verifier.verify(BODY, stale));
        assertThrows(InvalidSignature.class, () -> verifier.verify(BODY, future));
    }

    @Test
    void edgeOfToleranceWindowIsAccepted() {
        String edge = verifier.sign(BODY, NOW.getEpochSecond() - 300);
        assertDoesNotThrow(() -> verifier.verify(BODY, edge));
    }

    @Test
    void missingOrMalformedHeadersAreRejected() {
        assertThrows(InvalidSignature.class, () -> verifier.verify(BODY, null));
        assertThrows(InvalidSignature.class, () -> verifier.verify(BODY, ""));
        assertThrows(InvalidSignature.class, () -> verifier.verify(BODY, "garbage"));
        assertThrows(InvalidSignature.class, () -> verifier.verify(BODY, "t=abc,v1=00"));
        assertThrows(InvalidSignature.class, () -> verifier.verify(BODY, "t=" + NOW.getEpochSecond()));
        assertThrows(InvalidSignature.class, () -> verifier.verify(BODY, "v1=00"));
    }

    @Test
    void secretRotationAcceptsAnyValidSignature() {
        String current = verifier.sign(BODY, NOW.getEpochSecond());
        String currentSig = current.substring(current.indexOf(",v1=") + 4);
        String rotated = "t=" + NOW.getEpochSecond() + ",v1=deadbeef,v1=" + currentSig;
        assertDoesNotThrow(() -> verifier.verify(BODY, rotated));
    }
}
