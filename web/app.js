// Pagina do parceiro: controla o vibrador de outra pessoa pela internet.
//
// O link traz o codigo da sessao em ?s= e a chave secreta depois do #.
// A chave so e usada para pedir entrada; quem decide se entra e a dona,
// no app. As regras de acesso estao em database.rules.json.

import { initializeApp } from 'https://www.gstatic.com/firebasejs/10.12.2/firebase-app.js';
import { getAuth, signInAnonymously } from 'https://www.gstatic.com/firebasejs/10.12.2/firebase-auth.js';
import {
  getDatabase, ref, set, onValue, serverTimestamp,
} from 'https://www.gstatic.com/firebasejs/10.12.2/firebase-database.js';

const $ = (id) => document.getElementById(id);

const BEAT_MS = 2000;
const RASTRO_MS = 320;
const PADROES_DE_FABRICA = [
  ['onda', 'Onda'], ['pulso', 'Pulso'], ['batida', 'Batida'],
  ['escalada', 'Escalada'], ['provocacao', 'Provocação'],
];

const code = new URLSearchParams(location.search).get('s') || '';
const key = location.hash.slice(1);

let db, uid, base;
let seq = Date.now();          // cresce sempre, mesmo se a pagina recarregar
let aceito = false;
let bloqueado = true;          // pausado, sem conexao ou ainda nao aceito
let conectado = true;
let estado = null;
let encerrado = false;

// ---- Telas -----------------------------------------------------------------

const TELAS = ['tela-idade', 'tela-nome', 'tela-espera', 'tela-aviso', 'tela-controle'];

function mostrar(id) {
  for (const t of TELAS) $(t).hidden = t !== id;
}

function aviso(titulo, texto) {
  encerrado = true;
  $('aviso-titulo').textContent = titulo;
  $('aviso-texto').textContent = texto;
  $('selo').hidden = true;
  mostrar('tela-aviso');
}

function selo(texto, alerta = false) {
  const s = $('selo');
  s.textContent = texto;
  s.classList.toggle('alerta', alerta);
  s.hidden = false;
}

// ---- Entrada ---------------------------------------------------------------

function iniciar() {
  if (!/^[A-Z2-9]{10}$/.test(code) || !/^[0-9a-f]{32}$/.test(key)) {
    aviso('Link inválido', 'Peça um link novo para quem te convidou.');
    return;
  }

  $('btn-idade').addEventListener('click', () => {
    localStorage.setItem('av18', '1');
    telaNome();
  });
  $('btn-entrar').addEventListener('click', entrar);
  $('campo-nome').addEventListener('keydown', (e) => { if (e.key === 'Enter') entrar(); });

  if (localStorage.getItem('av18') === '1') telaNome();
  else mostrar('tela-idade');
}

function telaNome() {
  $('campo-nome').value = localStorage.getItem('avNome') || '';
  mostrar('tela-nome');
}

async function entrar() {
  const nome = $('campo-nome').value.trim().slice(0, 24);
  if (!nome) {
    $('campo-nome').focus();
    return;
  }
  localStorage.setItem('avNome', nome);
  $('btn-entrar').disabled = true;
  mostrar('tela-espera');

  try {
    // O Firebase Hosting entrega a configuracao do projeto neste endereco,
    // entao nenhuma chave precisa ficar no repositorio.
    const config = await (await fetch('/__/firebase/init.json')).json();
    const app = initializeApp(config);
    db = getDatabase(app);
    uid = (await signInAnonymously(getAuth(app))).user.uid;
    base = `sessions/${code}`;
    await set(ref(db, `${base}/requests/${uid}`), { name: nome, key });
  } catch (e) {
    aviso('Link inválido ou expirado', 'Este convite não vale mais. Peça um link novo.');
    return;
  }

  onValue(ref(db, `${base}/requests/${uid}/status`), (s) => {
    if (encerrado) return;
    const status = s.val();
    if (status === 'aceito') {
      if (!aceito) abrirControle();
    } else if (status === 'recusado') {
      aviso('Pedido recusado', 'A outra pessoa não aceitou o controle.');
    } else if (status === 'ocupada') {
      aviso('Sessão ocupada', 'Já tem outra pessoa neste link.');
    } else if (status === 'removido') {
      aviso('Você foi removido', 'A outra pessoa encerrou o seu controle.');
    } else if (aceito) {
      aviso('Sessão encerrada', 'O controle terminou.');
    }
  }, () => {
    aviso('Sessão encerrada', 'O controle terminou.');
  });
}

