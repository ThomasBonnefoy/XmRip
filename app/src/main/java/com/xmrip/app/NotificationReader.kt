package com.xmrip.app

import android.app.Notification
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.xmrip.app.bt.HeadsetState
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Le casque lit à voix haute les notifications de certaines applications quand l'ordinateur n'est pas connecté.
 *
 * S'appuie sur « l'accès aux notifications » (déjà demandé pour le titre et la pochette de la musique,
 * [MediaListenerService]). La voix est celle d'Android (synthèse vocale) ; elle sort par le casque seulement s'il est
 * connecté en audio au téléphone, jamais sur le haut-parleur. Les réglages (applications, lecture du texte) restent sur
 * le téléphone.
 */
object NotificationReader {

    private const val TAG = "XmRip-Reader"
    private const val PREFS = "notif_reader"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_APPS = "apps"
    private const val KEY_CONTENT = "content"
    private const val MAX_SPOKEN = 220
    private const val DEDUP_MS = 15_000L
    private const val KEY_MODE = "mode"
    private const val KEY_ASLEEP = "only_asleep"
    private const val TARGET_RMS = 0.22
    private const val MAX_GAIN = 4.0
    private const val KNEE = 0.7

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun apps(context: Context): Set<String> = prefs(context).getStringSet(KEY_APPS, emptySet()) ?: emptySet()

    fun setApps(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet(KEY_APPS, packages).apply()
    }

    /** Lire aussi le texte de la notification (sinon seulement « notification de <application> »). */
    fun readsContent(context: Context): Boolean = prefs(context).getBoolean(KEY_CONTENT, true)

