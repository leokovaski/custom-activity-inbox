# Novo App Inbox — variante AWS serverless (Java)

Implementação **100% serverless** da mesma custom activity documentada no [README raiz](../README.md): sem containers, sem servidor residente — só Lambda, API Gateway e SQS.

## Arquitetura

```
Journey Builder (SFMC)
      │  rajadas de POST /journey/execute (JWT assinado)
      ▼
┌──────────────────────────┐    ┌───────────────────────┐    ┌──────────────────────────┐
│  API Gateway (HTTP API)  │--> │  SQS FIFO             │--> │  Lambda: WorkerHandler   │
│  + Lambda: ApiHandler    │    │  inbox-activity-      │    │  (event source mapping:  │
│  valida JWT, enfileira,  │    │  execute.fifo         │    │  a AWS puxa, controla    │
│  responde 200 em ms;     │    │  dedup nativa + DLQ   │    │  concorrência, retry e   │
│  serve config.json e UI  │    │                       │    │  DLQ automaticamente)    │
└──────────────────────────┘    └───────────────────────┘    └──────────────────────────┘
                └── CloudWatch Logs (ambas as Lambdas) ──────────────┘
```

Diferenças-chave vs. a versão em containers (Azure):

- **Não existe processo worker seu rodando** — o *event source mapping* da AWS entrega os lotes à Lambda, controla a concorrência (`MaximumConcurrency=20`), refaz tentativas e move para a DLQ após 5 falhas. Você só escreve a função.
- **Deduplicação** é da própria fila FIFO: `MessageDeduplicationId` = hash de `activityInstanceId|contactKey|definitionInstanceId` (janela de 5 min). `MessageGroupId` = `contactKey`, preservando paralelismo entre contatos.
- **A UI é servida do próprio jar** pela Lambda da API (arquivos em `resources/static`) — dispensa S3/CloudFront para este porte.
- **Custo em repouso: zero.** Paga-se por requisição/execução.

## Estrutura

```
aws/
├── pom.xml                     # fat jar via maven-shade (exigência do Lambda)
├── deploy-aws.sh               # provisiona e publica tudo via AWS CLI
└── src/main/
    ├── java/com/inbox/activity/aws/
    │   ├── ApiHandler.java     # rotas: config.json, health, UI estática, /journey/*
    │   ├── WorkerHandler.java  # consumidor SQS (lógica de negócio aqui)
    │   ├── ConfigJson.java     # contrato do Journey Builder
    │   ├── JwtVerifier.java    # HS256 com o JWT Signing Secret
    │   └── SfmcClient.java     # OAuth2 + lookup do nome da jornada (cache 10min)
    └── resources/static/       # UI de configuração (mesma das outras versões)
```

## O que você precisa no ambiente

1. **Conta AWS** com um usuário/role com permissão de administrar Lambda, SQS, API Gateway e IAM (na prática: `AdministratorAccess` ou equivalente para o primeiro deploy).
2. **AWS CLI v2** — `brew install awscli`, depois `aws configure` (informe Access Key, Secret e região; o script usa `sa-east-1`/São Paulo por padrão — sobrescreva com `AWS_REGION=us-east-1` se quiser).
3. **JDK 21 + Maven** — `brew install openjdk@21 maven`.
4. Docker **não** é necessário (o deploy sobe o jar direto).

## Deploy

```bash
cd aws
bash deploy-aws.sh
```

O script (idempotente — rode de novo para publicar alterações):

1. builda o fat jar;
2. cria a DLQ e a fila FIFO com redrive policy (5 tentativas);
3. cria a IAM role das Lambdas (logs + acesso à fila);
4. cria/atualiza as duas Lambdas (`java21`, 1024 MB);
5. cria o API Gateway HTTP API apontando tudo para a `ApiHandler`;
6. injeta a `BASE_URL` real na Lambda;
7. liga a fila ao worker (event source mapping com `ReportBatchItemFailures`).

Ao final, imprime a **URL para o Installed Package** (`https://xxxx.execute-api.REGIAO.amazonaws.com/`). O fluxo no Marketing Cloud é o mesmo das outras versões (ver README raiz); para aplicar o JWT Signing Secret depois de criar o pacote:

```bash
JWT_SECRET="SEGREDO_DO_PACOTE" bash deploy-aws.sh
```

## Observações de produção

- **Cold start**: Java na Lambda pode levar alguns segundos na primeira execução. Se incomodar nos testes, considere habilitar **SnapStart** (gratuito para Java) ou provisioned concurrency na Lambda da API — o `execute` tem folga (timeout do SFMC em 10s), mas o modal da UI fica mais ágil.
- **Logs**: `aws logs tail /aws/lambda/inbox-activity-worker --follow` (idem `-api`).
- **Fila FIFO tem throughput padrão de 300 msg/s** (3.000 com high-throughput mode habilitado nos atributos da fila) — suficiente para a maioria dos disparos; para volumes extremos, habilite `FifoThroughputLimit=perMessageGroupId` e `DeduplicationScope=messageGroup`.
- **Segredos**: para produção séria, mova `JWT_SECRET` para o AWS Secrets Manager e leia no cold start em vez de env var.
