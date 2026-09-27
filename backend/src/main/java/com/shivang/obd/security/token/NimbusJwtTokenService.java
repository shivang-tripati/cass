package com.shivang.obd.security.token;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.shivang.obd.security.AuthenticatedUser;
import java.time.Instant;
import javax.crypto.SecretKey;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

@Service
public class NimbusJwtTokenService implements TokenService {

    private final JwtProperties properties;
    private final SecretKey secretKey;
    private final JwtEncoder encoder;

    public NimbusJwtTokenService(JwtProperties properties) {
        if (properties.secretBytes().length < 32) {
            throw new IllegalStateException(
                "obd.security.jwt.secret must be at least 32 characters for HMAC-SHA256");
        }
        this.properties = properties;
        this.secretKey = new javax.crypto.spec.SecretKeySpec(
            properties.secretBytes(), "HMACSHA256");
        OctetSequenceKey jwk = new OctetSequenceKey.Builder(secretKey)
            .algorithm(com.nimbusds.jose.JWSAlgorithm.HS256)
            .build();
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    }

    @Override
    public IssuedToken issueAccessToken(AuthenticatedUser user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(properties.accessTokenExpirationSeconds());
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .subject(user.userId().toString())
            .issuer(properties.issuer())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .claim("email", user.email())
            .claim("sid", user.sessionId() == null ? null : user.sessionId().toString())
            .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256)
            .type("JWT")
            .build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, properties.accessTokenExpirationSeconds());
    }
}
