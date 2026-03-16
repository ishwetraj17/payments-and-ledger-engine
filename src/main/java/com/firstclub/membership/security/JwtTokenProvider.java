package com.firstclub.membership.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecurityException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.stream.Collectors;

/**
 * Stateless JWT token provider.
 *
 * Generates and validates JWT access tokens and refresh tokens using HMAC-SHA256.
 * The secret must be at least 256 bits (32 bytes) for HS256.
 *
 * Access tokens: short-lived (app.jwt.expiration-ms, default 24 h).
 * Refresh tokens: longer-lived (app.jwt.refresh-expiration-ms, default 7 d),
 *                 identified by claim {@code type=refresh}.
 */
@Component
@Slf4j
public class JwtTokenProvider {

    /** Known dev-only fallback secret — used ONLY when JWT_SECRET env var is absent.
     *  Public so that {@code StartupValidationRunner} can detect and reject this value
     *  in non-dev/non-test profiles.
     */
    public static final String DEV_FALLBACK_SECRET =
        "Y2hhbmdlbWVpbnByb2R1Y3Rpb24tdGhpcy1pcy1hLWRldi1vbmx5LXNlY3JldC1rZXkh";

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiration-ms:86400000}")
    private long jwtExpirationMs;

    /**
     * Warn loudly at startup if the application is using the insecure dev-only
     * JWT secret. In production the JWT_SECRET environment variable MUST be set
     * to a randomly generated 256-bit Base64 value.
     * Generate one with: {@code openssl rand -base64 32}
     */
    @PostConstruct
    void validateJwtSecret() {
        if (DEV_FALLBACK_SECRET.equals(jwtSecret)) {
            log.warn("⚠️  JWT_SECRET is using the insecure dev-only default! "
                + "Set the JWT_SECRET environment variable before deploying to production.");
        }
    }

    @Value("${app.jwt.refresh-expiration-ms:604800000}")
    private long refreshExpirationMs;

    private SecretKey signingKey() {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(jwtSecret);
        } catch (IllegalArgumentException e) {
            // Secret is not valid Base64 — treat it as a raw UTF-8 string
            log.warn("JWT secret is not valid Base64; using raw UTF-8 bytes");
            keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(Authentication authentication) {
        String username = authentication.getName();
        String roles = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.joining(","));

        return Jwts.builder()
            .subject(username)
            .claim("roles", roles)
            .claim("type", "access")
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + jwtExpirationMs))
            .signWith(signingKey())
            .compact();
    }

    public String generateRefreshToken(Authentication authentication) {
        return Jwts.builder()
            .subject(authentication.getName())
            .claim("type", "refresh")
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + refreshExpirationMs))
            .signWith(signingKey())
            .compact();
    }

    public String getUsernameFromToken(String token) {
        return Jwts.parser()
            .verifyWith(signingKey())
            .build()
            .parseSignedClaims(token)
            .getPayload()
            .getSubject();
    }

    public Date getExpirationFromToken(String token) {
        return Jwts.parser()
            .verifyWith(signingKey())
            .build()
            .parseSignedClaims(token)
            .getPayload()
            .getExpiration();
    }

    public boolean isRefreshToken(String token) {
        try {
            Claims claims = Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
            return "refresh".equals(claims.get("type", String.class));
        } catch (Exception e) {
            return false;
        }
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(signingKey()).build().parseSignedClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("JWT token is expired: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            log.warn("JWT token is unsupported: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
        } catch (SecurityException e) {
            log.warn("Invalid JWT signature: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("JWT claims string is empty: {}", e.getMessage());
        }
        return false;
    }
}
