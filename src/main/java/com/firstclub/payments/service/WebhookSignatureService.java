package com.firstclub.payments.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Signs and verifies webhook payloads using HMAC-SHA256.
 *
 * <p>The gateway places {@code hex(hmac(payload, secret))} in the
 * {@code X-Signature} HTTP header.  Receivers call {@link #verify} before
 * processing any inbound event.
 */
@Service
public class WebhookSignatureService {

    private static final String ALGORITHM = "HmacSHA256";

    @Value("${payments.webhook.secret}")
    private String secret;

    /**
     * Computes HMAC-SHA256 of {@code payload} using the configured secret and
     * returns the lower-case hex-encoded digest.
     */
    public String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(raw);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /**
     * Returns {@code true} if {@code signature} matches {@link #sign(String) sign(payload)}.
     *
     * <p>Comparison uses {@link MessageDigest#isEqual} on the raw HMAC bytes so
     * that the result is computed in constant time, preventing timing-oracle attacks
     * that would otherwise allow an attacker to iteratively discover the correct digest.
     */
    public boolean verify(String payload, String signature) {
        if (payload == null || signature == null) {
            return false;
        }
        byte[] expected = signRaw(payload);
        byte[] provided  = hexToBytes(signature.toLowerCase(java.util.Locale.ROOT));
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(expected, provided);
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    /** Computes HMAC-SHA256 and returns the raw bytes (not hex-encoded). */
    private byte[] signRaw(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Decodes a lower-case hex string to bytes.
     * Returns {@code null} if the input is not valid hex or has an odd length.
     */
    private static byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) {
            return null;
        }
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi == -1 || lo == -1) {
                return null;
            }
            result[i / 2] = (byte) ((hi << 4) | lo);
        }
        return result;
    }
}
