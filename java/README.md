# Novo App Inbox — versão Java (Spring Boot)

Implementação Java da mesma custom activity documentada no [README raiz](../README.md). Arquitetura, endpoints, UI e infraestrutura Azure são **idênticos à versão Node** — muda apenas a stack do backend.

## Stack

- Java 21 · Spring Boot 3.3 · Maven
- `azure-messaging-servicebus` (fila) · `jjwt` (validação do JWT do SFMC)
- Mesma imagem Docker para API e worker (o worker ativa com `SPRING_PROFILES_ACTIVE=worker`)

## Estrutura

```
java/
├── pom.xml
├── Dockerfile                        # multi-stage: maven build -> JRE alpine
├── deploy-azure.sh                   # deploy reaproveitando a infra existente
└── src/main/
    ├── java/com/inbox/activity/
    │   ├── Application.java          # entrypoint (API ou worker, por profile)
    │   ├── config/AppProperties.java # env vars (12-factor)
    │   ├── controller/
    │   │   ├── ConfigJsonController.java  # GET /config.json + /health + fallback do ícone
    │   │   └── JourneyController.java     # POST /journey/execute|save|publish|validate|stop
    │   ├── security/JwtVerifier.java      # HS256 com o JWT Signing Secret
    │   ├── service/
    │   │   ├── QueuePublisher.java        # enfileira com messageId determinístico (dedup)
    │   │   └── SfmcClient.java            # OAuth2 + lookup do nome da jornada (cache 10min)
    │   └── worker/QueueWorker.java        # consumidor da fila (profile "worker")
    └── resources/
        ├── application.yml
        └── static/                        # UI de configuração (mesma da versão Node)
```

## Rodar localmente

```bash
mvn spring-boot:run                          # API em http://localhost:8080 (modo inline)
SPRING_PROFILES_ACTIVE=worker mvn spring-boot:run   # worker (exige SERVICEBUS_CONNECTION_STRING)
```

Variáveis de ambiente: as mesmas da versão Node (ver `.env.example`) — `BASE_URL`, `JWT_SECRET`, `SERVICEBUS_*`, `SFMC_*`. No shell: `export VAR=valor` antes do `mvn spring-boot:run`.

## Build e deploy

```bash
mvn package -DskipTests        # gera target/custom-activity-1.0.0.jar
bash deploy-azure.sh           # build da imagem + deploy na mesma infra Azure
```

O script reaproveita o resource group, registry, Service Bus e ambiente já criados pela versão Node — os Container Apps `activity-api` e `activity-worker` passam a rodar a imagem Java (`custom-activity-java:latest`). Para voltar à versão Node, rode o `deploy-azure.sh` da pasta `node/`.

> A porta da API Java é **8080** (Node usa 3000) — o script já configura o `--target-port` correto.

## Onde implementar o envio da mensagem

- **Produção (fila):** `worker/QueueWorker.java`, bloco marcado `>>> AQUI VAI A SUA LÓGICA <<<` — recebe `title`, `message`, `contactKey`, `journeyName` prontos.
- **Modo inline (dev):** `controller/JourneyController.java`, mesmo marcador.
