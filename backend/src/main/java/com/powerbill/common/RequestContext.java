package com.powerbill.common;

import jakarta.servlet.http.HttpServletRequest;

public record RequestContext(String correlationId, String remoteAddress, String userAgent) {
    public static RequestContext from(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null && userAgent.length() > 255) {
            userAgent = userAgent.substring(0, 255);
        }
        return new RequestContext((String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE),
                request.getRemoteAddr(), userAgent);
    }
}
