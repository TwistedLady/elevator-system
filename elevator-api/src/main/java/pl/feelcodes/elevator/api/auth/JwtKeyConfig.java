package pl.feelcodes.elevator.api.auth;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * The RSA signing key for passenger JWTs. Generated once at startup: the private half signs tokens
 * in {@link TokenService}, the public half validates them here and is published at /oauth2/jwks.
 * <p>
 * The key lives in-process, so multiple api replicas would each hold a different key and reject
 * each other's tokens. For a multi-replica cluster, mount a fixed key as a Secret instead
 * (single-replica demo works as-is).
 */
@Configuration
class JwtKeyConfig {

    @Bean
    RSAKey rsaKey() throws Exception {
        return new RSAKeyGenerator(2048)
                .keyID("elevator-signing-key")
                .generate();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(RSAKey rsaKey, AuthProperties props) throws Exception {
        RSAPublicKey publicKey = rsaKey.toRSAPublicKey();
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withPublicKey(publicKey).build();

        OAuth2TokenValidator<Jwt> audience =
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(props.getAudience()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(props.getIssuer()), audience));
        return decoder;
    }
}
