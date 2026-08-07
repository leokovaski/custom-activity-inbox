# Infraestrutura Azure para grande escala

Arquitetura recomendada para esta Custom Activity aguentar picos de milhões de execuções do Journey Builder:

```
Journey Builder (SFMC)
      │  rajadas de POST /journey/execute (JWT)
      ▼
┌─────────────────────────┐     ┌──────────────────────┐     ┌─────────────────────────┐
│  Container App: API     │ --> │  Service Bus (fila)  │ --> │  Container App: Worker  │
│  (server.js)            │     │  journey-execute     │     │  (worker.js)            │
│  valida JWT, enfileira, │     │  + duplicate         │     │  lógica de negócio,     │
│  responde 200 em ms     │     │    detection + DLQ   │     │  escala pelo tamanho    │
│  escala por HTTP        │     │                      │     │  da fila (KEDA)         │
└─────────────────────────┘     └──────────────────────┘     └─────────────────────────┘
              │                                                        │
              └──────────────► Application Insights ◄──────────────────┘
```

Por que assim:

- **API desacoplada do processamento**: o SFMC exige resposta em ~10s e reenviA em caso de erro. Enfileirar e responder em milissegundos elimina timeouts mesmo se a API externa que você chama estiver lenta.
- **Container Apps** escala horizontal e automaticamente (0 → N réplicas) e você não administra servidor. A API escala por requisições HTTP; o worker escala pelo número de mensagens na fila (KEDA nativo).
- **Service Bus** com *duplicate detection* descarta os retries do SFMC (o `messageId` é derivado de `activityInstanceId + contactKey`), e a *dead-letter queue* guarda mensagens que falharam repetidamente para você reprocessar.

## Provisionamento (az cli)

> Pré-requisitos: `az login` feito e Docker instalado (ou use `az acr build`, que builda na nuvem).

```bash
# Variáveis
RG=rg-custom-activity
LOC=brazilsouth
ACR=acrcustomactivity$RANDOM        # nome global único
SB_NS=sb-custom-activity$RANDOM     # nome global único
ENV=cae-custom-activity

# 1. Resource group
az group create -n $RG -l $LOC

# 2. Container Registry + build da imagem (na nuvem, sem Docker local)
az acr create -n $ACR -g $RG --sku Basic --admin-enabled true
az acr build -r $ACR -t custom-activity:latest .

# 3. Service Bus + fila com duplicate detection e DLQ
az servicebus namespace create -n $SB_NS -g $RG -l $LOC --sku Standard
az servicebus queue create -n journey-execute -g $RG --namespace-name $SB_NS \
  --enable-duplicate-detection true \
  --duplicate-detection-history-time-window PT30M \
  --max-delivery-count 5 \
  --lock-duration PT2M

SB_CONN=$(az servicebus namespace authorization-rule keys list -g $RG \
  --namespace-name $SB_NS -n RootManageSharedAccessKey \
  --query primaryConnectionString -o tsv)

# 4. Ambiente do Container Apps (inclui Log Analytics)
az containerapp env create -n $ENV -g $RG -l $LOC

# 5. API — escala por requisições HTTP concorrentes
az containerapp create -n activity-api -g $RG --environment $ENV \
  --image $ACR.azurecr.io/custom-activity:latest \
  --registry-server $ACR.azurecr.io \
  --target-port 3000 --ingress external \
  --min-replicas 1 --max-replicas 20 \
  --scale-rule-name http-burst --scale-rule-type http \
  --scale-rule-http-concurrency 100 \
  --secrets sbconn="$SB_CONN" jwtsecret="COLOQUE_O_JWT_SIGNING_SECRET" \
  --env-vars SERVICEBUS_CONNECTION_STRING=secretref:sbconn \
             JWT_SECRET=secretref:jwtsecret \
             SERVICEBUS_QUEUE=journey-execute

# Descubra a URL pública e configure BASE_URL
API_URL=https://$(az containerapp show -n activity-api -g $RG \
  --query properties.configuration.ingress.fqdn -o tsv)
az containerapp update -n activity-api -g $RG --set-env-vars BASE_URL=$API_URL

# 6. Worker — escala pelo tamanho da fila (KEDA), pode ir a ZERO quando ociosa
az containerapp create -n activity-worker -g $RG --environment $ENV \
  --image $ACR.azurecr.io/custom-activity:latest \
  --command "node" "worker.js" \
  --min-replicas 0 --max-replicas 30 \
  --secrets sbconn="$SB_CONN" \
  --env-vars SERVICEBUS_CONNECTION_STRING=secretref:sbconn \
             SERVICEBUS_QUEUE=journey-execute WORKER_CONCURRENCY=20 \
  --scale-rule-name queue-depth --scale-rule-type azure-servicebus \
  --scale-rule-metadata queueName=journey-execute messageCount=100 \
  --scale-rule-auth connection=sbconn

echo "Endpoint para o Installed Package do SFMC: $API_URL/"
```

Depois disso, use a `$API_URL/` como **Endpoint URL** no componente Journey Builder Activity do Installed Package (e o `config.json` já se ajusta sozinho via `BASE_URL`).

## Dimensionamento — por onde começar

| Peça | Configuração inicial | Efeito |
|---|---|---|
| API | 1–20 réplicas, 100 req concorrentes/réplica | absorve ~2.000 requisições simultâneas |
| Worker | 0–30 réplicas × `WORKER_CONCURRENCY=20` | até ~600 mensagens em paralelo |
| Fila | `messageCount=100` por réplica | +1 worker a cada 100 msgs acumuladas |

Ajuste `WORKER_CONCURRENCY` para baixo se a API externa que você chama tiver rate limit — a fila segura o excedente sem perder nada.

## Observabilidade e resiliência

- **Application Insights / Log Analytics**: o ambiente do Container Apps já envia stdout para o Log Analytics (`az containerapp logs show -n activity-worker -g $RG --follow`). Para tracing por requisição, adicione o SDK `applicationinsights` no `server.js`/`worker.js`.
- **Dead-letter queue**: mensagens que falharem 5 vezes (`max-delivery-count`) caem na DLQ da fila `journey-execute`. Monitore-a (alerta no Azure Monitor) e reprocesse quando a causa for corrigida.
- **Segredos**: para produção séria, mova `jwtsecret`/`sbconn` para o **Azure Key Vault** e referencie no Container App com identidade gerenciada.
- **Cache compartilhado**: com várias réplicas, o cache do token SFMC e de nomes de jornada é por instância. Se o volume de lookups incomodar, adicione **Azure Cache for Redis** e adapte `lib/sfmc.js`.

## Custos (ordem de grandeza)

- Container Apps cobra por vCPU/memória-segundo — com `min-replicas 0` no worker e 1 na API, o custo em repouso é de uma réplica pequena (~poucos dólares/mês).
- Service Bus Standard: valor fixo baixo + por operação (milhões de mensagens custam poucos dólares).
- O pico só custa enquanto durar — essa é a vantagem sobre uma VPS dimensionada para o pior caso.
