package app.morphe.patches.mapsautomotive

import java.util.logging.Logger

// Fork addition: accept AAOS Maps builds compiled for minSdk 29 (Android 10) as well as 120003.E (minSdk 30).
//
// A minSdk 29 build kept its own SDK_INT checks around Android 11 APIs, so the API 30 -> 29 rewrites in
// ApiCompatPatch.kt are not needed (and would not match). Its code is also older than 120003.E, which every
// hook here was written against, so the hooks for comfort features (UI density, language, driver side,
// color scheme, cluster, Settings rows, TTS warm-up, split-screen refresh) are allowed to miss on such an
// input: they are skipped with a warning in the patch log instead of failing the whole patch. The hooks
// Maps needs to start with MicroG-RE on a head unit without android.car stay required.
internal object InputApk {
    /** The input APK's minSdkVersion, read by the manifest patch before any bytecode patch runs. */
    @Volatile
    var minSdk: Int = 30

    /** True when the input is a minSdk 29 build (the fork's lenient mode). */
    val isApi29: Boolean get() = minSdk <= 29

    /** Labels of the optional steps that did not match the input and were skipped. */
    val skippedSteps = mutableListOf<String>()

    fun reset(newMinSdk: Int) {
        minSdk = newMinSdk
        skippedSteps.clear()
    }
}

internal val inputApkLogger: Logger = Logger.getLogger("app.morphe.patches.mapsautomotive.InputApk")

/**
 * Runs [block]. On a 120003.E (minSdk 30) input any failure is fatal, exactly as upstream.
 * On a minSdk 29 input a failure is logged and the step is skipped; returns false then.
 */
internal inline fun optionalOnApi29(label: String, block: () -> Unit): Boolean {
    if (!InputApk.isApi29) {
        block()
        return true
    }
    return try {
        block()
        inputApkLogger.info("API 29 input: applied '$label'")
        true
    } catch (e: Exception) {
        inputApkLogger.warning("API 29 input: skipped '$label' (${e.javaClass.simpleName}: ${e.message})")
        InputApk.skippedSteps += label
        false
    }
}

/** Steps that only exist to run Android 11 (API 30) code on Android 10. Skipped on a minSdk 29 input. */
internal inline fun api30OnlyStep(label: String, block: () -> Unit) {
    if (InputApk.isApi29) {
        inputApkLogger.info("API 29 input: '$label' not needed, skipped")
        return
    }
    block()
}
