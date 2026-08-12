package com.inbox.activity.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inbox.activity.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cliente da REST API do SFMC (OAuth2 client-credentials + cache de token).
 * Usado para buscar o nome ATUAL da jornada pelo journeyId — imune a
 * renomeações. Opcional: sem credenciais configuradas, retorna null e o
 * nome capturado na configuração da activity é usado.
 *
 * Requer componente "API Integration (Server-to-Server)" no Installed
 * Package com escopo "Journeys - Read".
 */
@Service
public class SfmcClient {

  private static final Logger log = LoggerFactory.getLogger(SfmcClient.class);
  private static final Duration NAME_CACHE_TTL = Duration.ofMinutes(10);

  private final AppProperties props;
  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper mapper = new ObjectMapper();

  private volatile String token;
  private volatile String restUrl;
  private volatile Instant tokenExpiresAt = Instant.EPOCH;

  private record CachedName(String name, Instant expiresAt) {}
  private final Map<String, CachedName> journeyNameCache = new ConcurrentHashMap<>();

  public SfmcClient(AppProperties props) {
    this.props = props;
  }

  /** Nome atual da jornada, ou null se lookup indisponível/falhar. */
  public String lookupJourneyName(String journeyId) {
    if (journeyId == null || journeyId.isBlank() || !props.isSfmcLookupEnabled()) return null;

    CachedName cached = journeyNameCache.get(journeyId);
    if (cached != null && Instant.now().isBefore(cached.expiresAt())) return cached.name();

    try {
      ensureToken();
      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(restUrl + "/interaction/v1/interactions/" + journeyId))
          .header("Authorization", "Bearer " + token)
          .GET()
          .build();
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode());

      String name = mapper.readTree(resp.body()).path("name").asText(null);
      if (name != null) {
        journeyNameCache.put(journeyId, new CachedName(name, Instant.now().plus(NAME_CACHE_TTL)));
      }
      return name;
    } catch (Exception e) {
      log.warn("[JOURNEY LOOKUP] {}", e.getMessage());
      return null;
    }
  }

  private synchronized void ensureToken() throws Exception {
    if (token != null && Instant.now().isBefore(tokenExpiresAt)) return;

    String body = mapper.writeValueAsString(Map.of(
        "grant_type", "client_credentials",
        "client_id", props.getSfmcClientId(),
        "client_secret", props.getSfmcClientSecret()));

    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create(props.getSfmcAuthUrl() + "/v2/token"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();

    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) throw new IllegalStateException("Auth SFMC falhou: HTTP " + resp.statusCode());

    JsonNode json = mapper.readTree(resp.body());
    this.token = json.path("access_token").asText();
    this.restUrl = json.path("rest_instance_url").asText().replaceAll("/$", "");
    this.tokenExpiresAt = Instant.now().plusSeconds(json.path("expires_in").asLong() - 60);
  }
}
