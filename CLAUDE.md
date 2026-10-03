# Anjos Vibe — contexto do projeto (handoff para o Claude Code)

App Android próprio da **Anjos do Amor** (sex shop online de Belo Horizonte) para controlar os vibradores Bluetooth que a loja vende — hoje controlados pelo app oficial **Love Spouse**. Objetivo: app da marca, em português, com mais funções que o Love Spouse, distribuído pela Play Store.

Dona do projeto: **Mari** (Mariana), sócia da loja. Ela não programa: o Claude escreve o código, ela testa no aparelho real e relata o que sentiu/viu. Explique os passos de forma simples e em português.

---

## Repositório e build

| Item | Valor |
|---|---|
| Repositório | https://github.com/anjosdoamorsexshop-design/Anjos-Vibe (público) |
| Branch principal | `main` |
| Último commit no `main` | ver `git log` — em 02/10/2026: rajada de 1 s + modos remapeados |
| Clone local no PC (Windows) | `C:\Anjos Central\App Anjos Vibe` (pasta oficial de trabalho). Clone antigo em `C:\Users\anjos\OneDrive\Documentos\GitHub\Anjos-Vibe` (GitHub Desktop) — não usar mais |
| Build | GitHub Actions, workflow **"Gerar APK"** (`.github/workflows/build.yml`), roda a cada push e manualmente |
| **Último APK** | aba Actions → último run verde de "Gerar APK" → artifact `anjos-vibe-apk` (contém `app-debug.apk`) |
| Instalar no celular | S25 Ultra da Mari com depuração USB: `adb install -r app-debug.apk` (adb via `winget install Google.PlatformTools`). Samsung: desligar "Bloqueador automático" para instalar por cabo |
| Versão do app | `versionCode = 1`, `versionName = "1.0"` (nunca foi incrementada) |
| Assinatura | **debug fixa** (`anjos-vibe/app/debug.keystore`, senha `android`) — todo APK de teste instala por cima do anterior. Não serve para a Play Store; a chave de release nunca entra no repo |

**Atenção à estrutura de pastas:** o projeto Gradle NÃO está na raiz. Está em `anjos-vibe/` dentro do repo:

```
Anjos-Vibe/                 ← raiz do repositório
├── .github/workflows/build.yml
└── anjos-vibe/             ← projeto Android (settings.gradle.kts aqui)
    ├── build.gradle.kts    (só os plugins com version ... apply false)
    ├── settings.gradle.kts
    ├── gradle.properties
    └── app/
        ├── build.gradle.kts
        └── src/main/java/br/com/anjosdoamor/vibe/
```

O `build.yml` localiza o `settings.gradle.kts` sozinho (`find`), então funciona com essa subpasta. Não há Gradle Wrapper no repo; o workflow usa `gradle-version: '8.7'` via `gradle/actions/setup-gradle@v4`. Se for reorganizar para a raiz, faça com cuidado e ajuste o workflow junto.