// ---- Controle --------------------------------------------------------------

function abrirControle() {
  aceito = true;
  mostrar('tela-controle');
  montarModos();
  montarAbas();
  montarDesenho();
  montarPadroes({});

  $('btn-parar').addEventListener('click', () => {
    enviar('stop');
    marcarAtivo(null);
  });

  onValue(ref(db, `${base}/state`), (s) => {
    if (encerrado) return;
    estado = s.val();
    if (!estado) {
      aviso('Sessão encerrada', 'O controle terminou.');
      return;
    }
    atualizarBloqueio();
  }, () => {
    if (!encerrado) aviso('Você foi removido', 'A outra pessoa encerrou o seu controle.');
  });

  onValue(ref(db, `${base}/patterns`), (s) => montarPadroes(s.val() || {}), () => {});

  onValue(ref(db, '.info/connected'), (s) => {
    conectado = s.val() === true;
    atualizarBloqueio();
  });

  // Sinal de vida: sem ele por 5 s, o app do outro lado para o motor
  const bater = () => {
    if (!encerrado && conectado) set(ref(db, `${base}/beat`), serverTimestamp()).catch(() => {});
  };
  bater();
  setInterval(bater, BEAT_MS);
}

function atualizarBloqueio() {
  let texto = '';
  if (!conectado) texto = 'Conexão perdida. Tentando voltar…';
  else if (estado && estado.online === false) texto = 'A outra pessoa está sem conexão.';
  else if (estado && estado.paused) texto = 'Pausado do outro lado.';

  bloqueado = texto !== '';
  $('bloqueio').hidden = !bloqueado;
  $('bloqueio-texto').textContent = texto;
  $('btn-parar').disabled = !conectado;

  if (bloqueado) {
    selo(conectado ? 'Pausado' : 'Sem conexão', true);
    marcarAtivo(null);
    $('agora').textContent = '';
  } else {
    selo('Conectado');
    $('agora').textContent = (estado && estado.now) || 'Parado';
  }
}

function enviar(t, v) {
  if (encerrado || (bloqueado && t !== 'stop')) return;
  seq += 1;
  const cmd = v === undefined ? { seq, t } : { seq, t, v };
  set(ref(db, `${base}/cmd`), cmd).catch(() => {});
}

function marcarAtivo(el) {
  document.querySelectorAll('.modo.ativo, .padrao.ativo').forEach((e) => e.classList.remove('ativo'));
  if (el) el.classList.add('ativo');
}

function montarModos() {
  const grade = $('grade');
  grade.textContent = '';
  for (let m = 1; m <= 9; m++) {
    const b = document.createElement('button');
    b.className = 'modo';
    b.textContent = String(m);
    b.addEventListener('click', () => {
      enviar('mode', m);
      marcarAtivo(b);
    });
    grade.appendChild(b);
  }
}

function montarAbas() {
  const abas = document.querySelectorAll('.aba');
  abas.forEach((aba) => aba.addEventListener('click', () => {
    abas.forEach((a) => a.classList.toggle('ativa', a === aba));
    for (const nome of ['modos', 'desenhar', 'padroes']) {
      $(`painel-${nome}`).hidden = nome !== aba.dataset.aba;
    }
    if (aba.dataset.aba === 'desenhar') ajustarTela();
  }));
}

function montarPadroes(salvos) {
  const lista = $('lista-padroes');
  lista.textContent = '';

  const item = (id, nome) => {
    const b = document.createElement('button');
    b.className = 'padrao';
    b.textContent = nome;
    b.addEventListener('click', () => {
      enviar('pattern', id);
      marcarAtivo(b);
    });
    lista.appendChild(b);
  };

  for (const [id, nome] of PADROES_DE_FABRICA) item(id, nome);

  const ids = Object.keys(salvos);
  if (ids.length) {
    const h = document.createElement('h2');
    h.textContent = 'Salvos';
    lista.appendChild(h);
    for (const id of ids) item(id, String(salvos[id].name || 'Sem nome'));
  }
}

