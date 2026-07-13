package za.co.capitec.sds.management.utils;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Arrays;

public final class JwtUtils {

    private JwtUtils() {
    }

    public static String resolveCallerId(Jwt jwt) {
        String azp = jwt.getClaimAsString("azp");
        return azp != null ? azp : jwt.getSubject();
    }

    public static boolean hasScope(Jwt jwt, String scope) {
        String scopes = jwt.getClaimAsString("scope");
        if (scopes == null) {
            return false;
        }
        return Arrays.asList(scopes.split("\\s+")).contains(scope);
    }
}
