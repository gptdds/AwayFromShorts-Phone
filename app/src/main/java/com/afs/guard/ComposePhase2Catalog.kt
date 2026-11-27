package com.afs.guard

import android.util.Log
import com.afs.feature.phase2.Phase2VisualPlugin
import com.afs.guard.style.StyleRegistry
import com.afs.style.fluidgradient.FluidGradientPhase2Plugin
import com.afs.style.liquidglass.LiquidGlassPhase2Plugin
import com.afs.style.spatial3d.SpatialPhase2Plugin

/** Composition root for optional Phase 2 visual capabilities. */
object ComposePhase2Catalog {
    private val aliases = mapOf(
        "LIQUID_GLASS" to "liquidglass",
        "SPATIAL" to "spatial3d",
        "FLUID_GRADIENT" to "fluidgradient"
    )

    @Synchronized
    fun install() {
        ComposeStyleCatalog.install()
        register("liquidglass") { LiquidGlassPhase2Plugin() }
        register("spatial3d") { SpatialPhase2Plugin() }
        register("fluidgradient") { FluidGradientPhase2Plugin() }
    }

    fun all(): List<Phase2VisualPlugin> {
        install()
        return StyleRegistry.all()
            .mapNotNull { renderer ->
                val id = renderer.id()
                if (!StyleRegistry.hasCapability(id, Phase2VisualPlugin::class.java)) {
                    return@mapNotNull null
                }
                try {
                    StyleRegistry.createCapability(id, Phase2VisualPlugin::class.java)
                } catch (failure: RuntimeException) {
                    Log.w("AFSStyle", "Unavailable Phase 2 plugin $id", failure)
                    null
                } catch (failure: LinkageError) {
                    Log.w("AFSStyle", "Unavailable Phase 2 plugin $id", failure)
                    null
                }
            }
            .sortedWith(compareBy(Phase2VisualPlugin::order, Phase2VisualPlugin::id))
    }

    fun resolve(id: String?): Phase2VisualPlugin {
        install()
        val requested = canonical(id)
        if (requested != null) {
            try {
                StyleRegistry.createCapability(requested, Phase2VisualPlugin::class.java)
                    ?.let { return it }
            } catch (failure: RuntimeException) {
                Log.w("AFSStyle", "Phase 2 fallback for $requested", failure)
            } catch (failure: LinkageError) {
                Log.w("AFSStyle", "Unavailable Phase 2 plugin $requested", failure)
            }
        }
        return all().firstOrNull() ?: error("No Phase 2 visual plugin is installed")
    }

    private fun register(
        id: String,
        factory: () -> Phase2VisualPlugin
    ) {
        if (StyleRegistry.find(id) == null) return
        if (!StyleRegistry.hasCapability(id, Phase2VisualPlugin::class.java)) {
            StyleRegistry.registerCapability(id, Phase2VisualPlugin::class.java, factory)
        }
    }

    private fun canonical(id: String?): String? {
        val value = id?.trim().orEmpty()
        if (value.isEmpty()) return null
        return aliases[value.uppercase()] ?: value.lowercase()
    }
}