// ---- Desenho ao vivo -------------------------------------------------------
// Altura do dedo escolhe o degrau: 1 fraco, 2 medio, 3 forte. So a mudanca de
// faixa e enviada, nao cada movimento. O rastro e uma lamina que afina e some.

const rastro = [];
let nivelAtual = 0;
let traco = 0;
let dedo = null;
let tela, ctx;

function ajustarTela() {
  if (!tela) return;
  const r = tela.getBoundingClientRect();
  const dpr = window.devicePixelRatio || 1;
  tela.width = Math.round(r.width * dpr);
  tela.height = Math.round(r.height * dpr);
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
}

function nivelDaAltura(y, altura) {
  const rel = 1 - Math.min(Math.max(y / altura, 0), 1);
  if (rel < 1 / 3) return 1;
  if (rel < 2 / 3) return 2;
  return 3;
}

function definirNivel(n) {
  if (n === nivelAtual) return;
  nivelAtual = n;
  enviar('level', n);
  for (const f of [1, 2, 3]) {
    document.querySelector(`.faixa.f${f}`).classList.toggle('acesa', f === n);
  }
  if (n > 0) marcarAtivo(null);
}

function montarDesenho() {
  tela = $('tela-desenho');
  ctx = tela.getContext('2d');
  window.addEventListener('resize', ajustarTela);

  const ponto = (e) => {
    const r = tela.getBoundingClientRect();
    const x = e.clientX - r.left;
    const y = e.clientY - r.top;
    rastro.push({ x, y, t: performance.now(), traco });
    definirNivel(nivelDaAltura(y, r.height));
  };

  tela.addEventListener('pointerdown', (e) => {
    if (bloqueado || dedo !== null) return;
    dedo = e.pointerId;
    tela.setPointerCapture(e.pointerId);
    ponto(e);
    e.preventDefault();
  });

  tela.addEventListener('pointermove', (e) => {
    if (e.pointerId !== dedo) return;
    ponto(e);
    e.preventDefault();
  });

  const soltar = (e) => {
    if (e.pointerId !== dedo) return;
    dedo = null;
    traco += 1;
    definirNivel(0);
  };
  tela.addEventListener('pointerup', soltar);
  tela.addEventListener('pointercancel', soltar);

  requestAnimationFrame(desenhar);
}

function desenhar(agora) {
  requestAnimationFrame(desenhar);
  if (!tela || tela.offsetParent === null) return;

  const largura = tela.clientWidth;
  const altura = tela.clientHeight;
  ctx.clearRect(0, 0, largura, altura);

  // Faixa do dedo acesa
  if (nivelAtual > 0) {
    ctx.fillStyle = `rgba(225, 43, 141, ${0.10 + 0.05 * nivelAtual})`;
    ctx.fillRect(0, altura - nivelAtual * (altura / 3), largura, altura / 3);
  }
  ctx.strokeStyle = 'rgba(168, 146, 166, 0.15)';
  ctx.lineWidth = 1;
  for (const f of [1, 2]) {
    ctx.beginPath();
    ctx.moveTo(0, altura - f * (altura / 3));
    ctx.lineTo(largura, altura - f * (altura / 3));
    ctx.stroke();
  }

  while (rastro.length && agora - rastro[0].t > RASTRO_MS) rastro.shift();

  ctx.lineCap = 'round';
  const camadas = [
    ['225, 43, 141', 0.30, 2.2],   // brilho magenta
    ['246, 134, 189', 0.85, 1.0],  // corpo rosa
    ['255, 255, 255', 0.90, 0.35], // fio branco
  ];
  for (const [cor, alfa, escala] of camadas) {
    for (let i = 1; i < rastro.length; i++) {
      const a = rastro[i - 1];
      const b = rastro[i];
      if (a.traco !== b.traco) continue;
      const vida = Math.max(0, 1 - (agora - b.t) / RASTRO_MS);
      if (vida <= 0) continue;
      ctx.strokeStyle = `rgba(${cor}, ${alfa * vida})`;
      ctx.lineWidth = (4 + 26 * vida) * escala;
      ctx.beginPath();
      ctx.moveTo(a.x, a.y);
      ctx.lineTo(b.x, b.y);
      ctx.stroke();
    }
  }
}

iniciar();
