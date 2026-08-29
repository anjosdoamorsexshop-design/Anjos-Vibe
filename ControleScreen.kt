package br.com.anjosdoamor.vibe.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import br.com.anjosdoamor.vibe.Mode
import br.com.anjosdoamor.vibe.VibeController
import br.com.anjosdoamor.vibe.VibeState
import kotlin.math.roundToInt

/**
 * Tela de controle. O dial circular e o elemento central: arrastar para
 * cima aumenta, para baixo diminui. O anel pulsa junto com a vibracao.
 */
@Composable
fun ControleScreen(state: VibeState) {

    val animated by animateFloatAsState(
        targetValue = state.intensity,
        label = "intensidade"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Spacer(Modifier.height(16.dp))

        Text(
            text = when (state.mode) {
                Mode.MANUAL -> "CONTROLE"
                Mode.PADRAO -> "PADRAO: ${state.patternId?.uppercase() ?: ""}"
                Mode.MUSICA -> "MUSICA"
                Mode.DIRETO -> "NIVEL ${state.directLevel}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = Brand.TextoFraco
        )

        Spacer(Modifier.height(24.dp))

        IntensityDial(
            intensity = animated,
            level = state.currentLevel,
            onChange = { VibeController.setManualIntensity(it) }
        )

        Spacer(Modifier.height(28.dp))

        // Botoes de nivel direto
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            listOf(1, 2, 3).forEach { level ->
                val active = state.currentLevel == level
                Button(
                    onClick = { VibeController.setLevel(level) },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (active) Brand.Magenta else Brand.Superficie,
                        contentColor = if (active) Color.White else Brand.TextoFraco
                    )
                ) {
                    Text("$level", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Parada. Sempre visivel, sempre funciona.
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

        Spacer(Modifier.height(20.dp))

        // Forca: empurra a curva inteira para cima sem perder a forma
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Forca", color = Brand.Texto, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    state.gain < 1.3f -> "suave"
                    state.gain < 1.9f -> "media"
                    state.gain < 2.3f -> "forte"
                    else -> "maxima"
                },
                color = Brand.Rosa,
                fontSize = 13.sp
            )
        }
        Slider(
            value = state.gain,
            onValueChange = { VibeController.setGain(it) },
            valueRange = 1f..2.6f
        )
        Text(
            "Deixa os padroes e o desenho mais intensos sem mudar o formato.",
            color = Brand.TextoFraco,
            fontSize = 12.sp
        )

        Spacer(Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.weight(1f)) {
                Text("Modo suave", color = Brand.Texto, fontSize = 15.sp)
                Text(
                    "Meios-termos entre os niveis. Desligue para vibracao mais forte e direta.",
                    color = Brand.TextoFraco,
                    fontSize = 12.sp
                )
            }
            Switch(
                checked = state.smoothMode,
                onCheckedChange = { VibeController.setSmoothMode(it) }
            )
        }

        state.error?.let { msg ->
            Spacer(Modifier.height(16.dp))
            Surface(
                color = Brand.Perigo.copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    msg,
                    color = Brand.Perigo,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

/**
 * Dial circular. O anel externo mostra a intensidade; o nucleo pulsa
 * com o nivel que esta realmente no ar.
 */
@Composable
private fun IntensityDial(
    intensity: Float,
    level: Int,
    onChange: (Float) -> Unit
) {
    var dragValue by remember { mutableFloatStateOf(intensity) }

    LaunchedEffect(intensity) {
        dragValue = intensity
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(260.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    // Arrastar para cima aumenta
                    val delta = -dragAmount.y / 320f
                    dragValue = (dragValue + delta).coerceIn(0f, 1f)
                    onChange(dragValue)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 18.dp.toPx()
            val radius = (size.minDimension - stroke) / 2f

            // Trilho
            drawCircle(
                color = Brand.Superficie,
                radius = radius,
                style = Stroke(width = stroke)
            )

            // Arco de intensidade
            if (intensity > 0f) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(Brand.Roxo, Brand.Magenta, Brand.Rosa, Brand.Roxo)
                    ),
                    startAngle = 135f,
                    sweepAngle = 270f * intensity,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    topLeft = Offset(
                        (size.width - radius * 2) / 2,
                        (size.height - radius * 2) / 2
                    ),
                    size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
                )
            }

            // Nucleo que respira com o nivel no ar
            if (level > 0) {
                drawCircle(
                    color = Brand.Magenta.copy(alpha = 0.06f + level * 0.05f),
                    radius = radius * (0.45f + level * 0.09f)
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${(intensity * 100).roundToInt()}",
                style = MaterialTheme.typography.displayLarge,
                color = Brand.Texto
            )
            Text(
                if (level > 0) "nivel $level" else "parado",
                color = Brand.TextoFraco,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "arraste para cima",
                color = Brand.TextoFraco.copy(alpha = 0.5f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
