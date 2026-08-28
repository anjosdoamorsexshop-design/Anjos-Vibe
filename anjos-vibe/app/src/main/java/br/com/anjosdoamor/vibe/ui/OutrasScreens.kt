package br.com.anjosdoamor.vibe.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.anjosdoamor.vibe.VibeController
import br.com.anjosdoamor.vibe.VibeState
import br.com.anjosdoamor.vibe.data.PatternStore
import br.com.anjosdoamor.vibe.engine.Pattern
import br.com.anjosdoamor.vibe.engine.Point

// ---------------------------------------------------------------- Padroes

@Composable
fun PadroesScreen(state: VibeState) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val patterns = remember(refresh) { PatternStore.all(context) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Spacer(Modifier.height(8.dp)) }

        items(patterns, key = { it.id }) { pattern ->
            val active = state.patternId == pattern.id
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (active) Brand.Magenta.copy(alpha = 0.18f) else Brand.Superficie,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { VibeController.playPattern(pattern) }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            pattern.name,
                            color = if (active) Brand.Rosa else Brand.Texto,
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp
                        )
                        Text(
                            "${pattern.durationMs / 1000}s por ciclo",
                            color = Brand.TextoFraco,
                            fontSize = 12.sp
                        )
                    }

                    PatternPreview(
                        pattern = pattern,
                        modifier = Modifier
                            .width(80.dp)
                            .height(34.dp)
                    )

                    if (!pattern.builtIn) {
                        Spacer(Modifier.width(10.dp))
                        TextButton(onClick = {
                            PatternStore.delete(context, pattern.id)
                            refresh++
                        }) {
                            Text("apagar", color = Brand.TextoFraco, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { VibeController.stop() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Brand.Perigo.copy(alpha = 0.16f),
                    contentColor = Brand.Perigo
                )
            ) {
                Text("PARAR", fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PatternPreview(pattern: Pattern, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val path = Path()
        val steps = 40
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val v = pattern.valueAt((t * pattern.durationMs).toLong())
            val x = t * size.width
            val y = size.height - (v * size.height)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            brush = Brush.horizontalGradient(listOf(Brand.Roxo, Brand.Rosa)),
            style = Stroke(width = 2.5f)
        )
    }
}

// --------------------------------------------------------------- Desenhar

@Composable
fun DesenharScreen() {
    val context = LocalContext.current
    var points by remember { mutableStateOf(listOf<Point>()) }
    var name by remember { mutableStateOf("") }
    var duration by remember { mutableFloatStateOf(6f) }
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        Text(
            "Arraste o dedo da esquerda para a direita para desenhar a intensidade ao longo do tempo.",
            color = Brand.TextoFraco,
            fontSize = 13.sp
        )

        Spacer(Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Brand.Superficie)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            points = listOf(
                                Point(
                                    (offset.x / size.width).coerceIn(0f, 1f),
                                    (1f - offset.y / size.height).coerceIn(0f, 1f)
                                )
                            )
                            saved = false
                        }
                    ) { change, _ ->
                        change.consume()
                        val t = (change.position.x / size.width).coerceIn(0f, 1f)
                        val v = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                        // So adiciona se avancou no tempo
                        if (points.isEmpty() || t > points.last().t) {
                            points = points + Point(t, v)
                        }
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Linhas de referencia nos 3 niveis do aparelho
                listOf(1f / 3f, 2f / 3f, 1f).forEach { level ->
                    val y = size.height * (1f - level)
                    drawLine(
                        color = Brand.TextoFraco.copy(alpha = 0.15f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f
                    )
                }

                if (points.size > 1) {
                    val path = Path()
                    points.forEachIndexed { i, p ->
                        val x = p.t * size.width
                        val y = size.height - p.v * size.height
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path = path,
                        brush = Brush.horizontalGradient(
                            listOf(Brand.Roxo, Brand.Magenta, Brand.Rosa)
                        ),
                        style = Stroke(width = 6f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    )
                }
            }

            if (points.isEmpty()) {
                Text(
                    "desenhe aqui",
                    color = Brand.TextoFraco.copy(alpha = 0.4f),
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Text("Duracao do ciclo: ${duration.toInt()}s", color = Brand.Texto, fontSize = 14.sp)
        Slider(
            value = duration,
            onValueChange = { duration = it },
            valueRange = 2f..30f
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Nome do padrao") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    points = emptyList()
                    saved = false
                },
                modifier = Modifier.weight(1f)
            ) { Text("Limpar") }

            Button(
                onClick = {
                    if (points.size > 1) {
                        VibeController.playPattern(
                            Pattern(
                                id = "preview",
                                name = "Previa",
                                durationMs = (duration * 1000).toInt(),
                                points = points
                            )
                        )
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = points.size > 1
            ) { Text("Testar") }
        }

        Spacer(Modifier.height(10.dp))

        Button(
            onClick = {
                if (points.size > 1 && name.isNotBlank()) {
                    PatternStore.save(
                        context,
                        Pattern(
                            id = PatternStore.newId(),
                            name = name.trim(),
                            durationMs = (duration * 1000).toInt(),
                            points = points
                        )
                    )
                    saved = true
                    name = ""
                    points = emptyList()
                }
            },
            enabled = points.size > 1 && name.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) { Text(if (saved) "Salvo" else "Salvar padrao") }

        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------- Musica

@Composable
fun MusicaScreen(state: VibeState, onNeedPermission: () -> Unit, hasPermission: Boolean) {
    var sensitivity by remember { mutableFloatStateOf(0.5f) }
    val ativo = state.mode == br.com.anjosdoamor.vibe.Mode.MUSICA && state.running

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(20.dp))

        Text(
            "O app escuta o som do ambiente e acompanha a batida. Funciona com qualquer musica tocando por perto.",
            color = Brand.TextoFraco,
            fontSize = 13.sp
        )

        Spacer(Modifier.height(28.dp))

        // Medidor ao vivo
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Brand.Superficie),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                val bars = 24
                val gap = 4f
                val w = (size.width - gap * (bars - 1)) / bars
                for (i in 0 until bars) {
                    val phase = (i.toFloat() / bars)
                    val h = size.height * (state.intensity * (0.4f + 0.6f * kotlin.math.sin(phase * 6.28f + state.intensity * 8f).let { kotlin.math.abs(it) }))
                    drawRoundRect(
                        color = if (h > 2f) Brand.Magenta else Brand.TextoFraco.copy(alpha = 0.15f),
                        topLeft = Offset(i * (w + gap), size.height - maxOf(h, 3f)),
                        size = androidx.compose.ui.geometry.Size(w, maxOf(h, 3f)),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2)
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Text("Sensibilidade", color = Brand.Texto, fontSize = 14.sp)
        Slider(
            value = sensitivity,
            onValueChange = {
                sensitivity = it
                VibeController.setMusicSensitivity(it)
            }
        )
        Text(
            "Aumente se o app nao estiver pegando a batida.",
            color = Brand.TextoFraco,
            fontSize = 12.sp
        )

        Spacer(Modifier.height(28.dp))

        Button(
            onClick = {
                if (!hasPermission) {
                    onNeedPermission()
                } else if (ativo) {
                    VibeController.stop()
                } else {
                    VibeController.startMusic()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (ativo) Brand.Perigo.copy(alpha = 0.16f) else Brand.Magenta,
                contentColor = if (ativo) Brand.Perigo else Color.White
            )
        ) {
            Text(
                when {
                    !hasPermission -> "PERMITIR MICROFONE"
                    ativo -> "PARAR"
                    else -> "OUVIR MUSICA"
                },
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
        }
    }
}
