package com.inventoryplatform.gateway.config;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects requests whose {@code Origin} or {@code Host} is not one we expect.
 *
 * <p>Binding to {@code 127.0.0.1} is not a security boundary on its own (ADR 0005). Any other
 * process on the machine can reach the port, and — more importantly — so can any web page the user
 * happens to visit: a page at evil.example can issue requests to {@code http://127.0.0.1:8080} from
 * the user's own browser. Checking the {@code Host} header alone does not help either, because DNS
 * rebinding lets an attacker's domain resolve to 127.0.0.1 and satisfy it.
 *
 * <p>So both are checked: {@code Host} must be a loopback name we recognise, and {@code Origin},
 * when present, must be loopback too. A same-origin fetch from the app itself sends either no
 * Origin or its own, and both pass.
 *
 * <p>This is not a substitute for authentication. It is what makes the absence of authentication
 * defensible for a single-user localhost install.
 */
public class OriginValidationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OriginValidationFilter.class);

    private static final Set<String> ALLOWED_HOSTNAMES = Set.of("127.0.0.1", "localhost", "[::1]");

    private final List<String> allowedOrigins;

    public OriginValidationFilter(List<String> allowedOrigins) {
        this.allowedOrigins = List.copyOf(allowedOrigins);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!isHostAllowed(request.getHeader("Host"))) {
            reject(response, "host", request.getHeader("Host"));
            return;
        }

        String origin = request.getHeader("Origin");
        if (origin != null && !isOriginAllowed(origin)) {
            reject(response, "origin", origin);
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isHostAllowed(String host) {
        if (host == null) {
            // HTTP/1.1 requires Host. Its absence is not a browser we should serve.
            return false;
        }
        String hostname = stripPort(host);
        return ALLOWED_HOSTNAMES.contains(hostname);
    }

    private boolean isOriginAllowed(String origin) {
        if (allowedOrigins.contains(origin)) {
            return true;
        }
        // Any loopback origin on any port: the dev server and the packaged app differ in port.
        String withoutScheme = origin.replaceFirst("^https?://", "");
        return ALLOWED_HOSTNAMES.contains(stripPort(withoutScheme));
    }

    private static String stripPort(String hostHeader) {
        if (hostHeader.startsWith("[")) {
            // IPv6 literal: [::1]:8080
            int close = hostHeader.indexOf(']');
            return close >= 0 ? hostHeader.substring(0, close + 1) : hostHeader;
        }
        int colon = hostHeader.indexOf(':');
        return colon >= 0 ? hostHeader.substring(0, colon) : hostHeader;
    }

    private void reject(HttpServletResponse response, String what, String value) throws IOException {
        log.warn("Rejected a request with an unexpected {}: {}", what, value);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/problem+json");
        response.getWriter().write(
                """
                {"type":"urn:problem:forbidden-origin",\
                "title":"Forbidden",\
                "status":403,\
                "detail":"This request did not come from the application."}\
                """);
    }
}
