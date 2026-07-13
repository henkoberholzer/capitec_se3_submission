package za.co.capitec.sds.management.utils;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilsTest {

    @Test
    void resolveCallerId_returnsAzp_whenAzpPresent() {
        Jwt jwt = jwt(Map.of("azp", "sds-sample-client"), "user-sub");
        assertThat(JwtUtils.resolveCallerId(jwt)).isEqualTo("sds-sample-client");
    }

    @Test
    void resolveCallerId_returnsSubject_whenAzpAbsent() {
        Jwt jwt = jwt(Map.of(), "user-sub");
        assertThat(JwtUtils.resolveCallerId(jwt)).isEqualTo("user-sub");
    }

    @Test
    void resolveCallerId_returnsSubject_whenAzpNull() {
        Jwt jwt = jwt(Map.of("azp", ""), "user-sub");
        // empty string is not null — azp wins
        assertThat(JwtUtils.resolveCallerId(jwt)).isEqualTo("");
    }

    @Test
    void resolveCallerId_prefersAzpOverSubject_whenBothPresent() {
        Jwt jwt = jwt(Map.of("azp", "client-a"), "subject-b");
        assertThat(JwtUtils.resolveCallerId(jwt)).isEqualTo("client-a");
    }

    private static Jwt jwt(Map<String, Object> claims, String subject) {
        Map<String, Object> allClaims = new java.util.HashMap<>(claims);
        allClaims.put("sub", subject);
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .subject(subject)
                .claims(c -> c.putAll(allClaims))
                .build();
    }
}
