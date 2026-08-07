'use strict';

/**
 * Helpers de integração com a REST API do SFMC.
 * Usado pelo server.js (modo inline) e pelo worker.js (modo fila).
 */

const SFMC_AUTH_URL = process.env.SFMC_AUTH_URL || ''; // https://SUBDOMINIO.auth.marketingcloudapis.com
const SFMC_CLIENT_ID = process.env.SFMC_CLIENT_ID || '';
const SFMC_CLIENT_SECRET = process.env.SFMC_CLIENT_SECRET || '';

let sfmcToken = { value: null, restUrl: null, expiresAt: 0 };
const journeyNameCache = new Map(); // journeyId -> { name, expiresAt }

async function getSfmcToken() {
  if (sfmcToken.value && Date.now() < sfmcToken.expiresAt) return sfmcToken;

  const resp = await fetch(`${SFMC_AUTH_URL}/v2/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      grant_type: 'client_credentials',
      client_id: SFMC_CLIENT_ID,
      client_secret: SFMC_CLIENT_SECRET
    })
  });
  if (!resp.ok) throw new Error(`Auth SFMC falhou: ${resp.status}`);
  const data = await resp.json();

  sfmcToken = {
    value: data.access_token,
    restUrl: data.rest_instance_url.replace(/\/$/, ''),
    expiresAt: Date.now() + (data.expires_in - 60) * 1000
  };
  return sfmcToken;
}

/**
 * Busca o nome ATUAL da jornada pelo journeyId (imune a renomeações).
 * Retorna null se as credenciais não estiverem configuradas ou em caso de erro.
 * Cache local de 10 min; em produção multi-instância, considere Azure Cache
 * for Redis para compartilhar o cache entre réplicas.
 */
async function lookupJourneyName(journeyId) {
  if (!journeyId || !SFMC_AUTH_URL || !SFMC_CLIENT_ID || !SFMC_CLIENT_SECRET) return null;

  const cached = journeyNameCache.get(journeyId);
  if (cached && Date.now() < cached.expiresAt) return cached.name;

  try {
    const token = await getSfmcToken();
    const resp = await fetch(`${token.restUrl}/interaction/v1/interactions/${journeyId}`, {
      headers: { Authorization: `Bearer ${token.value}` }
    });
    if (!resp.ok) throw new Error(`Lookup jornada falhou: ${resp.status}`);
    const journey = await resp.json();

    journeyNameCache.set(journeyId, {
      name: journey.name,
      expiresAt: Date.now() + 10 * 60 * 1000
    });
    return journey.name;
  } catch (err) {
    console.warn('[JOURNEY LOOKUP]', err.message);
    return null;
  }
}

module.exports = { getSfmcToken, lookupJourneyName };
