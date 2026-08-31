package com.joel.gameagent

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.joel.gameagent.databinding.ActivityMainBinding
import com.joel.gameagent.memory.MemoryStore
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var memory: MemoryStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        memory = MemoryStore(applicationContext)

        binding.enableAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.startButton.setOnClickListener {
            val pkg = binding.packageInput.text.toString().trim()
            if (pkg.isEmpty()) return@setOnClickListener
            GameAgentAccessibilityService.targetPackage = pkg
            GameAgentAccessibilityService.isRunning = true
            binding.statusText.text = "Running against: $pkg\n(switch to that app now)"
        }

        binding.stopButton.setOnClickListener {
            GameAgentAccessibilityService.isRunning = false
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
