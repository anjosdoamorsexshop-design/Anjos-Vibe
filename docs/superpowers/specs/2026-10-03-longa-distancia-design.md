# Longa distância — controle por link (design)

Data: 03/10/2026 · Aprovado pela Mari em conversa, parte por parte.

## Objetivo

A Mari (ou qualquer cliente) cria um link no app Anjos Vibe e envia para outra pessoa. Quem recebe abre o link **no navegador, sem instalar nada**, e controla o vibrador de onde estiver: os 9 modos, o desenho ao vivo e os padrões.

Fora deste projeto: chamada de vídeo. Ela será a etapa seguinte, com design próprio, dentro da mesma página e do app (WebRTC direto entre os aparelhos, usando a sessão descrita aqui para os dois se encontrarem). O WhatsApp em janela flutuante foi descartado porque nem todo celular oferece a janela flutuante.

## Visão geral

```
Parceiro (página no navegador) → Firebase → app Anjos Vibe (dona) → Bluetooth → vibrador
```

O vibrador só escuta Bluetooth de perto. Por isso o celular da dona fica com o app aberto ao lado dele e repassa cada comando que chega pela internet.

Três peças:

| Peça | Papel | Onde vive |
|---|---|---|
| Firebase (projeto da loja) | Hospeda a página; o Realtime Database repassa comandos; Auth anônimo identifica cada lado | Google |
| Página do parceiro | Interface de controle. HTML, CSS e JavaScript puros, sem etapa de build | `web/` no repositório, publicada no Firebase Hosting |
| App Anjos Vibe | Cria a sessão, aprova o parceiro, recebe comandos e aciona o `VibeController` | `anjos-vibe/` (código atual) |

Serviço escolhido: Firebase (plano gratuito Spark). Alternativas avaliadas e descartadas: servidor próprio na Cloudflare (mais código para manter) e conexão direta entre os celulares (falha em muitas redes sem servidor de apoio pago).

## Fluxo

1. Dona abre a aba **Longa distância** e toca **Criar link**. O app entra no Firebase com login anônimo, gera um código de sessão e uma chave secreta, e grava a sessão.
2. **Enviar link** abre o compartilhar do Android com `https://<projeto>.web.app/?s=<código>#<chave>`. A chave fica depois do `#`, parte do endereço que o navegador não envia a servidores nem grava em registros de acesso.
3. Parceiro abre o link, confirma **"Tenho 18 anos ou mais"**, digita um apelido. A página entra com login anônimo e grava um pedido de entrada com a chave.
4. No app aparece **"<apelido> quer controlar — Aceitar / Recusar"** (na tela e como notificação). Aceito, o parceiro passa a poder enviar comandos.
5. Cada comando gravado no banco chega ao app, que aciona o vibrador. O app publica de volta o estado (o que está tocando, se está pausado).
6. **Encerrar sessão** apaga a sessão. O link deixa de funcionar na hora.

## Dados no Realtime Database

```
sessionKeys/{codigo}            chave secreta. Só a dona grava; ninguém lê.
sessions/{codigo}/
  owner                         uid da dona
  createdAt                     horário do servidor
  partner/{uid, name}           parceiro aceito (gravado pela dona)
  requests/{uid}/{name, key, status}
                                pedido de entrada. Parceiro grava name+key;
                                dona grava status: "aceito" | "recusado"
  state/{paused, online, now, ts}
                                gravado pela dona. now = texto do que toca
  patterns/{id}/{name, durationMs, points}
                                padrões salvos da dona, publicados ao criar
  cmd/{seq, t, v}               último comando do parceiro
  beat                          sinal de vida do parceiro (horário do servidor)
```

Comandos (`cmd`):

| `t` | `v` | Efeito no app |
|---|---|---|
| `mode` | 1 a 9 | `VibeController.setMode(v)` |
| `level` | 0 a 3 | desenho ao vivo: `setLiveIntensity(intensidadeDoNivel(v))`; 0 = dedo fora da tela |
| `pattern` | id | toca o padrão (de fábrica ou de `patterns`) |
| `stop` | — | `VibeController.stop()` |

`seq` cresce a cada comando; o app ignora `seq` repetido ou menor que o último.

O desenho ao vivo envia só mudanças de faixa (fraco, médio, forte, soltou), não cada movimento do dedo. São poucos envios por segundo, o que mantém o atraso e o consumo baixos.

## Regras de segurança do banco

- Toda leitura e escrita exige login anônimo.
- `sessionKeys/{codigo}`: escrita só pela dona da sessão correspondente; leitura negada a todos (as regras leem internamente para comparar).
- `requests/{uid}`: o próprio `uid` cria o pedido, e só se `key` for igual a `sessionKeys/{codigo}`. A dona lê os pedidos e grava `status`. O parceiro lê apenas o próprio pedido.
- `partner`, `state`, `patterns`: escrita só pela dona. Leitura pela dona e pelo parceiro aceito.
- `cmd` e `beat`: escrita só pelo `uid` igual a `partner/uid`, e só enquanto `state/paused` não for verdadeiro. Leitura pela dona.
- Validação de formato: `t` dentro da lista, `v` dentro da faixa, apelido com no máximo 24 caracteres.
- Sessão com `createdAt` mais antigo que 24 h não aceita mais escrita de parceiro.

Um parceiro por sessão. **Remover parceiro** apaga `partner`, troca a chave e gera link novo; o parceiro antigo perde a permissão no mesmo instante.

## Quem manda: pausar, queda de conexão, abuso

