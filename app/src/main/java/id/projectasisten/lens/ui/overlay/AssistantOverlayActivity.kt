package id.projectasisten.lens.ui.overlay

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import id.projectasisten.lens.R
import id.projectasisten.lens.core.OcrLayoutAssembler.OcrLineBox
import id.projectasisten.lens.core.ScreenshotRepository
import id.projectasisten.lens.data.AppPreferences
import id.projectasisten.lens.databinding.ActivityAssistantOverlayBinding
import id.projectasisten.lens.feature.ocr.OcrEngine
import id.projectasisten.lens.feature.search.LensImageSearchHelper
import id.projectasisten.lens.feature.translate.TranslationEngine
import id.projectasisten.lens.service.LensAccessibilityService
import id.projectasisten.lens.service.MediaProjectionCaptureService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Floating Bottom Panel and Screen Assistant Workspace.
 *
 * Supports:
 * 1) Scan Screen -> Interactive Crop -> Search with Google Lens (or official Sharesheet fallback).
 * 2) Select Text (OCR) -> Word / Line / Marquee / Select All -> Copy to Clipboard.
 * 3) Translate Screen -> Auto Language ID -> Default Bahasa Indonesia (or other target language) ->
 *    In-place OCR coordinate overlay + readable panel + Copy Translation.
 */
class AssistantOverlayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAssistantOverlayBinding
    private lateinit var prefs: AppPreferences

    private val ocrEngine = OcrEngine()
    private val translationEngine = TranslationEngine()

    private enum class RequestedAction {
        SCAN,
        TRANSLATE
    }

    private var pendingActionAfterCapture: RequestedAction? = null
    private var recognizedLinesCache: List<OcrLineBox> = emptyList()
    private var activeTaskJob: Job? = null

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            runMediaProjectionCaptureService(result.resultCode, result.data!!)
        } else {
            binding.cardBottomPanel.visibility = View.VISIBLE
            binding.progressBar.visibility = View.GONE
            binding.tvPanelSubtitle.text = getString(R.string.status_capture_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPreferences(this)
        binding = ActivityAssistantOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPanelAnimations()
        setupTargetLanguageSpinner()
        setupClickListeners()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                dismissPopupAndCleanUp()
            }
        })

        // If launched directly from AssistantSession or Accessibility Shortcut with a pre-captured state
        if (intent?.getBooleanExtra(EXTRA_FROM_ASSISTANT_SESSION, false) == true) {
            handleExistingOrBlockedCapture()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_FROM_ASSISTANT_SESSION, false)) {
            handleExistingOrBlockedCapture()
        }
    }

    private fun setupPanelAnimations() {
        binding.cardBottomPanel.translationY = 260f
        binding.cardBottomPanel.alpha = 0f
        binding.cardBottomPanel.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun setupTargetLanguageSpinner() {
        val languages = AppPreferences.SUPPORTED_TARGET_LANGUAGES
        val labels = languages.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerTargetLanguage.adapter = adapter

        val defaultCode = prefs.defaultTargetLanguage
        val defaultIndex = languages.indexOfFirst { it.code == defaultCode }.coerceAtLeast(0)
        binding.spinnerTargetLanguage.setSelection(defaultIndex, false)

        binding.spinnerTargetLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (binding.layoutTranslateActions.visibility == View.VISIBLE && recognizedLinesCache.isNotEmpty()) {
                    val chosenCode = languages[position].code
                    performTranslationForLines(recognizedLinesCache, chosenCode)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupClickListeners() {
        binding.viewOutsideTouchTarget.setOnClickListener {
            // Only close on outside tap if not actively interacting with crop or OCR canvas
            if (binding.cropOverlayView.visibility != View.VISIBLE &&
                binding.ocrOverlayView.visibility != View.VISIBLE
            ) {
                dismissPopupAndCleanUp()
            }
        }

        binding.btnClosePopup.setOnClickListener {
            dismissPopupAndCleanUp()
        }

        // Main Button 1: [ Scan ]
        binding.btnModeScan.setOnClickListener {
            ensureScreenshotThenRun(RequestedAction.SCAN)
        }

        // Main Button 2: [ Terjemahkan ]
        binding.btnModeTranslate.setOnClickListener {
            ensureScreenshotThenRun(RequestedAction.TRANSLATE)
        }

        // Scan Mode Actions
        binding.btnSearchLens.setOnClickListener {
            val sourceBitmap = ScreenshotRepository.getMemoryBitmap() ?: return@setOnClickListener
            val cropped = binding.cropOverlayView.cropBitmap(sourceBitmap)
            try {
                val outcome = LensImageSearchHelper.searchWithGoogleLensOrFallback(this, cropped)
                if (outcome == LensImageSearchHelper.LaunchOutcome.FALLBACK_SHARESHEET) {
                    Toast.makeText(
                        this,
                        getString(R.string.status_lens_unavailable_fallback),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                if (cropped !== sourceBitmap && !cropped.isRecycled) {
                    cropped.recycle()
                }
            }
        }

        binding.btnShareFallback.setOnClickListener {
            val sourceBitmap = ScreenshotRepository.getMemoryBitmap() ?: return@setOnClickListener
            val cropped = binding.cropOverlayView.cropBitmap(sourceBitmap)
            try {
                LensImageSearchHelper.shareViaAndroidSharesheet(this, cropped)
            } finally {
                if (cropped !== sourceBitmap && !cropped.isRecycled) {
                    cropped.recycle()
                }
            }
        }

        // Access "Pilih Teks" inside Scan mode
        binding.btnSubSelectText.setOnClickListener {
            enterSelectTextOcrMode()
        }

        // OCR Mode Actions
        binding.ocrOverlayView.onSelectedTextChanged = { selectedText ->
            binding.tvSelectedOcrText.text = selectedText
        }

        binding.btnOcrSelectAll.setOnClickListener {
            binding.ocrOverlayView.selectAllWords()
        }

        binding.btnOcrCopy.setOnClickListener {
            val textToCopy = binding.ocrOverlayView.getSelectedText().trim()
            if (textToCopy.isNotEmpty()) {
                copyToClipboard("OCR Text", textToCopy)
            }
        }

        // Translate Mode Actions
        binding.cbCoordinateOverlay.setOnCheckedChangeListener { _, isChecked ->
            binding.ocrOverlayView.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.btnCopyTranslation.setOnClickListener {
            val translated = binding.tvTranslatedTextPanel.text?.toString()?.trim().orEmpty()
            if (translated.isNotEmpty()) {
                copyToClipboard("Translated Text", translated)
            }
        }
    }

    private fun handleExistingOrBlockedCapture() {
        if (ScreenshotRepository.lastCaptureBlockedBySecureFlag) {
            showFlagSecureBlockedState()
            return
        }
        val bmp = ScreenshotRepository.getMemoryBitmap()
        if (bmp != null) {
            bindScreenshotPreview(bmp)
        }
    }

    private fun ensureScreenshotThenRun(action: RequestedAction) {
        if (ScreenshotRepository.lastCaptureBlockedBySecureFlag) {
            showFlagSecureBlockedState()
            return
        }

        val existingBitmap = ScreenshotRepository.getMemoryBitmap()
        if (existingBitmap != null) {
            bindScreenshotPreview(existingBitmap)
            executeRequestedAction(action, existingBitmap)
            return
        }

        pendingActionAfterCapture = action

        // Hide bottom panel briefly so the popup itself is not captured in the screenshot
        binding.cardBottomPanel.visibility = View.INVISIBLE

        val accessibilityInstance = LensAccessibilityService.instance
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && accessibilityInstance != null) {
            lifecycleScope.launch {
                delay(90L)
                accessibilityInstance.captureScreenshotApi30 { success ->
                    binding.cardBottomPanel.visibility = View.VISIBLE
                    if (!success || ScreenshotRepository.lastCaptureBlockedBySecureFlag) {
                        showFlagSecureBlockedState()
                    } else {
                        val bmp = ScreenshotRepository.getMemoryBitmap()
                        if (bmp != null) {
                            bindScreenshotPreview(bmp)
                            executeRequestedAction(action, bmp)
                        }
                    }
                }
            }
        } else {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    private fun runMediaProjectionCaptureService(resultCode: Int, resultData: Intent) {
        MediaProjectionCaptureService.onCaptureFinishedCallback = { success ->
            binding.cardBottomPanel.visibility = View.VISIBLE
            val action = pendingActionAfterCapture
            pendingActionAfterCapture = null

            if (!success || ScreenshotRepository.lastCaptureBlockedBySecureFlag) {
                showFlagSecureBlockedState()
            } else {
                val bmp = ScreenshotRepository.getMemoryBitmap()
                if (bmp != null && action != null) {
                    bindScreenshotPreview(bmp)
                    executeRequestedAction(action, bmp)
                }
            }
        }

        val serviceIntent = Intent(this, MediaProjectionCaptureService::class.java).apply {
            putExtra(MediaProjectionCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MediaProjectionCaptureService.EXTRA_RESULT_DATA, resultData)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun bindScreenshotPreview(bitmap: Bitmap) {
        binding.ivScreenshotPreview.setImageBitmap(bitmap)
        binding.ivScreenshotPreview.visibility = View.VISIBLE
        binding.viewOutsideTouchTarget.visibility = View.GONE
    }

    private fun executeRequestedAction(action: RequestedAction, bitmap: Bitmap) {
        when (action) {
            RequestedAction.SCAN -> enterScanMode(bitmap)
            RequestedAction.TRANSLATE -> enterTranslateMode(bitmap)
        }
    }

    private fun enterScanMode(bitmap: Bitmap) {
        activeTaskJob?.cancel()
        binding.progressBar.visibility = View.GONE
        binding.tvPanelSubtitle.text = getString(R.string.status_crop_hint)

        binding.cropOverlayView.visibility = View.VISIBLE
        binding.cropOverlayView.resetDefaultSelection()
        binding.ocrOverlayView.visibility = View.GONE

        binding.layoutScanActions.visibility = View.VISIBLE
        binding.layoutOcrActions.visibility = View.GONE
        binding.layoutTranslateActions.visibility = View.GONE
    }

    private fun enterSelectTextOcrMode() {
        val fullBitmap = ScreenshotRepository.getMemoryBitmap() ?: return

        // If user was in Scan mode with an active crop box, run OCR on the full screen
        // so they can tap any word/line on screen, while keeping coordinates aligned!
        activeTaskJob?.cancel()
        binding.cropOverlayView.visibility = View.GONE
        binding.layoutScanActions.visibility = View.GONE
        binding.layoutTranslateActions.visibility = View.GONE
        binding.layoutOcrActions.visibility = View.VISIBLE

        binding.progressBar.visibility = View.VISIBLE
        binding.tvPanelSubtitle.text = getString(R.string.status_ocr_running)

        activeTaskJob = lifecycleScope.launch {
            val ocrResult = runCatching {
                ocrEngine.recognizeBitmap(fullBitmap)
            }.getOrNull()

            binding.progressBar.visibility = View.GONE

            if (ocrResult == null || ocrResult.lines.isEmpty()) {
                binding.tvPanelSubtitle.text = getString(R.string.status_no_text_found)
                binding.tvSelectedOcrText.text = ""
                binding.ocrOverlayView.visibility = View.GONE
                return@launch
            }

            recognizedLinesCache = ocrResult.lines
            binding.tvPanelSubtitle.text = getString(R.string.status_ocr_select_hint)
            binding.ocrOverlayView.mode = OcrOverlayView.OverlayMode.SELECT_TEXT
            binding.ocrOverlayView.visibility = View.VISIBLE
            binding.ocrOverlayView.setOcrData(
                imageWidth = fullBitmap.width,
                imageHeight = fullBitmap.height,
                recognizedLines = ocrResult.lines,
                selectAllByDefault = true
            )
        }
    }

    private fun enterTranslateMode(bitmap: Bitmap) {
        activeTaskJob?.cancel()
        binding.cropOverlayView.visibility = View.GONE
        binding.layoutScanActions.visibility = View.GONE
        binding.layoutOcrActions.visibility = View.GONE
        binding.layoutTranslateActions.visibility = View.VISIBLE

        binding.progressBar.visibility = View.VISIBLE
        binding.tvPanelSubtitle.text = getString(R.string.status_ocr_running)

        val selectedTargetIndex = binding.spinnerTargetLanguage.selectedItemPosition
            .coerceIn(0, AppPreferences.SUPPORTED_TARGET_LANGUAGES.lastIndex)
        val targetLangCode = AppPreferences.SUPPORTED_TARGET_LANGUAGES[selectedTargetIndex].code

        activeTaskJob = lifecycleScope.launch {
            val ocrResult = runCatching {
                ocrEngine.recognizeBitmap(bitmap)
            }.getOrNull()

            if (ocrResult == null || ocrResult.lines.isEmpty()) {
                binding.progressBar.visibility = View.GONE
                binding.tvPanelSubtitle.text = getString(R.string.status_no_text_found)
                binding.tvTranslatedTextPanel.text = getString(R.string.status_no_text_found)
                binding.ocrOverlayView.visibility = View.GONE
                return@launch
            }

            recognizedLinesCache = ocrResult.lines
            performTranslationForLines(ocrResult.lines, targetLangCode)
        }
    }

    private fun performTranslationForLines(lines: List<OcrLineBox>, targetLangCode: String) {
        val bitmap = ScreenshotRepository.getMemoryBitmap() ?: return
        activeTaskJob?.cancel()
        binding.progressBar.visibility = View.VISIBLE
        binding.tvPanelSubtitle.text = getString(R.string.status_translating)

        activeTaskJob = lifecycleScope.launch {
            val sampleText = lines.joinToString("\n") { it.text }
            val sourceLangCode = translationEngine.identifySourceLanguage(sampleText, targetLangCode)
            binding.tvDetectedLanguage.text = "${sourceLangCode.uppercase()} → "

            val modelsReady = translationEngine.areModelsDownloaded(sourceLangCode, targetLangCode)
            if (!modelsReady) {
                binding.progressBar.visibility = View.GONE
                promptModelDownloadConsent(
                    sourceLangCode = sourceLangCode,
                    targetLangCode = targetLangCode,
                    onApproved = {
                        executeModelDownloadAndTranslate(bitmap, lines, sourceLangCode, targetLangCode)
                    }
                )
            } else {
                executeModelDownloadAndTranslate(bitmap, lines, sourceLangCode, targetLangCode)
            }
        }
    }

    private fun promptModelDownloadConsent(
        sourceLangCode: String,
        targetLangCode: String,
        onApproved: () -> Unit
    ) {
        val sourceName = AppPreferences.getDisplayNameForCode(sourceLangCode)
        val targetName = AppPreferences.getDisplayNameForCode(targetLangCode)

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_model_download_title)
            .setMessage(getString(R.string.dialog_model_download_message, sourceName, targetName))
            .setPositiveButton(R.string.dialog_download_confirm) { _, _ ->
                onApproved()
            }
            .setNegativeButton(R.string.dialog_cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun executeModelDownloadAndTranslate(
        bitmap: Bitmap,
        lines: List<OcrLineBox>,
        sourceLangCode: String,
        targetLangCode: String
    ) {
        activeTaskJob?.cancel()
        binding.progressBar.visibility = View.VISIBLE
        binding.tvPanelSubtitle.text = getString(R.string.status_translating)

        activeTaskJob = lifecycleScope.launch {
            val result = runCatching {
                translationEngine.translateLines(lines, sourceLangCode, targetLangCode)
            }.getOrNull()

            binding.progressBar.visibility = View.GONE

            if (result == null) {
                binding.tvTranslatedTextPanel.text = "Gagal menerjemahkan teks. Pastikan koneksi tersedia jika model belum terunduh."
                return@launch
            }

            binding.tvPanelSubtitle.text = "${result.sourceLanguageCode.uppercase()} → ${result.targetLanguageCode.uppercase()}"
            binding.tvTranslatedTextPanel.text = result.translatedFullText

            binding.ocrOverlayView.mode = OcrOverlayView.OverlayMode.TRANSLATE_OVERLAY
            binding.ocrOverlayView.setOcrData(
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                recognizedLines = result.translatedLines,
                selectAllByDefault = false
            )
            binding.ocrOverlayView.visibility =
                if (binding.cbCoordinateOverlay.isChecked) View.VISIBLE else View.GONE
        }
    }

    private fun showFlagSecureBlockedState() {
        binding.progressBar.visibility = View.GONE
        binding.ivScreenshotPreview.visibility = View.GONE
        binding.cropOverlayView.visibility = View.GONE
        binding.ocrOverlayView.visibility = View.GONE
        binding.layoutScanActions.visibility = View.GONE
        binding.layoutOcrActions.visibility = View.GONE
        binding.layoutTranslateActions.visibility = View.GONE
        binding.tvPanelSubtitle.text = getString(R.string.status_flag_secure_blocked)
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, getString(R.string.status_copied_clipboard), Toast.LENGTH_SHORT).show()
    }

    private fun dismissPopupAndCleanUp() {
        activeTaskJob?.cancel()
        binding.cardBottomPanel.animate()
            .translationY(260f)
            .alpha(0f)
            .setDuration(160L)
            .withEndAction {
                ScreenshotRepository.clearMemoryBitmap()
                finish()
                overridePendingTransition(0, 0)
            }
            .start()
    }

    override fun onDestroy() {
        activeTaskJob?.cancel()
        ocrEngine.close()
        translationEngine.close()
        binding.ivScreenshotPreview.setImageBitmap(null)
        ScreenshotRepository.clearMemoryBitmap()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FROM_ASSISTANT_SESSION = "extra_from_assistant_session"
    }
}
