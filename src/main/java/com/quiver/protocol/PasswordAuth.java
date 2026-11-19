package com.quiver.protocol;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public final class PasswordAuth {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int KEY_DERIVATION_ITERATIONS = 120_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordAuth() {
    }

    public static byte[] newNonce() {
        byte[] nonce = new byte[32];
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    public static byte[] newSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return salt;
    }

    public static void validatePassword(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("password must not be empty");
        }
    }

    public static byte[] deriveKey(String password, byte[] salt) {
        validatePassword(password);
        char[] passwordChars = password.toCharArray();
        PBEKeySpec keySpec = new PBEKeySpec(passwordChars, salt, KEY_DERIVATION_ITERATIONS, 256);
        Arrays.fill(passwordChars, '\0');
        try {
            return SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM)
                    .generateSecret(keySpec)
                    .getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2-HMAC-SHA256 is unavailable", e);
        } finally {
            keySpec.clearPassword();
        }
    }

    public static String createProof(byte[] passwordKey, byte[] nonce) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(passwordKey, HMAC_ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(nonce));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    public static boolean verifyProof(byte[] passwordKey, byte[] nonce, String proof) {
        if (proof == null) {
            return false;
        }
        byte[] expected = createProof(passwordKey, nonce).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = proof.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }
}
