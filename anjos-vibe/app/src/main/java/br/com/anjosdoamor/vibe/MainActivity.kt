package br.com.anjosdoamor.vibe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.anjosdoamor.vibe.service.VibeService
import br.com.anjosdoamor.vibe.ui.*

class MainActivity : ComponentActivity() {

    private var micPermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        micPermission = result[Manifest.permission.RECORD_AUDIO] ?: micPermission
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        VibeController.init(this)
        requestNeededPermissions()

        setContent {
            AnjosVibeTheme {
                AppRoot(
                    micPermission = micPermission,
                    onRequestMic = {
                        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        // Nunca deixar o aparelho ligado depois que o app morre
        VibeController.shutdown()
        VibeService.stop(this)
        super.onDestroy()
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        micPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}

private data class Aba(val label: String, val icon: ImageVector)

@Composable
fun AppRoot(micPermission: Boolean, onRequestMic: () -> Unit) {
    val state by VibeController.state.collectAsStateWithLifecycle()
    var aba by remember { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Mantem o servico vivo enquanto houver sessao ativa
    LaunchedEffect(state.running) {
        if (state.running) VibeService.start(context) else VibeService.stop(context)
    }

    val abas = listOf(
        Aba("Controle", Icons.Default.TouchApp),
        Aba("Padroes", Icons.Default.GraphicEq),
        Aba("Desenhar", Icons.Default.Brush),
        Aba("Musica", Icons.Default.MusicNote),
        Aba("Ajustes", Icons.Default.Settings)
    )

    Scaffold(
        containerColor = Brand.Fundo,
        bottomBar = {
            NavigationBar(containerColor = Brand.Superficie) {
                abas.forEachIndexed { i, item ->
                    NavigationBarItem(
                        selected = aba == i,
                        onClick = { aba = i },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Brand.Rosa,
                            selectedTextColor = Brand.Rosa,
                            indicatorColor = Brand.Magenta.copy(alpha = 0.2f),
                            unselectedIconColor = Brand.TextoFraco,
                            unselectedTextColor = Brand.TextoFraco
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Brand.Fundo)
        ) {
            when (aba) {
                0 -> ControleScreen(state)
                1 -> PadroesScreen(state)
                2 -> DesenharScreen()
                3 -> MusicaScreen(state, onRequestMic, micPermission)
                else -> AjustesScreen()
            }
        }
    }
}
