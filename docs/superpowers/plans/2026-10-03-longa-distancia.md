# Longa distância (controle por link) — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A dona cria um link no app; quem abre o link no navegador controla o vibrador (9 modos, desenho ao vivo, padrões), com aprovação, pausa e parada por queda de conexão.

**Architecture:** Firebase Realtime Database repassa comandos entre uma página estática (Firebase Hosting, JavaScript puro) e o app Android. As regras do banco são a única barreira de segurança, então são escritas e testadas primeiro. No app, um arquivo novo (`RemoteSession.kt`) concentra tudo que fala com o Firebase e entrega comandos já validados ao `VibeController`.

**Tech Stack:** Firebase Realtime Database + Auth anônimo + Hosting (plano Spark); Firebase JS SDK 10.12.2 via CDN `gstatic`; Firebase Android BoM 33.1.2 (compatível com Kotlin 1.9.24), plugin `com.google.gms.google-services` 4.4.2; testes de regras com `@firebase/rules-unit-testing` no emulador, rodando no GitHub Actions; JUnit 4.13.2 para a lógica pura do app.

**Spec:** `docs/superpowers/specs/2026-10-03-longa-distancia-design.md`

## Global Constraints

- Kotlin 1.9.24, AGP 8.5.2, `compileSdk 34`, `minSdk 24`. Não subir versões.
- Projeto Gradle fica em `anjos-vibe/`, não na raiz. Não há Gradle nem JDK no PC: compilar e testar só pelo GitHub Actions (`gh run watch`), instalar no S25 com `adb install -r`.
- Nomes de arquivo e pasta em inglês, sem acento. Strings em Kotlin sem acento (convenção do projeto). Na página web, português com acento.
- Imports do Compose sempre explícitos quando não vierem dos curingas já usados.
- Não reintroduzir transmissão contínua nem reinícios periódicos do Bluetooth.
- Cores: roxo `#7D387D`, rosa `#F686BD`, magenta `#E12B8D`, fundo `#170A14`. Sem conteúdo explícito.
- Link da loja: `https://anjosdoamorshop.com.br/`.
- Um parceiro por sessão; link expira 24 h após criado; apelido com no máximo 24 caracteres; sinal de vida a cada 2 s, corte aos 5 s; no máximo 20 comandos por segundo.
- Repositório público: nunca commitar segredo. `google-services.json` e a configuração web do Firebase não são segredos, mas só entram depois que as regras estiverem publicadas.
- Desvio do spec, decidido aqui: `patterns/{id}` publica só `name` (a página não desenha a curva; o app já tem os pontos).

## Contrato de dados (vale para todas as tarefas)

```
sessionKeys/{code}                      string, 32 caracteres hex
sessions/{code}/owner                   uid
sessions/{code}/createdAt               ServerValue.TIMESTAMP
sessions/{code}/partner/{uid,name}
sessions/{code}/requests/{uid}/{name,key,status}   status: "aceito" | "recusado" | "ocupada" | "removido"
sessions/{code}/state/{paused,online,now}          paused, online: boolean; now: string
sessions/{code}/patterns/{id}/name
sessions/{code}/cmd/{seq,t,v}           t: "mode" | "level" | "pattern" | "stop"
sessions/{code}/beat                    ServerValue.TIMESTAMP
```

`code`: 10 caracteres de `ABCDEFGHJKMNPQRSTUVWXYZ23456789`. Link: `https://<projeto>.web.app/?s=<code>#<key>`.

---

### Task 1: Regras do banco, com testes no CI

**Files:**
- Create: `database.rules.json`, `firebase.json`
- Create: `web-tests/package.json`, `web-tests/rules.test.mjs`
- Create: `.github/workflows/rules.yml`

**Interfaces:**
- Produces: as regras que as tarefas 3 a 8 obedecem (ver contrato de dados).

- [ ] **Step 1: Escrever os testes** (`web-tests/rules.test.mjs`), um por regra do spec:
  - dona cria sessão e chave; outro uid não cria sessão em nome dela;
  - ninguém lê `sessionKeys`;
  - pedido com chave certa é aceito; com chave errada ou sem sessão é negado;
  - parceiro não consegue gravar `status` do próprio pedido;
  - sem `partner`, gravar `cmd` é negado; com `partner` do mesmo uid é aceito;
  - `cmd` negado com `state/paused = true`; `beat` continua aceito;
  - `cmd` com `t` fora da lista, `v` fora da faixa ou `seq` não numérico é negado;
  - outro uid (não parceiro) não lê `state` nem grava `cmd`;
  - parceiro removido (dona apaga `partner`) perde `cmd`;
  - sessão com `createdAt` de 25 h atrás nega `cmd` e pedido novo;
  - apelido com 25 caracteres é negado.
