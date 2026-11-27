package com.afs.style.paper

import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.afs.style.api.ComponentContracts

/** Original quiet typography and solid surfaces remain the universal fallback. */
class PaperComponents : ComponentContracts {
    override val id = "paper"
    @Composable override fun Background(modifier: Modifier) {
        Box(modifier)
    }
    @Composable override fun Card(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
        OutlinedCard(modifier, content = content)
    }
    @Composable override fun Action(onClick: () -> Unit, modifier: Modifier, enabled: Boolean,
                                    content: @Composable RowScope.() -> Unit) {
        TextButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    }
}
