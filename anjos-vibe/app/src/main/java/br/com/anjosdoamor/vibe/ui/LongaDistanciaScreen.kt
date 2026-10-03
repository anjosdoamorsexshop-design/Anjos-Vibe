package br.com.anjosdoamor.vibe.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.anjosdoamor.vibe.remote.RemoteSession
import br.com.anjosdoamor.vibe.remote.RemoteState

/**
 * Longa distancia: a dona cria um link, manda para alguem, e essa pessoa
 * controla o vibrador pelo navegador. Aqui ela aprova quem entra, pausa,
 * remove e encerra.
 */
@Composable
fun LongaDistanciaScreen() {
    val context = LocalContext.current
    val s by RemoteSession.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        if (!s.active) {
            Inicio(s) { RemoteSession.create(context) }
            return@Column
        }

        Status(s)

        Spacer(Modifier.height(16.dp))

        s.pendingRequest?.let { (_, nome) ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Brand.Magenta.copy(alpha = 0.16f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "$nome quer controlar",
                        color = Brand.Texto,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { RemoteSession.accept() },
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) { Text("Aceitar") }
                        OutlinedButton(
                            onClick = { RemoteSession.refuse() },
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) { Text("Recusar") }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (s.partnerName != null) {
            if (s.paused) {
                Button(
                    onClick = { RemoteSession.resume() },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(18.dp)
                ) { Text("RETOMAR", fontWeight = FontWeight.Bold, letterSpacing = 2.sp) }
            } else {
                Button(
                    onClick = { RemoteSession.pause() },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Brand.Perigo.copy(alpha = 0.16f),
                        contentColor = Brand.Perigo
                    )
                ) { Text("PARAR E PAUSAR", fontWeight = FontWeight.Bold, letterSpacing = 2.sp) }
            }
            Spacer(Modifier.height(12.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    val link = s.link ?: return@Button
                    val envio = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "Controle meu Anjos Vibe: $link")
                    context.startActivity(Intent.createChooser(envio, "Enviar link"))
                },
                modifier = Modifier.weight(1f).height(48.dp)
            ) { Text("Enviar link") }

            if (s.partnerName != null) {
                OutlinedButton(
                    onClick = { RemoteSession.removePartner() },
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("Remover") }
            }
        }

        Spacer(Modifier.height(10.dp))

        Text(
            if (s.partnerName == null)
                "So uma pessoa entra por link, e so depois que voce aceitar. " +
                    "O link vale por 24 horas ou ate voce encerrar."
            else
                "Remover tira ${s.partnerName} e cria um link novo.",
            color = Brand.TextoFraco,
            fontSize = 12.sp
        )

        s.link?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = Brand.TextoFraco.copy(alpha = 0.6f), fontSize = 10.sp)
        }

        Spacer(Modifier.height(24.dp))

        TextButton(
            onClick = { RemoteSession.end() },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Encerrar sessao", color = Brand.Perigo) }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun Inicio(s: RemoteState, onCriar: () -> Unit) {
    Text(
        "LONGA DISTANCIA",
        style = MaterialTheme.typography.labelSmall,
        color = Brand.TextoFraco
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Crie um link e envie para quem voce quiser. A pessoa abre no " +
            "navegador, sem instalar nada, e controla o seu vibrador de " +
            "onde estiver.",
        color = Brand.Texto,
        fontSize = 15.sp
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "Deixe este celular perto do vibrador, com o app aberto e internet. " +
            "Voce aprova quem entra e pode parar a qualquer momento.",
        color = Brand.TextoFraco,
        fontSize = 13.sp
    )

    Spacer(Modifier.height(24.dp))

    Button(
        onClick = onCriar,
        enabled = !s.creating,
        modifier = Modifier.fillMaxWidth().height(60.dp),
        shape = RoundedCornerShape(18.dp)
    ) {
        if (s.creating) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = Color.White
            )
        } else {
            Text("Criar link", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }

    s.error?.let {
        Spacer(Modifier.height(14.dp))
        Text(it, color = Brand.Perigo, fontSize = 13.sp)
    }
}

@Composable
private fun Status(s: RemoteState) {
    val (cor, titulo) = when {
        s.offline -> Brand.Perigo to "Sem internet neste celular"
        s.partnerName == null -> Brand.TextoFraco to "Aguardando alguem abrir o link"
        s.paused -> Brand.Rosa to "Pausado por voce"
        s.connectionLost -> Brand.Perigo to "Conexao com ${s.partnerName} perdida"
        else -> Brand.Rosa to "${s.partnerName} esta controlando"
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Brand.Superficie,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = cor, modifier = Modifier.size(10.dp)) {}
                Spacer(Modifier.width(10.dp))
                Text(titulo, color = Brand.Texto, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            if (s.partnerName != null && !s.paused && !s.offline) {
                Spacer(Modifier.height(10.dp))
                Text("Agora", color = Brand.TextoFraco, fontSize = 12.sp)
                Text(
                    s.now.ifEmpty { "Parado" },
                    color = Brand.Texto,
                    fontSize = 15.sp
                )
            }
        }
    }
}
