package com.inbox.activity.aws;

import java.util.List;
import java.util.Map;

/**
 * config.json — o "contrato" que o Journey Builder busca para montar a activity.
 * Gerado dinamicamente para injetar a BASE_URL (a URL do API Gateway).
 */
public final class ConfigJson {

  private ConfigJson() {}

  public static Map<String, Object> build(String base) {
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

    Map<String, Object> schema = Map.of(
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
        "schema", schema);
  }
}
