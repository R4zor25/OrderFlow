package com.orderflow.inventory;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

public final class TestJwts {

    private static final KeyPair KEYS = generate();

    private TestJwts() {
    }

    public static RSAPublicKey publicKey() {
        return (RSAPublicKey) KEYS.getPublic();
    }

    public static String customer(UUID customerId) {
        return sign(claims(Instant.now().plusSeconds(300), List.of("CUSTOMER"))
            .claim("customer_id", customerId.toString()).build());
    }

    public static String expiredCustomer(UUID customerId) {
        return sign(claims(Instant.now().minusSeconds(300), List.of("CUSTOMER"))
            .claim("customer_id", customerId.toString()).build());
    }

    public static String withRoles(String... roles) {
        return sign(claims(Instant.now().plusSeconds(300), List.of(roles)).build());
    }

    private static JWTClaimsSet.Builder claims(Instant expiresAt, List<String> roles) {
        return new JWTClaimsSet.Builder()
            .subject("test-" + UUID.randomUUID())
            .issueTime(Date.from(Instant.now().minusSeconds(5)))
            .expirationTime(Date.from(expiresAt))
            .claim("roles", roles);
    }

    private static String sign(JWTClaimsSet claims) {
        try {
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner(KEYS.getPrivate()));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Test token signing failed", exception);
        }
    }

    private static KeyPair generate() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