- [ ] **Step 2: Escrever `database.rules.json`** (conteúdo completo no arquivo; pontos que não são óbvios):
  - permissão concedida no nó pai não pode ser retirada no filho, então o pai `sessions/$code` só concede à dona, e cada filho concede o que o parceiro precisa;
  - a comparação da chave usa `root.child('sessionKeys').child($code).val()`, que as regras leem mesmo com `.read: false`;
  - `beat` valida `newData.val() === now`.
- [ ] **Step 3: `firebase.json`** apontando `database.rules` para `database.rules.json` e `hosting.public` para `web`, emulador do banco na porta 9000.
- [ ] **Step 4: Workflow `rules.yml`**: Node 20 + Java 17, `npm ci` em `web-tests`, `npx firebase emulators:exec --only database --project demo-anjos "node --test"`. Dispara em push que toque `database.rules.json` ou `web-tests/**`.
- [ ] **Step 5: Push e conferir** `gh run watch`: esperado, todos os testes passando. Um teste vermelho aqui é falha de segurança: corrigir a regra, não o teste.
- [ ] **Step 6: Commit.**

### Task 2: Filtro de comandos do app (lógica pura, testada)

**Files:**
- Create: `anjos-vibe/app/src/main/java/br/com/anjosdoamor/vibe/remote/RemoteCommand.kt`
- Create: `anjos-vibe/app/src/test/java/br/com/anjosdoamor/vibe/remote/RemoteCommandTest.kt`
- Modify: `anjos-vibe/app/build.gradle.kts` (dependência `testImplementation("junit:junit:4.13.2")`)
- Modify: `.github/workflows/build.yml` (passo `gradle testDebugUnitTest --no-daemon` antes de compilar o APK)

**Interfaces:**
- Produces:

```kotlin
sealed class RemoteCommand {
    data class Mode(val mode: Int) : RemoteCommand()      // 1..9
    data class Level(val level: Int) : RemoteCommand()    // 0..3
    data class PlayPattern(val id: String) : RemoteCommand()
    object Stop : RemoteCommand()

    companion object {
        /** null se o comando for invalido. v chega como Long, Double ou String do Firebase. */
        fun parse(t: String?, v: Any?): RemoteCommand?
    }
}

/** Decide se um comando recebido deve ser aplicado. Sem dependencia de Android. */
class RemoteGate(private val maxPerSecond: Int = 20) {
    /** true = aplicar. Rejeita seq repetido ou menor, e o excesso alem de maxPerSecond. */
    fun accept(seq: Long, nowMs: Long): Boolean
    fun reset()
}
```

- [ ] **Step 1: Testes que falham:** `parse("mode", 3L)` → `Mode(3)`; `parse("mode", 10L)` → null; `parse("level", 0L)` → `Level(0)`; `parse("level", 4L)` → null; `parse("pattern", "onda")` → `PlayPattern("onda")`; `parse("pattern", "")` → null; `parse("stop", null)` → `Stop`; `parse("xyz", 1L)` → null; `parse("mode", 2.0)` → `Mode(2)`. Gate: aceita seq crescente; rejeita igual e menor; o 21º comando dentro do mesmo segundo é rejeitado e o primeiro do segundo seguinte é aceito; `reset()` volta a aceitar seq 1.
- [ ] **Step 2: Rodar no CI, ver falhar** por classe inexistente.
- [ ] **Step 3: Implementar** o mínimo que passa.
- [ ] **Step 4: Rodar no CI, ver passar.**
- [ ] **Step 5: Commit.**

### Task 3: Projeto Firebase (depende da Mari) e publicação das regras

**Files:**
- Create: `.firebaserc`, `web/firebase-config.js`, `anjos-vibe/app/google-services.json`

- [ ] **Step 1 (Mari):** criar o projeto no console do Firebase com a conta da loja; ativar Realtime Database (região `southamerica-east1` se oferecida, senão `us-central1`), Authentication → Anônimo, Hosting; registrar um app Android (`br.com.anjosdoamor.vibe`) e um app Web.
- [ ] **Step 2 (Mari):** `npx firebase-tools login` no terminal do PC.
- [ ] **Step 3:** `npx firebase-tools deploy --only database` e conferir no console que as regras publicadas são as do repositório. Só depois disso:
- [ ] **Step 4:** baixar `google-services.json` e a configuração web (`npx firebase-tools apps:sdkconfig`), gravar nos dois arquivos, commit.

