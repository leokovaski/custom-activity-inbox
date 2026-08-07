# Novo App Inbox — Custom Activity para o Salesforce Marketing Cloud

Custom Activity do Journey Builder que envia uma **mensagem de inbox no app do cliente** (título + mensagem) para cada contato que passa pela etapa da jornada. Construída em Node.js e projetada para **grande escala na Azure**: API desacoplada por fila (Service Bus) e worker com autoscaling.

## Arquitetura

```
Journey Builder (SFMC)
      │  rajadas de POST /journey/execute (JWT assinado)
      ▼
┌─────────────────────────┐     ┌──────────────────────┐     ┌─────────────────────────┐
│  Container App: API     │ --> │  Service Bus (fila)  │ --> │  Container App: Worker  │
│  (server.js)            │     │  journey-execute     │     │  (worker.js)            │
│  valida JWT, enfileira, │     │  + duplicate         │     │  envia a mensagem de    │
│  responde 200 em ms     │     │    detection + DLQ   │     │  inbox; escala pelo     │
│  escala por HTTP        │     │                      │     │  tamanho da fila (KEDA) │
└─────────────────────────┘     └──────────────────────┘     └─────────────────────────┘
              │                                                        │
              └──────────────► Application Insights ◄──────────────────┘
```

Por que assim: o SFMC dispara em **rajadas** (uma injeção de milhões de contatos metralha o endpoint), exige resposta em ~10s e reenvia em caso de erro. A API só valida e enfileira (milissegundos); o worker consome no ritmo que a integração aguenta. A *duplicate detection* da fila descarta os retries do SFMC e a *dead-letter queue* (DLQ) guarda falhas repetidas para reprocesso.

## Estrutura do projeto — arquivo por arquivo

```
CustomActivity/
├── server.js                      # API Express (o que o SFMC chama)
├── worker.js                      # Consumidor da fila (a lógica de negócio roda aqui)
├── lib/
│   └── sfmc.js                    # Helpers da REST API do SFMC (token + lookup de jornada)
├── public/                        # UI de configuração (iframe no Journey Builder)
│   ├── index.html                 # Modal: campos Título e Mensagem
│   ├── js/customActivity.js       # Postmonger: conversa com o Journey Builder
│   ├── js/postmonger.min.js       # Biblioteca Postmonger (hospedada localmente)
│   ├── css/styles.css             # Estilo do modal (visual SLDS-like)
│   └── images/icon.svg            # Ícone da activity no canvas
├── deploy-azure.sh                # Provisiona e publica TUDO na Azure (idempotente)
├── Dockerfile                     # Imagem única para API e worker
├── .dockerignore                  # O que fica fora da imagem Docker
├── package.json / package-lock.json
├── .env.example                   # Modelo das variáveis de ambiente
├── .gitignore                     # Exclui .env e node_modules do git
└── Procfile                       # (legado/opcional) start para Heroku
```

### `server.js` — a API que o Marketing Cloud chama

Quatro responsabilidades:

1. **`GET /config.json`** — o "contrato" da activity. O Journey Builder busca este JSON para saber nome, ícone, endpoints e argumentos. É servido dinamicamente para injetar a `BASE_URL` do ambiente. Define os `inArguments`: `contactKey`, `emailAddress`, `title`, `message`, `journeyName`, `journeyId`.
2. **`POST /journey/execute`** — chamado **para cada contato**. O corpo é um JWT assinado com o segredo do Installed Package (validado com `jsonwebtoken`). Em produção (com `SERVICEBUS_CONNECTION_STRING`), apenas enfileira o job e responde `200`; sem a variável, processa inline (modo dev). O `messageId` da mensagem é um hash de `activityInstanceId + contactKey + definitionInstanceId` — é isso que ativa a deduplicação na fila.
3. **Ciclo de vida** — `POST /journey/save|publish|validate|stop`, chamados quando a jornada é salva/ativada/validada/parada. Hoje só logam e respondem `200`; use `validate` para vetar publicação com configuração inválida.
4. **Estáticos e utilitários** — serve a pasta `public/`, `GET /health` (probe do Container Apps) e o fallback do ícone (`/images/icon.png` responde com o SVG enquanto um PNG não for adicionado).

### `worker.js` — onde a mensagem é enviada de fato

