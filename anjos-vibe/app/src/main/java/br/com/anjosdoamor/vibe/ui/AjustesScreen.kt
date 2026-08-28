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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.anjosdoamor.vibe.VibeController
import br.com.anjosdoamor.vibe.ble.BleBroadcaster
import br.com.anjosdoamor.vibe.ble.Protocol

/**
 * Ajustes tecnicos. Existe por um motivo pratico: se a fabrica trocar o
 * firmware de um lote, os bytes de comando mudam. Aqui da para corrigir
 * sem recompilar o app -- basta capturar o novo pacote com o nRF Connect
 * e digitar aqui.
 */
@Composable
fun AjustesScreen() {
    val context = LocalContext.current

    var companyId by remember {
        mutableStateOf("%04X".format(Protocol.companyId(context)))
    }
    var serviceUuid by remember {
        mutableStateOf(
            Protocol.serviceUuid(context).uuid.toString()
                .substring(4, 8).uppercase()
        )
    }
    var prefix by remember { mutableStateOf(Protocol.prefix(context)) }
    var stop by remember { mutableStateOf(Protocol.suffix(context, 0)) }
    var s1 by remember { mutableStateOf(Protocol.suffix(context, 1)) }
    var s2 by remember { mutableStateOf(Protocol.suffix(context, 2)) }
    var s3 by remember { mutableStateOf(Protocol.suffix(context, 3)) }
    var mensagem by remember { mutableStateOf<String?>(null) }

    val status = VibeController.bleStatus()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // Diagnostico
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

        Spacer(Modifier.height(24.dp))

        Text("COMANDOS DO APARELHO", style = MaterialTheme.typography.labelSmall, color = Brand.TextoFraco)
        Spacer(Modifier.height(6.dp))
        Text(
            "Capture o pacote com o nRF Connect e cole aqui se algum comando parar de funcionar.",
            color = Brand.TextoFraco,
            fontSize = 12.sp
        )

        Spacer(Modifier.height(16.dp))

        HexField("Company ID", companyId, 2) { companyId = it }
        HexField("Service UUID", serviceUuid, 2) { serviceUuid = it }
        HexField("Prefixo (8 bytes)", prefix, 8) { prefix = it }
        HexField("Parar", stop, 3) { stop = it }
        HexField("Velocidade 1", s1, 3) { s1 = it }
        HexField("Velocidade 2", s2, 3) { s2 = it }
        HexField("Velocidade 3", s3, 3) { s3 = it }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                val erro = listOf(
                    Protocol.validateHex(companyId, 2),
                    Protocol.validateHex(serviceUuid, 2),
                    Protocol.validateHex(prefix, 8),
                    Protocol.validateHex(stop, 3),
                    Protocol.validateHex(s1, 3),
                    Protocol.validateHex(s2, 3),
                    Protocol.validateHex(s3, 3)
                ).firstOrNull { it != null }

                if (erro != null) {
                    mensagem = erro
                } else {
                    Protocol.save(
                        context,
                        companyId.trim().removePrefix("0x").toInt(16),
                        serviceUuid, prefix, stop, s1, s2, s3
                    )
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
                s1 = Protocol.DEFAULT_SPEED_1
                s2 = Protocol.DEFAULT_SPEED_2
                s3 = Protocol.DEFAULT_SPEED_3
                mensagem = "Valores de fabrica restaurados."
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Restaurar valores de fabrica", color = Brand.TextoFraco) }

        mensagem?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Brand.Rosa, fontSize = 13.sp)
        }

        Spacer(Modifier.height(24.dp))

        Text("TESTE DE COMANDO", style = MaterialTheme.typography.labelSmall, color = Brand.TextoFraco)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 1, 2, 3).forEach { level ->
                OutlinedButton(
                    onClick = { VibeController.setLevel(level) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (level == 0) "off" else "$level")
                }
            }
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
        textStyle = androidx.compose.ui.text.TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            letterSpacing = 1.sp
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    )
}
