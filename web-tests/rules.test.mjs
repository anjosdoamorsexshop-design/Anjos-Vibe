// Testes das regras do Realtime Database. Rodam contra o emulador:
//   cd web-tests && npm test
// Cada teste corresponde a uma regra do design da longa distancia.
// Um teste vermelho aqui e uma falha de seguranca: corrija a regra.

import { test, before, after, beforeEach } from 'node:test';
import { readFileSync } from 'node:fs';
import {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} from '@firebase/rules-unit-testing';
import { ref, set, get, update, remove, serverTimestamp } from 'firebase/database';

const CODE = 'ABCDEFGH23';
const KEY = '0123456789abcdef0123456789abcdef';
const OUTRA_KEY = 'ffffffffffffffffffffffffffffffff';

let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-anjos',
    database: {
      host: '127.0.0.1',
      port: 9000,
      rules: readFileSync(new URL('../database.rules.json', import.meta.url), 'utf8'),
    },
  });
});

after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearDatabase(); });

const db = (uid) => env.authenticatedContext(uid).database();

/** Sessao pronta, gravada sem passar pelas regras. */
async function semear({ partner = false, paused = false, idadeMs = 0 } = {}) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const d = ctx.database();
    await set(ref(d, `sessions/${CODE}`), {
      owner: 'dona',
      createdAt: Date.now() - idadeMs,
      state: { paused, online: true, now: '' },
      ...(partner ? { partner: { uid: 'parceiro', name: 'Leo' } } : {}),
    });
    await set(ref(d, `sessionKeys/${CODE}`), KEY);
  });
}

const cmd = (extra = {}) => ({ seq: 1, t: 'mode', v: 3, ...extra });

// ---- Criacao -------------------------------------------------------------

test('dona cria a sessao e a chave', async () => {
  const d = db('dona');
  await assertSucceeds(set(ref(d, `sessions/${CODE}`), {
    owner: 'dona', createdAt: serverTimestamp(),
    state: { paused: false, online: true, now: '' },
  }));
  await assertSucceeds(set(ref(d, `sessionKeys/${CODE}`), KEY));
});

test('ninguem cria sessao em nome de outra pessoa', async () => {
  await assertFails(set(ref(db('intruso'), `sessions/${CODE}`), {
    owner: 'dona', createdAt: serverTimestamp(),
  }));
});

test('quem nao e a dona nao grava a chave', async () => {
  await semear();
  await assertFails(set(ref(db('intruso'), `sessionKeys/${CODE}`), OUTRA_KEY));
});

test('ninguem le a chave, nem a dona', async () => {
  await semear();
  await assertFails(get(ref(db('dona'), `sessionKeys/${CODE}`)));
  await assertFails(get(ref(db('parceiro'), `sessionKeys/${CODE}`)));
});

test('sem login nao le nem grava nada', async () => {
  await semear({ partner: true });
  const d = env.unauthenticatedContext().database();
  await assertFails(get(ref(d, `sessions/${CODE}`)));
  await assertFails(set(ref(d, `sessions/${CODE}/cmd`), cmd()));
});

// ---- Pedido de entrada ---------------------------------------------------

test('pedido com a chave certa entra', async () => {
  await semear();
  await assertSucceeds(set(ref(db('parceiro'), `sessions/${CODE}/requests/parceiro`),
    { name: 'Leo', key: KEY }));
});

test('pedido com a chave errada e negado', async () => {
  await semear();
  await assertFails(set(ref(db('parceiro'), `sessions/${CODE}/requests/parceiro`),
    { name: 'Leo', key: OUTRA_KEY }));
});

test('pedido para sessao que nao existe e negado', async () => {
  await assertFails(set(ref(db('parceiro'), `sessions/${CODE}/requests/parceiro`),
    { name: 'Leo', key: KEY }));
});

test('ninguem faz pedido em nome de outro uid', async () => {
  await semear();
  await assertFails(set(ref(db('intruso'), `sessions/${CODE}/requests/parceiro`),
    { name: 'Leo', key: KEY }));
});

test('apelido com mais de 24 caracteres e negado', async () => {
  await semear();
  await assertFails(set(ref(db('parceiro'), `sessions/${CODE}/requests/parceiro`),
    { name: 'x'.repeat(25), key: KEY }));
});

test('parceiro nao consegue se aprovar sozinho', async () => {
  await semear();
  await assertFails(set(ref(db('parceiro'), `sessions/${CODE}/requests/parceiro`),
    { name: 'Leo', key: KEY, status: 'aceito' }));
  await assertFails(set(ref(db('parceiro'), `sessions/${CODE}/partner`),
    { uid: 'parceiro', name: 'Leo' }));
});

test('dona le os pedidos e grava o status', async () => {
  await semear();
  await set(ref(db('parceiro'), `sessions/${CODE}/requests/parceiro`), { name: 'Leo', key: KEY });
  const d = db('dona');
  await assertSucceeds(get(ref(d, `sessions/${CODE}/requests`)));
  await assertSucceeds(update(ref(d, `sessions/${CODE}/requests/parceiro`), { status: 'aceito' }));
  await assertSucceeds(set(ref(d, `sessions/${CODE}/partner`), { uid: 'parceiro', name: 'Leo' }));
});