Consome a fila `journey-execute` com concorrência configurável (`WORKER_CONCURRENCY`, padrão 20 por instância). Para cada job: resolve o nome atual da jornada (via `lib/sfmc.js`, com cache) e executa a lógica de negócio — o bloco marcado `>>> AQUI VAI A SUA LÓGICA <<<` é onde entra a chamada à API de inbox do app, com `job.title`, `job.message`, `job.contactKey` prontos.

Semântica de entrega: modo *peekLock* — a mensagem só sai da fila após `completeMessage` (sucesso). Em erro, `abandonMessage` devolve à fila; após 5 tentativas (`max-delivery-count`), vai para a DLQ. Trata `SIGTERM` para encerramento gracioso quando o autoscaling desescala.

### `lib/sfmc.js` — integração com a REST API do SFMC

Autentica via OAuth2 client-credentials (`SFMC_AUTH_URL` + `SFMC_CLIENT_ID`/`SECRET`) e expõe `lookupJourneyName(journeyId)`: busca o nome **atual** da jornada (imune a renomeações) com cache de 10 minutos. Opcional — sem as credenciais, vale o nome capturado na configuração. Requer componente **API Integration (Server-to-Server)** no Installed Package com escopo **Journeys → Read**.

### `public/` — a UI de configuração

- **`index.html`** — o modal que abre quando o usuário arrasta a activity para o canvas: campos **Título** (obrigatório, 100 chars) e **Mensagem** (obrigatória, 500 chars), box com a jornada atual e o aviso dos dados enviados automaticamente.
- **`js/customActivity.js`** — a ponte com o Journey Builder via Postmonger: dispara `ready` (sem ele o modal fica em loading eterno), pede os dados da jornada (`requestInteraction` → captura `journeyName`/`journeyId`), restaura valores salvos (`initActivity`), desabilita o botão *Done* até título e mensagem estarem preenchidos (`updateButton`) e salva a configuração na jornada (`updateActivity` no `clickedNext`). A configuração é persistida **pelo próprio Journey Builder** dentro da definição da jornada — não há banco de dados.
- **`js/postmonger.min.js`** — hospedado localmente (sem CDN) de propósito: se o CDN falhar dentro do iframe do SFMC, o `ready` nunca dispara e o modal trava no loading.

### `deploy-azure.sh` — provisionamento e publicação

Script idempotente que faz o caminho completo: registra os *resource providers* (1x por subscription) → resource group → Container Registry → **build da imagem** (Docker local `buildx --platform linux/amd64`, com fallback para ACR Tasks) → Service Bus + fila com duplicate detection/DLQ → ambiente Container Apps → `activity-api` → `activity-worker`. Ao final imprime a **URL para o Installed Package**. Pode ser rodado de novo a qualquer momento (reaproveita recursos existentes) — é também como se publica qualquer alteração de código.

### `Dockerfile` / `.dockerignore`

Uma única imagem (`node:20-alpine`, `npm ci --omit=dev`) serve os dois containers: a API usa o CMD padrão (`node server.js`); o worker sobrescreve o comando (`node worker.js`) na definição do Container App.

### `.env.example`

Modelo de todas as variáveis: `BASE_URL`, `JWT_SECRET` (JWT Signing Secret do Installed Package), `SERVICEBUS_CONNECTION_STRING`/`SERVICEBUS_QUEUE`/`WORKER_CONCURRENCY` (modo fila) e `SFMC_*` (lookup opcional do nome da jornada). Copie para `.env` no dev local; na Azure viram *secrets/env vars* dos Container Apps.

## Como funciona ponta a ponta

1. **Descoberta** — o Journey Builder lê `GET /config.json` e mostra "Novo App Inbox" na paleta (nome/descrição em `pt-BR` e `en-US`; o JB escolhe pelo idioma do usuário, com `en-US` como fallback obrigatório).
2. **Configuração** — o usuário arrasta a activity, o modal abre, preenche título e mensagem; `updateActivity` grava tudo na definição da jornada (persistência é do SFMC — sem banco).
3. **Execução** — a cada contato, o SFMC chama `/journey/execute` com JWT. A API valida, monta o job e enfileira; o worker consome e envia a mensagem de inbox.
4. **Resiliência** — retry do SFMC é deduplicado pela fila; falha no worker volta para a fila e, persistindo, cai na DLQ.

## Desenvolvimento local

