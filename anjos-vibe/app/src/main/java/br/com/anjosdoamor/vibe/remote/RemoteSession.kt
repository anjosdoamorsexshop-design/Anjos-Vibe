package br.com.anjosdoamor.vibe.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import br.com.anjosdoamor.vibe.MainActivity
import br.com.anjosdoamor.vibe.R
import br.com.anjosdoamor.vibe.VibeController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

data class RemoteState(
    /** Existe sessao aberta. */
    val active: Boolean = false,
    /** Criando a sessao: aguardando o Firebase. */
    val creating: Boolean = false,
    val link: String? = null,
    val partnerName: String? = null,
    /** Pedido de entrada aguardando resposta: uid e apelido. */
    val pendingRequest: Pair<String, String>? = null,
    val paused: Boolean = false,
    /** Parceiro sem sinal de vida. */
    val connectionLost: Boolean = false,
    /** Celular da dona sem internet. */
    val offline: Boolean = false,
    /** O que esta tocando por comando do parceiro. */
    val now: String = "",
    val error: String? = null
)

/**
 * Sessao de longa distancia: tudo que fala com o Firebase mora aqui.
 *
 * A dona cria a sessao, o parceiro abre o link no navegador, e cada comando
 * dele chega por [ouvirComandos] e vai para o [VibeController]. As regras
 * de quem pode ler e gravar o que estao em database.rules.json, na raiz do
 * repositorio, com testes em web-tests/.
 *
 * Seguranca do lado do app, alem das regras do banco:
 *  - a dona aprova quem entra;
 *  - PARAR pausa o controle ate ela retomar;
 *  - sem sinal de vida do parceiro por [BEAT_TIMEOUT_MS], o motor para --
 *    o vibrador guarda o ultimo modo sozinho, entao o silencio nao basta.
 */
object RemoteSession {

    private const val PREFS = "anjos_vibe_remote"
    private const val BASE_URL = "https://anjos-vibe.web.app/"
    private const val BEAT_TIMEOUT_MS = 5000L
    private const val CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val CHANNEL_ID = "anjos_vibe_pedido"
    private const val NOTIFICATION_ID = 4202