Branch extra: `claude/opensquad-install-0a37tb` (runs #20 e #21) — instalou e depois reverteu o framework "opensquad". Resultado líquido zero; o `main` não foi afetado.

O artifact do Actions expira (padrão do GitHub ~90 dias). Se precisar de APK novo, rode o workflow de novo (aba Actions → "Gerar APK" → Run workflow) ou faça um commit vazio.

---

## Stack

- Kotlin 1.9.24, Jetpack Compose (BOM 2024.06.00, compiler ext. 1.5.14), Material 3
- Android Gradle Plugin 8.5.2, Java 17
- `compileSdk 34`, `targetSdk 34`, `minSdk 24`
- `applicationId` / namespace: `br.com.anjosdoamor.vibe`
- kotlinx-serialization-json 1.6.3, coroutines 1.8.1, lifecycle 2.8.3
- Sem conta. Tudo local, exceto a aba Longa distância, que usa Firebase (BoM 33.1.2 — última linha compatível com Kotlin 1.9.24)

## Estrutura do código (`app/src/main/java/br/com/anjosdoamor/vibe/`)

| Arquivo | Papel |
|---|---|
| `ble/Protocol.kt` | Bytes do protocolo (com valores de fábrica), 9 modos, nomes, escala de intensidade, intervalo de reenvio — tudo editável em runtime e salvo em SharedPreferences `anjos_vibe_protocol` |
| `ble/BleBroadcaster.kt` | Transmissão BLE advertising (`setMode`/`forceMode`/`stopAll`) com a correção do bug de reinício |
| `engine/IntensityDriver.kt` | Converte intensidade contínua 0..1 nos modos da escala (com "modo suave" que alterna entre degraus vizinhos) |
| `engine/Pattern.kt` | Modelo de padrão (curva tempo × intensidade) + 5 padrões de fábrica: Onda, Pulso, Batida, Escalada, Provocação |
| `audio/BeatDetector.kt` | Detecção de batida pelo microfone (energia RMS vs média móvel) |
| `data/PatternStore.kt` | Padrões salvos pelo usuário (SharedPreferences, JSON) |
| `service/VibeService.kt` | Foreground service — mantém transmitindo com a tela apagada; notificação com botão "Parar" |
| `VibeController.kt` | Singleton central: estado (`StateFlow<VibeState>`), loop de tick (80 ms), timer, parada de emergência |
| `MainActivity.kt` | Navegação em 6 abas + pedidos de permissão |
| `ui/ControleScreen.kt` | Aba Controle (grade de 9 modos) |
| `ui/OutrasScreens.kt` | Abas Padrões, Desenhar e Música |
| `ui/AjustesScreen.kt` | Aba Ajustes: escala de intensidade, força da transmissão (reenvio), testar os 9 modos, editor dos bytes |
| `ui/Theme.kt` | Cores da marca sobre fundo escuro (roxo `#7D387D`, rosa `#F686BD`, magenta `#E12B8D`, fundo `#170A14`) |

### As 5 abas
1. **Controle** — grade com os 9 modos do aparelho + PARAR sempre visível
2. **Padrões** — 5 de fábrica + os que o usuário salvar
3. **Desenhar** — usuário desenha a curva de intensidade com o dedo, testa e salva
4. **Música** — escuta o ambiente pelo microfone e acompanha a batida (funciona com qualquer fonte de som)
5. **Ajustes** — diagnóstico do Bluetooth, escala, reenvio, teste dos modos e editor de bytes (para corrigir sem recompilar se a fábrica mudar o firmware)

---

## Protocolo BLE (confirmado por captura real)

Capturado com **nRF Connect** em dois Androids (Samsung e Xiaomi) com o Love Spouse controlando os aparelhos do estoque.

O aparelho só **escuta** pacotes de advertising — é broadcast de mão única, sem conexão GATT e sem resposta/confirmação.

| Campo | Valor |
|---|---|
| Company ID (Manufacturer Data) | `0x00FF` |
| Service UUID (16-bit) | `0xAE8F` |
| Prefixo do payload | `6DB643CE97FE427C` |
| Parar | `E5157D` |
| Payload = | prefixo + sufixo de 3 bytes |

**Como o Love Spouse transmite (captura HCI de 02/10/2026, S25 Ultra escutando um S21 FE):** cada toque é uma **rajada de ~0,9 s** — 6 a 8 pacotes legacy connectable (ADV_IND), um a cada ~135 ms, com **endereço novo por rajada** — e depois **silêncio**. O vibrador guarda o modo sozinho. Pacote: flags `01`, Manufacturer `FF00` + prefixo + sufixo, lista de UUID `8FAE`. O app agora faz igual: cada comando fica no ar `burst_ms` = **1000 ms** (Ajustes → "Comando no ar"; 0 = contínuo). **Transmitir sem parar faz os modos pulsarem** — não volte ao contínuo.

**9 modos — remapeados em 02/10/2026** (botão a botão, comparando a sensação no Love Spouse com a do nosso app; a ordem de agosto estava trocada):

| Botão (= Love Spouse) | Sufixo | Sensação |
|---|---|---|
| 1 | `E49C6C` | Contínuo fraco |
| 2 | `E7075E` | Contínuo médio |
| 3 | `E68E4F` | Contínuo forte |
| 4 | `E1313B` | Pulsa, pulsa, direto (ciclo ~3 s) |
| 5 | `E0B82A` | Pulsa pulsa pulsa |
| 6 | `E32318` | Pulsa ×3, depois rápido (~8 s) |
| 7 | `E2AA09` | Pulsa ×3, depois rápido |
| 8 | `ED5DF1` | Pulsa pulsa rápido |
| 9 | `ECD4E0` | Pulsa rápido + direto forte (~4 s) |

Botões 1–3 confirmados pela sensação. 4–9: casados pela sensação mais próxima e pela ordem 7-8-9 da captura HCI; se algum parecer trocado, é só reordenar `DEFAULT_MODOS`. Botões sem nome (só o número, como no Love Spouse).

**Escala de intensidade padrão = botões 1, 2, 3** (`DEFAULT_ESCALA = listOf(0, 1, 2)`). Padrões, desenho e música só usam os contínuos como degraus.

Valores salvos no aparelho (`anjos_vibe_protocol`: `modo_i`, `escala`, `refresh_ms`, `burst_ms`) não são sobrescritos por update — "Restaurar valores de fábrica" nos Ajustes limpa.

---

## Bugs resolvidos e armadilhas (ler antes de mexer)

1. **Vibração fraca / pulsando (o bug principal).** Causa: `ADVERTISE_FAILED_ALREADY_STARTED` era tratado como falha e disparava reinícios em cascata da transmissão, criando buracos de silêncio (no scanner aparecia um monte de MACs diferentes). Correção em `BleBroadcaster.kt`:
   - ignorar `ADVERTISE_FAILED_ALREADY_STARTED` sem zerar o estado;
   - intervalo mínimo de **150 ms** entre reinícios (`minRestartGapMs`);
   - reenvio padrão **0 = transmissão contínua** (`refresh_ms` default 0). Valor já salvo no aparelho não é sobrescrito por update — se alguém mexeu no slider, precisa voltar para "Reenvio desligado".
   - Sinal objetivo de que está certo: scanner mostra **um** endereço estável.
   **Não reintroduza reinícios periódicos.** (Nota de 02/10/2026: a "força igual ao Love Spouse" registrada em agosto estava errada — o pulsar real vinha da ordem trocada dos modos e da transmissão contínua; ver Protocolo.)

6. **App fechava ao apertar um modo em instalação nova** (02/10/2026): `startForeground` sem tipo herdava `microphone` do manifesto, que exige permissão de microfone já concedida. Agora declara `connectedDevice` sempre e `microphone` só com a permissão (`VibeService.entrarEmPrimeiroPlano`).

7. **Troca de degrau religava a transmissão** (02/10/2026): no Android 8+ usa `AdvertisingSet.setAdvertisingData` para trocar o pacote sem religar.

8. **Parada atrasada derrubava modo recém-ligado** (02/10/2026): o serviço chamava `VibeController.stop()` ao ser encerrado pelo próprio app. Agora o app usa `ACTION_DISMISS` (só tira o serviço) e o botão Parar da notificação usa `ACTION_STOP`.

9. **Build quebrado pelo `setup-android`** (02/10/2026): a action tentava instalar o pacote `tools`, que deixou de existir. Workflow usa `packages: ''`.

2. **Tradução automática do Chrome** já renomeou arquivos (`Protocol.kt` → `Protocolo.kt`, `Pattern.kt` → `Padrão.kt`) quando a Mari editou pelo site do GitHub, quebrando o build. Nomes de arquivo e pastas são sempre em inglês, sem acento.

3. **Imports explícitos do Compose**: `combinedClickable`, `animateFloat` etc. precisaram de import explícito — o build quebrou sem eles.

4. **`build.gradle.kts` raiz × `app/`**: já houve troca de conteúdo entre os dois. A raiz tem só os plugins com `version ... apply false`; o do `app/` tem `android { ... }` e as dependências.

5. **Strings sem acento** no código Kotlin (ex.: "Padroes", "Musica", "Continuo") — foi escolha para evitar problemas de encoding; pode-se migrar para `strings.xml` com acentos corretamente.

## Longa distância (controle por link) — desde 03/10/2026

Design: `docs/superpowers/specs/2026-10-03-longa-distancia-design.md`. Plano: `docs/superpowers/plans/2026-10-03-longa-distancia.md`.

```
Parceiro (página no navegador) → Firebase Realtime Database → app (dona) → Bluetooth → vibrador
```

| Peça | Onde |
|---|---|
| Projeto Firebase | `anjos-vibe` (conta Google da loja). Realtime Database `anjos-vibe-default-rtdb` (us-central1), Auth anônimo, Hosting |
| Página do parceiro | `web/` (HTML/CSS/JS puros, sem build) → https://anjos-vibe.web.app |
| Regras do banco | `database.rules.json` + 25 testes em `web-tests/` (workflow "Testar regras do banco", roda no emulador) |
| App | `remote/RemoteSession.kt` (tudo que fala com o Firebase), `remote/RemoteCommand.kt` (validação + limite de 20 comandos/s, com testes JUnit), `ui/LongaDistanciaScreen.kt` (6ª aba, "Distancia") |

- **Publicar** página ou regras: `npx firebase-tools deploy --only hosting` / `--only database` (login já feito no PC da Mari com `npx firebase-tools login`).
- **Chaves fora do repositório** (pedido da Mari depois de um alerta do GitHub): `anjos-vibe/app/google-services.json` fica só no PC e no secret `GOOGLE_SERVICES_JSON` do GitHub Actions; a página lê a configuração de `/__/firebase/init.json`. Não commitar nenhum dos dois.
- Regras de produto: dona aprova quem entra; um parceiro por link; link vale 24 h; **PARAR pausa o controle** até "Retomar"; sem sinal de vida do parceiro por 5 s o motor para; fechar o app encerra a sessão.
- Ao apagar uma sessão, a chave (`sessionKeys/{code}`) sai **antes** da sessão — depois as regras não deixam mais.
- Testado de ponta a ponta em 03/10/2026 (PC como parceiro + S25 no cabo, e pela Mari com um segundo celular).
- **Próxima etapa:** chamada de vídeo dentro da página e do app (WebRTC direto, usando a mesma sessão para os dois se encontrarem). Ainda sem design.

## O que NÃO dá com este hardware
- Web Bluetooth/site/extensão/PWA: não conseguem fazer advertising (só cliente GATT); Safari nem tem Web Bluetooth. WebView híbrido ainda exigiria Bluetooth e serviço nativos.
- Sincronia entre dois brinquedos (estilo Lovense) — não há sensor nem canal de volta.
- Intensidade contínua de verdade — só existem os degraus/modos fixos; dá apenas para simular.

---

## Estado atual

- App **funcional e testado** pela Mari em Samsung e Xiaomi (APK debug instalado direto).
- Ainda **não** testado em vários aparelhos (Motorola, Android 11 vs 12+), nem o comportamento com tela apagada por muito tempo em Xiaomi/Samsung (otimização de bateria mata serviço).
- Nada publicado na Play Store ainda.

## Próximos passos / backlog

**Curto prazo (v1 → pronto para cliente)**
- Testar o roteiro do README (itens críticos: tela apagada por 2+ min; fechar pelo "recentes" para; botão Parar da notificação)
- Nomear os modos 4–9 depois de testá-los
- Avisar/guiar o usuário a liberar o app da otimização de bateria (Xiaomi/Samsung)
- Incrementar `versionCode`/`versionName` a cada release
- Release assinado: gerar keystore (**guardar em lugar seguro — se perder, nunca mais atualiza o app**), configurar assinatura no build (secrets do GitHub), gerar AAB
- Play Store: conta de desenvolvedor (US$ 25, CNPJ), política de privacidade publicada, classificação adulta, ícone/prints/descrição sensuais mas não explícitos

**v1.1 (ideias aprovadas como viáveis, sem servidor)**
- Alarme/timer de vibração (despertar com padrão escolhido)
- Modo velocidade: intensidade pelo acelerômetro do celular
- Playlists de padrões

**v2 (precisa de servidor, ex. Firebase)**
- Controle a distância / ao vivo (inclusive durante live)
- Biblioteca de padrões compartilhados (exige moderação)

**iOS — não testado, não comprometer ainda.** CoreBluetooth não deixa app colocar Manufacturer Data no advertising. Antes de qualquer código: com um iPhone rodando o Love Spouse e um Android no scanner do nRF Connect, verificar o que o iPhone transmite (Manufacturer Data `0x00FF`? UUID de 128 bits escondendo os bytes? nada?).

---

## Como a Mari trabalha
- PC Windows (principal) + Mac disponível; GitHub Desktop para enviar arquivos; testa no celular com o APK baixado do Actions.
- Prefere receber soluções completas e testadas, e que o Claude execute direto o que puder.
- Fluxo de entrega que funcionou: Claude altera → commit/push no `main` → Actions gera APK → Mari baixa o artifact, instala e testa.
