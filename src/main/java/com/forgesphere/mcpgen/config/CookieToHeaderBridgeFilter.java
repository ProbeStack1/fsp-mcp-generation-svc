package com.forgesphere.mcpgen.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Bridges the UI's real session credential — the HttpOnly {@code ps_auth_token}
 * cookie — into the Authorization header forge-auth-lib's own, unmodified
 * {@code ForgeAuthnAuthenticationFilter} already expects. See SecurityConfig's
 * own javadoc for why this bridging has to happen server-side at all.
 * <p>
 * This filter deliberately does NOT verify the token itself — that stays
 * forge-auth-lib's job, unchanged, in the filter this runs immediately
 * before (see SecurityConfig). Its only responsibility is moving the raw JWT
 * from one place a servlet request carries it to another; if the token is
 * missing, expired, or forged, the library's own filter rejects it exactly
 * as it always has for a header-based caller.
 */
public class CookieToHeaderBridgeFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final String cookieName;

    public CookieToHeaderBridgeFilter(String cookieName) {
        this.cookieName = cookieName;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        HttpServletRequest effectiveRequest = request;
        if (request.getHeader(AUTHORIZATION_HEADER) == null) {
            String token = readCookie(request, cookieName);
            if (token != null && !token.isBlank()) {
                effectiveRequest = new AuthorizationHeaderRequest(request, BEARER_PREFIX + token.trim());
            }
        }
        filterChain.doFilter(effectiveRequest, response);
    }

    private String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /** Makes exactly one synthetic header — Authorization — visible on top of the real request, without touching anything else the servlet container already parsed. */
    private static final class AuthorizationHeaderRequest extends HttpServletRequestWrapper {
        private final String authorizationValue;

        AuthorizationHeaderRequest(HttpServletRequest request, String authorizationValue) {
            super(request);
            this.authorizationValue = authorizationValue;
        }

        @Override
        public String getHeader(String name) {
            if (AUTHORIZATION_HEADER.equalsIgnoreCase(name)) {
                return authorizationValue;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (AUTHORIZATION_HEADER.equalsIgnoreCase(name)) {
                return Collections.enumeration(Collections.singletonList(authorizationValue));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            if (names.stream().noneMatch(AUTHORIZATION_HEADER::equalsIgnoreCase)) {
                names.add(AUTHORIZATION_HEADER);
            }
            return Collections.enumeration(names);
        }
    }
}
