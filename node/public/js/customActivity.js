'use strict';

/**
 * Comunicação com o Journey Builder via Postmonger.
 * Fluxo: ready -> initActivity + requestedInteraction
 *        -> (usuário configura) -> clickedNext -> updateActivity
 */
if (typeof Postmonger === 'undefined') {
  // Sem Postmonger o Journey Builder nunca recebe o "ready" e o loading não some.
  // Este guard torna a falha visível em vez de silenciosa.
  document.body.innerHTML =
    '<p style="padding:24px;color:#c23934;font-family:sans-serif">' +
    'Erro: postmonger.min.js não carregou. Verifique se o arquivo existe em /js/.</p>';
  throw new Error('Postmonger não carregado');
}

var connection = new Postmonger.Session();
var activity = null;
var journeyName = '';
var journeyId = '';

$onReady();

function $onReady() {
  // Dispara quando o iframe é carregado dentro do Journey Builder
  connection.on('initActivity', onInitActivity);
  connection.on('clickedNext', onClickedNext);

  // O Journey Builder responde com os dados da jornada (interaction) atual
  connection.on('requestedInteraction', onRequestedInteraction);

  // Avisa o Journey Builder que a UI está pronta
  connection.trigger('ready');

  // Solicita os dados da jornada (nome, id, key)
  connection.trigger('requestInteraction');

  // Botão Done só habilita com título e mensagem preenchidos
  toggleDoneButton(false);
  document.getElementById('title').addEventListener('input', validateForm);
  document.getElementById('message').addEventListener('input', validateForm);
}

function validateForm() {
  var title = document.getElementById('title').value.trim();
  var message = document.getElementById('message').value.trim();
  toggleDoneButton(Boolean(title && message));
}

function toggleDoneButton(enabled) {
  connection.trigger('updateButton', {
    button: 'next',
    text: 'done',
    visible: true,
    enabled: enabled
  });
}

function onRequestedInteraction(interaction) {
  if (!interaction) return;

  journeyName = interaction.name || '';
  journeyId = interaction.id || '';

  var el = document.getElementById('journeyName');
  if (el) el.textContent = journeyName || '(não disponível)';
}

function onInitActivity(payload) {
  activity = payload || {};

  var inArguments =
    activity.arguments &&
    activity.arguments.execute &&
    activity.arguments.execute.inArguments
      ? activity.arguments.execute.inArguments
      : [];

  // Achata a lista de inArguments em um único objeto
  var args = inArguments.reduce(function (acc, arg) {
    Object.keys(arg).forEach(function (k) { acc[k] = arg[k]; });
    return acc;
  }, {});

  // Restaura valores salvos anteriormente
  if (args.title) {
    document.getElementById('title').value = args.title;
  }
  if (args.message) {
    document.getElementById('message').value = args.message;
  }
  validateForm();
}

function onClickedNext() {
  var title = document.getElementById('title').value.trim();
  var message = document.getElementById('message').value.trim();

  activity.arguments = activity.arguments || {};
  activity.arguments.execute = activity.arguments.execute || {};

  activity.arguments.execute.inArguments = [
    { contactKey: '{{Contact.Key}}' },
    { emailAddress: '{{InteractionDefaults.Email}}' },
    { title: title },
    { message: message },
    { journeyName: journeyName },
    { journeyId: journeyId }
  ];

  // Marca a activity como configurada (habilita o botão "Done")
  activity.metaData = activity.metaData || {};
  activity.metaData.isConfigured = true;

  connection.trigger('updateActivity', activity);
}
