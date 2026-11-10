package id.projectasisten.lens.core

/**
 * Safeguard that checks whether a known package name (if provided by VoiceInteractionSession
 * AssistStructure) belongs to a sensitive banking, digital wallet, or authentication app
 * so Project Asisten refuses to activate over sensitive financial contexts.
 */
object SensitiveAppGuard {

    private val KNOWN_SENSITIVE_PACKAGE_PREFIXES = listOf(
        "com.bca",
        "id.co.bri",
        "com.mandiri",
        "src.com.bni",
        "id.co.btn",
        "com.cimbniaga",
        "com.permata",
        "com.danamon",
        "com.btpn.dc",       // Jenius
        "com.jago.digitalBanking",
        "id.co.bankbsi",
        "id.dana",
        "ovo.id",
        "com.gojek.gopay",
        "com.telkom.mwallet", // LinkAja
        "id.co.seabank",
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator",
        "com.duosecurity.duomobile"
    )

    private val SENSITIVE_KEYWORDS = listOf(
        "bank",
        "mbanking",
        "wallet",
        "authenticator",
        "keyguard",
        "biometric",
        "permissioncontroller"
    )

    fun isSensitivePackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val normalized = packageName.trim().lowercase()
        if (KNOWN_SENSITIVE_PACKAGE_PREFIXES.any { normalized.startsWith(it.lowercase()) }) {
            return true
        }
        return SENSITIVE_KEYWORDS.any { normalized.contains(it) }
    }
}
