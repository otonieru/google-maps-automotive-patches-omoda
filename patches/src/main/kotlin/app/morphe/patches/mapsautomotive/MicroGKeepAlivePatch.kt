package app.morphe.patches.mapsautomotive

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch

private const val KEEP_ALIVE_CLASS = "Lapp/morphe/extension/mapsautomotive/patches/GmsCoreKeepAlive;"

/**
 * Fork addition, ported from otonieru/gmaps-patches-omoda (phone Maps). Head units such as Chery/Omoda kill
 * MicroG-RE in the background; Maps then loses its location (and can fall back to signed out). The extension
 * starts MicroG-RE when Maps starts, holds an important binding to its location service while Maps runs, and
 * re-binds within a minute if the head unit force-stops it (GmsCoreKeepAlive.java). Main process only.
 */
@Suppress("unused")
val microGKeepAlivePatch = bytecodePatch(
    name = "MicroG-RE keep-alive",
    description = "Starts MicroG-RE when Maps starts and keeps it bound while Maps runs, re-binding within a minute " +
        "if the head unit kills it, so location and sign-in survive aggressive app killers (Chery/Omoda and others).",
    default = true,
) {
    compatibleWith(compatibility)
    dependsOn(automotiveMicroGPatch)

    execute {
        requireExtensionMethod(KEEP_ALIVE_CLASS, "onApplicationCreate", listOf("Landroid/content/Context;"), "V")
        val method = findHierarchyHook(APPLICATION_CLASS, "Maps application onCreate") {
            it.name == "onCreate" && it.returnType == "V" && it.parameterTypes.isEmpty()
        }
        // The extension checks the process itself, so its place before the core's report-process check is fine.
        method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $KEEP_ALIVE_CLASS->onApplicationCreate(Landroid/content/Context;)V",
        )
        IncludedFeatures.features += "microg_keep_alive"
    }
}
