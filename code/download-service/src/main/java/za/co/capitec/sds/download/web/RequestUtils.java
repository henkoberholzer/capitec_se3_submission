package za.co.capitec.sds.download.web;

import jakarta.servlet.http.HttpServletRequest;

final class RequestUtils {

    private RequestUtils() {
    }

    static String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
