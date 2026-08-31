package com.joel.gameagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.joel.gameagent.databinding.ActivityMainBinding
import com.joel.gameagent.memory.MemoryStore
import com.joel.gameagent.vision.VisionCaptureService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var memory: MemoryStore
    private val uiHandler = Handler(Looper.getMainLooper())

    /** Polls the running service's thought log so the UI feels "live" without any binding. */
    private val thoughtLogPoller = object : Runnable {
        private var tick = 0
        override fun run() {
            val entries = VisionCaptureService.thoughtLog
            binding.thoughtLogText.text = if (entries.isEmpty()) "(nothing yet)" else entries.joinToString("\n")
            tick++
            if (tick % 5 == 0) refreshLearnedCount()
            uiHandler.postDelayed(this, 1000)
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val excluded = binding.packageInput.text.toString().trim()
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, VisionCaptureService::class.java).apply {
                putExtra(VisionCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(VisionCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(VisionCaptureService.EXTRA_EXCLUDED_PACKAGES, excluded)
            }
            startForegroundService(intent)
            binding.statusText.text = if (excluded.isNotEmpty()) {
                "Vision mode running - free-roaming, staying out of:\n$excluded\n\nTip: pull down the notification and tap 'Instruct' to give it commands without opening this app."
            } else {
                "Vision mode running - free-roaming the whole phone (nothing excluded)\n\nTip: pull down the notification and tap 'Instruct' to give it commands without opening this app."
            }
        } else {
            binding.statusText.text = "Screen capture permission was needed to start"
        }
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) launchSpeechRecognizer() }

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!text.isNullOrBlank()) {
            binding.instructionInput.setText(text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        memory = MemoryStore(applicationContext)

        binding.enableAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.startButton.setOnClickListener {
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        binding.stopButton.setOnClickListener {
            stopService(Intent(this, VisionCaptureService::class.java))
            binding.statusText.text = "Stopped"
        }

        binding.sendInstructionButton.setOnClickListener {
            VisionCaptureService.currentInstruction = binding.instructionInput.text.toString()
            binding.statusText.text = "Instruction sent: \"${VisionCaptureService.currentInstruction}\""
        }

        binding.micButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                launchSpeechRecognizer()
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        refreshLearnedCount()
    }

    private fun launchSpeechRecognizer() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Tell GameAgent what to do")
        }
        speechLauncher.launch(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshLearnedCount()
        uiHandler.post(thoughtLogPoller)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(thoughtLogPoller)
    }

    private fun refreshLearnedCount() {
        lifecycleScope.launch {
            val count = memory.learnedEntryCount()
            binding.learnedCountText.text = "Learned screen/action pairs: $count"
        }
    }
}