```bash
npm install
cp .env.example .env        # preencha ao menos o JWT_SECRET quando tiver
npm run dev                 # API em http://localhost:3000 (modo inline, sem fila)
npm run worker              # (opcional) worker, exige SERVICEBUS_CONNECTION_STRING
```

Para testar dentro do SFMC ainda em dev, exponha com HTTPS: `ngrok http 3000` e use a URL gerada como `BASE_URL` e no Installed Package.

## Publicação na Azure

Pré-requisitos: `az` CLI (`brew install azure-cli`), `az login`, Docker Desktop rodando (`brew install --cask docker`).

```bash
bash deploy-azure.sh
```

O script imprime ao final a URL pública, algo como `https://activity-api.<ambiente>.brazilsouth.azurecontainerapps.io/`. Valide:

```bash
curl SUA_URL/health        # {"status":"ok","queue":true}
curl SUA_URL/config.json
```

### Dimensionamento inicial

| Peça | Configuração | Efeito |
|---|---|---|
| API | 1–20 réplicas, 100 req concorrentes/réplica | absorve ~2.000 requisições simultâneas |
| Worker | 0–30 réplicas × `WORKER_CONCURRENCY=20` | até ~600 mensagens em paralelo |
| Fila | +1 réplica de worker a cada 100 msgs acumuladas | escala guiada pelo backlog |

Reduza `WORKER_CONCURRENCY` se a API de inbox do app tiver rate limit — a fila segura o excedente sem perder nada. Em repouso o worker escala a **zero** e a API mantém 1 réplica pequena; o pico só custa enquanto dura.

## Disponibilizar no Marketing Cloud

1. **Setup → Apps → Installed Packages → New** (ex.: `Novo App Inbox`).
2. **Add Component → Journey Builder Activity** — Category: Custom; **Endpoint URL**: a URL do deploy (com `/` no final).
3. Copie o **JWT Signing Secret** do pacote e aplique na API:
   ```bash
   az containerapp secret set -n activity-api -g rg-custom-activity --secrets jwtsecret="SEGREDO"
   REV=$(az containerapp show -n activity-api -g rg-custom-activity --query properties.latestRevisionName -o tsv)
   az containerapp revision restart -n activity-api -g rg-custom-activity --revision "$REV"
   ```
4. (Opcional) **Add Component → API Integration (Server-to-Server)** com escopo **Journeys → Read**, e configure `SFMC_AUTH_URL`/`SFMC_CLIENT_ID`/`SFMC_CLIENT_SECRET` na API e no worker — habilita o nome de jornada sempre atualizado.
5. No componente, **Available to all business units** controla a disponibilidade para outras BUs.
6. Abra o Journey Builder → a activity aparece na paleta → arraste, configure, publique a jornada.

## Teste e depuração

- Logs em tempo real: `az containerapp logs show -n activity-worker -g rg-custom-activity --follow` (idem para `activity-api`).
- Modal em branco/loading eterno → erro de JS na UI; veja o console do navegador (F12). O guard do Postmonger exibe erro explícito se a lib não carregar.
- `401` no execute → `JWT_SECRET` ausente/errado.
- Activity não aparece na paleta → Endpoint URL sem HTTPS ou `config.json` inacessível.
- Retry infinito → o execute deve responder `200` em até 10s (com a fila isso não acontece).
- Mudou o `config.json` (nome, campos)? Recarregue a página do JB; activities já posicionadas em jornadas mantêm a configuração antiga até serem reabertas e salvas.

## Personalização

- **Lógica de envio**: bloco marcado em `worker.js` (produção) e em `server.js` (modo inline).
- **Novos campos na UI**: adicione o input em `index.html`, inclua no `inArguments` em `customActivity.js` e declare no `config.json` (`server.js`).
- **Data binding nos inArguments**: `{{Contact.Key}}`, `{{InteractionDefaults.Email}}`, `{{Event.DEAudience-XXXX."Campo"}}` (campo da DE do entry event) — o SFMC resolve o valor por contato antes de chamar o execute.
- **Múltiplos caminhos (splits)**: adicione `outcomes` no `config.json` e retorne o `branchResult` correspondente no execute.
- **Ícone**: salve um PNG quadrado (48–128px) em `public/images/icon.png` e rode o deploy — há fallback automático para o SVG enquanto ele não existir.
