package com.joel.gameagent

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.joel.gameagent.databinding.ActivityMainBinding
import com.joel.gameagent.memory.MemoryStore
import com.joel.gameagent.vision.VisionCaptureService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var memory: MemoryStore

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
                "Vision mode running - free-roaming, staying out of:\n$excluded"
            } else {
                "Vision mode running - free-roaming the whole phone (nothing excluded)"
            }
        } else {
            binding.statusText.text = "Screen capture permission was needed to start"
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

        refreshLearnedCount()
    }

    override fun onResume() {
        super.onResume()
        refreshLearnedCount()
    }

    private fun refreshLearnedCount() {
        lifecycleScope.launch {
            val count = memory.learnedEntryCount()
            binding.learnedCountText.text = "Learned screen/action pairs: $count"
        }
    }
}
