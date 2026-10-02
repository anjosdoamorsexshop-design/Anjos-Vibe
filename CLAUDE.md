# Anjos Vibe — contexto do projeto (handoff para o Claude Code)

App Android próprio da **Anjos do Amor** (sex shop online de Belo Horizonte) para controlar os vibradores Bluetooth que a loja vende — hoje controlados pelo app oficial **Love Spouse**. Objetivo: app da marca, em português, com mais funções que o Love Spouse, distribuído pela Play Store.

Dona do projeto: **Mari** (Mariana), sócia da loja. Ela não programa: o Claude escreve o código, ela testa no aparelho real e relata o que sentiu/viu. Explique os passos de forma simples e em português.

---

## Repositório e build

| Item | Valor |
|---|---|
| Repositório | https://github.com/anjosdoamorsexshop-design/Anjos-Vibe (público) |
| Branch principal | `main` |
| Último commit no `main` | `5f8e9ff` — "Update Protocol.kt" (30/08/2026) — escala 1-2-3 + nomes dos modos contínuos |
| Clone local no PC (Windows) | `C:\Users\anjos\OneDrive\Documentos\GitHub\Anjos-Vibe` (gerenciado pelo GitHub Desktop) |
| Build | GitHub Actions, workflow **"Gerar APK"** (`.github/workflows/build.yml`), roda a cada push e manualmente |
| **Último APK** | **Run #19** (commit `5f8e9ff`, sucesso) → https://github.com/anjosdoamorsexshop-design/Anjos-Vibe/actions/runs/33329281448 → artifact `anjos-vibe-apk` (contém `app-debug.apk`) |
| Versão do app | `versionCode = 1`, `versionName = "1.0"` (nunca foi incrementada) |
| Assinatura | **debug** — serve para teste, não para a Play Store |

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
- Sem servidor, sem conta, sem internet — tudo local (v1)

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
| `MainActivity.kt` | Navegação em 5 abas + pedidos de permissão |
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

**9 modos** (a documentação pública fala em 3 — está errada para este estoque):

| Modo | Sufixo | Tipo (confirmado no aparelho) |
|---|---|---|
| 1 | `E0B82A` | Contínuo fraco |
| 2 | `E1313B` | Contínuo médio |
| 3 | `E2AA09` | Contínuo forte (o mais forte) |
| 4 | `E32318` | Padrão de fábrica (pulsa) |
| 5 | `E49C6C` | Padrão de fábrica |
| 6 | `E68E4F` | Padrão de fábrica |
| 7 | `E7075E` | Padrão de fábrica |
| 8 | `ECD4E0` | Padrão de fábrica |
| 9 | `ED5DF1` | Padrão de fábrica |

**Escala de intensidade padrão = modos 1, 2, 3** (`DEFAULT_ESCALA = listOf(0, 1, 2)`, índices base zero). Padrões, desenho e música só devem usar os modos contínuos como degraus — usar modos 4–9 como degrau estraga as curvas, porque eles já pulsam sozinhos. Os modos 4–9 ainda não foram nomeados (cada um pode virar um nome comercial depois de testado).

---

## Bugs resolvidos e armadilhas (ler antes de mexer)

1. **Vibração fraca / pulsando (o bug principal).** Causa: `ADVERTISE_FAILED_ALREADY_STARTED` era tratado como falha e disparava reinícios em cascata da transmissão, criando buracos de silêncio (no scanner aparecia um monte de MACs diferentes). Correção em `BleBroadcaster.kt`:
   - ignorar `ADVERTISE_FAILED_ALREADY_STARTED` sem zerar o estado;
   - intervalo mínimo de **150 ms** entre reinícios (`minRestartGapMs`);
   - reenvio padrão **0 = transmissão contínua** (`refresh_ms` default 0). Valor já salvo no aparelho não é sobrescrito por update — se alguém mexeu no slider, precisa voltar para "Reenvio desligado".
   - Sinal objetivo de que está certo: scanner mostra **um** endereço estável.
   Depois disso a força ficou igual à do Love Spouse no Samsung e no Xiaomi. **Não reintroduza reinícios periódicos.**

2. **Tradução automática do Chrome** já renomeou arquivos (`Protocol.kt` → `Protocolo.kt`, `Pattern.kt` → `Padrão.kt`) quando a Mari editou pelo site do GitHub, quebrando o build. Nomes de arquivo e pastas são sempre em inglês, sem acento.

3. **Imports explícitos do Compose**: `combinedClickable`, `animateFloat` etc. precisaram de import explícito — o build quebrou sem eles.

4. **`build.gradle.kts` raiz × `app/`**: já houve troca de conteúdo entre os dois. A raiz tem só os plugins com `version ... apply false`; o do `app/` tem `android { ... }` e as dependências.

5. **Strings sem acento** no código Kotlin (ex.: "Padroes", "Musica", "Continuo") — foi escolha para evitar problemas de encoding; pode-se migrar para `strings.xml` com acentos corretamente.

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
