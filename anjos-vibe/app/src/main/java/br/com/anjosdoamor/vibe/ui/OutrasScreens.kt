package br.com.anjosdoamor.vibe.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.SystemClock
import br.com.anjosdoamor.vibe.Mode
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

/** Um ponto do rastro na tela. [traco] separa um toque do outro. */
private class Rastro(val x: Float, val y: Float, val em: Long, val traco: Int)

private const val RASTRO_MS = 320L
private const val GRAVACAO_MAX_MS = 30_000L
private const val AMOSTRA_MS = 40L

/** Faixa da tela (0 = baixo) para o degrau do aparelho: 1 fraco, 2 medio, 3 forte. */
private fun nivelDaAltura(alturaRelativa: Float): Int = when {
    alturaRelativa < 1f / 3f -> 1
    alturaRelativa < 2f / 3f -> 2
    else -> 3
}

/**
 * Desenho ao vivo: o vibrador acompanha o dedo enquanto ele esta na tela,
 * como no Love Spouse. Altura = forca, em tres faixas (os tres degraus
 * continuos do aparelho). O rastro e uma lamina que afina e some, no
 * estilo Fruit Ninja.
 *
 * Tudo o que e feito fica gravado com o tempo real -- inclusive as pausas
 * com o dedo fora da tela -- e pode ser testado de novo ou salvo como
 * padrao.
 */
