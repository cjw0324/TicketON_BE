package org.codenbug.common.util;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

/**
 * JWT 설정 및 유틸리티 클래스
 * JWT 토큰 생성, 검증, 정보 추출 등의 기능을 제공
 * application.yml에 정의된 jwt.secret과 jwt.expiration 속성을 사용
 */
@Component
public class JwtConfig {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration;

    @Value("${jwt.refresh-token-expiration:604800000}")
    private long refreshTokenExpiration;

    /**
     * Access Token 생성 (userId, role 클레임 포함)
     */
    public String generateAccessToken(String identifier, Long userId, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("role", role);
        return createToken(claims, identifier, expiration);
    }

    /**
     * Refresh Token 생성 (userId, role 클레임 포함 — refresh 시 DB 조회 불필요)
     */
    public String generateRefreshToken(String identifier, Long userId, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("role", role);
        return createToken(claims, identifier, refreshTokenExpiration);
    }

    /**
     * 토큰에서 subject(식별자) 추출
     */
    public String extractSubject(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * 토큰에서 만료 시간 추출
     */
    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    /**
     * 토큰에서 userId 추출
     */
    public Long extractUserId(String token) {
        return extractClaim(token, claims -> {
            Object userId = claims.get("userId");
            if (userId instanceof Integer) return ((Integer) userId).longValue();
            if (userId instanceof Long) return (Long) userId;
            return null;
        });
    }

    /**
     * 토큰에서 role 추출
     */
    public String extractRole(String token) {
        return extractClaim(token, claims -> (String) claims.get("role"));
    }

    /**
     * 토큰 유효성 검증
     */
    public boolean validateToken(String token) {
        try {
            return !isTokenExpired(token);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Refresh Token 만료 시간 반환
     */
    public long getRefreshTokenExpiration() {
        return refreshTokenExpiration;
    }

    /**
     * 토큰의 남은 만료 시간을 밀리초 단위로 계산
     */
    public long getRemainingTime(String token) {
        Date expiration = extractExpiration(token);
        Date now = new Date();
        return Math.max(0, expiration.getTime() - now.getTime());
    }

    private String createToken(Map<String, Object> claims, String subject, long expirationTime) {
        return Jwts.builder()
            .setClaims(claims)
            .setSubject(subject)
            .setIssuedAt(new Date(System.currentTimeMillis()))
            .setExpiration(new Date(System.currentTimeMillis() + expirationTime))
            .signWith(getSigningKey(), SignatureAlgorithm.HS256)
            .compact();
    }

    private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
            .setSigningKey(getSigningKey())
            .build()
            .parseClaimsJws(token)
            .getBody();
    }

    private Key getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }
} 