package com.kama.jmindops.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtTokenService {
    private static final String ISSUER = "jmindops";
    private static final String AUDIENCE = "jmindops-ui";
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final String secret;
    private final long ttlHours;

    public JwtTokenService(
            ObjectMapper objectMapper,
            @Value("${app.security.jwt-secret}") String secret,
            @Value("${app.security.token-ttl-hours}") long ttlHours
    ) {
        this.objectMapper = objectMapper;
        this.secret = secret;
        this.ttlHours = ttlHours;
    }

    @PostConstruct
    void validateSecret() {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("APP_JWT_SECRET must contain at least 32 bytes");
        }
        if (ttlHours <= 0 || ttlHours > 168) {
            throw new IllegalStateException("APP_JWT_TTL_HOURS must be between 1 and 168");
        }
    }

    public String createToken(AuthenticatedUser user) {
        try {
            String header = URL_ENCODER.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            long issuedAt = Instant.now().getEpochSecond();
            long expiresAt = issuedAt + ttlHours * 3600;
            String payload = URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(Map.of(
                    "sub", user.id(),
                    "username", user.username(),
                    "role", user.role(),
                    "iss", ISSUER,
                    "aud", AUDIENCE,
                    "iat", issuedAt,
                    "nbf", issuedAt,
                    "jti", UUID.randomUUID().toString(),
                    "exp", expiresAt
            )));
            String signedContent = header + "." + payload;
            return signedContent + "." + sign(signedContent);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create access token", e);
        }
    }

    public AuthenticatedUser parse(String token) {
        try {
            if (token == null || token.isBlank() || token.length() > 8192) {
                throw new IllegalArgumentException("Invalid token length");
            }
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || !MessageDigest.isEqual(
                    URL_DECODER.decode(parts[2]), URL_DECODER.decode(sign(parts[0] + "." + parts[1])))) {
                throw new IllegalArgumentException("Invalid token signature");
            }
            Map<String, Object> header = objectMapper.readValue(URL_DECODER.decode(parts[0]), new TypeReference<>() {});
            if (!"HS256".equals(header.get("alg")) || !"JWT".equals(header.get("typ"))) {
                throw new IllegalArgumentException("Invalid token header");
            }
            Map<String, Object> payload = objectMapper.readValue(URL_DECODER.decode(parts[1]), new TypeReference<>() {});
            if (!ISSUER.equals(payload.get("iss")) || !AUDIENCE.equals(payload.get("aud"))) {
                throw new IllegalArgumentException("Invalid token audience");
            }
            long now = Instant.now().getEpochSecond();
            long notBefore = requiredNumber(payload, "nbf");
            long issuedAt = requiredNumber(payload, "iat");
            long expiresAt = requiredNumber(payload, "exp");
            if (now < notBefore || issuedAt > now + 60 || now >= expiresAt || expiresAt <= issuedAt) {
                throw new IllegalArgumentException("Token is outside its validity window");
            }
            String subject = requiredString(payload, "sub");
            String username = requiredString(payload, "username");
            String role = requiredString(payload, "role");
            return new AuthenticatedUser(
                    subject,
                    username,
                    role
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid access token", e);
        }
    }

    private String sign(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return URL_ENCODER.encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private long requiredNumber(Map<String, Object> payload, String claim) {
        Object value = payload.get(claim);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Missing numeric claim: " + claim);
        }
        return number.longValue();
    }

    private String requiredString(Map<String, Object> payload, String claim) {
        Object value = payload.get(claim);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Missing string claim: " + claim);
        }
        return text;
    }
}
