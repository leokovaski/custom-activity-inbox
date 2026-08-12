package com.inbox.activity.security;

import com.inbox.activity.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Valida o JWT enviado pelo Marketing Cloud (useJwt=true no config.json).
 * O corpo das requisições chega como um JWT bruto assinado (HS256) com o
 * JWT Signing Secret do Installed Package.
 */
@Component
public class JwtVerifier {

  private static final Logger log = LoggerFactory.getLogger(JwtVerifier.class);

  private final SecretKey key;
  private final boolean enabled;

  public JwtVerifier(AppProperties props) {
    String secret = props.getJwtSecret();
    this.enabled = secret != null && !secret.isBlank();
    this.key = enabled ? Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)) : null;
    if (!enabled) {
      log.warn("[JWT] JWT_SECRET não configurado — validação desabilitada (apenas dev).");
    }
  }

  /**
   * Verifica a assinatura e retorna as claims (o payload do SFMC).
   * @throws io.jsonwebtoken.JwtException se o token for inválido.
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> verify(String rawJwt) {
    if (!enabled) {
      // Modo dev sem segredo: decodifica sem validar (NÃO usar em produção)
      int i = rawJwt.indexOf('.');
      int j = rawJwt.lastIndexOf('.');
      byte[] payload = java.util.Base64.getUrlDecoder().decode(rawJwt.substring(i + 1, j));
      try {
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(payload, Map.class);
      } catch (Exception e) {
        throw new IllegalArgumentException("Payload JWT ilegível", e);
      }
    }
    Claims claims = Jwts.parser()
        .verifyWith(key)
        .build()
        .parseSignedClaims(rawJwt)
        .getPayload();
    return claims;
  }
}
