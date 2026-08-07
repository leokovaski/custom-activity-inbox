# Custom Activity — Salesforce Marketing Cloud (Journey Builder)

Custom Activity completa em Node.js + Express, pronta para desenvolver, publicar e disponibilizar no Journey Builder.

## Estrutura do projeto

```
CustomActivity/
├── server.js                  # Servidor Express: config.json dinâmico + endpoints do JB
├── package.json
├── Procfile                   # Para deploy no Heroku
├── .env.example               # Variáveis de ambiente (copie para .env)
└── public/
    ├── index.html             # UI de configuração (iframe no Journey Builder)
    ├── js/customActivity.js   # Postmonger (comunicação com o Journey Builder)
    ├── css/styles.css
    └── images/icon.svg        # Ícone da activity no canvas
```

## Como funciona

O Journey Builder interage com a activity de três formas:

1. **`GET /config.json`** — define nome, ícone, endpoints e argumentos da activity. É servido dinamicamente para injetar a `BASE_URL`.
2. **UI de configuração** (`index.html` + Postmonger) — aberta em um modal quando o usuário arrasta a activity para o canvas. Ao clicar em *Next/Done*, os `inArguments` são salvos na jornada via `updateActivity`.
3. **`POST /journey/execute`** — chamado pelo SFMC **para cada contato** que passa pela etapa. O corpo é um **JWT assinado** com o segredo do Installed Package; o servidor valida com `jsonwebtoken` e executa a lógica de negócio.

Endpoints de ciclo de vida: `save`, `publish`, `validate`, `stop` (chamados quando a jornada é salva/publicada/validada/parada).

## 1. Desenvolvimento local

```bash
npm install
cp .env.example .env       # preencha depois com o JWT_SECRET
npm run dev                # http://localhost:3000
```

Para testar dentro do SFMC ainda em desenvolvimento, exponha o localhost com **ngrok**:

```bash
ngrok http 3000
# use a URL https gerada como BASE_URL no .env e no Installed Package
```

> O SFMC exige **HTTPS** em todos os endpoints — por isso o ngrok é necessário em dev.

## 2. Publicação (deploy)

Qualquer host Node.js com HTTPS serve. Opções comuns:

### Render (gratuito para começar)
1. Suba o projeto para um repositório GitHub.
2. Em [render.com](https://render.com): **New > Web Service**, conecte o repo.
3. Build: `npm install` · Start: `node server.js`.
4. Em *Environment*, configure `BASE_URL` (a URL do próprio serviço) e `JWT_SECRET`.

### Heroku
```bash
heroku create minha-custom-activity
heroku config:set BASE_URL=https://minha-custom-activity.herokuapp.com JWT_SECRET=xxx
git push heroku main
```

Após o deploy, confira: `https://SUA-URL/config.json` e `https://SUA-URL/health`.

## 3. Disponibilizar no Marketing Cloud

1. No SFMC: **Setup → Apps → Installed Packages → New**.
2. Dê um nome (ex.: `Custom Activity`) e salve.
3. **Add Component → Journey Builder Activity**:
   - **Category**: Custom (ou a que preferir)
   - **Endpoint URL**: `https://SUA-URL/` (a raiz — o JB busca o `config.json` a partir dela)
4. Salve. Copie o **JWT Signing Secret** do pacote e configure como `JWT_SECRET` no host (Render/Heroku). Reinicie o serviço.
5. Abra o **Journey Builder** → crie/edite uma jornada → a activity aparece na paleta (categoria *Custom*).
6. Arraste para o canvas, configure no modal, salve e **publique a jornada**.

### Disponibilidade por Business Unit
O componente do Installed Package tem a opção **Available to all business units** (ou seleção específica de BUs) — é isso que "disponibiliza" a activity para outras BUs da conta.

## 4. Teste e depuração

- Injete um contato de teste na jornada (Journey Builder → *Test* ou disparo real do entry event).
- Acompanhe os logs do host (`heroku logs --tail` / painel do Render) — o `execute` loga `contactKey` e `inArguments`.
- Erros comuns:
  - **401 no execute** → `JWT_SECRET` errado ou ausente.
  - **Activity não aparece na paleta** → Endpoint URL sem HTTPS ou `config.json` inacessível.
  - **Modal em branco** → erro de JS na UI; veja o console do navegador (F12).
  - **Retry infinito** → o `execute` deve responder `200` em até 10s; trabalhos longos devem ser assíncronos (enfileirar e responder já).

## 5. Personalização

- **Lógica de negócio**: edite o bloco marcado em `server.js` (`/journey/execute`).
- **Campos da UI**: adicione inputs em `index.html` e inclua-os nos `inArguments` em `customActivity.js` e no `config.json`.
- **Nome da jornada**: capturado em duas camadas:
  1. **Na configuração** — a UI pede os dados da jornada ao Journey Builder via Postmonger (`requestInteraction` → `requestedInteraction`) e salva `journeyName` e `journeyId` nos `inArguments`.
  2. **Na execução (opcional, recomendado)** — o JWT do `execute` sempre traz o `journeyId`; se as variáveis `SFMC_AUTH_URL`, `SFMC_CLIENT_ID` e `SFMC_CLIENT_SECRET` estiverem configuradas, o servidor consulta `GET /interaction/v1/interactions/{journeyId}` e usa o nome **atual** (imune a renomeações), com cache de 10 min. Para isso, adicione um componente **API Integration (Server-to-Server)** no mesmo Installed Package com o escopo **Journeys → Read**.
- **Data binding**: nos `inArguments` você pode usar:
  - `{{Contact.Key}}` — chave do contato
  - `{{InteractionDefaults.Email}}` — e-mail padrão da jornada
  - `{{Event.DEAudience-XXXX."Campo"}}` — campo da Data Extension do entry event
- **Múltiplos caminhos (splits)**: adicione `outcomes` no `config.json` e retorne `branchResult` correspondente no `execute`.
