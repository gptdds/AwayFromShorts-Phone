package com.afs.guard

import com.afs.guard.style.StyleRegistry
import com.afs.style.api.ComponentContracts
import com.afs.style.paper.PaperComponents
import com.afs.style.fluidgradient.FluidComponents
import com.afs.style.spatial3d.SpatialComponents
import com.afs.style.liquidglass.LiquidGlassComponents

/** Optional UI capabilities attach to the existing registry, not a second business/style catalog. */
object ComposeStyleCatalog {
    @Synchronized fun install() {
        if (!StyleRegistry.hasCapability("paper", ComponentContracts::class.java))
            StyleRegistry.registerCapability("paper", ComponentContracts::class.java) { PaperComponents() }
        if (!StyleRegistry.hasCapability("fluidgradient", ComponentContracts::class.java))
            StyleRegistry.registerCapability("fluidgradient", ComponentContracts::class.java) { FluidComponents() }
        if (!StyleRegistry.hasCapability("spatial3d", ComponentContracts::class.java))
            StyleRegistry.registerCapability("spatial3d", ComponentContracts::class.java) { SpatialComponents() }
        if (!StyleRegistry.hasCapability("liquidglass", ComponentContracts::class.java))
            StyleRegistry.registerCapability("liquidglass", ComponentContracts::class.java) { LiquidGlassComponents() }
    }
    fun resolve(id: String): ComponentContracts {
        try { StyleRegistry.createCapability(id, ComponentContracts::class.java)?.let { return it } }
        catch (failure: RuntimeException) { android.util.Log.w("AFSStyle", "Component fallback for $id", failure) }
        catch (failure: LinkageError) { android.util.Log.w("AFSStyle", "Unavailable component style $id", failure) }
        return StyleRegistry.createCapability("paper", ComponentContracts::class.java) ?: PaperComponents()
    }
}
