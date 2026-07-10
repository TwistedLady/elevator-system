package pl.feelcodes.elevator.api.auth;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the real decoder wired by {@link JwtKeyConfig} — signature, issuer, audience and expiry
 * validators — instead of a mocked decoder, so a token issued by {@link TokenService} must actually
 * pass and every tampered/expired/wrong-claim variant must be rejected.
 */
class JwtValidationTest {

    private RSAKey key;
    private ReactiveJwtDecoder decoder;

    @BeforeEach
    void setUp() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        decoder = new JwtKeyConfig().jwtDecoder(key, props("elevator-api", "elevator"));
    }

    private static AuthProperties props(String issuer, String audience) {
        AuthProperties p = new AuthProperties();
        p.setIssuer(issuer);
        p.setAudience(audience);
        return p;
    }

    private String tokenSignedBy(RSAKey signingKey, String issuer, String audience, long ttlSeconds) throws Exception {
        AuthProperties p = props(issuer, audience);
        p.setTokenTtlSeconds(ttlSeconds);
        return new TokenService(signingKey, p).issue("rider-1");
    }

    private String validToken() throws Exception {
        return tokenSignedBy(key, "elevator-api", "elevator", 300);
    }

    @Test
    void accepts_a_well_formed_token_and_exposes_its_subject() throws Exception {
        Jwt jwt = decoder.decode(validToken()).block();
        assertThat(jwt).isNotNull();
        assertThat(jwt.getSubject()).isEqualTo("rider-1");
    }

    @Test
    void rejects_an_expired_token() throws Exception {
        String expired = tokenSignedBy(key, "elevator-api", "elevator", -300);
        assertThatThrownBy(() -> decoder.decode(expired).block()).isInstanceOf(JwtException.class);
    }

    @Test
    void rejects_a_wrong_issuer() throws Exception {
        String wrongIssuer = tokenSignedBy(key, "someone-else", "elevator", 300);
        assertThatThrownBy(() -> decoder.decode(wrongIssuer).block()).isInstanceOf(JwtException.class);
    }

    @Test
    void rejects_a_wrong_audience() throws Exception {
        String wrongAudience = tokenSignedBy(key, "elevator-api", "not-elevator", 300);
        assertThatThrownBy(() -> decoder.decode(wrongAudience).block()).isInstanceOf(JwtException.class);
    }

    @Test
    void rejects_a_token_signed_by_another_key() throws Exception {
        RSAKey attackerKey = new RSAKeyGenerator(2048).keyID("attacker").generate();
        String forged = tokenSignedBy(attackerKey, "elevator-api", "elevator", 300);
        assertThatThrownBy(() -> decoder.decode(forged).block()).isInstanceOf(JwtException.class);
    }

    @Test
    void rejects_a_tampered_token() throws Exception {
        String[] parts = validToken().split("\\.");
        String tamperedPayload = parts[1].substring(0, parts[1].length() - 2) + (parts[1].endsWith("A") ? "BB" : "AA");
        String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];
        assertThatThrownBy(() -> decoder.decode(tampered).block()).isInstanceOf(JwtException.class);
    }
}
