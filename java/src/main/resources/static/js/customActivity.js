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

// ponytail: catálogo fixo; trocar pelos nomes de ícone que o app reconhece.
var ICONS = [
  ['bell', '🔔 Sino'], ['gift', '🎁 Presente'], ['star', '⭐ Estrela'], ['tag', '🏷️ Oferta'],
  ['info', 'ℹ️ Informação'], ['warning', '⚠️ Alerta'], ['calendar', '📅 Calendário'],
  ['card', '💳 Cartão'], ['truck', '🚚 Entrega'], ['heart', '❤️ Favorito'],
  ['check', '✅ Concluído'], ['clock', '⏰ Lembrete']
];

// Campos visíveis: id do elemento == nome no inArguments
var FIELDS = ['classificacao', 'title', 'message', 'midiaTipo', 'icone', 'imagemUrl',
  'cta', 'deepLink', 'dataInicio', 'dataFim'];

var connection = new Postmonger.Session();
var activity = null;
var journeyName = '';
var journeyId = '';

$onReady();

function $onReady() {
  connection.on('initActivity', onInitActivity);
  connection.on('clickedNext', onClickedNext);
  connection.on('requestedInteraction', onRequestedInteraction);

  var icone = document.getElementById('icone');
  ICONS.forEach(function (ic) {
    var opt = document.createElement('option');
    opt.value = ic[0];
    opt.textContent = ic[1];
    icone.appendChild(opt);
  });

  connection.trigger('ready');
  connection.trigger('requestInteraction');

  toggleDoneButton(false);
  FIELDS.forEach(function (id) {
    document.getElementById(id).addEventListener('input', validateForm);
    document.getElementById(id).addEventListener('change', validateForm);
  });
  document.getElementById('midiaTipo').addEventListener('change', toggleMidia);
  toggleMidia();
}

function val(id) {
  return document.getElementById(id).value.trim();
}

function toggleMidia() {
  var imagem = val('midiaTipo') === 'imagem';
  document.getElementById('grupoIcone').classList.toggle('hidden', imagem);
  document.getElementById('grupoImagem').classList.toggle('hidden', !imagem);
  validateForm();
}

function validateForm() {
  var midiaOk = val('midiaTipo') === 'imagem' ? Boolean(val('imagemUrl')) : Boolean(val('icone'));
  var datasOk = !val('dataInicio') || !val('dataFim') || val('dataInicio') <= val('dataFim');
  toggleDoneButton(Boolean(val('title') && val('message') && midiaOk && datasOk));
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

  var args = inArguments.reduce(function (acc, arg) {
    Object.keys(arg).forEach(function (k) { acc[k] = arg[k]; });
    return acc;
  }, {});

  // Restaura valores salvos anteriormente
  FIELDS.forEach(function (id) {
    if (args[id]) document.getElementById(id).value = args[id];
  });
  toggleMidia();
}

function onClickedNext() {
  activity.arguments = activity.arguments || {};
  activity.arguments.execute = activity.arguments.execute || {};

  var imagem = val('midiaTipo') === 'imagem';
  var inArguments = [{ contactKey: '{{Contact.Key}}' }];
  FIELDS.forEach(function (id) {
    var o = {};
    // só a mídia escolhida vai no payload
    o[id] = (id === 'icone' && imagem) || (id === 'imagemUrl' && !imagem) ? '' : val(id);
    inArguments.push(o);
  });
  // Ocultos para o usuário
  inArguments.push({ journeyName: journeyName });
  inArguments.push({ journeyId: journeyId });
  inArguments.push({ tipoNotificacao: 'Novidade' });

  activity.arguments.execute.inArguments = inArguments;

  activity.metaData = activity.metaData || {};
  activity.metaData.isConfigured = true;

  connection.trigger('updateActivity', activity);
}