- **PARAR** no app ou na notificação, durante uma sessão remota: para o vibrador e grava `state/paused = true`. A página mostra "Pausado". Só **Retomar**, no app, devolve o controle.
- **Sinal de vida:** a página grava `beat` a cada 2 s. O app, sem `beat` novo por 5 s, para o vibrador e mostra "Conexão perdida". Necessário porque o vibrador guarda o último modo sozinho. Ao reconectar, o controle volta com o vibrador parado.
- **Internet da dona caiu:** o app detecta (`.info/connected`), para o vibrador e avisa. `onDisconnect` grava `state/online = false` para a página mostrar "Ela está sem conexão".
- **Abuso:** o app aplica no máximo 20 comandos por segundo e descarta o excesso.
- **Sessões esquecidas:** o app guarda o código da última sessão e a apaga ao abrir, caso tenha fechado sem encerrar.

## Telas

**App — aba "Longa distância" (6ª aba)**

- Sem sessão: explicação curta e botão **Criar link**.
- Com sessão: estado ("Aguardando alguém abrir o link" / "<apelido> está controlando"), o que está tocando agora, **Parar e pausar** em destaque (vira **Retomar** quando pausado), **Enviar link**, **Remover**, **Encerrar sessão**.
- Pedido de entrada: cartão com **Aceitar / Recusar**, mais notificação.

**Página do parceiro**

- Entrada: confirmação de maioridade, apelido, "Aguardando aceitar".
- Controle: três seções — **Modos** (grade 1 a 9), **Desenhar** (três faixas com rastro de lâmina, igual ao app), **Padrões** (5 de fábrica + os salvos da dona).
- **Parar** sempre visível.
- Avisos: "Pausado por ela", "Conexão perdida", "Ela está sem conexão", "Sessão encerrada", "Sessão ocupada", "Link inválido".
- Identidade visual do app (roxo `#7D387D`, rosa `#F686BD`, magenta `#E12B8D`, fundo `#170A14`). Sem conteúdo explícito.
- Rodapé: link discreto **"Conheça a Anjos do Amor"** → https://anjosdoamorshop.com.br/
- Espaço reservado no topo para o vídeo da etapa seguinte.

## Mudanças no app

| Arquivo | Mudança |
|---|---|
| `remote/RemoteSession.kt` (novo) | Tudo que fala com o Firebase: criar e encerrar sessão, ouvir pedidos e comandos, publicar estado e padrões, sinal de vida. Expõe um `StateFlow` para a tela |
| `ui/LongaDistanciaScreen.kt` (novo) | A aba |
| `VibeController.kt` | `applyRemote(cmd)` traduz comando em chamada já existente; `stop()` avisa a sessão remota para pausar |
| `MainActivity.kt` | Sexta aba |
| `AndroidManifest.xml` | Permissão `INTERNET` |
| `app/build.gradle.kts` | Firebase BoM, Realtime Database, Auth; plugin `google-services` |
| `app/google-services.json` (novo) | Configuração do projeto Firebase. Não é segredo (as chaves de API do Firebase são públicas por desenho; a proteção são as regras do banco), mas o repositório é público, então as regras precisam estar publicadas antes |

O resto do app continua sem internet.

## Privacidade

- Sem conta, cadastro ou nome real. Só o identificador anônimo temporário do Firebase e o apelido digitado.
- Nenhum histórico de comandos: o banco guarda apenas o último comando, e a sessão é apagada ao encerrar.
- A política de privacidade exigida pela Play Store precisa descrever o uso de internet nesta aba. O texto será escrito junto com esta entrega.

## Testes

- **Regras do banco:** testes automáticos com o emulador do Firebase (`@firebase/rules-unit-testing`): sem chave não entra; chave errada não entra; parceiro não aceito não grava `cmd`; parceiro removido perde acesso; pausado não grava `cmd`; ninguém lê `sessionKeys`.
- **Tradução de comandos no app:** teste de unidade de `applyRemote` e do limite de 20 comandos por segundo.
- **Ponta a ponta:** página aberta no navegador do PC fazendo o papel do parceiro, S25 Ultra no cabo; conferência no registro do celular de que cada comando virou transmissão Bluetooth.
- **Teste da Mari:** segundo celular no 4G controlando o vibrador; desligar o Wi-Fi no meio para ver a parada por queda de conexão.

## Ordem de entrega

1. Projeto Firebase, regras e página com Modos e Parar; app cria sessão e obedece. Primeiro teste de ponta a ponta.
2. Aprovação do parceiro, pausar e retomar, remover, sinal de vida.
3. Desenhar ao vivo na página.
4. Padrões (de fábrica e salvos).
5. Acabamento: visual, link da loja, tela de maioridade, mensagens, texto da política de privacidade, aviso de economia de bateria.

## Depende da Mari

- Criar o projeto no Firebase com a conta Google da loja e ativar Realtime Database, Auth anônimo e Hosting.
- Fazer o login do Firebase no computador para permitir a publicação da página.

## Custo e limites

- Plano gratuito: 100 conexões simultâneas ao banco (cerca de 50 casais, pois cada sessão usa duas), 10 GB de tráfego por mês, Hosting gratuito.
- Acima disso: plano por uso (Blaze), com cartão cadastrado. No volume de uma loja, centavos por mês.

## Riscos

- **Atraso no desenho ao vivo:** 0,1 a 0,3 s em internet boa; mais em 4G fraco. Não há como zerar.
- **Economia de bateria** (Samsung, Xiaomi) pode encerrar o app com a tela apagada no meio da sessão. Mitigação: aviso para liberar o app da otimização.
- **Play Store:** controle remoto de produto adulto é permitido com classificação 18+ e sem conteúdo explícito.