@Composable
fun DesenharScreen() {
    val context = LocalContext.current

    val rastro = remember { mutableStateListOf<Rastro>() }
    var agora by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var nivelAtual by remember { mutableIntStateOf(0) }
    var traco by remember { mutableIntStateOf(0) }

    // Gravacao: (ms desde o primeiro toque, intensidade)
    val gravacao = remember { mutableStateListOf<Pair<Long, Float>>() }
    var inicioGravacao by remember { mutableStateOf<Long?>(null) }
    var ultimaAmostra by remember { mutableLongStateOf(0L) }

    var name by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    // Anima o rastro sumindo mesmo com o dedo parado
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            agora = SystemClock.uptimeMillis()
            if (rastro.isNotEmpty()) rastro.removeAll { agora - it.em > RASTRO_MS }
        }
    }

    // Saiu da aba com o dedo ainda valendo: para tudo
    DisposableEffect(Unit) {
        onDispose {
            val s = VibeController.state.value
            if (s.running && s.mode == Mode.MANUAL) VibeController.stop()
        }
    }

    fun gravar(intensidade: Float, inicioDeTraco: Boolean) {
        val t0 = inicioGravacao ?: SystemClock.uptimeMillis().also { inicioGravacao = it }
        val t = SystemClock.uptimeMillis() - t0
        if (t > GRAVACAO_MAX_MS) return
        if (inicioDeTraco && t > 0) {
            // Sem isso a curva subiria aos poucos durante a pausa
            gravacao.add((t - 1).coerceAtLeast(0L) to 0f)
        }
        if (inicioDeTraco || intensidade == 0f || t - ultimaAmostra >= AMOSTRA_MS) {
            gravacao.add(t to intensidade)
            ultimaAmostra = t
        }
    }

    fun tocar(x: Float, y: Float, altura: Float, inicioDeTraco: Boolean) {
        val relativa = (1f - y / altura).coerceIn(0f, 1f)
        val nivel = nivelDaAltura(relativa)
        val intensidade = VibeController.intensidadeDoNivel(nivel)
        nivelAtual = nivel
        rastro.add(Rastro(x, y, SystemClock.uptimeMillis(), traco))
        VibeController.setLiveIntensity(intensidade)
        gravar(intensidade, inicioDeTraco)
        saved = false
    }

    fun soltar() {
        nivelAtual = 0
        traco++
        VibeController.setLiveIntensity(0f)
        gravar(0f, inicioDeTraco = false)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        Text(
            "Deslize o dedo: o vibrador acompanha na hora. Mais alto, mais forte.",
            color = Brand.TextoFraco,
            fontSize = 13.sp
        )

        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(Brand.Superficie)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val h = size.height.toFloat()
                        tocar(down.position.x, down.position.y, h, inicioDeTraco = true)
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            c.consume()
                            tocar(c.position.x, c.position.y, h, inicioDeTraco = false)
                        }
                        soltar()
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val faixa = size.height / 3f

                // Faixas dos 3 degraus; a do dedo acende
                for (n in 1..3) {
                    val topo = size.height - n * faixa
                    if (n == nivelAtual) {
                        drawRect(
                            color = Brand.Magenta.copy(alpha = 0.10f + 0.05f * n),
                            topLeft = Offset(0f, topo),
                            size = Size(size.width, faixa)
                        )
                    }
                    if (n < 3) {
                        drawLine(
                            color = Brand.TextoFraco.copy(alpha = 0.15f),
                            start = Offset(0f, topo),
                            end = Offset(size.width, topo),
                            strokeWidth = 1f
                        )
                    }
                }

                // Lamina: grossa e brilhante na ponta, fina e apagada na cauda
                for (i in 1 until rastro.size) {
                    val a = rastro[i - 1]
                    val b = rastro[i]
                    if (a.traco != b.traco) continue
                    val vida = (1f - (agora - b.em).toFloat() / RASTRO_MS).coerceIn(0f, 1f)
                    if (vida <= 0f) continue
                    val grossura = 4f + 26f * vida
                    val p1 = Offset(a.x, a.y)
                    val p2 = Offset(b.x, b.y)
                    drawLine(
                        color = Brand.Magenta.copy(alpha = 0.30f * vida),
                        start = p1, end = p2,
                        strokeWidth = grossura * 2.2f,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Brand.Rosa.copy(alpha = 0.85f * vida),
                        start = p1, end = p2,
                        strokeWidth = grossura,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color.White.copy(alpha = 0.9f * vida),
                        start = p1, end = p2,
                        strokeWidth = grossura * 0.35f,
                        cap = StrokeCap.Round
                    )
                }
            }

            // Rotulos das faixas
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp)
            ) {
                listOf(3 to "forte", 2 to "medio", 1 to "fraco").forEach { (n, rotulo) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            rotulo,
                            color = if (n == nivelAtual) Brand.Rosa
                            else Brand.TextoFraco.copy(alpha = 0.45f),
                            fontSize = 12.sp,
                            fontWeight = if (n == nivelAtual) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        val gravadoMs = gravacao.lastOrNull()?.first ?: 0L
        Text(
            if (gravacao.isEmpty()) "Gravacao: comeca no primeiro toque (ate 30 s)"
            else "Gravado: ${gravadoMs / 1000}s" +
                if (gravadoMs >= GRAVACAO_MAX_MS) " (limite)" else "",
            color = Brand.TextoFraco,
            fontSize = 12.sp
        )

        Spacer(Modifier.height(8.dp))

        fun padraoGravado(id: String, nome: String): Pattern? {
            if (gravacao.size < 2) return null
            val dur = (gravacao.last().first + 200L).coerceAtLeast(1000L)
            return Pattern(
                id = id,
                name = nome,
                durationMs = dur.toInt(),
                points = gravacao.map { (t, v) -> Point(t.toFloat() / dur, v) }
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    VibeController.stop()
                    gravacao.clear()
                    inicioGravacao = null
                    ultimaAmostra = 0L
                    saved = false
                },
                modifier = Modifier.weight(1f)
            ) { Text("Limpar") }

            Button(
                onClick = {
                    padraoGravado("preview", "Previa")?.let { VibeController.playPattern(it) }
                },
                modifier = Modifier.weight(1f),
                enabled = gravacao.size > 1
            ) { Text("Repetir") }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nome do padrao") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    padraoGravado(PatternStore.newId(), name.trim())?.let {
                        PatternStore.save(context, it)
                        saved = true
                        name = ""
                    }
                },
                enabled = gravacao.size > 1 && name.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.height(56.dp)
            ) { Text(if (saved) "Salvo" else "Salvar") }
        }

        Spacer(Modifier.height(16.dp))
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
