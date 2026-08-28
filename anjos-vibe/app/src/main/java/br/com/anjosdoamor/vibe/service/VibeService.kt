package br.com.anjosdoamor.vibe.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
            val intent = Intent(context, VibeService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, VibeService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        VibeController.init(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                VibeController.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startForeground(NOTIFICATION_ID, buildNotification())
        }
        return START_STICKY
    }

    override fun onDestroy() {
        VibeController.stop()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App fechado pelo usuario: para tudo por seguranca
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
