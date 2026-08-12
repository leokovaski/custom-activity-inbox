package com.inbox.activity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Custom Activity "Novo App Inbox" — Journey Builder / Salesforce Marketing Cloud.
 *
 * Dois modos de execução (mesma imagem Docker):
 *   - API  (padrão):                       recebe as chamadas do SFMC e enfileira
 *   - Worker (SPRING_PROFILES_ACTIVE=worker): consome a fila e envia a mensagem de inbox
 */
@SpringBootApplication
public class Application {
  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}
