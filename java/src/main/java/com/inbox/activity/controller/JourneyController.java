package com.inbox.activity.controller;

import com.inbox.activity.security.JwtVerifier;
import com.inbox.activity.service.QueuePublisher;
import com.inbox.activity.service.SfmcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoints chamados pelo Journey Builder.
 *
 * /journey/execute — para CADA contato que passa pela activity. Valida o JWT,
 * monta o job e enfileira (produção) ou processa inline (dev). Responder 200
 * em milissegundos é o que absorve as rajadas do SFMC.
 *
 * /journey/save|publish|validate|stop — ciclo de vida da jornada.
 */
@RestController
@Profile("!worker")
public class JourneyController {

  private static final Logger log = LoggerFactory.getLogger(JourneyController.class);

  private final JwtVerifier jwt;
  private final QueuePublisher queue;
  private final SfmcClient sfmc;

  public JourneyController(JwtVerifier jwt, QueuePublisher queue, SfmcClient sfmc) {
    this.jwt = jwt;
    this.queue = queue;
    this.sfmc = sfmc;
  }

  @PostMapping("/journey/execute")
  public ResponseEntity<Map<String, Object>> execute(@RequestBody String rawJwt) {
    try {
      Map<String, Object> payload = jwt.verify(rawJwt);
      Map<String, Object> inArgs = flattenInArguments(payload);

      Map<String, Object> job = new HashMap<>();
      job.put("contactKey", inArgs.get("contactKey"));
      job.put("title", inArgs.get("title"));
      job.put("message", inArgs.get("message"));
      job.put("inArguments", inArgs);
      job.put("journeyId", inArgs.getOrDefault("journeyId", payload.get("journeyId")));
      job.put("journeyName", inArgs.get("journeyName"));
      job.put("activityId", payload.get("activityId"));
      job.put("activityInstanceId", payload.get("activityInstanceId"));
      job.put("definitionInstanceId", payload.get("definitionInstanceId"));
      job.put("receivedAt", Instant.now().toString());

      if (queue.isEnabled()) {
        // ---------- MODO FILA (produção / grande escala) ----------
        queue.publish(job);
        return ResponseEntity.ok(Map.of("branchResult", "ok"));
      }

      // ---------- MODO INLINE (dev / baixa escala) ----------
      String currentName = sfmc.lookupJourneyName((String) job.get("journeyId"));
      if (currentName != null) job.put("journeyName", currentName);

      log.info("[EXECUTE] contactKey={} journeyName={} journeyId={}",
          job.get("contactKey"), job.get("journeyName"), job.get("journeyId"));

      // =====================================================================
      // >>> AQUI VAI A SUA LÓGICA DE NEGÓCIO (modo inline) <<<
      // Em produção com fila, a lógica fica no worker (QueueWorker)
      // =====================================================================

      return ResponseEntity.ok(Map.of("branchResult", "ok"));

    } catch (io.jsonwebtoken.JwtException e) {
      log.error("[JWT] Falha na validação: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "JWT inválido"));
    } catch (Exception e) {
      log.error("[EXECUTE] Erro", e);
      // 500 faz o Journey Builder tentar novamente (retry)
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Erro na execução"));
    }
  }

  /** Ciclo de vida: save, publish, validate, stop. */
  @PostMapping("/journey/{event:save|publish|validate|stop}")
  public ResponseEntity<Map<String, Object>> lifecycle(@PathVariable String event, @RequestBody String rawJwt) {
    try {
      Map<String, Object> payload = jwt.verify(rawJwt);
      log.info("[{}] {}", event.toUpperCase(), payload);
      return ResponseEntity.ok(Map.of("success", true));
    } catch (io.jsonwebtoken.JwtException e) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "JWT inválido"));
    }
  }

  /** Achata a lista de inArguments ([{a:1},{b:2}]) em um único mapa. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> flattenInArguments(Map<String, Object> payload) {
    Map<String, Object> flat = new HashMap<>();
    Object raw = payload.get("inArguments");
    if (raw instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> m) {
          flat.putAll((Map<String, Object>) m);
        }
      }
    }
    return flat;
  }
}
