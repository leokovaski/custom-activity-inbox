# Novo App Inbox — Custom Activity para o Salesforce Marketing Cloud

Custom Activity do Journey Builder que envia uma **mensagem de inbox no app do cliente** (título + mensagem) para cada contato que passa pela etapa da jornada. Projetada para grande escala: API desacoplada por fila de mensagens e worker com autoscaling.

Este repositório contém **três implementações equivalentes** do backend — escolha uma:

| Pasta | Stack | Documentação |
|---|---|---|
| [`node/`](node/) | Node.js 20 · Express · `@azure/service-bus` | [node/README.md](node/README.md) — documentação completa (arquitetura, arquivo por arquivo, deploy, SFMC) |
| [`java/`](java/) | Java 21 · Spring Boot 3.3 · Maven | [java/README.md](java/README.md) — estrutura e particularidades da versão Java |
| [`aws/`](aws/) | Java 21 · AWS Lambda + API Gateway + SQS FIFO (serverless) | [aws/README.md](aws/README.md) — variante 100% serverless na AWS |

As três versões implementam exatamente o mesmo contrato (`config.json`, endpoints, UI de configuração, fila com deduplicação, worker). `node/` e `java/` **compartilham a mesma infraestrutura Azure** — o `deploy-azure.sh` de cada pasta publica a respectiva imagem nos mesmos Container Apps (`activity-api` / `activity-worker`). A `aws/` é autônoma: provisiona sua própria stack serverless via `deploy-aws.sh`.

## Arquitetura (comum às três)

```
Journey Builder (SFMC)
      │  rajadas de POST /journey/execute (JWT assinado)
      ▼
┌─────────────────────────┐     ┌──────────────────────┐     ┌─────────────────────────┐
│  Container App: API     │ --> │  Service Bus (fila)  │ --> │  Container App: Worker  │
│  valida JWT, enfileira, │     │  journey-execute     │     │  envia a mensagem de    │
│  responde 200 em ms     │     │  + duplicate         │     │  inbox; escala pelo     │
│  escala por HTTP        │     │    detection + DLQ   │     │  tamanho da fila (KEDA) │
└─────────────────────────┘     └──────────────────────┘     └─────────────────────────┘
```

## Documentos

- [node/README.md](node/README.md) — referência principal: como a activity funciona ponta a ponta, deploy na Azure, publicação no Marketing Cloud, depuração e personalização (válido para as duas stacks; só os nomes de arquivos diferem).
- [ARQUITETURA-JUSTIFICATIVA.md](ARQUITETURA-JUSTIFICATIVA.md) — contexto para o time de arquitetura: o que é necessário, por que CloudPage não atende (com citações da documentação oficial da Salesforce) e a decisão de buffer (Service Bus vs. Kafka).
