package br.com.anjosdoamor.vibe.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.anjosdoamor.vibe.Mode
import br.com.anjosdoamor.vibe.VibeController
import br.com.anjosdoamor.vibe.VibeState
import br.com.anjosdoamor.vibe.ble.Protocol

/**
 * Tela principal: os 9 modos do aparelho, em grade.
 *
 * O aparelho tem 9 modos de fabrica -- nao tem controle continuo de
 * intensidade. Entao a tela mostra o que ele realmente faz, em vez de
 * fingir um dial que nao corresponde ao hardware.
 */
@Composable
fun ControleScreen(state: VibeState) {
    val context = LocalContext.current
    var renomeando by remember { mutableStateOf<Int?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        Text(
            text = when (state.mode) {
                Mode.DIRETO -> Protocol.modoNome(context, state.directMode).uppercase()
                Mode.PADRAO -> "PADRAO"
                Mode.MUSICA -> "MUSICA"
                Mode.MANUAL -> "PARADO"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (state.running) Brand.Rosa else Brand.TextoFraco
        )

        Spacer(Modifier.height(4.dp))

        PulseBar(ativo = state.running, intensidade = state.intensity)

        Spacer(Modifier.height(20.dp))

        // Grade dos 9 modos
        key(refresh) {
            for (linha in 0 until 3) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                ) {
                    for (col in 0 until 3) {
                        val modo = linha * 3 + col + 1
                        val ativo = state.mode == Mode.DIRETO && state.directMode == modo
                        ModoButton(
                            numero = modo,
                            nome = Protocol.modoNome(context, modo),
                            ativo = ativo,
                            modifier = Modifier.weight(1f),
                            onClick = { VibeController.setMode(modo) },
                            onLongClick = { renomeando = modo }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = { VibeController.stop() },
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Brand.Perigo.copy(alpha = 0.16f),
                contentColor = Brand.Perigo
            )
        ) {
            Text("PARAR", fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        }

        Spacer(Modifier.height(14.dp))

        Text(
            "Segure um modo para dar um nome a ele.",
            color = Brand.TextoFraco.copy(alpha = 0.7f),
            fontSize = 12.sp
        )

        state.error?.let { msg ->
            Spacer(Modifier.height(16.dp))
            Surface(
                color = Brand.Perigo.copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(msg, color = Brand.Perigo, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
            }
        }

        Spacer(Modifier.height(28.dp))
    }

    renomeando?.let { modo ->
        var nome by remember(modo) { mutableStateOf(Protocol.modoNome(context, modo)) }
        AlertDialog(
            onDismissRequest = { renomeando = null },
            title = { Text("Nome do modo $modo") },
            text = {
                OutlinedTextField(
                    value = nome,
                    onValueChange = { nome = it },
                    singleLine = true,
                    placeholder = { Text("ex: onda forte, pulsando...") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Protocol.setModoNome(context, modo, nome)
                    renomeando = null
                    refresh++
                }) { Text("Salvar") }
            },
            dismissButton = {
                TextButton(onClick = { renomeando = null }) { Text("Cancelar") }
            },
            containerColor = Brand.Superficie
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModoButton(
    numero: Int,
    nome: String,
    ativo: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (ativo) Brand.Magenta else Brand.Superficie,
        modifier = modifier
            .height(76.dp)
            .combinedClickableCompat(onClick = onClick, onLongClick = onLongClick)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(6.dp)
        ) {
            Text(
                "$numero",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (ativo) Color.White else Brand.Texto
            )
            if (nome != "Modo $numero") {
                Text(
                    nome,
                    fontSize = 10.sp,
                    maxLines = 2,
                    color = if (ativo) Color.White.copy(alpha = 0.85f) else Brand.TextoFraco
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(
    onClick: () -> Unit,
    onLongClick: () -> Unit
): Modifier = this.combinedClickable(
    onClick = onClick,
    onLongClick = onLongClick
)

/** Barra que pulsa enquanto ha sessao ativa. */
@Composable
private fun PulseBar(ativo: Boolean, intensidade: Float) {
    val transition = rememberInfiniteTransitionSafe(ativo)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        val bars = 32
        val gap = 5f
        val w = (size.width - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val phase = i.toFloat() / bars
            val h = if (!ativo) 4f else {
                val wave = kotlin.math.abs(
                    kotlin.math.sin(phase * 9f + transition * 6.28f)
                )
                (size.height * (0.15f + 0.85f * wave * intensidade.coerceAtLeast(0.35f)))
                    .coerceAtLeast(4f)
            }
            drawRoundRect(
                brush = Brush.verticalGradient(
                    if (ativo) listOf(Brand.Rosa, Brand.Magenta)
                    else listOf(Brand.TextoFraco.copy(alpha = 0.2f), Brand.TextoFraco.copy(alpha = 0.2f))
                ),
                topLeft = Offset(i * (w + gap), (size.height - h) / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2)
            )
        }
    }
}

@Composable
private fun rememberInfiniteTransitionSafe(ativo: Boolean): Float {
    if (!ativo) return 0f
    val t = rememberInfiniteTransition(label = "pulso")
    val v by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800),
            repeatMode = RepeatMode.Restart
        ),
        label = "fase"
    )
    return v
}
