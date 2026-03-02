package com.s310.kakaon.global.jwt;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.SignatureAlgorithm;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtTokenProvider {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_KEY = "role";

    private final TokenBlacklistService tokenBlacklistService;

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.access-token-validity-seconds}")
    private long accessTokenValiditySeconds;

    @Value("${jwt.refresh-token-validity-seconds}")
    private long refreshTokenValiditySeconds;

    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        byte[] keyBytes = isBase64(secret)
                ? Base64.getDecoder().decode(secret)
                : secret.getBytes(StandardCharsets.UTF_8);
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    private boolean isBase64(String secret) {
        try {
            Base64.getDecoder().decode(secret);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public String createAccessToken(String kakaoId, String role){
        return createToken(kakaoId, role, accessTokenValiditySeconds);
    }

    public String createRefreshToken(String kakaoId){
        return createToken(kakaoId, null, refreshTokenValiditySeconds);
    }

    public TokenResponseDto createTokenResponse(String kakaoId, String role){
        String access = createAccessToken(kakaoId, role);
        String refresh = createRefreshToken(kakaoId);
        return TokenResponseDto.of(access, refresh);
    }

    private String createToken(String kakaoId, String role, long validSeconds) {
        Instant now = Instant.now();
        JwtBuilder builder = Jwts.builder()
                .setSubject(kakaoId)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusSeconds(validSeconds)))
                .signWith(secretKey, SignatureAlgorithm.HS256);

        if(role != null) {
            builder.claim(ROLE_KEY, role);
        }
        return builder.compact();
    }

    public boolean validateToken(String token) {
        try {
            // 블랙리스트 체크
            if (tokenBlacklistService.isBlackListed(token)) {
                return false;
            }
            parseClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.debug("토큰이 만료되었습니다: {}", e.getMessage());
            return false;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("유효하지 않은 토큰입니다: {}", e.getMessage());
            return false;
        }
    }

    public Claims parseClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(this.secretKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public String getSubject(String token) { return parseClaims(token).getSubject(); }

    public String getRole(String token) {
        Object v = parseClaims(token).get(ROLE_KEY);
        return v == null ? null : v.toString();
    }

    public Optional<String> resolve(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if(header != null && header.startsWith(BEARER_PREFIX)) {
            return Optional.of(header.substring(BEARER_PREFIX.length()));
        }
        return Optional.empty();
    }

    public void addToBlackList(String token) {
        try {
            Claims claims = parseClaims(token);
            Date expiration = claims.getExpiration();
            Duration ttl = Duration.between(Instant.now(), expiration.toInstant());

            if(!ttl.isNegative() && !ttl.isZero()) {
                tokenBlacklistService.addToBlackList(token, ttl);
            }
        } catch (JwtException e) {
            log.warn("블랙리스트 추가 실패 - 이미 만료된 토큰: {}", e.getMessage());
        }
    }


}