    private val _state = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main)
    private val random = SecureRandom()
    private val gate = RemoteGate()

    private lateinit var appContext: Context
    private var code: String? = null
    private var sessionRef: DatabaseReference? = null
    private var partnerUid: String? = null

    private var requestsListener: ChildEventListener? = null
    private var cmdListener: ValueEventListener? = null
    private var beatListener: ValueEventListener? = null
    private var connectedListener: ValueEventListener? = null
    private var watchdog: Job? = null

    /** Momento (relogio do aparelho) do ultimo sinal de vida recebido. */
    private var lastBeatAt = 0L

    /** O motor esta ligado por um comando do parceiro. */
    private var remoteDriving = false

    private fun db() = FirebaseDatabase.getInstance()

    private fun update(f: (RemoteState) -> RemoteState) {
        _state.value = f(_state.value)
    }

    // ---- Criar e encerrar --------------------------------------------------

    fun create(context: Context) {
        if (_state.value.active || _state.value.creating) return
        appContext = context.applicationContext
        update { RemoteState(creating = true) }

        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
        if (user != null) {
            abrir(user.uid)
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { r ->
                    val uid = r.user?.uid
                    if (uid != null) abrir(uid) else falhar("Nao consegui entrar. Tente de novo.")
                }
                .addOnFailureListener { falhar("Sem conexao com a internet.") }
        }
    }

    private fun falhar(msg: String) {
        update { RemoteState(error = msg) }
    }

    private fun abrir(uid: String) {
        // Sessao esquecida de uma vez anterior (app fechado sem encerrar)
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("code", null)?.let { apagar(it) }

        val novo = gerar(CODE_CHARS, 10)
        val key = gerarHex(32)
        val ref = db().getReference("sessions/$novo")

        val padroes = VibeController.patterns()
            .filter { !it.builtIn && it.id.none { c -> c in ".\$#[]/" } }
            .associate { it.id to mapOf("name" to it.name.take(40)) }

        val sessao = mutableMapOf<String, Any>(
            "owner" to uid,
            "createdAt" to ServerValue.TIMESTAMP,
            "state" to mapOf("paused" to false, "online" to true, "now" to "")
        )
        if (padroes.isNotEmpty()) sessao["patterns"] = padroes

        ref.setValue(sessao)
            .addOnSuccessListener {
                db().getReference("sessionKeys/$novo").setValue(key)
                    .addOnSuccessListener {
                        prefs.edit().putString("code", novo).apply()
                        code = novo
                        sessionRef = ref
                        partnerUid = null
                        gate.reset()
                        remoteDriving = false
                        update { RemoteState(active = true, link = linkDe(novo, key)) }
                        ouvir(ref)
                        VibeController.onUserStop = {
                            if (_state.value.partnerName != null && !_state.value.paused) pause()
                        }
                    }
                    .addOnFailureListener { falhar("Nao consegui criar o link. Tente de novo.") }
            }
            .addOnFailureListener { falhar("Nao consegui criar o link. Tente de novo.") }
    }

    private fun linkDe(code: String, key: String) = "$BASE_URL?s=$code#$key"

    /** A chave sai primeiro: sem a sessao, as regras nao deixam mais apaga-la. */
    private fun apagar(code: String) {
        db().getReference("sessionKeys/$code").removeValue()
            .addOnCompleteListener { db().getReference("sessions/$code").removeValue() }
    }

    fun end() {
        val c = code ?: run {
            update { RemoteState() }
            return
        }
        if (remoteDriving) VibeController.stopSilently()
        pararDeOuvir()
        VibeController.onUserStop = null
        apagar(c)
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        cancelarNotificacao()
        code = null
        sessionRef = null
        partnerUid = null
        remoteDriving = false
        update { RemoteState() }
    }

    // ---- Parceiro ----------------------------------------------------------

    fun accept() {
        val ref = sessionRef ?: return
        val (uid, name) = _state.value.pendingRequest ?: return
        partnerUid = uid
        gate.reset()
        lastBeatAt = SystemClock.elapsedRealtime()
        ref.child("cmd").removeValue()
        ref.child("partner").setValue(mapOf("uid" to uid, "name" to name))
        ref.child("requests/$uid/status").setValue("aceito")
        cancelarNotificacao()
        update { it.copy(partnerName = name, pendingRequest = null, connectionLost = false) }
    }

    fun refuse() {
        val ref = sessionRef ?: return
        val (uid, _) = _state.value.pendingRequest ?: return
        ref.child("requests/$uid/status").setValue("recusado")
        cancelarNotificacao()
        update { it.copy(pendingRequest = null) }
    }

    /** Tira o parceiro e troca a chave: o link antigo deixa de valer. */
    fun removePartner() {
        val ref = sessionRef ?: return
        val c = code ?: return
        if (remoteDriving) VibeController.stopSilently()
        remoteDriving = false
        partnerUid?.let { ref.child("requests/$it/status").setValue("removido") }
        partnerUid = null
        ref.child("partner").removeValue()
        ref.child("cmd").removeValue()
        ref.child("beat").removeValue()
        setPausado(false)
        val key = gerarHex(32)
        db().getReference("sessionKeys/$c").setValue(key)
        gate.reset()
        update {
            it.copy(
                partnerName = null, paused = false, connectionLost = false,
                now = "", link = linkDe(c, key)
            )
        }
    }

    fun pause() {
        if (remoteDriving) VibeController.stopSilently()
        remoteDriving = false
        setPausado(true)
        update { it.copy(paused = true, now = "") }
    }

    fun resume() {
        lastBeatAt = SystemClock.elapsedRealtime()
        setPausado(false)
        update { it.copy(paused = false) }
    }

    private fun setPausado(p: Boolean) {
        val ref = sessionRef ?: return
        ref.child("state/paused").setValue(p)
        ref.child("state/now").setValue("")
    }

    // ---- Ouvir o banco -----------------------------------------------------

    private fun ouvir(ref: DatabaseReference) {
        ouvirConexao(ref)
        ouvirPedidos(ref)
        ouvirComandos(ref)
        ouvirSinalDeVida(ref)
        vigiar()
    }

    private fun ouvirConexao(ref: DatabaseReference) {
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val conectado = s.getValue(Boolean::class.java) == true
                if (conectado) {
                    ref.child("state/online").onDisconnect().setValue(false)
                    ref.child("state/online").setValue(true)
                    lastBeatAt = SystemClock.elapsedRealtime()
                    update { it.copy(offline = false) }
                } else {
                    // Sem internet ninguem consegue mandar parar: para ja
                    if (remoteDriving) VibeController.stopSilently()
                    remoteDriving = false
                    update { it.copy(offline = true, now = "") }
                }
            }

            override fun onCancelled(e: DatabaseError) {}
        }
        connectedListener = l
        db().getReference(".info/connected").addValueEventListener(l)
    }

    private fun ouvirPedidos(ref: DatabaseReference) {
        val l = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) = pedido(ref, s)
            override fun onChildChanged(s: DataSnapshot, prev: String?) = pedido(ref, s)
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }
        requestsListener = l
        ref.child("requests").addChildEventListener(l)
    }

    private fun pedido(ref: DatabaseReference, s: DataSnapshot) {
        val uid = s.key ?: return
        if (s.child("status").exists()) return
        val name = (s.child("name").getValue(String::class.java) ?: "").trim().take(24)
        if (name.isEmpty()) return

        val atual = partnerUid
        when {
            // O proprio parceiro recarregou a pagina
            atual == uid -> ref.child("requests/$uid/status").setValue("aceito")
            atual != null -> ref.child("requests/$uid/status").setValue("ocupada")
            _state.value.pendingRequest == null -> {
                update { it.copy(pendingRequest = uid to name) }
                notificarPedido(name)
            }
            _state.value.pendingRequest?.first != uid ->
                ref.child("requests/$uid/status").setValue("ocupada")
        }
    }

    private fun ouvirComandos(ref: DatabaseReference) {
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (!s.exists()) return
                if (partnerUid == null || _state.value.paused || _state.value.offline) return
                val seq = (s.child("seq").value as? Number)?.toLong() ?: return
                val cmd = RemoteCommand.parse(
                    s.child("t").getValue(String::class.java),
                    s.child("v").value
                ) ?: return
                if (!gate.accept(seq, SystemClock.elapsedRealtime())) return

                val texto = VibeController.applyRemote(cmd)
                remoteDriving = when (cmd) {
                    is RemoteCommand.Stop -> false
                    is RemoteCommand.Level -> cmd.level > 0
                    else -> true
                }
                // Comando chegando e sinal de vida tambem
                lastBeatAt = SystemClock.elapsedRealtime()
                ref.child("state/now").setValue(texto.take(40))
                update { it.copy(now = texto, connectionLost = false) }
            }

            override fun onCancelled(e: DatabaseError) {}
        }
        cmdListener = l
        ref.child("cmd").addValueEventListener(l)
    }

    private fun ouvirSinalDeVida(ref: DatabaseReference) {
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (!s.exists()) return
                lastBeatAt = SystemClock.elapsedRealtime()
                if (_state.value.connectionLost) update { it.copy(connectionLost = false) }
            }

            override fun onCancelled(e: DatabaseError) {}
        }
        beatListener = l
        ref.child("beat").addValueEventListener(l)
    }

    /** Sem sinal de vida com o motor ligado pelo parceiro: para o motor. */
    private fun vigiar() {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (true) {
                delay(1000)
                val semSinal = SystemClock.elapsedRealtime() - lastBeatAt > BEAT_TIMEOUT_MS
                if (partnerUid != null && semSinal && !_state.value.connectionLost) {
                    if (remoteDriving) VibeController.stopSilently()
                    remoteDriving = false
                    sessionRef?.child("state/now")?.setValue("")
                    update { it.copy(connectionLost = true, now = "") }
                }
            }
        }
    }

    private fun pararDeOuvir() {
        watchdog?.cancel()
        watchdog = null
        val ref = sessionRef
        requestsListener?.let { ref?.child("requests")?.removeEventListener(it) }
        cmdListener?.let { ref?.child("cmd")?.removeEventListener(it) }
        beatListener?.let { ref?.child("beat")?.removeEventListener(it) }
        connectedListener?.let { db().getReference(".info/connected").removeEventListener(it) }
        ref?.child("state/online")?.onDisconnect()?.cancel()
        requestsListener = null
        cmdListener = null
        beatListener = null
        connectedListener = null
    }

    // ---- Notificacao do pedido ---------------------------------------------

    private fun notificarPedido(name: String) {
        val nm = appContext.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Pedido de controle", NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val abrir = PendingIntent.getActivity(
            appContext, 2,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(appContext.getString(R.string.app_name))
            .setContentText("$name quer controlar. Toque para responder.")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            nm.notify(NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            // Sem permissao de notificacao: o pedido aparece so na tela
        }
    }

    private fun cancelarNotificacao() {
        if (!::appContext.isInitialized) return
        appContext.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    // ---- Codigo e chave ----------------------------------------------------

    private fun gerar(alfabeto: String, n: Int): String =
        buildString { repeat(n) { append(alfabeto[random.nextInt(alfabeto.length)]) } }

    private fun gerarHex(n: Int): String = gerar("0123456789abcdef", n)
}
