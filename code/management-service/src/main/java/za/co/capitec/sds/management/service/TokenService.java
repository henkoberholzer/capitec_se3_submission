package za.co.capitec.sds.management.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

@Service
public class TokenService {

    private static final String CROCKFORD_ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz";
    private static final int BITS_PER_CHAR = 5;
    private static final int MIN_TOKEN_LENGTH = 6;
    private static final int MAX_TOKEN_LENGTH = 12;

    @Value("${sds.token.length:8}")
    private int tokenLength;

    private final SecureRandom secureRandom = new SecureRandom();

    public String generateToken() {
        validateTokenLength();
        int bytesNeeded = (tokenLength * BITS_PER_CHAR + 7) / 8;
        byte[] bytes = new byte[bytesNeeded];
        secureRandom.nextBytes(bytes);

        // Pack bytes into a long for 5-bit extraction
        long value = 0;
        for (byte b : bytes) {
            value = (value << 8) | (b & 0xFF);
        }
        // Mask to the bits we actually use
        long mask = (1L << (tokenLength * BITS_PER_CHAR)) - 1;
        value &= mask;

        char[] token = new char[tokenLength];
        for (int i = tokenLength - 1; i >= 0; i--) {
            token[i] = CROCKFORD_ALPHABET.charAt((int) (value & 0x1F));
            value >>= BITS_PER_CHAR;
        }
        return new String(token);
    }

    private void validateTokenLength() {
        if (tokenLength < MIN_TOKEN_LENGTH || tokenLength > MAX_TOKEN_LENGTH) {
            throw new IllegalStateException(
                String.format("Token length must be between %d and %d characters (configured: %d)",
                    MIN_TOKEN_LENGTH, MAX_TOKEN_LENGTH, tokenLength));
        }
    }

    public String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.toUpperCase().getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public String normalise(String token) {
        return token.toUpperCase();
    }
}
