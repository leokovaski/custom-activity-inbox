#!/usr/bin/env bash
# ============================================================================
# Deploy da Custom Activity (versão JAVA) na Azure
# (Container Apps + Service Bus + Container Registry)
#
# Uso:
#   az login
#   cd java && bash deploy-azure.sh
#
# Reaproveita a MESMA infraestrutura da versão Node (resource group, registry,
# Service Bus, ambiente) e atualiza os apps activity-api/activity-worker para
# a imagem Java. Ao final, imprime a URL para o Installed Package do SFMC.
# ============================================================================
set -euo pipefail

RG="rg-custom-activity"
LOC="brazilsouth"
SUFFIX="$(whoami | tr -dc 'a-z0-9' | cut -c1-6)$RANDOM"
ENV="cae-custom-activity"
QUEUE="journey-execute"
IMAGE_TAG="custom-activity-java:latest"

JWT_SECRET="${JWT_SECRET:-PLACEHOLDER_TROQUE_DEPOIS}"

echo "==> Registrando resource providers (necessário 1x por subscription)"
for NS in Microsoft.ContainerRegistry Microsoft.ServiceBus Microsoft.App Microsoft.OperationalInsights; do
  STATE=$(az provider show -n "$NS" --query registrationState -o tsv 2>/dev/null || echo "NotRegistered")
  [ "$STATE" != "Registered" ] && az provider register -n "$NS" --wait
done

echo "==> Resource group"
az group create -n "$RG" -l "$LOC" -o none

# Reaproveita recursos já criados (idempotente)
ACR=$(az acr list -g "$RG" --query "[0].name" -o tsv 2>/dev/null || true)
[ -z "$ACR" ] && ACR="acrcactivity${SUFFIX}"
SB_NS=$(az servicebus namespace list -g "$RG" --query "[0].name" -o tsv 2>/dev/null || true)
[ -z "$SB_NS" ] && SB_NS="sb-cactivity-${SUFFIX}"

echo "==> Container Registry"
az acr create -n "$ACR" -g "$RG" --sku Basic --admin-enabled true -o none

echo "==> Build da imagem Java"
IMAGE="$ACR.azurecr.io/$IMAGE_TAG"
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  echo "    usando Docker local (buildx, linux/amd64)..."
  az acr login -n "$ACR"
  docker buildx build --platform linux/amd64 -t "$IMAGE" --push .
else
  echo "    Docker não encontrado — tentando build na nuvem (ACR Tasks)..."
  az acr build -r "$ACR" -t "$IMAGE_TAG" . -o none
fi

echo "==> Service Bus + fila"
az servicebus namespace create -n "$SB_NS" -g "$RG" -l "$LOC" --sku Standard -o none
az servicebus queue create -n "$QUEUE" -g "$RG" --namespace-name "$SB_NS" \
  --enable-duplicate-detection true \
  --duplicate-detection-history-time-window PT30M \
  --max-delivery-count 5 \
  --lock-duration PT2M -o none

SB_CONN=$(az servicebus namespace authorization-rule keys list -g "$RG" \
  --namespace-name "$SB_NS" -n RootManageSharedAccessKey \
  --query primaryConnectionString -o tsv)

echo "==> Ambiente do Container Apps"
az containerapp env create -n "$ENV" -g "$RG" -l "$LOC" -o none

echo "==> API (activity-api) — escala por HTTP"
az containerapp create -n activity-api -g "$RG" --environment "$ENV" \
  --image "$IMAGE" \
  --registry-server "$ACR.azurecr.io" \
  --target-port 8080 --ingress external \
  --min-replicas 1 --max-replicas 20 \
  --scale-rule-name http-burst --scale-rule-type http \
  --scale-rule-http-concurrency 100 \
  --secrets sbconn="$SB_CONN" jwtsecret="$JWT_SECRET" \
  --env-vars SERVICEBUS_CONNECTION_STRING=secretref:sbconn \
             JWT_SECRET=secretref:jwtsecret \
             SERVICEBUS_QUEUE="$QUEUE" -o none

API_FQDN=$(az containerapp show -n activity-api -g "$RG" \
  --query properties.configuration.ingress.fqdn -o tsv)
API_URL="https://${API_FQDN}"

az containerapp update -n activity-api -g "$RG" --set-env-vars BASE_URL="$API_URL" -o none

echo "==> Worker (activity-worker) — escala pelo tamanho da fila (0 a 30)"
az containerapp create -n activity-worker -g "$RG" --environment "$ENV" \
  --image "$IMAGE" \
  --registry-server "$ACR.azurecr.io" \
  --min-replicas 0 --max-replicas 30 \
  --secrets sbconn="$SB_CONN" \
  --env-vars SERVICEBUS_CONNECTION_STRING=secretref:sbconn \
             SERVICEBUS_QUEUE="$QUEUE" WORKER_CONCURRENCY=20 \
             SPRING_PROFILES_ACTIVE=worker \
  --scale-rule-name queue-depth --scale-rule-type azure-servicebus \
  --scale-rule-metadata queueName="$QUEUE" messageCount=100 \
  --scale-rule-auth connection=sbconn -o none

echo
echo "============================================================"
echo " DEPLOY (JAVA) CONCLUÍDO"
echo "============================================================"
echo
echo " URL da aplicação (Endpoint URL no Installed Package):"
echo
echo "   ${API_URL}/"
echo
echo " Valide: curl ${API_URL}/health && curl ${API_URL}/config.json"
echo
echo " JWT Signing Secret (após criar o Installed Package):"
echo "   az containerapp secret set -n activity-api -g $RG --secrets jwtsecret=\"SEGREDO\""
echo "   REV=\$(az containerapp show -n activity-api -g $RG --query properties.latestRevisionName -o tsv)"
echo "   az containerapp revision restart -n activity-api -g $RG --revision \"\$REV\""
echo "============================================================"