### Task 4: `VibeController` aceita comandos remotos e distingue parada do usuário

**Files:**
- Modify: `anjos-vibe/app/src/main/java/br/com/anjosdoamor/vibe/VibeController.kt`

**Interfaces:**
- Consumes: `RemoteCommand` (Task 2).
- Produces:

```kotlin
/** Chamado quando a propria dona manda parar (botao, notificacao, timer). */
var onUserStop: (() -> Unit)? = null

/** Para o motor sem avisar a sessao remota. Usado por comando remoto e por queda de conexao. */
fun stopSilently()

/** Aplica um comando ja validado. Devolve o texto do que passou a tocar ("Modo 3", "Desenho: forte", "Onda", "Parado"). */
fun applyRemote(cmd: RemoteCommand): String
```

- [ ] **Step 1:** mover o corpo atual de `stop()` para `stopSilently()`; `stop()` passa a chamar `stopSilently()` e depois `onUserStop?.invoke()`.
- [ ] **Step 2:** `applyRemote`: `Mode` → `setMode`; `Level(0)` → `setLiveIntensity(0f)`; `Level(n)` → `setLiveIntensity(intensidadeDoNivel(n))`; `PlayPattern(id)` → procura em `patterns()` e toca, ignora id desconhecido; `Stop` → `stopSilently()`.
- [ ] **Step 3:** CI verde; no S25, as abas atuais continuam parando normalmente (teste manual: modo 3 → PARAR).
- [ ] **Step 4: Commit.**

### Task 5: `RemoteSession` — sessão, pedidos, comandos, sinal de vida

**Files:**
- Create: `anjos-vibe/app/src/main/java/br/com/anjosdoamor/vibe/remote/RemoteSession.kt`
- Modify: `anjos-vibe/app/build.gradle.kts`, `anjos-vibe/build.gradle.kts` (plugin google-services, BoM 33.1.2, `firebase-database`, `firebase-auth`)
- Modify: `anjos-vibe/app/src/main/AndroidManifest.xml` (`INTERNET`)

**Interfaces:**
- Consumes: `RemoteCommand`, `RemoteGate`, `VibeController.applyRemote/stopSilently/onUserStop/patterns`.
- Produces:

```kotlin
data class RemoteState(
    val active: Boolean = false,          // existe sessao aberta
    val link: String? = null,
    val partnerName: String? = null,
    val pendingRequest: Pair<String, String>? = null,  // uid, apelido
    val paused: Boolean = false,
    val connectionLost: Boolean = false,  // parceiro sem sinal de vida
    val offline: Boolean = false,         // celular da dona sem internet
    val now: String = "",
    val error: String? = null
)

object RemoteSession {
    val state: StateFlow<RemoteState>
    fun create(context: Context)          // login anonimo, apaga sessao antiga, grava sessao + chave + padroes
    fun accept()                          // aceita pendingRequest
    fun refuse()
    fun removePartner()                   // apaga partner, troca a chave, novo link
    fun pause()                           // state/paused = true
    fun resume()
    fun end()                             // apaga sessions/{code} e sessionKeys/{code}
}
```

- [ ] **Step 1:** dependências e manifesto; CI verde só com isso.
- [ ] **Step 2:** `create` + `end`, com o código da sessão salvo em SharedPreferences `anjos_vibe_remote` para apagar sessão esquecida na próxima abertura. `onDisconnect` em `state/online`.
- [ ] **Step 3:** ouvir `requests`: primeiro pedido sem `status` vira `pendingRequest`; se já houver parceiro, responde `status = "ocupada"`.
- [ ] **Step 4:** ouvir `cmd`: `RemoteCommand.parse` → `RemoteGate.accept` → `VibeController.applyRemote` → grava `state/now`. Ignora tudo se `paused`.
- [ ] **Step 5:** ouvir `beat` e `.info/connected`; laço de 1 s: sem `beat` há 5 s e motor ligado por comando remoto → `stopSilently()` + `connectionLost = true`. Internet da dona fora → `stopSilently()` + `offline = true`.
- [ ] **Step 6:** `VibeController.onUserStop = { if (state.value.partnerName != null) pause() }`.
- [ ] **Step 7:** CI verde. **Commit.**

