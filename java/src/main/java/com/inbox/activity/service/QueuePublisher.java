package com.inbox.activity.service;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inbox.activity.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * Produtor da fila (queue-based load leveling).
 *
 * O messageId determinístico (hash de activityInstanceId + contactKey +
 * definitionInstanceId) ativa a deduplicação nativa da fila: os retries do
 * SFMC são descartados automaticamente (consumidor idempotente).
 */
@Service
@Profile("!worker")
public class QueuePublisher {

  private static final Logger log = LoggerFactory.getLogger(QueuePublisher.class);

  private final ServiceBusSenderClient sender; // null => modo inline (dev)
  private final ObjectMapper mapper = new ObjectMapper();

  public QueuePublisher(AppProperties props) {
    if (props.isQueueEnabled()) {
      this.sender = new ServiceBusClientBuilder()
          .connectionString(props.getServiceBusConnectionString())
          .sender()
          .queueName(props.getServiceBusQueue())
          .buildClient();
      log.info("[QUEUE] Service Bus habilitado (fila: {})", props.getServiceBusQueue());
    } else {
      this.sender = null;
      log.warn("[QUEUE] SERVICEBUS_CONNECTION_STRING ausente — execute processará inline (modo dev).");
    }
  }

  public boolean isEnabled() {
    return sender != null;
  }

  public void publish(Map<String, Object> job) {
    try {
      String messageId = sha256(
          job.get("activityInstanceId") + "|" + job.get("contactKey") + "|" + job.get("definitionInstanceId"));

      ServiceBusMessage message = new ServiceBusMessage(mapper.writeValueAsString(job));
      message.setMessageId(messageId);
      message.setContentType("application/json");

      sender.sendMessage(message);
    } catch (Exception e) {
      throw new IllegalStateException("Falha ao enfileirar job", e);
    }
  }

  private static String sha256(String value) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
