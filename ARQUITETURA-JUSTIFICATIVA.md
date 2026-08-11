# Custom Activity "Novo App Inbox" — Contexto para o Time de Arquitetura

> Documento de apoio à decisão de arquitetura: o que é necessário para termos uma
> Custom Activity no Journey Builder do Salesforce Marketing Cloud, por que a
> hospedagem via CloudPage não atende, e qual a decisão em aberto (buffer de mensageria).

---

## 1. O que precisamos para ter uma Custom Activity

Uma Custom Activity é um bloco customizado que arrastamos nas jornadas do Journey Builder — no nosso caso, para **enviar mensagem de inbox no app do cliente** (título + mensagem definidos pelo usuário de marketing na própria jornada). O Salesforce não hospeda nada: **nós fornecemos uma aplicação web própria** e o Marketing Cloud a consome.

São três blocos:

**1) Uma aplicação HTTPS pública**, com dois papéis:

- Uma **tela de configuração** aberta dentro do Journey Builder (onde o usuário define título e mensagem);
- Uma **API** que o Salesforce chama **para cada contato** que passa pela etapa, autenticada por JWT assinado com segredo compartilhado.

**2) Arquitetura assíncrona para absorver picos.** O Salesforce dispara em rajadas (milhões de contatos de uma vez), exige resposta rápida (timeout configurável, padrão 20s e máx. 100s) e reenvia em caso de falha. Por isso a API apenas valida e publica o evento em um **buffer de mensageria**, e um **worker** consome no ritmo adequado, com escala horizontal automática, deduplicação de reenvios e tratamento de falhas (retry + dead-letter). Sem estado próprio: a configuração das jornadas fica persistida no próprio Salesforce.

**3) Um registro no Marketing Cloud** (Installed Package) apontando para a URL da aplicação — é o que faz a activity aparecer nas jornadas — mais observabilidade padrão (logs/métricas) e gestão de segredos no nosso lado.

Já existe uma **implementação de referência funcionando**: Node.js, dois containers com autoscaling na Azure (Container Apps) + fila, custo em repouso próximo de zero e escala guiada pelo backlog.

---

## 2. Por que publicar como CloudPage não atende

CloudPages (incluindo Code Resources) foram desenhadas para páginas de destino e formulários voltados ao assinante — não para operar como backend de API. Hospedar a custom activity ali é tecnicamente possível como prova de conceito, mas esbarra em limitações estruturais:

**Escala e desempenho.** O endpoint de execução é chamado uma vez por contato, em rajadas de até milhões de requisições. CloudPages não oferecem autoscaling controlável nem garantia de latência; o processamento em SSJS é interpretado e lento, e sob rajada a página degrada ou sofre throttling da própria plataforma — causando timeouts, reprocessos e contatos com erro na jornada.

**Ausência de processamento assíncrono.** A necessidade exige responder ao Journey Builder em milissegundos e processar o envio de forma desacoplada (fila + worker), com controle de concorrência, retry com backoff, deduplicação e dead-letter. Nada disso existe em CloudPages: todo o trabalho seria síncrono dentro da janela de timeout, com risco de avalanche quando a API de destino oscilar.

**Limitações da plataforma de código.** SSJS é uma implementação antiga de JavaScript, sem bibliotecas modernas — sem SDK de mensageria (Service Bus/Kafka), sem validação robusta de JWT e sem gestão adequada de segredos (credenciais ficariam embutidas no código da página, visíveis a qualquer usuário com acesso ao Web Studio).

**Operação e engenharia.** CloudPages não se integram ao ciclo de engenharia: sem versionamento em Git, sem CI/CD, sem ambientes segregados (dev/homolog/prod), sem observabilidade (logs estruturados, métricas, alertas) e sem forma adequada de depurar incidentes.

**Dependência circular e resiliência.** A activity existiria dentro da mesma plataforma que a consome: qualquer instabilidade do Marketing Cloud afetaria simultaneamente o chamador e o executor, eliminando a capacidade de amortecer falhas — exatamente o que a arquitetura com fila resolve.

**Custo.** Visitas a CloudPages consomem Super Messages do contrato. Com execução por contato em escala de milhões, o custo por disparo se tornaria relevante e recorrente — enquanto a arquitetura proposta tem custo próximo de zero em repouso e proporcional ao uso no pico.

### O que diz a documentação oficial da Salesforce

Não há documento que proíba CloudPages nominalmente — o que existe é mais forte: **todo o modelo oficial pressupõe uma aplicação web externa hospedada pelo cliente**, e a recomendação nominal de hospedagem da Salesforce é um PaaS externo:

- *"Use a web server that communicates via SSL (port 443)."* e *"Salesforce recommends using **Heroku** as a hosting provider."* — [Build Custom Activities and Events (Salesforce Developers)](https://developer.salesforce.com/docs/marketing/marketing-cloud/guide/creating-activities.html)
- *"You then need to host your custom activity on a web server that communicates via SSL (port 443). We recommend using Heroku as a hosting provider."* — [Develop Your Custom Activity (Trailhead oficial)](https://trailhead.salesforce.com/content/learn/modules/custom-activities-in-journey-builder/develop-your-custom-activity)
- A especificação do `config.json` estrutura a activity em torno de **endpoints HTTPS externos** (`execute`, `save`, `publish`, `validate`, `stop`) invocados pelo Journey Builder — [Create Custom Activity Configuration (Salesforce Developers)](https://developer.salesforce.com/docs/marketing/marketing-cloud/guide/custom-activity-config.html)

CloudPages **não aparece como opção de hosting em nenhum material oficial** de Custom Activities; seu uso para esse fim é um improviso da comunidade, sem suporte documentado.

---

## 3. Decisão em aberto: qual buffer de mensageria

A implementação de referência usa **Azure Service Bus**, mas a escolha do buffer está **isolada em dois pontos do código** (publicar e consumir) — trocá-la não afeta a activity, o SFMC ou a UI. Opções:

| Opção | Prós | Contras |
|---|---|---|
| **Azure Service Bus** | Fila gerenciada; deduplicação nativa (descarta retries do SFMC), dead-letter e retry por mensagem prontos; zero operação | Menos adequado se o padrão corporativo já for Kafka |
| **Kafka** | Throughput altíssimo, replay, consumer groups; ideal se **já existe cluster operado** na empresa | Dedup, retry com backoff e DLQ viram responsabilidade do nosso código; operar cluster só para este caso é desproporcional |
| **Azure Event Hubs (protocolo Kafka)** | Time usa bibliotecas/conhecimento Kafka **sem operar cluster**; gerenciado na mesma nuvem | Mesmas responsabilidades de dedup/retry no código que o Kafka |

O autoscaling da camada de processamento (KEDA / Container Apps) funciona com as três opções.

**Recomendação:** Service Bus se não houver Kafka como padrão corporativo (menos código e menos operação); Kafka/Event Hubs se a empresa já tiver o ecossistema e quiser padronizar.

---

## 4. Resumo executivo

| Item | Definição |
|---|---|
| O que é | Bloco customizado no Journey Builder que envia mensagem de inbox no app |
| O que fornecemos | Aplicação web HTTPS própria (UI de configuração + API por contato) |
| Padrão de escala | API stateless → buffer de mensageria → worker com autoscaling (0→N) |
| Onde roda | Azure Container Apps (referência implementada) |
| Por que não CloudPage | Sem escala/assíncrono/observabilidade/CI-CD; custo em Super Messages; modelo oficial pressupõe app externa |
| Decisão pendente | Buffer: Service Bus (default) vs. Kafka/Event Hubs (se padrão corporativo) |
