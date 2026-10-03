package br.com.anjosdoamor.vibe.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import br.com.anjosdoamor.vibe.MainActivity
import br.com.anjosdoamor.vibe.R
import br.com.anjosdoamor.vibe.VibeController

/**
 * Sem este servico, o Android suspende o app quando a tela apaga e a
 * transmissao para no meio da sessao.
 *
 * A notificacao fixa tem um botao de PARAR -- assim da para cortar tudo
 * pela barra de notificacoes, sem precisar desbloquear o celular.
 */
class VibeService : Service() {

    companion object {
        private const val CHANNEL_ID = "anjos_vibe_sessao"
        private const val NOTIFICATION_ID = 4201

        const val ACTION_START = "br.com.anjosdoamor.vibe.START"
        const val ACTION_STOP = "br.com.anjosdoamor.vibe.STOP"

        fun start(context: Context) {
            // Sempre chamado com o app na tela, entao startService basta -- e
            // nao derruba o app se o Android recusar o primeiro plano.
            context.startService(
                Intent(context, VibeService::class.java).setAction(ACTION_START)
            )
        }

        /**
         * Tira o servico do ar quando a sessao ja acabou. Nao para o motor:
         * quem chama ja parou. Parar de novo aqui chegava atrasado e
         * derrubava um modo que o usuario tinha acabado de ligar.
         */
        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, VibeService::class.java).setAction(ACTION_DISMISS)
                )
            } catch (e: Exception) {
                // App em segundo plano: o Android nao deixa, e nao precisa
            }
        }

        private const val ACTION_DISMISS = "br.com.anjosdoamor.vibe.DISMISS"
    }

    /** O app pediu para encerrar: a sessao ja esta parada. */
    private var encerradoPeloApp = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        VibeController.init(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> {
                encerradoPeloApp = true
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            // Botao "Parar" da notificacao
            ACTION_STOP -> {
                VibeController.stop()
                encerradoPeloApp = true
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> entrarEmPrimeiroPlano()
        }
        return START_STICKY
    }

    /**
     * Declara so os tipos que o app pode usar agora.
     *
     * Sem tipo explicito o Android assume todos os do manifesto, e o tipo
     * "microphone" exige a permissao do microfone ja concedida -- quem nunca
     * abriu a aba Musica nao tem, e o app fechava ao ligar qualquer modo.
     */
    private fun entrarEmPrimeiroPlano() {
        encerradoPeloApp = false
        var tipos = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tipos = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            val temMicrofone = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && temMicrofone) {
                tipos = tipos or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), tipos)
        } catch (e: Exception) {
            // Sem primeiro plano a sessao ainda funciona com a tela acesa
            Log.w("AnjosVibe/Service", "startForeground: ${e.message}")
        }
    }

    override fun onDestroy() {
        // So para o motor se o servico morreu sem o app pedir
        if (!encerradoPeloApp) VibeController.stop()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App fechado pelo usuario: para tudo por seguranca
        br.com.anjosdoamor.vibe.remote.RemoteSession.end()
        VibeController.stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sessao ativa",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mantem a conexao enquanto a tela esta apagada"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, VibeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Sessao ativa")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .addAction(0, "Parar", stopIntent)
            .build()
    }
}
