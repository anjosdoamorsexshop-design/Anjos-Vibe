package br.com.anjosdoamor.vibe.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.anjosdoamor.vibe.VibeController
import br.com.anjosdoamor.vibe.ble.BleBroadcaster
import br.com.anjosdoamor.vibe.ble.Protocol

/**
 * Ajustes tecnicos.
 *
 * Existe por um motivo pratico: se a fabrica trocar o firmware de um lote,
 * os bytes mudam e o app para de funcionar naquele produto. Aqui da para
 * corrigir sem recompilar -- basta capturar o pacote novo com o nRF Connect.
 */
@Composable
fun AjustesScreen() {
    val context = LocalContext.current

    var companyId by remember { mutableStateOf("%04X".format(Protocol.companyId(context))) }
    var serviceUuid by remember {
        mutableStateOf(Protocol.serviceUuid(context).uuid.toString().substring(4, 8).uppercase())
    }
    var prefix by remember { mutableStateOf(Protocol.prefix(context)) }
    var stop by remember { mutableStateOf(Protocol.stopSuffix(context)) }
    val modos = remember {
        mutableStateListOf(*(1..Protocol.TOTAL_MODOS).map {
            Protocol.modoSuffix(context, it)
        }.toTypedArray())
    }
    var escala by remember { mutableStateOf(Protocol.escala(context)) }
    var refresh by remember { mutableFloatStateOf(Protocol.refreshMs(context).toFloat()) }
    var mensagem by remember { mutableStateOf<String?>(null) }

    val status = VibeController.bleStatus()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (status == BleBroadcaster.Status.PRONTO)
                Brand.Roxo.copy(alpha = 0.2f) else Brand.Perigo.copy(alpha = 0.14f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("Bluetooth", style = MaterialTheme.typography.labelSmall, color = Brand.TextoFraco)
                Spacer(Modifier.height(4.dp))
                Text(
                    when (status) {
                        BleBroadcaster.Status.PRONTO -> "Pronto para transmitir"
                        BleBroadcaster.Status.BLUETOOTH_DESLIGADO -> "Bluetooth desligado"
                        BleBroadcaster.Status.SEM_PERMISSAO -> "Falta permissao de Bluetooth"
                        BleBroadcaster.Status.NAO_SUPORTADO -> "Este aparelho nao consegue transmitir"
                        BleBroadcaster.Status.SEM_BLUETOOTH -> "Sem Bluetooth neste aparelho"
                        BleBroadcaster.Status.ERRO -> "Erro"
                    },
                    color = Brand.Texto,
                    fontSize = 15.sp
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        // ---- Escala usada por padroes, desenho e musica -------------------

        Text("ESCALA DE INTENSIDADE", style = MaterialTheme.typography.labelSmall, color = Brand.TextoFraco)
        Spacer(Modifier.height(6.dp))
        Text(
            "Os padroes, o desenho e a musica precisam de tres degraus. " +
                "Escolha quais dos 9 modos sao velocidades constantes, do mais " +
                "fraco ao mais forte. Se escolher um modo que ja e um padrao de " +
                "fabrica, o resultado fica estranho.",
            color = Brand.TextoFraco,
            fontSize = 12.sp
        )

        Spacer(Modifier.height(14.dp))

        listOf("Fraco", "Medio", "Forte").forEachIndexed { i, rotulo ->
            Text("$rotulo: modo ${escala.getOrElse(i) { 1 }}", color = Brand.Texto, fontSize = 14.sp)
            Slider(
                value = escala.getOrElse(i) { 1 }.toFloat(),
                onValueChange = { v ->
                    escala = escala.toMutableList().also { list ->
                        while (list.size < 3) list.add(1)
                        list[i] = v.toInt().coerceIn(1, Protocol.TOTAL_MODOS)
                    }
                },
                valueRange = 1f..Protocol.TOTAL_MODOS.toFloat(),
                steps = Protocol.TOTAL_MODOS - 2
            )
        }

        Button(
            onClick = {
                Protocol.setEscala(context, escala)
                VibeController.reloadEscala()
                mensagem = "Escala salva."
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text("Salvar escala") }

        Spacer(Modifier.height(32.dp))

        // ---- Reenvio da transmissao --------------------------------------

        Text("FORCA DA TRANSMISSAO", style = MaterialTheme.typography.labelSmall, color = Brand.TextoFraco)
        Spacer(Modifier.height(6.dp))
        Text(
            "Desligado significa transmissao continua e estavel -- e o que " +
                "deixa o motor mais forte, porque ele nunca fica sem sinal. " +
                "So ligue o reenvio para teste: cada reenvio cria um intervalo " +
                "de silencio que enfraquece a vibracao.",
            color = Brand.TextoFraco,
            fontSize = 12.sp
        )

        Spacer(Modifier.height(12.dp))

        Text(
            if (refresh <= 0f) "Reenvio desligado" else "Reenviar a cada ${refresh.toInt()} ms",
            color = Brand.Texto,
            fontSize = 14.sp
        )
        Slider(
            value = refresh,
            onValueChange = { refresh = it },
            onValueChangeFinished = {
                Protocol.setRefreshMs(context, refresh.toLong())
                VibeController.reloadEscala()
            },
            valueRange = 0f..600f
        )
        Text(
            "Deixe desligado, que e o recomendado.",
            color = Brand.TextoFraco,
            fontSize = 11.sp
        )

        Spacer(Modifier.height(32.dp))

        // ---- Teste rapido dos 9 modos ------------------------------------

        Text("TESTAR OS MODOS", style = MaterialTheme.typography.labelSmall, color = Brand.TextoFraco)
        Spacer(Modifier.height(10.dp))

        for (linha in 0 until 3) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                for (col in 0 until 3) {
                    val m = linha * 3 + col + 1
                    OutlinedButton(
                        onClick = { VibeController.setMode(m) },
                        modifier = Modifier.weight(1f)
                    ) { Text("$m") }
                }
            }
        }

        OutlinedButton(
            onClick = { VibeController.stop() },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Parar", color = Brand.Perigo) }

        Spacer(Modifier.height(32.dp))

        // ---- Bytes do protocolo ------------------------------------------

        Text("COMANDOS DO APARELHO", style = MaterialTheme.typography.labelSmall, color = Brand.TextoFraco)
        Spacer(Modifier.height(6.dp))
        Text(
            "Capture com o nRF Connect e cole aqui se algum modo parar de funcionar.",
            color = Brand.TextoFraco,
            fontSize = 12.sp
        )

        Spacer(Modifier.height(14.dp))

        HexField("Company ID", companyId, 2) { companyId = it }
        HexField("Service UUID", serviceUuid, 2) { serviceUuid = it }
        HexField("Prefixo (8 bytes)", prefix, 8) { prefix = it }
        HexField("Parar", stop, 3) { stop = it }

        Spacer(Modifier.height(8.dp))

        modos.forEachIndexed { i, valor ->
            HexField("Modo ${i + 1}", valor, 3) { modos[i] = it }
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                val erro = (
                    listOf(
                        Protocol.validateHex(companyId, 2),
                        Protocol.validateHex(serviceUuid, 2),
                        Protocol.validateHex(prefix, 8),
                        Protocol.validateHex(stop, 3)
                    ) + modos.map { Protocol.validateHex(it, 3) }
                    ).firstOrNull { it != null }

                if (erro != null) {
                    mensagem = erro
                } else {
                    Protocol.saveBase(
                        context,
                        companyId.trim().removePrefix("0x").toInt(16),
                        serviceUuid, prefix, stop
                    )
                    modos.forEachIndexed { i, v -> Protocol.saveModo(context, i + 1, v) }
                    mensagem = "Comandos salvos."
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text("Salvar comandos") }

        Spacer(Modifier.height(10.dp))

        TextButton(
            onClick = {
                Protocol.restoreDefaults(context)
                companyId = "%04X".format(Protocol.DEFAULT_COMPANY_ID)
                serviceUuid = Protocol.DEFAULT_SERVICE_UUID
                prefix = Protocol.DEFAULT_PREFIX
                stop = Protocol.DEFAULT_STOP
                Protocol.DEFAULT_MODOS.forEachIndexed { i, v -> modos[i] = v }
                escala = Protocol.DEFAULT_ESCALA.map { it + 1 }
                refresh = 0f
                VibeController.reloadEscala()
                mensagem = "Valores de fabrica restaurados."
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Restaurar valores de fabrica", color = Brand.TextoFraco) }

        mensagem?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Brand.Rosa, fontSize = 13.sp)
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun HexField(
    label: String,
    value: String,
    expectedBytes: Int,
    onChange: (String) -> Unit
) {
    val erro = Protocol.validateHex(value, expectedBytes)
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.uppercase()) },
        label = { Text(label) },
        singleLine = true,
        isError = erro != null,
        supportingText = erro?.let { { Text(it, fontSize = 11.sp) } },
        textStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            letterSpacing = 1.sp
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    )
}
