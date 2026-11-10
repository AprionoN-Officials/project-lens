package id.projectasisten.lens.ui.settings

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import id.projectasisten.lens.R
import id.projectasisten.lens.core.ScreenshotRepository
import id.projectasisten.lens.data.AppPreferences
import id.projectasisten.lens.databinding.ActivityMainBinding
import id.projectasisten.lens.service.LensAccessibilityService
import id.projectasisten.lens.ui.overlay.AssistantOverlayActivity

/**
 * Settings and Activation Management Activity (Section 10).
 *
 * Allows the user to:
 * - Enable or disable assistant shortcuts when not in use (e.g., before opening banking apps).
 * - Configure Activation Method A (Default Digital Assistant), Method B (Accessibility Shortcut), or Method C (Quick Settings Tile).
 * - Select default translation target language (default: Bahasa Indonesia).
 * - Switch between System / Light / Dark themes.
 * - Inspect and clear temporary image cache.
 * - Review transparent permission & banking compatibility disclosures.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPreferences

    private val roleRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshActivationStatuses()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPreferences(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupShortcutMasterSwitch()
        setupActivationButtons()
        setupLanguageSpinner()
        setupThemeRadioGroup()
        setupCacheManagement()
    }

    override fun onResume() {
        super.onResume()
        refreshActivationStatuses()
        refreshCacheSizeText()
    }

    private fun setupShortcutMasterSwitch() {
        binding.switchEnableShortcut.isChecked = prefs.isShortcutEnabled
        binding.switchEnableShortcut.setOnCheckedChangeListener { _, isChecked ->
            prefs.isShortcutEnabled = isChecked
        }
    }

    private fun setupActivationButtons() {
        // Method A: Default Digital Assistant
        binding.btnConfigureAssistantRole.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(RoleManager::class.java)
                if (roleManager != null &&
                    roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                    !roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
                ) {
                    val roleIntent = roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                    roleRequestLauncher.launch(roleIntent)
                    return@setOnClickListener
                }
            }
            val voiceSettingsIntent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            runCatching { startActivity(voiceSettingsIntent) }
                .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
        }

        // Method B: Accessibility Shortcut
        binding.btnOpenAccessibilitySettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        // Method C: Quick Settings Tile / Immediate Test Popup
        binding.btnLaunchPopupNow.setOnClickListener {
            val intent = Intent(this, AssistantOverlayActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupLanguageSpinner() {
        val languages = AppPreferences.SUPPORTED_TARGET_LANGUAGES
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            languages.map { it.displayName }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerDefaultLanguage.adapter = adapter

        val currentCode = prefs.defaultTargetLanguage
        val index = languages.indexOfFirst { it.code == currentCode }.coerceAtLeast(0)
        binding.spinnerDefaultLanguage.setSelection(index, false)

        binding.spinnerDefaultLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.defaultTargetLanguage = languages[position].code
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupThemeRadioGroup() {
        when (prefs.themeMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> binding.rbThemeLight.isChecked = true
            AppCompatDelegate.MODE_NIGHT_YES -> binding.rbThemeDark.isChecked = true
            else -> binding.rbThemeSystem.isChecked = true
        }

        binding.rgThemeMode.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                R.id.rbThemeLight -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.rbThemeDark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            if (prefs.themeMode != newMode) {
                prefs.themeMode = newMode
            }
        }
    }

    private fun setupCacheManagement() {
        binding.btnClearCache.setOnClickListener {
            ScreenshotRepository.clearMemoryBitmap()
            ScreenshotRepository.clearDiskCache(this)
            refreshCacheSizeText()
            Toast.makeText(this, getString(R.string.settings_cache_cleared_toast), Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshActivationStatuses() {
        val isAssistantHeld = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            rm?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        } else {
            false
        }
        binding.tvAssistantRoleStatus.text = if (isAssistantHeld) {
            "Status: Aktif sebagai Asisten Digital Default"
        } else {
            "Status: Belum diatur sebagai Asisten Default"
        }

        val isAccessibilityEnabled = isLensAccessibilityServiceEnabled()
        binding.tvAccessibilityStatus.text = if (isAccessibilityEnabled) {
            "Status: Pintasan Aksesibilitas Aktif (On-Demand)"
        } else {
            "Status: Nonaktif"
        }
    }

    private fun isLensAccessibilityServiceEnabled(): Boolean {
        if (LensAccessibilityService.instance != null) return true
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val enabledList = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledList.any { info ->
            info.resolveInfo?.serviceInfo?.packageName == packageName &&
                info.resolveInfo?.serviceInfo?.name == LensAccessibilityService::class.java.name
        }
    }

    private fun refreshCacheSizeText() {
        val bytes = ScreenshotRepository.getDiskCacheSizeBytes(this)
        val kb = bytes / 1024.0
        binding.tvCacheSizeInfo.text = String.format("Penggunaan Cache Gambar Sementara: %.1f KB", kb)
    }
}
