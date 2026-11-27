package com.afs.guard

import com.afs.guard.style.StyleRegistry

/** Remove only this test's synthetic entries; production catalog stays untouched. */
internal object RegistryFixtures {
    @Suppress("UNCHECKED_CAST")
    fun remove(id: String) = synchronized(StyleRegistry::class.java) {
        for (field in listOf("renderers", "capabilities")) {
            val entries = StyleRegistry::class.java.getDeclaredField(field).apply { isAccessible = true }
                .get(null) as MutableMap<String, *>
            entries.remove(id)
        }
    }
}