test('parceiro le o proprio pedido, e nao o dos outros', async () => {
  await semear();
  await set(ref(db('parceiro'), `sessions/${CODE}/requests/parceiro`), { name: 'Leo', key: KEY });
  await assertSucceeds(get(ref(db('parceiro'), `sessions/${CODE}/requests/parceiro`)));
  await assertFails(get(ref(db('intruso'), `sessions/${CODE}/requests/parceiro`)));
});

// ---- Comandos ------------------------------------------------------------

test('sem ser aceito, nao manda comando', async () => {
  await semear();
  await assertFails(set(ref(db('parceiro'), `sessions/${CODE}/cmd`), cmd()));
});

test('parceiro aceito manda comando e sinal de vida', async () => {
  await semear({ partner: true });
  const d = db('parceiro');
  await assertSucceeds(set(ref(d, `sessions/${CODE}/cmd`), cmd()));
  await assertSucceeds(set(ref(d, `sessions/${CODE}/cmd`), { seq: 2, t: 'level', v: 0 }));
  await assertSucceeds(set(ref(d, `sessions/${CODE}/cmd`), { seq: 3, t: 'pattern', v: 'onda' }));
  await assertSucceeds(set(ref(d, `sessions/${CODE}/cmd`), { seq: 4, t: 'stop' }));
  await assertSucceeds(set(ref(d, `sessions/${CODE}/beat`), serverTimestamp()));
});

test('outra pessoa nao manda comando nem le o estado', async () => {
  await semear({ partner: true });
  const d = db('intruso');
  await assertFails(set(ref(d, `sessions/${CODE}/cmd`), cmd()));
  await assertFails(set(ref(d, `sessions/${CODE}/beat`), serverTimestamp()));
  await assertFails(get(ref(d, `sessions/${CODE}/state`)));
  await assertFails(get(ref(d, `sessions/${CODE}`)));
});

test('parceiro le estado e padroes, mas nao altera', async () => {
  await semear({ partner: true });
  const d = db('parceiro');
  await assertSucceeds(get(ref(d, `sessions/${CODE}/state`)));
  await assertSucceeds(get(ref(d, `sessions/${CODE}/patterns`)));
  await assertFails(set(ref(d, `sessions/${CODE}/state/paused`), false));
  await assertFails(set(ref(d, `sessions/${CODE}/patterns/x`), { name: 'x' }));
});

test('pausado: comando negado, sinal de vida continua', async () => {
  await semear({ partner: true, paused: true });
  const d = db('parceiro');
  await assertFails(set(ref(d, `sessions/${CODE}/cmd`), cmd()));
  await assertSucceeds(set(ref(d, `sessions/${CODE}/beat`), serverTimestamp()));
});

test('comando fora do formato e negado', async () => {
  await semear({ partner: true });
  const d = db('parceiro');
  await assertFails(set(ref(d, `sessions/${CODE}/cmd`), cmd({ t: 'explodir' })));
  await assertFails(set(ref(d, `sessions/${CODE}/cmd`), cmd({ v: 10 })));
  await assertFails(set(ref(d, `sessions/${CODE}/cmd`), cmd({ v: 0 })));
  await assertFails(set(ref(d, `sessions/${CODE}/cmd`), { seq: 1, t: 'level', v: 4 }));
  await assertFails(set(ref(d, `sessions/${CODE}/cmd`), cmd({ seq: 'um' })));
  await assertFails(set(ref(d, `sessions/${CODE}/cmd`), cmd({ extra: true })));
  await assertFails(set(ref(d, `sessions/${CODE}/cmd`), { t: 'stop' }));
});

test('sinal de vida so aceita o horario do servidor', async () => {
  await semear({ partner: true });
  await assertFails(set(ref(db('parceiro'), `sessions/${CODE}/beat`), 123));
});

test('parceiro removido perde o controle', async () => {
  await semear({ partner: true });
  await assertSucceeds(remove(ref(db('dona'), `sessions/${CODE}/partner`)));
  await assertFails(set(ref(db('parceiro'), `sessions/${CODE}/cmd`), cmd()));
  await assertFails(set(ref(db('parceiro'), `sessions/${CODE}/beat`), serverTimestamp()));
});

test('chave trocada: pedido com a chave antiga e negado', async () => {
  await semear();
  await assertSucceeds(set(ref(db('dona'), `sessionKeys/${CODE}`), OUTRA_KEY));
  await assertFails(set(ref(db('parceiro'), `sessions/${CODE}/requests/parceiro`),
    { name: 'Leo', key: KEY }));
});

// ---- Validade ------------------------------------------------------------

test('sessao com mais de 24 h nao aceita comando nem pedido', async () => {
  await semear({ partner: true, idadeMs: 25 * 60 * 60 * 1000 });
  await assertFails(set(ref(db('parceiro'), `sessions/${CODE}/cmd`), cmd()));
  await assertFails(set(ref(db('outro'), `sessions/${CODE}/requests/outro`),
    { name: 'Ana', key: KEY }));
});

test('dona encerra: sessao e chave somem', async () => {
  await semear({ partner: true });
  const d = db('dona');
  await assertSucceeds(remove(ref(d, `sessionKeys/${CODE}`)));
  await assertSucceeds(remove(ref(d, `sessions/${CODE}`)));
});

test('dona nao consegue recuar a data de criacao', async () => {
  await semear({ idadeMs: 25 * 60 * 60 * 1000 });
  await assertFails(set(ref(db('dona'), `sessions/${CODE}/createdAt`), serverTimestamp()));
});
