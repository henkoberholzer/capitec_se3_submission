package za.co.capitec.sds.management.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceTest {

    private static final String CROCKFORD_ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz";
    private static final int TOKEN_LENGTH = 8;

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService();
        ReflectionTestUtils.setField(tokenService, "tokenLength", TOKEN_LENGTH);
    }

    @Test
    void generateToken_hasCorrectLength() {
        assertThat(tokenService.generateToken()).hasSize(TOKEN_LENGTH);
    }

    @Test
    void generateToken_respectsConfigurableLength() {
        ReflectionTestUtils.setField(tokenService, "tokenLength", 10);
        assertThat(tokenService.generateToken()).hasSize(10);

        ReflectionTestUtils.setField(tokenService, "tokenLength", 6);
        assertThat(tokenService.generateToken()).hasSize(6);
    }

    @Test
    void generateToken_containsOnlyCrockfordCharacters() {
        String token = tokenService.generateToken();
        for (char c : token.toCharArray()) {
            assertThat(CROCKFORD_ALPHABET).contains(String.valueOf(c));
        }
    }

    @RepeatedTest(100)
    void generateToken_alwaysLowerCase() {
        String token = tokenService.generateToken();
        assertThat(token).isEqualTo(token.toLowerCase());
    }

    @Test
    void generateToken_producesUniqueValues() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            tokens.add(tokenService.generateToken());
        }
        // Statistically near-impossible to collide in 1000 draws from 2^40 space
        assertThat(tokens).hasSize(1000);
    }

    @Test
    void hashToken_producesHexString() {
        String hash = tokenService.hashToken("ABC12345");
        assertThat(hash).matches("[0-9a-f]{64}");
    }

    @Test
    void hashToken_isCaseInsensitive() {
        assertThat(tokenService.hashToken("abc12345"))
                .isEqualTo(tokenService.hashToken("ABC12345"));
    }

    @Test
    void hashToken_isDeterministic() {
        String token = "XYZ98765";
        assertThat(tokenService.hashToken(token))
                .isEqualTo(tokenService.hashToken(token));
    }

    @Test
    void hashToken_differentTokensProduceDifferentHashes() {
        assertThat(tokenService.hashToken("AAAAAAAA"))
                .isNotEqualTo(tokenService.hashToken("BBBBBBBB"));
    }

    @Test
    void normalise_uppercasesInput() {
        assertThat(tokenService.normalise("abc12345")).isEqualTo("ABC12345");
    }

    @Test
    void normalise_idempotentOnUpperCase() {
        assertThat(tokenService.normalise("ABC12345")).isEqualTo("ABC12345");
    }
}
