package com.afs.guard

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.afs.guard.style.*
import com.afs.style.api.ComponentContracts
import com.afs.style.paper.PaperComponents
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComponentRegistryTest {
    @After fun removeFixtures() {
        RegistryFixtures.remove("qa_components_lazy")
        RegistryFixtures.remove("qa_components_broken")
    }
    private fun descriptor(key: String) = object : RendererFactory {
        override fun id() = key
        override fun displayName(context: Context) = key
        override fun create(): StyleRenderer = PaperStyle()
    }
    @Test fun capabilitiesAreLazyAndIndependentOfBusiness() {
        ComposeStyleCatalog.install()
        val key = "qa_components_lazy"
        if (StyleRegistry.find(key) == null) StyleRegistry.register(descriptor(key))
        var creates = 0
        StyleRegistry.registerCapability(key, ComponentContracts::class.java) {
            creates++
            object : ComponentContracts by PaperComponents() { override val id = key }
        }
        assertEquals(0, creates)
        assertEquals(key, ComposeStyleCatalog.resolve(key).id)
        assertEquals(1, creates)
        assertEquals("paper", ComposeStyleCatalog.resolve("not-installed").id)
    }
    @Test fun failedComponentFactoryFallsBackToMinimal() {
        ComposeStyleCatalog.install()
        val key = "qa_components_broken"
        if (StyleRegistry.find(key) == null) StyleRegistry.register(descriptor(key))
        StyleRegistry.registerCapability(key, ComponentContracts::class.java) {
            throw IllegalStateException("expected test renderer failure")
        }
        assertEquals("paper", ComposeStyleCatalog.resolve(key).id)
    }
}
