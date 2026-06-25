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
import org.vosk.android.StorageService
import java.io.File
import java.io.IOException

// ── Callbacks vers MainActivity ───────────────────────────────────────────

interface RecognitionListener {
    fun onPartialResult(text: String)
    fun onFinalResult(text: String)
    fun onError(message: String)
    fun onStatusChange(status: RecognizerStatus)
}

enum class RecognizerStatus {
    IDLE,       // inactif
    LOADING,    // chargement du modèle
    LISTENING,  // écoute active
    STOPPED     // arrêté
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
        private const val BUFFER_SIZE_S = 0.25f   // 250 ms de buffer
    }

    private var model       : Model?       = null
    private var recognizer  : Recognizer?  = null
    private var audioRecord : AudioRecord? = null
    private var isListening = false

    // ── Initialisation du modèle (à appeler une seule fois) ───────────────

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

    // ── Prépare le modèle (copie assets → stockage interne si besoin) ─────

    private fun prepareModel(): File {
        val dest = File(context.filesDir, MODEL_NAME)
        if (dest.exists() && dest.isDirectory) {
            // Déjà copié lors d'un lancement précédent
            return dest
        }
        // Copie depuis assets/ vers filesDir (une seule fois)
        StorageService.unpack(context, MODEL_NAME, MODEL_NAME,
            { /* progress — ignoré ici */ },
            { e -> throw IOException("Décompression modèle échouée : ${e.message}") }
        )
        return dest
    }

    // ── Démarrer l'écoute ─────────────────────────────────────────────────

    fun start() {
        if (isListening || model == null) return

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast((SAMPLE_RATE * BUFFER_SIZE_S * 2).toInt())

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

        // Boucle de lecture audio dans un thread dédié
        Thread {
            val buf = ShortArray(bufferSize / 2)
            while (isListening) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: 0
                if (read > 0) {
                    processChunk(buf, read)
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    // ── Traitement d'un chunk audio ───────────────────────────────────────

    private fun processChunk(buf: ShortArray, size: Int) {
        val rec = recognizer ?: return

        // Conversion Short → ByteArray (little-endian, int16)
        val bytes = ByteArray(size * 2)
        for (i in 0 until size) {
            bytes[i * 2]     = (buf[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (buf[i].toInt() shr 8 and 0xFF).toByte()
        }

        if (rec.acceptWaveForm(bytes, bytes.size)) {
            // Résultat final — phrase complète
            val result = rec.result
            val text   = extractText(result, "text")
            if (text.isNotBlank()) {
                listener.onFinalResult(text)
            }
        } else {
            // Résultat partiel — mot en cours
            val partial = rec.partialResult
            val text    = extractText(partial, "partial")
            if (text.isNotBlank()) {
                listener.onPartialResult(text)
            }
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

    // ── Utilitaire : extraire un champ du JSON Vosk ───────────────────────

    private fun extractText(json: String, key: String): String {
        return try {
            // JSON Vosk : {"text": "bonjour"} ou {"partial": "bon"}
            val pattern = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
            pattern.find(json)?.groupValues?.get(1)?.trim() ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
