package com.altronixsoft.opp.order.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * What the decoder of {@link SecurityConfiguration} accepts, with a local key standing in for Keycloak's: the validators
 * are the production ones, only the key source differs.
 */
class TokenValidationTest {

    private static final String ISSUER = "http://localhost:8180/realms/opp";
    private static final String AUDIENCE = "order-service";

    private static KeyPair keys;
    private static JwtDecoder decoder;

    @BeforeAll
    static void keys() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        nimbus.setJwtValidator(SecurityConfiguration.tokenValidator(ISSUER, AUDIENCE));
        decoder = nimbus;
    }

    private static JWTClaimsSet.Builder validClaims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("00000000-0000-4000-8000-000000000001")
                .audience(List.of(AUDIENCE, "payment-service"))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("realm_access", Map.of("roles", List.of("customer", "offline_access")));
    }

    private static String sign(JWTClaimsSet claims) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(keys.getPrivate()));
        return jwt.serialize();
    }

    @Test
    void acceptsATokenForThisServiceFromTheRealm() throws JOSEException {
        Jwt jwt = decoder.decode(sign(validClaims().build()));

        assertThat(jwt.getSubject()).isEqualTo("00000000-0000-4000-8000-000000000001");
    }

    @Test
    void rejectsAnExpiredToken() throws JOSEException {
        Instant longAgo = Instant.now().minusSeconds(3600);
        JWTClaimsSet claims = validClaims().expirationTime(Date.from(longAgo)).build();

        assertThatThrownBy(() -> decoder.decode(sign(claims))).isInstanceOf(BadJwtException.class);
    }

    @Test
    void rejectsATokenThatIsNotValidYet() throws JOSEException {
        JWTClaimsSet claims = validClaims()
                .notBeforeTime(Date.from(Instant.now().plusSeconds(3600)))
                .expirationTime(Date.from(Instant.now().plusSeconds(7200)))
                .build();

        assertThatThrownBy(() -> decoder.decode(sign(claims))).isInstanceOf(BadJwtException.class);
    }

    @Test
    void rejectsAnotherIssuer() throws JOSEException {
        JWTClaimsSet claims =
                validClaims().issuer("http://evil.example/realms/opp").build();

        assertThatThrownBy(() -> decoder.decode(sign(claims))).isInstanceOf(BadJwtException.class);
    }

    @Test
    void rejectsATokenWithoutAnIssuer() throws JOSEException {
        JWTClaimsSet claims = validClaims().issuer(null).build();

        assertThatThrownBy(() -> decoder.decode(sign(claims))).isInstanceOf(BadJwtException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment-service", "account", "order-service-2", "ORDER-SERVICE"})
    void rejectsATokenForAnotherAudience(String audience) throws JOSEException {
        JWTClaimsSet claims = validClaims().audience(audience).build();

        assertThatThrownBy(() -> decoder.decode(sign(claims)))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("not issued for this service");
    }

    @ParameterizedTest
    @NullSource
    void rejectsATokenWithoutAnAudience(String none) throws JOSEException {
        JWTClaimsSet claims = validClaims().audience(none).build();

        assertThatThrownBy(() -> decoder.decode(sign(claims))).isInstanceOf(BadJwtException.class);
    }

    @Test
    void rejectsATokenSignedWithAnotherKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        SignedJWT jwt =
                new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), validClaims().build());
        jwt.sign(new RSASSASigner(generator.generateKeyPair().getPrivate()));

        assertThatThrownBy(() -> decoder.decode(jwt.serialize())).isInstanceOf(BadJwtException.class);
    }

    @Test
    void rejectsAnUnsignedToken() {
        String header =
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes());
        String payload = java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(("{\"iss\":\"" + ISSUER + "\",\"aud\":\"" + AUDIENCE + "\"}").getBytes());

        assertThatThrownBy(() -> decoder.decode(header + "." + payload + ".")).isInstanceOf(BadJwtException.class);
    }

    @Test
    void rejectsATokenSignedWithAnotherAlgorithm() throws Exception {
        // HS256 keyed with the public key: the classic algorithm-confusion attack
        byte[] secret = new byte[64];
        java.util.Arrays.fill(secret, (byte) 7);
        SignedJWT jwt =
                new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), validClaims().build());
        jwt.sign(new com.nimbusds.jose.crypto.MACSigner(secret));

        assertThatThrownBy(() -> decoder.decode(jwt.serialize())).isInstanceOf(BadJwtException.class);
    }

    // ---- realm roles ----

    private static List<String> rolesOf(Object realmAccess) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").subject("s");
        if (realmAccess != null) {
            builder.claim("realm_access", realmAccess);
        } else {
            builder.claim("other", "x");
        }
        return new RealmRolesConverter()
                .convert(builder.build()).stream()
                        .map(GrantedAuthority::getAuthority)
                        .toList();
    }

    @Test
    void mapsRealmRolesToRoleAuthorities() {
        assertThat(rolesOf(Map.of("roles", List.of("customer", "admin", "ops"))))
                .containsExactlyInAnyOrder("ROLE_CUSTOMER", "ROLE_ADMIN", "ROLE_OPS");
    }

    @Test
    void ignoresRealmRolesThatMeanNothingHere() {
        assertThat(rolesOf(
                        Map.of("roles", List.of("offline_access", "uma_authorization", "default-roles-opp", "Admin"))))
                .isEmpty();
    }

    @Test
    void aMissingOrMalformedClaimGrantsNothing() {
        assertThat(rolesOf(null)).isEmpty();
        assertThat(rolesOf("admin")).isEmpty();
        assertThat(rolesOf(Map.of("roles", "admin"))).isEmpty();
        assertThat(rolesOf(Map.of("other", List.of("admin")))).isEmpty();
        assertThat(rolesOf(Map.of("roles", List.of(1, "customer")))).containsExactly("ROLE_CUSTOMER");
    }

    @Test
    void roleClaimsOutsideRealmAccessAreNotTrusted() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("s")
                .claim("roles", List.of("admin"))
                .claim("resource_access", Map.of("opp-web", Map.of("roles", List.of("admin"))))
                .claim("scope", "admin")
                .build();

        assertThat(new RealmRolesConverter().convert(jwt)).isEmpty();
        assertThat(ReflectionTestUtils.getField(RealmRolesConverter.class, "KNOWN_ROLES"))
                .isEqualTo(java.util.Set.of("customer", "admin", "ops"));
    }
}
