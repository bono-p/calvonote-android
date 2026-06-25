// ══════════════════════════════════════════════════════════════════════════
//  CalvoNote Mobile — MainActivity.kt
//  Activité principale : interface bloc-note + contrôle transcription
//  DevLab · 2026
// ══════════════════════════════════════════════════════════════════════════

package com.devlab.calvonote

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.devlab.calvonote.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), RecognitionListener {

    companion object {
        private const val REQUEST_MIC = 100
    }

    private lateinit var binding    : ActivityMainBinding
    private lateinit var recognizer : VoskRecognizer
    private var isRecording         = false

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        recognizer = VoskRecognizer(this, this)

        setupButtons()
        loadModel()
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.release()
    }

    // ── Chargement modèle ─────────────────────────────────────────────────

    private fun loadModel() {
        lifecycleScope.launch {
            recognizer.init()
        }
    }

    // ── Boutons ───────────────────────────────────────────────────────────

    private fun setupButtons() {
        // Bouton principal : Démarrer / Arrêter
        binding.btnRecord.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                checkPermissionAndStart()
            }
        }

        // Copier le texte dans le presse-papier
        binding.btnCopy.setOnClickListener {
            val text = binding.tvNote.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, R.string.msg_nothing_to_copy, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("CalvoNote", text))
            Toast.makeText(this, R.string.msg_copied, Toast.LENGTH_SHORT).show()
        }

        // Effacer le bloc-note
        binding.btnClear.setOnClickListener {
            if (binding.tvNote.text.isNotEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Effacer")
                    .setMessage("Effacer tout le texte ?")
                    .setPositiveButton("Effacer") { _, _ ->
                        binding.tvNote.text = ""
                        binding.tvPartial.text = ""
                        Toast.makeText(this, R.string.msg_cleared, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
        }
    }

    // ── Démarrer / Arrêter ────────────────────────────────────────────────

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
            binding.btnRecord.text      = getString(R.string.btn_stop)
            binding.btnRecord.setBackgroundColor(
                ContextCompat.getColor(this, R.color.rec_on))
        } else {
            binding.btnRecord.text      = getString(R.string.btn_start)
            binding.btnRecord.setBackgroundColor(
                ContextCompat.getColor(this, R.color.accent))
        }
    }

    // ── Permission microphone ─────────────────────────────────────────────

    private fun checkPermissionAndStart() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED -> {
                startRecording()
            }
            ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.RECORD_AUDIO) -> {
                AlertDialog.Builder(this)
                    .setTitle("Permission requise")
                    .setMessage(getString(R.string.msg_perm_required))
                    .setPositiveButton("Autoriser") { _, _ ->
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(Manifest.permission.RECORD_AUDIO),
                            REQUEST_MIC
                        )
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
            else -> {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    REQUEST_MIC
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording()
            } else {
                Toast.makeText(this,
                    R.string.msg_perm_denied, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── RecognitionListener — callbacks depuis VoskRecognizer ─────────────

    override fun onPartialResult(text: String) {
        // Appelé depuis un thread audio → retour sur UI thread
        runOnUiThread {
            binding.tvPartial.text = "… $text"
        }
    }

    override fun onFinalResult(text: String) {
        runOnUiThread {
            // Ajoute le texte final au bloc-note avec un espace
            val current = binding.tvNote.text.toString()
            val separator = if (current.isNotEmpty()) " " else ""
            binding.tvNote.text = current + separator + text

            // Efface le partiel
            binding.tvPartial.text = ""

            // Auto-scroll vers le bas
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
                RecognizerStatus.IDLE     -> getString(R.string.status_ready)
                RecognizerStatus.LOADING  -> getString(R.string.status_loading)
                RecognizerStatus.LISTENING -> getString(R.string.status_listening)
                RecognizerStatus.STOPPED  -> getString(R.string.status_stopped)
            }
            // Désactiver le bouton pendant le chargement
            binding.btnRecord.isEnabled = (status != RecognizerStatus.LOADING)
        }
    }
}
