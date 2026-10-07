package com.inbox.activity.worker;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusErrorContext;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inbox.activity.config.AppProperties;
import com.inbox.activity.service.SfmcClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Worker — consome a fila do Service Bus e executa a lógica de negócio
 * (competing consumers). Ativado com SPRING_PROFILES_ACTIVE=worker.
 *
 * Semântica de entrega (peekLock):
 *  - complete()  => sucesso, mensagem removida da fila
 *  - abandon()   => falha, volta para a fila; após maxDeliveryCount vai para a DLQ
 *
 * Escala horizontalmente via KEDA conforme o tamanho da fila; a concorrência
 * POR instância é WORKER_CONCURRENCY (ajuste ao rate limit da API de inbox).
 */
@Component
@Profile("worker")
public class QueueWorker implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(QueueWorker.class);

  private final AppProperties props;
  private final SfmcClient sfmc;
  private final ObjectMapper mapper = new ObjectMapper();
  private ServiceBusProcessorClient processor;

  public QueueWorker(AppProperties props, SfmcClient sfmc) {
    this.props = props;
    this.sfmc = sfmc;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!props.isQueueEnabled()) {
      throw new IllegalStateException("SERVICEBUS_CONNECTION_STRING é obrigatório para o worker.");
    }

    processor = new ServiceBusClientBuilder()
        .connectionString(props.getServiceBusConnectionString())
        .processor()
        .queueName(props.getServiceBusQueue())
        .maxConcurrentCalls(props.getWorkerConcurrency())
        .disableAutoComplete()
        .processMessage(this::processMessage)
        .processError(this::processError)
        .buildProcessorClient();

    processor.start();
    log.info("Worker ouvindo a fila \"{}\" (concorrência: {})",
        props.getServiceBusQueue(), props.getWorkerConcurrency());
  }

  private void processMessage(ServiceBusReceivedMessageContext context) {
    try {
      Map<String, Object> job = mapper.readValue(
          context.getMessage().getBody().toString(), new TypeReference<>() {});

      // Nome atual da jornada (com cache) — à prova de renomeação
      String currentName = sfmc.lookupJourneyName((String) job.get("journeyId"));
      String journeyName = currentName != null ? currentName : (String) job.get("journeyName");

      log.debug("[WORKER] job={}", job);  // ponytail: PII — só com LOGGING_LEVEL_COM_INBOX_ACTIVITY=DEBUG
      log.info("[WORKER] contactKey={} title=\"{}\" journey=\"{}\" ({})",
          job.get("contactKey"), job.get("title"), journeyName, job.get("journeyId"));

      // =====================================================================
      // >>> AQUI VAI A SUA LÓGICA DE NEGÓCIO <<<
      // Ex.: chamar a API de inbox do app com job.get("title"),
      // job.get("message"), job.get("contactKey")...
      // =====================================================================

      context.complete(); // sucesso: remove da fila
    } catch (Exception e) {
      log.error("[WORKER] Erro ao processar: {}", e.getMessage());
      context.abandon(); // volta para a fila; após maxDeliveryCount vai para a DLQ
    }
  }

  private void processError(ServiceBusErrorContext context) {
    log.error("[WORKER] Erro no receiver: {}", context.getException().getMessage());
  }

  /** Encerramento gracioso (Container Apps envia SIGTERM ao desescalar). */
  @PreDestroy
  public void shutdown() {
    log.info("Encerrando worker...");
    if (processor != null) {
      processor.close();
    }
  }
}
