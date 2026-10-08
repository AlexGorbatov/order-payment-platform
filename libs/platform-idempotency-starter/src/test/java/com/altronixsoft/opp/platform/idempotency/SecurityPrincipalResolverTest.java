package com.altronixsoft.opp.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class SecurityPrincipalResolverTest {

    private final SecurityPrincipalResolver resolver = new SecurityPrincipalResolver();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void noAuthenticationIsAnonymous() {
        assertThat(resolver.resolve()).isEqualTo("anonymous");
    }

    @Test
    void anAnonymousTokenIsAnonymous() {
        SecurityContextHolder.getContext()
                .setAuthentication(new AnonymousAuthenticationToken(
                        "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThat(resolver.resolve()).isEqualTo("anonymous");
    }

    @Test
    void anUnauthenticatedTokenIsAnonymous() {
        TestingAuthenticationToken token = new TestingAuthenticationToken("mallory", "pw");
        token.setAuthenticated(false);
        SecurityContextHolder.getContext().setAuthentication(token);

        assertThat(resolver.resolve()).isEqualTo("anonymous");
    }

    @Test
    void theSubjectOfAJwtIsThePrincipal() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("8f2c-user-sub")
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, List.of(), jwt.getSubject()));

        assertThat(resolver.resolve()).isEqualTo("8f2c-user-sub");
    }

    @Test
    void anyOtherAuthenticatedUserIsIdentifiedByName() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("alice", "pw", "ROLE_X"));

        assertThat(resolver.resolve()).isEqualTo("alice");
    }

    @Test
    void aNameLongerThanTheColumnIsReplacedByItsHash() {
        String longName = "n".repeat(300);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(longName, "pw", "ROLE_X"));

        String principal = resolver.resolve();

        assertThat(principal).startsWith("sha256:").hasSize("sha256:".length() + 64);
        assertThat(resolver.resolve()).as("stable").isEqualTo(principal);
    }
}
