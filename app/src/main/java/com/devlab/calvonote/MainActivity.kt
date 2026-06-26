// ══════════════════════════════════════════════════════════════════════════
//  CalvoNote Mobile — MainActivity.kt
//  DevLab · 2026
// ══════════════════════════════════════════════════════════════════════════

package com.devlab.calvonote

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.devlab.calvonote.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity(),
    RecognitionListener,
    SpellCheckerSession.SpellCheckerSessionListener {

    companion object {
        private const val REQUEST_MIC  = 100
        private const val FONT_DEFAULT = 16f
        private const val FONT_STEP    = 2f
        private const val FONT_MIN     = 10f
        private const val FONT_MAX     = 32f
    }

    private lateinit var binding    : ActivityMainBinding
    private lateinit var recognizer : VoskRecognizer
    private var isRecording         = false
    private var currentFontSizeSp   = FONT_DEFAULT

    // ── Correcteur orthographique ──────────────────────────────────────────
    private var spellCheckerSession : SpellCheckerSession? = null
    private val sentenceStartMap    = Collections.synchronizedMap(mutableMapOf<Int, Int>())
    private val processedSentences  = AtomicInteger(0)
    private var totalSentences      = 0
    @Volatile private var spellSpannable: SpannableStringBuilder? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        recognizer = VoskRecognizer(this, this)

        setupButtons()
        setupMenu()
        initSpellChecker()
        loadModel()
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.release()
        spellCheckerSession?.close()
    }

    // ── Chargement modèle ──────────────────────────────────────────────────

    private fun loadModel() {
        lifecycleScope.launch { recognizer.init() }
    }

    // ── Menu hamburger (3 traits) ──────────────────────────────────────────

    private fun setupMenu() {
        binding.btnMenu.setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor)
            popup.menuInflater.inflate(R.menu.popup_menu, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.popup_spellcheck -> { runSpellCheck(); true }
                    R.id.popup_select_all -> { binding.etNote.selectAll(); true }
                    R.id.popup_font_plus  -> { adjustFontSize(+FONT_STEP); true }
                    R.id.popup_font_minus -> { adjustFontSize(-FONT_STEP); true }
                    R.id.popup_clear_format -> {
                        clearSpellHighlights()
                        Toast.makeText(this, R.string.msg_format_cleared, Toast.LENGTH_SHORT).show()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun adjustFontSize(delta: Float) {
        currentFontSizeSp = (currentFontSizeSp + delta).coerceIn(FONT_MIN, FONT_MAX)
        binding.etNote.textSize = currentFontSizeSp
    }

    // ── Correction orthographique ──────────────────────────────────────────

    private fun initSpellChecker() {
        try {
            val tsm = getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as TextServicesManager
            spellCheckerSession = tsm.newSpellCheckerSession(null, Locale.FRENCH, this, true)
        } catch (_: Exception) {
            // Correcteur non disponible — on continue sans
        }
    }

    private fun runSpellCheck() {
        if (spellCheckerSession == null) {
            Toast.makeText(this, R.string.msg_spell_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val fullText = binding.etNote.text.toString()
        if (fullText.isBlank()) {
            Toast.makeText(this, R.string.msg_nothing_to_check, Toast.LENGTH_SHORT).show()
            return
        }

        // Effacer les anciens surlignages avant la nouvelle passe
        clearSpellHighlights()
        Toast.makeText(this, R.string.msg_spell_checking, Toast.LENGTH_SHORT).show()

        sentenceStartMap.clear()
        val sentences = mutableListOf<Pair<String, Int>>()

        // Découper en phrases en conservant la position de chaque début
        var lastStart = 0
        Regex("(?<=[.!?])\\s+|\\n+").findAll(fullText).forEach { match ->
            val s = fullText.substring(lastStart, match.range.first + 1).trim()
            if (s.isNotBlank()) sentences.add(s to lastStart)
            lastStart = match.range.last + 1
        }
        if (lastStart < fullText.length) {
            val s = fullText.substring(lastStart).trim()
            if (s.isNotBlank()) sentences.add(s to lastStart)
        }

        if (sentences.isEmpty()) return

        totalSentences = sentences.size
        processedSentences.set(0)
        spellSpannable = SpannableStringBuilder(fullText)

        sentences.forEachIndexed { idx, (sentence, startOffset) ->
            sentenceStartMap[idx] = startOffset
            spellCheckerSession?.getSentenceSuggestions(
                arrayOf(TextInfo(sentence, 0, sentence.length, idx, 0)), 5
            )
        }
    }

    // ── Callbacks SpellCheckerSession ──────────────────────────────────────

    /** Non utilisé (on passe par getSentenceSuggestions) */
    override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) = Unit

    override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
        val spannable  = spellSpannable ?: return
        val errorColor = ContextCompat.getColor(this, R.color.err)

        results?.forEach { sentInfo ->
            val sentStart = sentenceStartMap[sentInfo.cookie] ?: 0
            for (i in 0 until sentInfo.suggestionsCount) {
                val info     = sentInfo.getSuggestionsInfoAt(i)
                val wStart   = sentStart + sentInfo.getOffsetAt(i)
                val wLen     = sentInfo.getLengthAt(i)
                val wEnd     = wStart + wLen
                if (wEnd > spannable.length || wLen <= 1) continue
                val notInDict = (info.suggestionsAttributes and
                        SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY) == 0
                if (notInDict) {
                    synchronized(spannable) {
                        spannable.setSpan(
                            ForegroundColorSpan(errorColor),
                            wStart, wEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        spannable.setSpan(
                            UnderlineSpan(),
                            wStart, wEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
            }
        }

        // Quand toutes les phrases sont traitées → appliquer les spans dans l'UI
        val done = processedSentences.addAndGet(results?.size ?: 0)
        if (done >= totalSentences) {
            runOnUiThread {
                val cursor = (binding.etNote.selectionEnd).coerceAtMost(spannable.length)
                binding.etNote.setText(spannable)
                binding.etNote.setSelection(cursor.coerceAtLeast(0))
                Toast.makeText(this, R.string.msg_spell_done, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun clearSpellHighlights() {
        val text = binding.etNote.text ?: return
        text.getSpans(0, text.length, ForegroundColorSpan::class.java).forEach { text.removeSpan(it) }
        text.getSpans(0, text.length, UnderlineSpan::class.java).forEach { text.removeSpan(it) }
    }

    // ── Boutons principaux ─────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnRecord.setOnClickListener {
            if (isRecording) stopRecording() else checkPermissionAndStart()
        }

        binding.btnCopy.setOnClickListener {
            val text = binding.etNote.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, R.string.msg_nothing_to_copy, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("CalvoNote", text))
            Toast.makeText(this, R.string.msg_copied, Toast.LENGTH_SHORT).show()
        }

        binding.btnClear.setOnClickListener {
            if (binding.etNote.text?.isNotEmpty() == true) {
                AlertDialog.Builder(this)
                    .setTitle("Effacer")
                    .setMessage("Effacer tout le texte ?")
                    .setPositiveButton("Effacer") { _, _ ->
                        binding.etNote.text?.clear()
                        binding.tvPartial.text = ""
                        Toast.makeText(this, R.string.msg_cleared, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
        }
    }

    // ── Enregistrement ─────────────────────────────────────────────────────

    private fun startRecording() {
        isRecording = true
        recognizer.start()
        updateRecordButton()
    }

    private fun stopRecording() {
        isRecording = false
        recognizer.stop()
        binding.tvPartial.text = ""
        updateRecordButton()
    }

    private fun updateRecordButton() {
        if (isRecording) {
            binding.btnRecord.text = getString(R.string.btn_stop)
            binding.btnRecord.setBackgroundColor(ContextCompat.getColor(this, R.color.rec_on))
        } else {
            binding.btnRecord.text = getString(R.string.btn_start)
            binding.btnRecord.setBackgroundColor(ContextCompat.getColor(this, R.color.accent))
        }
    }

    // ── Permissions microphone ─────────────────────────────────────────────

    private fun checkPermissionAndStart() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED -> startRecording()

            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO) -> {
                AlertDialog.Builder(this)
                    .setTitle("Permission requise")
                    .setMessage(getString(R.string.msg_perm_required))
                    .setPositiveButton("Autoriser") { _, _ ->
                        ActivityCompat.requestPermissions(
                            this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
                    }
                    .setNegativeButton("Annuler", null).show()
            }

            else -> ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else if (requestCode == REQUEST_MIC) {
            Toast.makeText(this, R.string.msg_perm_denied, Toast.LENGTH_LONG).show()
        }
    }

    // ── RecognitionListener — callbacks depuis VoskRecognizer ──────────────

    override fun onPartialResult(text: String) {
        runOnUiThread { binding.tvPartial.text = "… $text" }
    }

    override fun onFinalResult(text: String) {
        runOnUiThread {
            // Append au lieu d'écraser pour préserver les spans existants
            val current = binding.etNote.text.toString()
            val separator = if (current.isNotEmpty()) " " else ""
            binding.etNote.append("$separator$text")
            binding.tvPartial.text = ""
            binding.scrollNote.post {
                binding.scrollNote.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            binding.tvStatus.text = "⚠ $message"
            isRecording = false
            updateRecordButton()
        }
    }

    override fun onStatusChange(status: RecognizerStatus) {
        runOnUiThread {
            binding.tvStatus.text = when (status) {
                RecognizerStatus.IDLE      -> getString(R.string.status_ready)
                RecognizerStatus.LOADING   -> getString(R.string.status_loading)
                RecognizerStatus.LISTENING -> getString(R.string.status_listening)
                RecognizerStatus.STOPPED   -> getString(R.string.status_stopped)
            }
            binding.btnRecord.isEnabled = (status != RecognizerStatus.LOADING)
        }
    }
}
