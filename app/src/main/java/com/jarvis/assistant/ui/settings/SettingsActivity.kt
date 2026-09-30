package com.jarvis.assistant.ui.settings

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val preferences by lazy { JarvisApp.instance.preferences }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        populateDropdowns()
        loadCurrentSettings()
        setupListeners()
        updateApiStatus()

        // Open PermissionsActivity
        binding.btnManagePermissions.setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateApiStatus()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            finish()
        }
        binding.btnSaveKey.setOnClickListener {
            saveSettings()
        }
    }

    /** Refreshes the Gemini API Status card based on stored key. */
    private fun updateApiStatus() {
        val key = preferences.apiKey.trim()
        if (key.isBlank()) {
            binding.tvApiStatus.text = "• STATUS: NO KEY CONFIGURED"
            binding.tvApiStatus.setTextColor(ContextCompat.getColor(this, R.color.luna_red_light))
        } else if (key.length < 20) {
            binding.tvApiStatus.text = "• STATUS: KEY LOOKS INVALID"
            binding.tvApiStatus.setTextColor(ContextCompat.getColor(this, R.color.luna_red_light))
        } else {
            // Show partial key (first 6 chars + *****)
            val masked = key.take(6) + "•".repeat(minOf(12, key.length - 6))
            binding.tvApiStatus.text = "• STATUS: KEY CONFIGURED  $masked"
            binding.tvApiStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        }
    }

    private fun populateDropdowns() {
        // 1. AI Models — styled drop-down adapter
        binding.spinnerModel.adapter = buildAdapter(GeminiConstants.SUPPORTED_MODELS.toTypedArray())
        binding.spinnerVoice.adapter = buildAdapter(GeminiConstants.SUPPORTED_VOICES.toTypedArray())
        binding.spinnerPersonality.adapter = buildAdapter(GeminiConstants.SUPPORTED_PERSONALITIES.toTypedArray())

        binding.spinnerPersonality.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = GeminiConstants.SUPPORTED_PERSONALITIES.getOrElse(position) { GeminiConstants.PERSONALITY_ASSISTANT }
                binding.tvPersonalityDesc.text = when (selected) {
                    GeminiConstants.PERSONALITY_GIRLFRIEND ->
                        "Warm Hinglish • Emotional but natural • Short spoken replies"
                    GeminiConstants.PERSONALITY_PROFESSIONAL ->
                        "Formal English • Precise • No emojis"
                    else ->
                        "Friendly • Helpful • Hinglish or English"
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun buildAdapter(items: Array<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_item, items
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v as? TextView)?.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                return v
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent)
                (v as? TextView)?.apply {
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    setBackgroundColor(ContextCompat.getColor(context, R.color.bg_card))
                }
                return v
            }
        }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    private fun loadCurrentSettings() {
        val key = preferences.apiKey
        binding.etApiKey.setText(key)
        binding.etUserName.setText(preferences.userName)
        binding.switchMemory.isChecked = preferences.isMemoryEnabled

        val modelIndex = GeminiConstants.SUPPORTED_MODELS.indexOf(preferences.aiModel)
        if (modelIndex >= 0) binding.spinnerModel.setSelection(modelIndex)

        val voiceIndex = GeminiConstants.SUPPORTED_VOICES.indexOf(preferences.voice)
        if (voiceIndex >= 0) binding.spinnerVoice.setSelection(voiceIndex)

        val personalityIndex = GeminiConstants.SUPPORTED_PERSONALITIES.indexOf(preferences.personality)
        if (personalityIndex >= 0) binding.spinnerPersonality.setSelection(personalityIndex)

    }

    private fun setupListeners() {
        binding.switchMemory.setOnCheckedChangeListener { _, checked ->
            preferences.isMemoryEnabled = checked
        }
        // Toggle API key visibility
        binding.btnToggleKeyVisibility.setOnClickListener {
            val isHidden = binding.etApiKey.transformationMethod is PasswordTransformationMethod ||
                (binding.etApiKey.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0
            if (isHidden) {
                binding.etApiKey.transformationMethod = null
                binding.btnToggleKeyVisibility.setImageResource(R.drawable.ic_eye)
                binding.btnToggleKeyVisibility.alpha = 1f
            } else {
                binding.etApiKey.transformationMethod = PasswordTransformationMethod.getInstance()
                binding.btnToggleKeyVisibility.setImageResource(R.drawable.ic_eye)
                binding.btnToggleKeyVisibility.alpha = 0.5f
            }
            binding.etApiKey.setSelection(binding.etApiKey.text?.length ?: 0)
        }

        // Get API Key from Google AI Studio
        binding.btnGetAiStudioKey.setOnClickListener {
            startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://aistudio.google.com/app/apikey"))
            )
        }
    }

    private fun saveSettings() {
        val apiKey = binding.etApiKey.text?.toString()?.trim() ?: ""
        val userName = binding.etUserName.text?.toString()?.trim() ?: ""

        val selectedModel = binding.spinnerModel.selectedItem as? String
            ?: GeminiConstants.DEFAULT_MODEL
        val selectedVoice = binding.spinnerVoice.selectedItem as? String ?: "Aoede"
        val selectedPersonality = binding.spinnerPersonality.selectedItem as? String
            ?: GeminiConstants.PERSONALITY_ASSISTANT

        // Validate API Key
        if (apiKey.isBlank()) {
            showErrorDialog(
                "API Key Not Saved",
                "API Key is not available. Please enter your Gemini API key before saving."
            )
            return
        }
        if (apiKey.length < 20) {
            showErrorDialog(
                "Invalid API Key",
                "The API key you entered looks invalid (too short). Please paste your full Gemini API key."
            )
            return
        }

        // Save
        preferences.apiKey = apiKey
        preferences.userName = userName
        preferences.aiModel = selectedModel
        preferences.voice = selectedVoice
        preferences.personality = selectedPersonality

        updateApiStatus()

        // Show success dialog
        showSuccessDialog(
            "Settings Saved",
            "API key entered and saved successfully.\nModel: $selectedModel\nVoice: $selectedVoice\nPersonality: $selectedPersonality"
        )
    }

    private fun showSuccessDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle("✅ $title")
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle("⚠️ $title")
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .setCancelable(true)
            .show()
    }
}
