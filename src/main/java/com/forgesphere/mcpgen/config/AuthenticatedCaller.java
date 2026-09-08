package com.forgesphere.mcpgen.config;

import com.forge.security.authn.model.AuthnToken;
import com.forge.security.authn.security.ForgeAuthnAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Every controller here used to read "who is calling" straight off a client-supplied {@code
 * createdBy}/{@code updatedBy} field in the request body (or an {@code X-User-Email} header for
 * the mock endpoints) — trusted at face value, so any caller could attribute a project edit, a
 * deploy, or a rollback to any name they liked. Once a request is past {@link SecurityConfig}'s
 * gate, forge-auth-lib has already verified a real, signed token, and that token's own {@code
 * email} claim is exactly this same information, except a caller genuinely cannot forge it without
 * the identity provider's signing key.
 * <p>
 * Returns empty when there's no verified token on this request — forge.authn disabled (local dev),
 * or a request that reached the always-open mock-runtime/actuator/swagger paths — callers decide
 * their own fallback for that case rather than this class silently inventing one.
 */
public final class AuthenticatedCaller {

    private AuthenticatedCaller() {
    }

    private static Optional<AuthnToken> token() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof ForgeAuthnAuthenticationToken && auth.getDetails() instanceof AuthnToken authnToken) {
            return Optional.of(authnToken);
        }
        return Optional.empty();
    }

    public static Optional<String> email() {
        return token().map(t -> stringClaim(t, "email")).filter(e -> e != null && !e.isBlank());
    }

    /**
     * The verified token's own email always wins over whatever the caller sent — {@code
     * clientSuppliedFallbacks} (checked in order) is used only when there's no verified token at
     * all (forge.authn disabled locally), matching this service's existing behavior for that case.
     */
    public static String resolveActorEmail(String... clientSuppliedFallbacks) {
        return email().orElseGet(() -> {
            for (String candidate : clientSuppliedFallbacks) {
                if (candidate != null && !candidate.isBlank()) {
                    return candidate;
                }
            }
            return null;
        });
    }

    private static String stringClaim(AuthnToken token, String claimName) {
        Object value = token.getClaim(claimName);
        return value == null ? null : value.toString();
    }
}
