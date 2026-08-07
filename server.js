'use strict';

require('dotenv').config();

const express = require('express');
const path = require('path');
const crypto = require('crypto');
const jwt = require('jsonwebtoken');
const { lookupJourneyName } = require('./lib/sfmc');

const app = express();
const PORT = process.env.PORT || 3000;

// URL pública da aplicação (ex.: https://minha-activity.azurecontainerapps.io)
const BASE_URL = (process.env.BASE_URL || `http://localhost:${PORT}`).replace(/\/$/, '');

// Segredo JWT do Installed Package (Marketing Cloud assina o payload de /execute com ele)
const JWT_SECRET = process.env.JWT_SECRET || '';

// ---------------------------------------------------------------------------
// Fila (Azure Service Bus) — modo produção em grande escala.
// Com SERVICEBUS_CONNECTION_STRING configurado, o /execute apenas ENFILEIRA o
// trabalho e responde 200 em milissegundos; o worker.js processa em paralelo.
// Sem a variável, processa inline (modo dev / baixa escala).
// ---------------------------------------------------------------------------
const SB_CONN = process.env.SERVICEBUS_CONNECTION_STRING || '';
const SB_QUEUE = process.env.SERVICEBUS_QUEUE || 'journey-execute';

let sbSender = null;
if (SB_CONN) {
  const { ServiceBusClient } = require('@azure/service-bus');
  const sbClient = new ServiceBusClient(SB_CONN);
  sbSender = sbClient.createSender(SB_QUEUE);
  console.log(`[QUEUE] Service Bus habilitado (fila: ${SB_QUEUE})`);
} else {
  console.warn('[QUEUE] SERVICEBUS_CONNECTION_STRING ausente — execute processará inline (modo dev).');
}

app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

// Fallback do ícone: enquanto public/images/icon.png não existir,
// serve o icon.svg no lugar (o express.static acima tem prioridade
// quando o png estiver presente).
app.get('/images/icon.png', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'images', 'icon.svg'));
});

// ---------------------------------------------------------------------------
// config.json — o Journey Builder busca este arquivo para montar a activity.
// ---------------------------------------------------------------------------
app.get('/config.json', (req, res) => {
  res.json({
    workflowApiVersion: '1.1',
    metaData: {
      icon: `${BASE_URL}/images/icon.png`,
      category: 'customer'
    },
    type: 'REST',
    lang: {
      'en-US': {
        name: 'Novo App Inbox',
        description: 'Envia uma mensagem de inbox no app do cliente'
      },
      'pt-BR': {
        name: 'Novo App Inbox',
        description: 'Envia uma mensagem de inbox no app do cliente'
      }
    },
    arguments: {
      execute: {
        inArguments: [
          { contactKey: '{{Contact.Key}}' },
          { emailAddress: '{{InteractionDefaults.Email}}' },
          { title: '' },
          { message: '' },
          { journeyName: '' },
          { journeyId: '' }
        ],
        outArguments: [],
        url: `${BASE_URL}/journey/execute`,
        verb: 'POST',
        body: '',
        header: '',
        format: 'json',
        useJwt: true,
        timeout: 10000,
        retryCount: 3,
        retryDelay: 1000
      }
    },
    configurationArguments: {
      save: { url: `${BASE_URL}/journey/save`, verb: 'POST', useJwt: true },
      publish: { url: `${BASE_URL}/journey/publish`, verb: 'POST', useJwt: true },
      validate: { url: `${BASE_URL}/journey/validate`, verb: 'POST', useJwt: true },
      stop: { url: `${BASE_URL}/journey/stop`, verb: 'POST', useJwt: true }
    },
    userInterfaces: {
      configModal: {
        height: 480,
        width: 640,
        fullscreen: false
      }
    },
    schema: {
      arguments: {
        execute: {
          inArguments: [
            { contactKey: { dataType: 'Text', isNullable: false, direction: 'in' } },
            { emailAddress: { dataType: 'Email', isNullable: true, direction: 'in' } },
            { title: { dataType: 'Text', isNullable: false, direction: 'in' } },
            { message: { dataType: 'Text', isNullable: false, direction: 'in' } },
            { journeyName: { dataType: 'Text', isNullable: true, direction: 'in' } },
            { journeyId: { dataType: 'Text', isNullable: true, direction: 'in' } }
          ],
          outArguments: []
        }
      }
    }
  });
});

