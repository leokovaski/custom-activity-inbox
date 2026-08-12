package com.inbox.activity.controller;

import com.inbox.activity.config.AppProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * config.json — o "contrato" que o Journey Builder busca para montar a activity.
 * Servido dinamicamente para injetar a BASE_URL do ambiente em todos os endpoints.
 */
@RestController
public class ConfigJsonController {

  private final AppProperties props;

  public ConfigJsonController(AppProperties props) {
    this.props = props;
  }

  @GetMapping(value = "/config.json", produces = MediaType.APPLICATION_JSON_VALUE)
  public Map<String, Object> configJson() {
    String base = props.getBaseUrl();

    Map<String, Object> lang = Map.of(
        "en-US", Map.of(
            "name", "Novo App Inbox",
            "description", "Envia uma mensagem de inbox no app do cliente"),
        "pt-BR", Map.of(
            "name", "Novo App Inbox",
            "description", "Envia uma mensagem de inbox no app do cliente"));

    Map<String, Object> execute = Map.of(
        "inArguments", List.of(
            Map.of("contactKey", "{{Contact.Key}}"),
            Map.of("emailAddress", "{{InteractionDefaults.Email}}"),
            Map.of("title", ""),
            Map.of("message", ""),
            Map.of("journeyName", ""),
            Map.of("journeyId", "")),
        "outArguments", List.of(),
        "url", base + "/journey/execute",
        "verb", "POST",
        "body", "",
        "header", "",
        "format", "json",
        "useJwt", true,
        "timeout", 10000);

    Map<String, Object> configurationArguments = Map.of(
        "save", Map.of("url", base + "/journey/save", "verb", "POST", "useJwt", true),
        "publish", Map.of("url", base + "/journey/publish", "verb", "POST", "useJwt", true),
        "validate", Map.of("url", base + "/journey/validate", "verb", "POST", "useJwt", true),
        "stop", Map.of("url", base + "/journey/stop", "verb", "POST", "useJwt", true));

    Map<String, Object> schemaInArguments = Map.of(
        "arguments", Map.of(
            "execute", Map.of(
                "inArguments", List.of(
                    Map.of("contactKey", Map.of("dataType", "Text", "isNullable", false, "direction", "in")),
                    Map.of("emailAddress", Map.of("dataType", "Email", "isNullable", true, "direction", "in")),
                    Map.of("title", Map.of("dataType", "Text", "isNullable", false, "direction", "in")),
                    Map.of("message", Map.of("dataType", "Text", "isNullable", false, "direction", "in")),
                    Map.of("journeyName", Map.of("dataType", "Text", "isNullable", true, "direction", "in")),
                    Map.of("journeyId", Map.of("dataType", "Text", "isNullable", true, "direction", "in"))),
                "outArguments", List.of())));

    return Map.of(
        "workflowApiVersion", "1.1",
        "metaData", Map.of(
            "icon", base + "/images/icon.png",
            "category", "customer"),
        "type", "REST",
        "lang", lang,
        "arguments", Map.of("execute", execute),
        "configurationArguments", configurationArguments,
        "userInterfaces", Map.of(
            "configModal", Map.of("height", 480, "width", 640, "fullscreen", false)),
        "schema", schemaInArguments);
  }

  /** Healthcheck usado pelos probes do Container Apps. */
  @GetMapping("/health")
  public Map<String, Object> health() {
    return Map.of("status", "ok", "queue", props.isQueueEnabled());
  }

  /**
   * Fallback do ícone: enquanto static/images/icon.png não existir,
   * serve o icon.svg no lugar (o resource handler padrão tem prioridade
   * quando o png estiver presente).
   */
  @GetMapping(value = "/images/icon.png", produces = "image/svg+xml")
  public ResponseEntity<ClassPathResource> iconFallback() {
    ClassPathResource png = new ClassPathResource("static/images/icon.png");
    if (png.exists()) {
      return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }
    return ResponseEntity.ok().body(new ClassPathResource("static/images/icon.svg"));
  }
}
