package pl.feelcodes.elevator.api.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The "small as hell" dev token issuer: signs a short-lived RS256 JWT vouching for a passenger
 * subject. Stands in for a real passenger login / IdP — the API validates these exactly as it would
 * a production token.
 */
@Service
public class TokenService {

    private final RSAKey rsaKey;
    private final AuthProperties props;

    TokenService(RSAKey rsaKey, AuthProperties props) {
        this.rsaKey = rsaKey;
        this.props = props;
    }

    /** Sign a JWT for {@code subject}; returns the compact serialization. */
    public String issue(String subject) throws Exception {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(props.getTokenTtlSeconds());

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(props.getIssuer())
                .audience(List.of(props.getAudience()))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .build();

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(rsaKey.getKeyID())
                .build();

        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new RSASSASigner(rsaKey.toPrivateKey()));
        return jwt.serialize();
    }
}