// ---------------------------------------------------------------------------
// Middleware de verificação do JWT enviado pelo Marketing Cloud
// ---------------------------------------------------------------------------
function verifyJwt(req, res, next) {
  if (!JWT_SECRET) {
    console.warn('[JWT] JWT_SECRET não configurado — pulando validação (apenas dev).');
    req.mcPayload = req.body;
    return next();
  }

  const token =
    typeof req.body === 'string'
      ? req.body
      : req.body && req.body.toString
        ? req.body.toString('utf8')
        : null;

  try {
    req.mcPayload = jwt.verify(token, JWT_SECRET, { algorithms: ['HS256'] });
    return next();
  } catch (err) {
    console.error('[JWT] Falha na validação:', err.message);
    return res.status(401).json({ error: 'JWT inválido' });
  }
}

// Para rotas com useJwt, o corpo chega como text/plain (JWT bruto)
const rawJwtParser = express.text({ type: '*/*' });

// ---------------------------------------------------------------------------
// EXECUTE — chamado para CADA contato que passa pela activity na jornada
// ---------------------------------------------------------------------------
app.post('/journey/execute', rawJwtParser, verifyJwt, async (req, res) => {
  try {
    const payload = req.mcPayload || {};
    const inArguments = (payload.inArguments || []).reduce(
      (acc, arg) => Object.assign(acc, arg),
      {}
    );

    const journeyId = inArguments.journeyId || payload.journeyId || null;

    const job = {
      contactKey: inArguments.contactKey,
      title: inArguments.title || null,
      message: inArguments.message || null,
      inArguments,
      journeyId,
      journeyName: inArguments.journeyName || null,
      activityId: payload.activityId || null,
      activityInstanceId: payload.activityInstanceId || null,
      definitionInstanceId: payload.definitionInstanceId || null,
      receivedAt: new Date().toISOString()
    };

    if (sbSender) {
      // ---------- MODO FILA (produção / grande escala) ----------
      // messageId determinístico => o Service Bus (com duplicate detection
      // habilitado na fila) descarta reenvios do SFMC automaticamente.
      const messageId = crypto
        .createHash('sha256')
        .update(`${job.activityInstanceId}|${job.contactKey}|${job.definitionInstanceId}`)
        .digest('hex');

      await sbSender.sendMessages({
        messageId,
        contentType: 'application/json',
        body: job
      });

      return res.status(200).json({ branchResult: 'ok' });
    }

    // ---------- MODO INLINE (dev / baixa escala) ----------
    const currentName = await lookupJourneyName(journeyId);
    if (currentName) job.journeyName = currentName;

    console.log('[EXECUTE] contactKey:', job.contactKey);
    console.log('[EXECUTE] journeyName:', job.journeyName, '| journeyId:', job.journeyId);
    console.log('[EXECUTE] inArguments:', JSON.stringify(inArguments));

    // =====================================================================
    // >>> AQUI VAI A SUA LÓGICA DE NEGÓCIO (modo inline) <<<
    // Em produção com fila, a lógica fica no worker.js
    // =====================================================================

    return res.status(200).json({ branchResult: 'ok' });
  } catch (err) {
    console.error('[EXECUTE] Erro:', err);
    // 500 faz o Journey Builder tentar novamente (retryCount)
    return res.status(500).json({ error: 'Erro na execução' });
  }
});

// ---------------------------------------------------------------------------
// Endpoints de ciclo de vida (save / publish / validate / stop)
// ---------------------------------------------------------------------------
app.post('/journey/save', rawJwtParser, verifyJwt, (req, res) => {
  console.log('[SAVE]', JSON.stringify(req.mcPayload));
  res.status(200).json({ success: true });
});

app.post('/journey/publish', rawJwtParser, verifyJwt, (req, res) => {
  console.log('[PUBLISH]', JSON.stringify(req.mcPayload));
  res.status(200).json({ success: true });
});

app.post('/journey/validate', rawJwtParser, verifyJwt, (req, res) => {
  console.log('[VALIDATE]', JSON.stringify(req.mcPayload));
  res.status(200).json({ success: true });
});

app.post('/journey/stop', rawJwtParser, verifyJwt, (req, res) => {
  console.log('[STOP]', JSON.stringify(req.mcPayload));
  res.status(200).json({ success: true });
});

// Healthcheck (usado pelos probes do Container Apps)
app.get('/health', (req, res) => res.json({ status: 'ok', queue: Boolean(sbSender) }));

app.listen(PORT, () => {
  console.log(`Custom Activity rodando em ${BASE_URL} (porta ${PORT})`);
});
