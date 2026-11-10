package id.projectasisten.lens.feature.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import id.projectasisten.lens.core.OcrLayoutAssembler.OcrLineBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class TranslationResultData(
    val sourceLanguageCode: String,
    val targetLanguageCode: String,
    val translatedFullText: String,
    val translatedLines: List<OcrLineBox>
)

/**
 * On-device language identification and translation engine using Google ML Kit.
 * Checks if models (~30 MB each) are downloaded first so the UI can request user consent before downloading.
 */
class TranslationEngine {

    private var languageIdentifier: LanguageIdentifier? = null
    private var activeTranslator: Translator? = null

    /**
     * Identifies the BCP-47 language code of [sampleText], falling back to "en" (or "id" if target is "en")
     * when confidence is too low ("und").
     */
    suspend fun identifySourceLanguage(
        sampleText: String,
        targetLanguageCode: String
    ): String = withContext(Dispatchers.Default) {
        if (sampleText.isBlank()) return@withContext fallbackSourceForTarget(targetLanguageCode)

        val identifier = languageIdentifier ?: LanguageIdentification.getClient().also {
            languageIdentifier = it
        }
        val detected = runCatching {
            identifier.identifyLanguage(sampleText.take(600)).await()
        }.getOrDefault("und")

        val supportedCode = if (detected != "und") {
            TranslateLanguage.fromLanguageTag(detected)
        } else {
            null
        }
        supportedCode ?: fallbackSourceForTarget(targetLanguageCode)
    }

    /**
     * Checks whether both source and target ML Kit translation models already exist on the device.
     */
    suspend fun areModelsDownloaded(
        sourceLanguageCode: String,
        targetLanguageCode: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (sourceLanguageCode == targetLanguageCode) return@withContext true
        val modelManager = RemoteModelManager.getInstance()
        val sourceModel = TranslateRemoteModel.Builder(sourceLanguageCode).build()
        val targetModel = TranslateRemoteModel.Builder(targetLanguageCode).build()

        val hasSource = runCatching { modelManager.isModelDownloaded(sourceModel).await() }.getOrDefault(false)
        val hasTarget = runCatching { modelManager.isModelDownloaded(targetModel).await() }.getOrDefault(false)
        hasSource && hasTarget
    }

    /**
     * Downloads required models (after user consent if needed) and translates each OCR line
     * for coordinate-based rendering as well as the full readable panel text.
     */
    suspend fun translateLines(
        lines: List<OcrLineBox>,
        sourceLanguageCode: String,
        targetLanguageCode: String
    ): TranslationResultData = withContext(Dispatchers.Default) {
        if (lines.isEmpty()) {
            return@withContext TranslationResultData(
                sourceLanguageCode = sourceLanguageCode,
                targetLanguageCode = targetLanguageCode,
                translatedFullText = "",
                translatedLines = emptyList()
            )
        }

        if (sourceLanguageCode == targetLanguageCode) {
            val identityLines = lines.map { it.copy(translatedText = it.text) }
            return@withContext TranslationResultData(
                sourceLanguageCode = sourceLanguageCode,
                targetLanguageCode = targetLanguageCode,
                translatedFullText = identityLines.joinToString("\n") { it.text },
                translatedLines = identityLines
            )
        }

        activeTranslator?.close()
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguageCode)
            .setTargetLanguage(targetLanguageCode)
            .build()
        val translator = Translation.getClient(options)
        activeTranslator = translator

        val conditions = DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions).await()

        val translatedLines = lines.map { line ->
            val translatedLineText = runCatching {
                translator.translate(line.text).await()
            }.getOrDefault(line.text)
            line.copy(translatedText = translatedLineText)
        }

        val fullTranslatedText = translatedLines.joinToString("\n") {
            it.translatedText ?: it.text
        }

        TranslationResultData(
            sourceLanguageCode = sourceLanguageCode,
            targetLanguageCode = targetLanguageCode,
            translatedFullText = fullTranslatedText,
            translatedLines = translatedLines
        )
    }

    private fun fallbackSourceForTarget(targetLanguageCode: String): String {
        return if (targetLanguageCode == TranslateLanguage.ENGLISH) {
            TranslateLanguage.INDONESIAN
        } else {
            TranslateLanguage.ENGLISH
        }
    }

    fun close() {
        languageIdentifier?.close()
        languageIdentifier = null
        activeTranslator?.close()
        activeTranslator = null
    }
}
