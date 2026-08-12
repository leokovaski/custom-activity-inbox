#!/usr/bin/env bash
# ============================================================================
# Deploy da Custom Activity (variante JAVA SERVERLESS) na AWS
# (Lambda + API Gateway HTTP API + SQS FIFO com DLQ)
#
# Pré-requisitos:
#   - AWS CLI v2 instalada e autenticada (aws configure / aws sso login)
#   - Maven + JDK 21
#
# Uso:
#   cd aws && bash deploy-aws.sh
#
# Idempotente: pode ser rodado de novo para publicar alterações de código.
# Ao final, imprime a URL para o Installed Package do SFMC.
# ============================================================================
set -euo pipefail

REGION="${AWS_REGION:-sa-east-1}"          # São Paulo; troque se preferir
PREFIX="inbox-activity"
QUEUE_NAME="${PREFIX}-execute.fifo"
DLQ_NAME="${PREFIX}-execute-dlq.fifo"
API_FN="${PREFIX}-api"
WORKER_FN="${PREFIX}-worker"
ROLE_NAME="${PREFIX}-lambda-role"
JAR="target/custom-activity-aws-1.0.0.jar"

# JWT Signing Secret do Installed Package (placeholder no 1º deploy;
# depois de criar o pacote no SFMC, rode de novo com JWT_SECRET=... )
JWT_SECRET="${JWT_SECRET:-PLACEHOLDER_TROQUE_DEPOIS}"

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)

echo "==> Build (fat jar)"
mvn -q package -DskipTests

echo "==> Fila DLQ + fila principal (SQS FIFO com deduplicação)"
DLQ_URL=$(aws sqs create-queue --region "$REGION" --queue-name "$DLQ_NAME" \
  --attributes '{"FifoQueue":"true"}' --query QueueUrl --output text)
DLQ_ARN=$(aws sqs get-queue-attributes --region "$REGION" --queue-url "$DLQ_URL" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)

QUEUE_URL=$(aws sqs create-queue --region "$REGION" --queue-name "$QUEUE_NAME" \
  --attributes "{
    \"FifoQueue\":\"true\",
    \"VisibilityTimeout\":\"120\",
    \"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$DLQ_ARN\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\"
  }" --query QueueUrl --output text)
QUEUE_ARN=$(aws sqs get-queue-attributes --region "$REGION" --queue-url "$QUEUE_URL" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)

echo "==> IAM role das Lambdas"
if ! aws iam get-role --role-name "$ROLE_NAME" >/dev/null 2>&1; then
  aws iam create-role --role-name "$ROLE_NAME" --assume-role-policy-document '{
    "Version":"2012-10-17",
    "Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]
  }' >/dev/null
  aws iam attach-role-policy --role-name "$ROLE_NAME" \
    --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole
  aws iam put-role-policy --role-name "$ROLE_NAME" --policy-name sqs-access \
    --policy-document "{
      \"Version\":\"2012-10-17\",
      \"Statement\":[{\"Effect\":\"Allow\",
        \"Action\":[\"sqs:SendMessage\",\"sqs:ReceiveMessage\",\"sqs:DeleteMessage\",\"sqs:GetQueueAttributes\"],
        \"Resource\":[\"$QUEUE_ARN\",\"$DLQ_ARN\"]}]
    }"
  echo "    aguardando propagação da role (10s)..."
  sleep 10
fi
ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/${ROLE_NAME}"

create_or_update_function() {
  local NAME=$1 HANDLER=$2 TIMEOUT=$3 ENV_VARS=$4
  if aws lambda get-function --region "$REGION" --function-name "$NAME" >/dev/null 2>&1; then
    aws lambda update-function-code --region "$REGION" --function-name "$NAME" \
      --zip-file "fileb://$JAR" >/dev/null
    aws lambda wait function-updated --region "$REGION" --function-name "$NAME"
    aws lambda update-function-configuration --region "$REGION" --function-name "$NAME" \
      --environment "$ENV_VARS" --timeout "$TIMEOUT" >/dev/null
    aws lambda wait function-updated --region "$REGION" --function-name "$NAME"
  else
    aws lambda create-function --region "$REGION" --function-name "$NAME" \
      --runtime java21 --handler "$HANDLER" --role "$ROLE_ARN" \
      --zip-file "fileb://$JAR" --memory-size 1024 --timeout "$TIMEOUT" \
      --environment "$ENV_VARS" >/dev/null
    aws lambda wait function-active --region "$REGION" --function-name "$NAME"
  fi
}

echo "==> Lambda da API ($API_FN)"
create_or_update_function "$API_FN" "com.inbox.activity.aws.ApiHandler::handleRequest" 15 \
  "Variables={QUEUE_URL=$QUEUE_URL,JWT_SECRET=$JWT_SECRET,BASE_URL=pendente}"

echo "==> Lambda do worker ($WORKER_FN)"
create_or_update_function "$WORKER_FN" "com.inbox.activity.aws.WorkerHandler::handleRequest" 120 \
  "Variables={QUEUE_URL=$QUEUE_URL}"

echo "==> API Gateway (HTTP API)"
API_ID=$(aws apigatewayv2 get-apis --region "$REGION" \
  --query "Items[?Name=='$PREFIX'].ApiId | [0]" --output text)
if [ "$API_ID" = "None" ] || [ -z "$API_ID" ]; then
  API_ID=$(aws apigatewayv2 create-api --region "$REGION" --name "$PREFIX" \
    --protocol-type HTTP \
    --target "arn:aws:lambda:${REGION}:${ACCOUNT_ID}:function:${API_FN}" \
    --query ApiId --output text)
  aws lambda add-permission --region "$REGION" --function-name "$API_FN" \
    --statement-id apigw-invoke --action lambda:InvokeFunction \
    --principal apigateway.amazonaws.com \
    --source-arn "arn:aws:execute-api:${REGION}:${ACCOUNT_ID}:${API_ID}/*" >/dev/null
fi
API_URL="https://${API_ID}.execute-api.${REGION}.amazonaws.com"

echo "==> Atualizando BASE_URL=$API_URL"
aws lambda update-function-configuration --region "$REGION" --function-name "$API_FN" \
  --environment "Variables={QUEUE_URL=$QUEUE_URL,JWT_SECRET=$JWT_SECRET,BASE_URL=$API_URL}" >/dev/null
aws lambda wait function-updated --region "$REGION" --function-name "$API_FN"

echo "==> Event source mapping: fila -> worker"
EXISTING=$(aws lambda list-event-source-mappings --region "$REGION" \
  --function-name "$WORKER_FN" --query "EventSourceMappings[0].UUID" --output text)
if [ "$EXISTING" = "None" ] || [ -z "$EXISTING" ]; then
  aws lambda create-event-source-mapping --region "$REGION" \
    --function-name "$WORKER_FN" \
    --event-source-arn "$QUEUE_ARN" \
    --batch-size 10 \
    --function-response-types ReportBatchItemFailures \
    --scaling-config MaximumConcurrency=20 >/dev/null
fi

echo
echo "============================================================"
echo " DEPLOY (AWS SERVERLESS) CONCLUÍDO"
echo "============================================================"
echo
echo " URL da aplicação (Endpoint URL no Installed Package):"
echo
echo "   ${API_URL}/"
echo
echo " Valide: curl ${API_URL}/health && curl ${API_URL}/config.json"
echo
echo " JWT Signing Secret (após criar o Installed Package no SFMC):"
echo "   JWT_SECRET=\"SEGREDO\" bash deploy-aws.sh   # re-rodar aplica o segredo"
echo "============================================================"
