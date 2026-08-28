# Anjos Vibe — v1

App Android para controlar os vibradores Bluetooth vendidos pela Anjos do Amor,
com a identidade da marca e em português.

---

## Como gerar o APK sem instalar nada

Você não precisa de Android Studio, nem de Mac, nem de saber programar.
O GitHub compila o app na nuvem, de graça, e te entrega o arquivo pronto.

**1.** Crie uma conta em github.com (grátis).

**2.** Clique em **New repository**. Dê o nome `anjos-vibe`, marque **Private**
e clique em **Create repository**.

**3.** Na tela que abrir, clique em **uploading an existing file**.
Arraste a pasta `anjos-vibe` inteira para dentro da janela. Espere subir tudo
e clique em **Commit changes**.

**4.** Vá na aba **Actions**, no topo. Vai aparecer uma tarefa chamada
"Gerar APK" rodando. Leva de 5 a 10 minutos na primeira vez.

**5.** Quando aparecer o ✅ verde, clique no nome da tarefa e role até o fim.
Em **Artifacts** vai ter `anjos-vibe-apk`. Baixe.

**6.** É um arquivo .zip. Descompacte e você tem o `anjos-vibe.apk`.

**Se aparecer ❌ vermelho:** clique na tarefa, abra o passo que falhou e me
mande a mensagem de erro. É normal precisar de um ajuste na primeira vez.

## Como instalar no celular

Mande o `.apk` para você mesma pelo WhatsApp ou passe por cabo.
Abra o arquivo no celular. O Android vai avisar que é de fonte desconhecida —
toque em **Configurações**, libere para o app que está abrindo, e volte.

Este APK é de teste (assinatura de debug). Serve para testar em quantos
aparelhos você quiser, mas **não é o arquivo que sobe na Play Store**.
Para a loja é preciso gerar uma versão assinada, o que exige a chave de
assinatura — falamos disso na hora certa.

---

## O que o app faz

| Aba | Função |
|---|---|
| **Controle** | Dial circular, arraste para cima. Botões 1, 2 e 3. Parada sempre visível |
| **Padrões** | Onda, Pulso, Batida, Escalada, Provocação — mais os que o cliente criar |
| **Desenhar** | Cliente desenha a curva de intensidade com o dedo, testa e salva |
| **Música** | Escuta o ambiente pelo microfone e acompanha a batida |
| **Ajustes** | Diagnóstico do Bluetooth e edição dos bytes de comando |

### Modo suave

O aparelho só entende parado / 1 / 2 / 3. O modo suave alterna rapidamente
entre dois níveis vizinhos para simular intensidades intermediárias.
Funciona, mas pode ficar trepidante em alguns motores — por isso tem
interruptor para desligar.

### A aba Ajustes existe por um motivo

Se a fábrica trocar o firmware de um lote, os bytes de comando mudam e o app
para de funcionar naquele produto. Em vez de recompilar tudo, você captura o
novo pacote com o nRF Connect e digita ali. Sem republicar nada.

---

## Protocolo capturado do estoque

Confirmado por captura no aparelho em 28/08/2026:

```
Company ID     0x00FF
Service UUID   0xAE8F
Prefixo        6DB643CE97FE427C
Velocidade 2   E7075E     <- CONFIRMADO no aparelho
```

Ainda **não confirmados** (vieram da documentação pública do protocolo):

```
Parar          E5157D
Velocidade 1   E49C6C
Velocidade 3   E68E4F
```

Como o prefixo e a velocidade 2 bateram exatamente, é muito provável que os
outros três também estejam certos. Se algum não responder, capture o correto
e corrija na aba Ajustes.

---

## Roteiro de teste

Copie isto e mande para quem for testar. Peça para responder item por item —
"não funcionou" sozinho não ajuda a consertar.

**Aparelho:** marca, modelo e versão do Android

1. O app abre e pede permissão de Bluetooth?
2. Na aba Ajustes, o status diz "Pronto para transmitir"?
3. Os botões de teste 1, 2 e 3 na aba Ajustes fazem o aparelho vibrar?
4. O botão "off" para de verdade?
5. No Controle, arrastar o dial aumenta e diminui de forma perceptível?
6. Com o modo suave ligado, a vibração fica trepidante ou natural?
7. Cada um dos 5 padrões funciona? Algum não faz nada?
8. Desenhar um padrão, testar e salvar funciona?
9. A música acompanha a batida? Precisou aumentar a sensibilidade?
10. **Com a tela apagada por 2 minutos, continua vibrando?** ← o mais importante
11. O botão Parar na notificação funciona sem desbloquear o celular?
12. Quanto de bateria caiu em 20 minutos de uso?
13. Fechar o app pela lista de recentes faz o aparelho parar?

Os itens 10 e 13 são os que mais quebram entre fabricantes. Xiaomi e Samsung
matam serviços em segundo plano por padrão — se falhar, o usuário precisa
liberar o app em "otimização de bateria".

---

## Estrutura do código

```
ble/Protocol.kt        Bytes do protocolo, editáveis sem recompilar
ble/BleBroadcaster.kt  Transmissão dos pacotes de advertising
engine/Pattern.kt      Modelo de padrão e os 5 de fábrica
engine/IntensityDriver Converte intensidade contínua nos 4 níveis
audio/BeatDetector.kt  Detecção de batida pelo microfone
service/VibeService.kt Mantém a transmissão com a tela apagada
VibeController.kt      Coração: estado e comandos
ui/                    Telas em Jetpack Compose
```

---

## Ainda falta para a Play Store

- Gerar chave de assinatura e **guardar em lugar seguro** — se perder,
  nunca mais dá para atualizar o app, só publicar outro do zero
- Conta Google Play Developer (US$ 25, pagamento único, precisa de CNPJ)
- Política de privacidade publicada em um link
- Classificação Mature 17+
- Ícone, capturas de tela e descrição — sensuais mas não explícitos,
  ou a revisão reprova
