'use strict';

/**
 * Worker — consome a fila do Azure Service Bus e executa a lógica de negócio.
 *
 * Roda como um Container App separado da API. Escala horizontalmente via KEDA
 * conforme o tamanho da fila (ver README-AZURE.md). Assim, os picos do
 * Journey Builder nunca derrubam o processamento: a API responde em ms e o
 * worker consome no ritmo que a sua infraestrutura/APIs externas aguentam.
 */

require('dotenv').config();

const { ServiceBusClient } = require('@azure/service-bus');
const { lookupJourneyName } = require('./lib/sfmc');

const SB_CONN = process.env.SERVICEBUS_CONNECTION_STRING;
const SB_QUEUE = process.env.SERVICEBUS_QUEUE || 'journey-execute';

// Quantas mensagens processar em paralelo POR instância do worker.
// Ajuste conforme a capacidade das APIs que você chama na lógica de negócio.
const CONCURRENCY = parseInt(process.env.WORKER_CONCURRENCY || '20', 10);

if (!SB_CONN) {
  console.error('SERVICEBUS_CONNECTION_STRING é obrigatório para o worker.');
  process.exit(1);
}

const sbClient = new ServiceBusClient(SB_CONN);
const receiver = sbClient.createReceiver(SB_QUEUE); // peekLock (padrão): mensagem só some após complete

async function processJob(job) {
  // Nome atual da jornada (com cache) — à prova de renomeação
  const currentName = await lookupJourneyName(job.journeyId);
  const journeyName = currentName || job.journeyName;

  console.log(
    `[WORKER] contactKey=${job.contactKey} title="${job.title}" journey="${journeyName}" (${job.journeyId})`
  );

  // =========================================================================
  // >>> AQUI VAI A SUA LÓGICA DE NEGÓCIO <<<
  // Ex.: chamar API externa, gravar em Data Extension, enviar SMS/WhatsApp...
  // Você tem à disposição: job.contactKey, job.inArguments, journeyName,
  // job.journeyId, job.activityId, job.activityInstanceId, job.receivedAt
  // =========================================================================
}

receiver.subscribe(
  {
    processMessage: async (message) => {
      try {
        await processJob(message.body);
        // peekLock: só remove da fila se processou com sucesso
        await receiver.completeMessage(message);
      } catch (err) {
        console.error('[WORKER] Erro ao processar:', err.message);
        // abandona => volta para a fila; após maxDeliveryCount vai para a DLQ
        await receiver.abandonMessage(message);
      }
    },
    processError: async (args) => {
      console.error('[WORKER] Erro no receiver:', args.error.message);
    }
  },
  { maxConcurrentCalls: CONCURRENCY, autoCompleteMessages: false }
);

console.log(`Worker ouvindo a fila "${SB_QUEUE}" (concorrência: ${CONCURRENCY})`);

// Encerramento gracioso (Container Apps envia SIGTERM ao desescalar)
async function shutdown() {
  console.log('Encerrando worker...');
  await receiver.close();
  await sbClient.close();
  process.exit(0);
}
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
