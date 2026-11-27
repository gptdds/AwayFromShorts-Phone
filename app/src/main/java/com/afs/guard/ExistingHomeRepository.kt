package com.afs.guard

import android.content.Context
import com.afs.core.model.*
import com.afs.core.domain.HomeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** Read-only presentation adapter to the deployed core. No second enforcement implementation. */
class ExistingHomeRepository(context: Context) : HomeRepository {
    private val app = context.applicationContext
    private val reads = AtomicInteger()
    val readCount get() = reads.get()
    override suspend fun load(): HomeSnapshot = withContext(Dispatchers.IO) {
        reads.incrementAndGet()
        val packages = Guard.enforcedRules(app).keys
        val apps = packages.map { pkg ->
            val reason = Guard.blockReasonNow(app, pkg)
            val installed = Guard.installed(app.packageManager, pkg)
            ManagedApp(pkg, Guard.labelOf(app.packageManager, pkg),
                if (installed) Guard.blockReasonText(reason) else "未安装 · 名单保留", installed && reason != 0)
        }.sortedBy { it.name.lowercase() }
        HomeSnapshot(apps, System.currentTimeMillis())
    }
}
