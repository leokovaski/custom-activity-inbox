package com.inbox.activity.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Configuração via variáveis de ambiente (12-factor). */
@Component
public class AppProperties {

  /** URL pública da aplicação (ex.: https://activity-api.xxx.azurecontainerapps.io) */
  @Value("${BASE_URL:http://localhost:8080}")
  private String baseUrl;

  /** JWT Signing Secret do Installed Package no Marketing Cloud */
  @Value("${JWT_SECRET:}")
  private String jwtSecret;

  @Value("${SERVICEBUS_CONNECTION_STRING:}")
  private String serviceBusConnectionString;

  @Value("${SERVICEBUS_QUEUE:journey-execute}")
  private String serviceBusQueue;

  @Value("${WORKER_CONCURRENCY:20}")
  private int workerConcurrency;

  @Value("${SFMC_AUTH_URL:}")
  private String sfmcAuthUrl;

  @Value("${SFMC_CLIENT_ID:}")
  private String sfmcClientId;

  @Value("${SFMC_CLIENT_SECRET:}")
  private String sfmcClientSecret;

  public String getBaseUrl() {
    return baseUrl.replaceAll("/$", "");
  }

  public String getJwtSecret() { return jwtSecret; }
  public String getServiceBusConnectionString() { return serviceBusConnectionString; }
  public String getServiceBusQueue() { return serviceBusQueue; }
  public int getWorkerConcurrency() { return workerConcurrency; }
  public String getSfmcAuthUrl() { return sfmcAuthUrl; }
  public String getSfmcClientId() { return sfmcClientId; }
  public String getSfmcClientSecret() { return sfmcClientSecret; }

  public boolean isQueueEnabled() {
    return serviceBusConnectionString != null && !serviceBusConnectionString.isBlank();
  }

  public boolean isSfmcLookupEnabled() {
    return !sfmcAuthUrl.isBlank() && !sfmcClientId.isBlank() && !sfmcClientSecret.isBlank();
  }
}