    fun setReadsContent(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONTENT, on).apply()
    }

    /** Ne lire que quand le téléphone est en veille (écran éteint) : quand on le regarde, la notification se lit toute seule. */
    fun onlyWhenAsleep(context: Context): Boolean = prefs(context).getBoolean(KEY_ASLEEP, false)

    fun setOnlyWhenAsleep(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ASLEEP, on).apply()
    }

    /** Applications qu'on peut ouvrir depuis le lanceur, triées par nom : la liste proposée à Thomas. */
    fun launchableApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != context.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    private val recent = HashMap<String, Long>()

    /**
     * Décide si une notification doit être lue et, si oui, renvoie le texte à dire. Appelé pour chaque notification
     * publiée ; la plupart sont écartées ici (autres applications, notifications permanentes, appels, mode « Ne pas
     * déranger », ordinateur connecté, casque absent).
     */
    fun textFor(service: NotificationListenerService, sbn: StatusBarNotification): String? {
        val context = service.applicationContext
        if (!isEnabled(context)) return null
        if (sbn.packageName == context.packageName || sbn.packageName !in apps(context)) return null

        // À partir d'ici la notification vient d'une application choisie : on retient pourquoi elle est lue ou non
        // (ligne « Dernière notification » de la carte), sans en garder le contenu.
        fun skip(reason: String): String? {
            setLastDecision(context, sbn.packageName, "ignorée, $reason")
            return null
        }
        if (onlyWhenAsleep(context) && context.getSystemService(PowerManager::class.java).isInteractive) {
            return skip("l'écran est allumé")
        }

        val n = sbn.notification
        // Les résumés de groupe sont créés par Android quand plusieurs notifications s'empilent : ils n'ont pas à écraser
        // la décision prise pour la vraie notification.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (sbn.isOngoing) return skip("notification permanente")
        if (n.category in setOf(
                Notification.CATEGORY_CALL, Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_PROGRESS,
                Notification.CATEGORY_SERVICE, Notification.CATEGORY_ALARM, Notification.CATEGORY_NAVIGATION,
            )
        ) return skip("catégorie ${n.category}")

        // Ce qu'Android lui-même jugerait assez important pour alerter, et que « Ne pas déranger » laisse passer.
        val ranking = NotificationListenerService.Ranking()
        if (service.currentRanking.getRanking(sbn.key, ranking)) {
            if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return skip("importance faible (silencieuse)")
            if (!ranking.matchesInterruptionFilter()) return skip("« Ne pas déranger » la bloque")
        }

        // L'ordinateur est connecté au casque : les notifications du téléphone n'ont pas à y passer, sauf si c'est
        // le téléphone qu'on écoute.
        if (PcQuiet.watchNeeded(context) && PcQuiet.isPcConnected(context) && !PcQuiet.phonePlaying(context)) {
            return skip("l'ordinateur est connecté au casque")
        }

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (!HeadsetState.connectedToPhone(context, adapter)) return skip("le casque n'est pas connecté au téléphone")

        val audio = context.getSystemService(AudioManager::class.java)
        if (audio.mode != AudioManager.MODE_NORMAL) return skip("un appel est en cours")

        val text = compose(context, sbn) ?: return skip("notification sans texte")
        val now = System.currentTimeMillis()
        synchronized(recent) {
            recent.values.removeAll { now - it > DEDUP_MS }
            val key = sbn.key + text.hashCode()
            if (recent.containsKey(key)) return skip("déjà lue il y a moins de 15 s")
            recent[key] = now
        }
        setLastDecision(context, sbn.packageName, "lue")
        return text
    }

    private const val KEY_LAST = "last_decision"

    private fun setLastDecision(context: Context, pkg: String, what: String) {
        val pm = context.packageManager
        val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        val time = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())
        prefs(context).edit().putString(KEY_LAST, "$time · $app : $what").apply()
    }

    /** Ce qui est arrivé à la dernière notification d'une application choisie (lue, ou pourquoi ignorée). */
    fun lastDecision(context: Context): String? = prefs(context).getString(KEY_LAST, null)
    private fun compose(context: Context, sbn: StatusBarNotification): String? {
        val pm = context.packageManager
        val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }
            .getOrDefault(sbn.packageName)
        if (!readsContent(context)) return "Notification de $app"
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()
        if (title.isEmpty() && body.isEmpty()) return null
        val said = listOf(app, title, body).filter { it.isNotEmpty() }.joinToString(". ")
        return if (said.length > MAX_SPOKEN) said.take(MAX_SPOKEN) + "…" else said
    }

    /** Dit une phrase d'essai par le casque, pour vérifier la voix et le volume. */
    fun test(context: Context): Speaker {
        val speaker = Speaker(context.applicationContext)
        speaker.say("Test de lecture des notifications par le casque.")
        return speaker
    }

    /** Pendant la voix, les autres sons sont : [MODE_LOUD] baissés par Android, la voix étant amplifiée ; [MODE_PAUSE] mis en pause. */
    const val MODE_LOUD = "loud"
    const val MODE_PAUSE = "pause"

    fun mode(context: Context): String = prefs(context).getString(KEY_MODE, MODE_LOUD) ?: MODE_LOUD

    fun setMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_MODE, mode).apply()
    }

    // ---------------------------------------------------------------------------------------
    // Choix de la voix
    //
    // La voix « robotique » est souvent la voix hors ligne de base. Le moteur de Google propose aussi, pour le
    // français, des voix « en ligne » (réseau), nettement plus naturelles : on prend la meilleure disponible, en ligne
    // si le téléphone a internet, sinon la meilleure hors ligne. Thomas peut aussi en imposer une.
    // ---------------------------------------------------------------------------------------

    private const val KEY_VOICE = "voice"

    /** Nom de la voix choisie, null = automatique (la plus naturelle disponible). */
    fun voiceName(context: Context): String? = prefs(context).getString(KEY_VOICE, null)

    fun setVoiceName(context: Context, name: String?) {
        prefs(context).edit().putString(KEY_VOICE, name).apply()
    }

    private fun online(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    /** Voix françaises installées (ou téléchargeables en ligne), les plus naturelles d'abord. */
    fun frenchVoices(engine: TextToSpeech): List<android.speech.tts.Voice> {
        val language = Locale.getDefault().language.takeIf { it == "fr" } ?: "fr"
        return runCatching { engine.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == language && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
            .sortedWith(
                compareByDescending<android.speech.tts.Voice> { it.quality }
                    .thenByDescending { it.isNetworkConnectionRequired }
                    .thenBy { if (it.locale.country == "FR") 0 else 1 }
                    .thenBy { it.name },
            )
    }

    /** Voix à utiliser maintenant : celle choisie si elle est utilisable, sinon la meilleure disponible. */
    fun pickVoice(context: Context, engine: TextToSpeech): android.speech.tts.Voice? {
        val voices = frenchVoices(engine)
        val net = online(context)
        voiceName(context)?.let { name ->
            voices.firstOrNull { it.name == name }?.let { if (!it.isNetworkConnectionRequired || net) return it }
        }
        return voices.firstOrNull { net || !it.isNetworkConnectionRequired }
    }

    /** « Voix C · France · en ligne » à partir de « fr-fr-x-frc-network ». */
    fun voiceLabel(v: android.speech.tts.Voice): String {
        val variant = Regex("-x-([a-z]+)").find(v.name)?.groupValues?.get(1)
        val letter = variant?.lastOrNull()?.uppercaseChar()
        val country = when (v.locale.country) {
            "FR" -> "France"
            "CA" -> "Canada"
            "BE" -> "Belgique"
            "CH" -> "Suisse"
            else -> v.locale.displayCountry.ifEmpty { "français" }
        }
        val where = if (v.isNetworkConnectionRequired) "en ligne, plus naturelle" else "hors ligne"
        return listOf(if (letter != null) "Voix $letter" else "Voix standard", country, where).joinToString(" · ")
    }

    /** Charge la liste des voix (le moteur démarre en quelques secondes) puis appelle [then] sur le thread d'interface. */
    fun listVoices(context: Context, then: (List<android.speech.tts.Voice>) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context.applicationContext) { status ->
            val e = engine
            val voices = if (status == TextToSpeech.SUCCESS && e != null) frenchVoices(e) else emptyList()
            main.post {
                then(voices)
                runCatching { e?.shutdown() }
            }
        }
    }

    /**
     * Voix du téléphone, dirigée vers le casque.
     *
     * Android baisse déjà les autres sons de 14 dB quand on demande le focus audio transitoire, mais ne laisse pas
     * une application les baisser davantage : le volume de la voix et celui de la musique sont le même réglage.
     * [MODE_LOUD] garde donc cette baisse et rend la voix plus forte : la phrase est synthétisée dans un fichier, son
     * niveau est remonté (jusqu'à +12 dB, limiteur doux contre la saturation), puis elle est jouée ; compter environ
     * une demi-seconde de délai. [MODE_PAUSE] demande le focus « transitoire complet » : les autres applications se
     * mettent en pause le temps de la phrase puis reprennent.
     * La file ne garde que trois phrases en attente du démarrage de la voix.
     */
    class Speaker(private val context: Context) {
        private val audio = context.getSystemService(AudioManager::class.java)
        private val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        private var tts: TextToSpeech? = null
        private var ready = false
        private val pending = ArrayDeque<String>()
        private val outstanding = AtomicInteger(0)
        private val files = java.util.concurrent.ConcurrentHashMap<String, File>()
        private val player = Executors.newSingleThreadExecutor { r -> Thread(r, "xmrip-voice").apply { isDaemon = true } }
        private var focus: AudioFocusRequest? = null
        private var focusType = 0
        private var nextId = 0

        /** Prépare la voix sans rien dire : le premier démarrage du moteur prend plusieurs secondes. */
        @Synchronized
        fun warmUp() {
            if (tts == null) init()
        }

        @Synchronized
        fun say(text: String) {
            if (tts == null) init()
            if (!ready) {
                if (pending.size >= 3) pending.removeFirst()
                pending.addLast(text)
                return
            }
            speak(text)
        }

        private fun init() {
            tts = TextToSpeech(context) { status ->
                synchronized(this) {
                    val engine = tts
                    if (status != TextToSpeech.SUCCESS || engine == null) {
                        Log.w(TAG, "Synthèse vocale indisponible (code $status)")
                        return@synchronized
                    }
                    if (engine.setLanguage(Locale.getDefault()) < TextToSpeech.LANG_AVAILABLE) engine.setLanguage(Locale.FRENCH)
                    pickVoice(context, engine)?.let { engine.voice = it }
                    engine.setAudioAttributes(attributes)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}

                        override fun onDone(utteranceId: String?) {
                            val file = utteranceId?.let { files.remove(it) }
                            Log.d(TAG, "Synthèse terminée : $utteranceId, fichier=${file?.length()} octets")
                            if (file != null) player.execute { playLoud(file) } else finished()
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            Log.w(TAG, "Erreur de synthèse : $utteranceId")
                            utteranceId?.let { files.remove(it) }?.delete()
                            finished()
                        }
                    })
                    ready = true
                    while (pending.isNotEmpty()) speak(pending.removeFirst())
                }
            }
        }

        private fun speak(text: String) {
            outstanding.incrementAndGet()
            val id = "xmrip-${nextId++}"
            val engine = tts ?: return finished()
            // La connexion internet peut avoir changé depuis le démarrage : voix en ligne seulement si elle est là.
            pickVoice(context, engine)?.let { if (engine.voice?.name != it.name) engine.voice = it }
            Log.d(TAG, "Voix demandée ($id), mode ${mode(context)}, voix ${engine.voice?.name}")
            if (mode(context) == MODE_PAUSE) {
                holdFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                engine.speak(text, TextToSpeech.QUEUE_ADD, null, id)
                return
            }
            val file = File(context.cacheDir, "$id.wav")
            Log.d(TAG, "Synthèse vers $id (${text.length} caractères)")
            files[id] = file
            val result = engine.synthesizeToFile(text, null, file, id)
            if (result != TextToSpeech.SUCCESS) {
                Log.w(TAG, "synthesizeToFile a échoué ($result)")
                files.remove(id)
                holdFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) // repli : voix directe, sans amplification
            }
        }

        /** Joue une phrase synthétisée, amplifiée. Exécuté sur le fil [player] : les phrases passent l'une après l'autre. */
        private fun playLoud(file: File) {
            try {
                Log.d(TAG, "Lecture amplifiée de ${file.name}")
                val clip = readWav(file)
                if (clip == null) {
                    Log.w(TAG, "Fichier vocal illisible : ${file.length()} octets, début ${file.readBytes().take(48).joinToString(" ") { "%02X".format(it) }}")
                    return
                }
                val gain = amplify(clip.samples)
                Log.d(TAG, "Voix amplifiée de %.1f dB".format(20 * Math.log10(gain)))
                holdFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                val frames = clip.samples.size / clip.channels
                val track = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(clip.rate)
                            .setChannelMask(if (clip.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO)
                            .build(),
                    )
                    .setBufferSizeInBytes(clip.samples.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                try {
                    track.write(clip.samples, 0, clip.samples.size)
                    track.play()
                    val deadline = System.currentTimeMillis() + frames * 1000L / clip.rate + 1_000
                    while (track.playbackHeadPosition < frames && System.currentTimeMillis() < deadline) Thread.sleep(40)
                } finally {
                    track.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Lecture de la voix impossible : ${e.javaClass.simpleName} ${e.message}")
            } finally {
                file.delete()
                finished()
            }
        }

        private class Clip(val rate: Int, val channels: Int, val samples: ShortArray)

        /** Lit un fichier WAV PCM 16 bits produit par le moteur vocal. */
        private fun readWav(file: File): Clip? {
            val b = file.readBytes()
            fun le16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
            fun le32(o: Int) = le16(o) or (le16(o + 2) shl 16)
            if (b.size < 44 || String(b, 0, 4) != "RIFF") return null
            var pos = 12
            var rate = 0
            var channels = 0
            var bits = 0
            while (pos + 8 <= b.size) {
                val id = String(b, pos, 4)
                val len = le32(pos + 4)
                if (id == "fmt ") {
                    channels = le16(pos + 10)
                    rate = le32(pos + 12)
                    bits = le16(pos + 22)
                } else if (id == "data") {
                    if (bits != 16 || rate <= 0 || channels !in 1..2) return null
                    val start = pos + 8
                    val available = b.size - start
                    val size = (if (len in 1..available) len else available) and 1.inv()
                    val samples = ShortArray(size / 2) { i -> le16(start + i * 2).toShort() }
                    return Clip(rate, channels, samples)
                }
                pos += 8 + len + (len and 1)
                if (len < 0) break
            }
            return null
        }

        /** Remonte le niveau moyen vers [TARGET_RMS] (au plus [MAX_GAIN]) avec un limiteur doux qui évite de saturer. */
        private fun amplify(samples: ShortArray): Double {
            if (samples.isEmpty()) return 1.0
            var sum = 0.0
            for (s in samples) sum += s.toDouble() * s
            val rms = Math.sqrt(sum / samples.size) / 32768.0
            if (rms < 1e-4) return 1.0
            val gain = (TARGET_RMS / rms).coerceIn(1.0, MAX_GAIN)
            for (i in samples.indices) {
                val v = samples[i] / 32768.0 * gain
                val a = Math.abs(v)
                val limited = if (a < KNEE) a else KNEE + (1 - KNEE) * Math.tanh((a - KNEE) / (1 - KNEE))
                samples[i] = (Math.signum(v) * limited * 32767.0).toInt().toShort()
            }
            return gain
        }

        @Synchronized
        private fun holdFocus(type: Int) {
            if (focus != null && focusType == type) return
            abandonFocus()
            focusType = type
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(type).setAudioAttributes(attributes).build()
                focus = request
                audio.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, type)
                focus = null
            }
        }

        @Synchronized
        private fun abandonFocus() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focus?.let { audio.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audio.abandonAudioFocus(null)
            }
            focus = null
            focusType = 0
        }

        private fun finished() {
            if (outstanding.decrementAndGet() <= 0) {
                outstanding.set(0)
                abandonFocus()
            }
        }

        @Synchronized
        fun shutdown() {
            runCatching { tts?.stop(); tts?.shutdown() }
            tts = null
            ready = false
            files.values.forEach { it.delete() }
            files.clear()
            player.shutdownNow()
            abandonFocus()
        }
    }
    /** Vrai si Android a donné à l'app « l'accès aux notifications » (nécessaire pour lire les notifications). */
    fun hasListenerAccess(context: Context): Boolean {
        val enabled = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?: return false
        return enabled.split(':').any { it.startsWith(context.packageName + "/") }
    }
}