### Task 6: Aba "Longa distância" no app

**Files:**
- Create: `anjos-vibe/app/src/main/java/br/com/anjosdoamor/vibe/ui/LongaDistanciaScreen.kt`
- Modify: `anjos-vibe/app/src/main/java/br/com/anjosdoamor/vibe/MainActivity.kt` (sexta aba, rótulo "Distancia", ícone `Icons.Default.Public`)
- Modify: `anjos-vibe/app/src/main/java/br/com/anjosdoamor/vibe/service/VibeService.kt` (notificação do pedido de entrada, canal `anjos_vibe_pedido`)

**Interfaces:**
- Consumes: `RemoteSession.state` e suas funções.

- [ ] **Step 1:** tela sem sessão: texto + **Criar link**.
- [ ] **Step 2:** tela com sessão: estado, "Agora: …", **Parar e pausar** / **Retomar**, **Enviar link** (`Intent.ACTION_SEND`, texto "Controle meu Anjos Vibe: <link>"), **Remover**, **Encerrar sessao**; cartão **Aceitar / Recusar**; avisos de conexão.
- [ ] **Step 3:** manter o serviço em primeiro plano enquanto houver sessão aberta, mesmo com o motor parado (senão o Android suspende o app e os comandos param de chegar).
- [ ] **Step 4:** CI verde, instalar no S25, screenshot da aba. **Commit.**

### Task 7: Página do parceiro — entrada, Modos e Parar (primeiro teste de ponta a ponta)

**Files:**
- Create: `web/index.html`, `web/style.css`, `web/app.js`

**Interfaces:**
- Consumes: contrato de dados; `web/firebase-config.js` (Task 3).

- [ ] **Step 1:** `index.html` + `style.css`: telas "18+", apelido, aguardando, controle, e os avisos do spec; rodapé com o link da loja.
- [ ] **Step 2:** `app.js`: lê `s` e a chave do `#`; login anônimo; grava o pedido; observa o próprio `status`; aceito, observa `state` e habilita os controles; envia `cmd` com `seq` crescente; `beat` a cada 2 s; `state` nulo = "Sessão encerrada"; erro de permissão no pedido = "Link inválido ou expirado".
- [ ] **Step 3:** `npx firebase-tools deploy --only hosting`.
- [ ] **Step 4: Ponta a ponta:** página no navegador do PC, S25 no cabo. Criar link no app, abrir, aceitar, apertar modo 3 e Parar. Conferir no `adb logcat` que cada comando gerou `startAdvertisingSet`/troca de dados. Testar: Parar e pausar no app → página mostra "Pausado" e os botões não respondem; fechar a aba do navegador → em 5 s o app para o motor.
- [ ] **Step 5: Commit.** Entregar para a Mari testar com um segundo celular no 4G.

### Task 8: Desenhar e Padrões na página

**Files:**
- Modify: `web/index.html`, `web/style.css`, `web/app.js`

- [ ] **Step 1: Desenhar:** `<canvas>` com três faixas; `pointerdown/move/up`; envia `level` só quando a faixa muda e `level 0` ao soltar; rastro de lâmina com `requestAnimationFrame` (pontos com 320 ms de vida, três traços sobrepostos: magenta largo, rosa, branco fino). `touch-action: none` para a página não rolar.
- [ ] **Step 2: Padrões:** lista fixa dos 5 de fábrica (ids `onda`, `pulso`, `batida`, `escalada`, `provocacao`) + os de `patterns`; toque envia `pattern`.
- [ ] **Step 3:** deploy, ponta a ponta com o S25 (desenho responde; padrão salvo no app aparece na página e toca). **Commit.**

### Task 9: Acabamento

**Files:**
- Modify: `web/*`, `anjos-vibe/app/src/main/java/br/com/anjosdoamor/vibe/ui/LongaDistanciaScreen.kt`, `CLAUDE.md`
- Create: `docs/politica-de-privacidade.md`

- [ ] **Step 1:** revisão visual da página em 375 px de largura e no modo paisagem.
- [ ] **Step 2:** aviso na aba, na primeira sessão, para liberar o app da otimização de bateria (abre `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`).
- [ ] **Step 3:** texto da política de privacidade cobrindo: uso de internet só na longa distância, identificador anônimo, apelido, nenhum histórico, microfone só local.
- [ ] **Step 4:** atualizar `CLAUDE.md` (nova aba, Firebase, como publicar a página, como rodar os testes de regras).
- [ ] **Step 5: Commit.**
