// ══════════════════════════════════════════════════════════════════════════
//  CalvoNote Mobile — VoskRecognizer.kt
//  Moteur de reconnaissance vocale offline (Vosk + AudioRecord)
//  DevLab · 2026
// ══════════════════════════════════════════════════════════════════════════

package com.devlab.calvonote

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

// ── Callbacks vers MainActivity ───────────────────────────────────────────

interface RecognitionListener {
    fun onPartialResult(text: String)
    fun onFinalResult(text: String)
    fun onError(message: String)
    fun onStatusChange(status: RecognizerStatus)
}

enum class RecognizerStatus {
    IDLE,        // inactif
    LOADING,     // chargement du modèle
    LISTENING,   // écoute active
    STOPPED      // arrêté
}

// ══════════════════════════════════════════════════════════════════════════
//  VoskRecognizer
// ══════════════════════════════════════════════════════════════════════════

class VoskRecognizer(
    private val context: Context,
    private val listener: RecognitionListener
) {

    companion object {
        private const val SAMPLE_RATE   = 16000
        private const val MODEL_NAME    = "vosk-model-small-fr-0.22"
        private const val BUFFER_SIZE_S = 0.25f
    }

    private var model       : Model?       = null
    private var recognizer  : Recognizer?  = null
    private var audioRecord : AudioRecord? = null

    // ── @Volatile : visible immédiatement depuis le thread audio ──────────
    @Volatile private var isListening = false

    // ── Initialisation du modèle ──────────────────────────────────────────

    suspend fun init() = withContext(Dispatchers.IO) {
        listener.onStatusChange(RecognizerStatus.LOADING)
        try {
            val modelDir = prepareModel()
            model = Model(modelDir.absolutePath)
            listener.onStatusChange(RecognizerStatus.IDLE)
        } catch (e: Exception) {
            listener.onError("Erreur chargement modèle : ${e.message}")
        }
    }

    // ── Copie SYNCHRONE des assets vers filesDir ──────────────────────────
    //  (remplace StorageService.unpack() qui est asynchrone et causait le crash)

    private fun prepareModel(): File {
        val dest = File(context.filesDir, MODEL_NAME)
        if (dest.exists() && dest.isDirectory && dest.list()?.isNotEmpty() == true) {
            return dest  // Déjà copié lors d'un lancement précédent
        }
        dest.deleteRecursively()
        copyAssetDir(MODEL_NAME, dest)
        return dest
    }

    private fun copyAssetDir(assetPath: String, destDir: File) {
        destDir.mkdirs()
        val entries = context.assets.list(assetPath) ?: return
        for (name in entries) {
            val subAsset = "$assetPath/$name"
            val destFile = File(destDir, name)
            val children = context.assets.list(subAsset)
            if (!children.isNullOrEmpty()) {
                // Sous-dossier → récursion
                copyAssetDir(subAsset, destFile)
            } else {
                // Fichier → copie directe
                context.assets.open(subAsset).use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    // ── Démarrer l'écoute ─────────────────────────────────────────────────

    fun start() {
        if (isListening || model == null) return

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = minBuf.coerceAtLeast((SAMPLE_RATE * BUFFER_SIZE_S * 2).toInt())

        recognizer = Recognizer(model, SAMPLE_RATE.toFloat())

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            listener.onError("Impossible d'initialiser le microphone")
            return
        }

        isListening = true
        audioRecord?.startRecording()
        listener.onStatusChange(RecognizerStatus.LISTENING)

        Thread {
            val buf = ShortArray(bufferSize / 2)
            while (isListening) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: 0
                if (read > 0) processChunk(buf, read)
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    // ── Traitement d'un chunk audio ───────────────────────────────────────

    private fun processChunk(buf: ShortArray, size: Int) {
        val rec = recognizer ?: return

        val bytes = ByteArray(size * 2)
        for (i in 0 until size) {
            bytes[i * 2]     = (buf[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (buf[i].toInt() shr 8 and 0xFF).toByte()
        }

        if (rec.acceptWaveForm(bytes, bytes.size)) {
            val text = extractText(rec.result, "text")
            if (text.isNotBlank()) listener.onFinalResult(text)
        } else {
            val text = extractText(rec.partialResult, "partial")
            if (text.isNotBlank()) listener.onPartialResult(text)
        }
    }

    // ── Arrêter l'écoute ──────────────────────────────────────────────────

    fun stop() {
        isListening = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recognizer?.close()
        recognizer = null
        listener.onStatusChange(RecognizerStatus.STOPPED)
    }

    // ── Libérer toutes les ressources ─────────────────────────────────────

    fun release() {
        stop()
        model?.close()
        model = null
    }

    // ── Extraire un champ du JSON Vosk avec JSONObject (plus fiable) ──────

    private fun extractText(json: String, key: String): String {
        return try {
            org.json.JSONObject(json).optString(key, "").trim()
        } catch (e: Exception) {
            ""
        }
    }
}
