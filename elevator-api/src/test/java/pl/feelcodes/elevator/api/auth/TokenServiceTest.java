package pl.feelcodes.elevator.api.auth;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceTest {

    private RSAKey key;
    private TokenService service;

    @BeforeEach
    void setUp() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        AuthProperties props = new AuthProperties();
        props.setIssuer("elevator-api");
        props.setAudience("elevator");
        props.setTokenTtlSeconds(300);
        service = new TokenService(key, props);
    }

    @Test
    void issued_token_is_signed_and_carries_the_expected_claims() throws Exception {
        String token = service.issue("rider-7");

        SignedJWT jwt = SignedJWT.parse(token);
        assertThat(jwt.verify(new RSASSAVerifier(key.toRSAPublicKey()))).isTrue();

        var claims = jwt.getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo("rider-7");
        assertThat(claims.getIssuer()).isEqualTo("elevator-api");
        assertThat(claims.getAudience()).containsExactly("elevator");
        assertThat(claims.getExpirationTime()).isAfter(new Date());
    }

    @Test
    void token_expiry_honours_the_configured_ttl() throws Exception {
        var claims = SignedJWT.parse(service.issue("rider-7")).getJWTClaimsSet();
        long ttlMillis = claims.getExpirationTime().getTime() - claims.getIssueTime().getTime();
        assertThat(ttlMillis).isEqualTo(300_000L);
    }
}
