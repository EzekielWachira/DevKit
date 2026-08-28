package io.devkit.fillkit.debug.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.devkit.fillkit.FillKitConfig
import io.devkit.fillkit.debug.runtime.FormRegistry

/** Secondary surfaces reachable from the main panel. */
internal enum class PanelRoute { Persona, Locale, Scenario, Suggestions }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FillKitOverlay(registry: FormRegistry, config: FillKitConfig) {
    var open by remember { mutableStateOf(false) }
    var route by remember { mutableStateOf<PanelRoute?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (config.showTrigger) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            contentAlignment = Alignment.BottomEnd,
        ) {
            FillKitTrigger(onClick = { open = true })
        }
    }

    if (open) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = sheetState,
            shape = SheetShape,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            dragHandle = { FillKitDragHandle() },
        ) {
            FillKitPanel(
                registry = registry,
                config = config,
                onFillAll = {
                    registry.fillAll()
                    open = false
                },
                onOpenRoute = { route = it },
            )
        }
    }

    // Sibling — not nested — so the secondary sheet owns its own window and
    // dismissing it returns to the panel instead of tearing both down.
    route?.let { current ->
        FillKitRouteSheet(
            route = current,
            registry = registry,
            onDismiss = { route = null },
        )
    }
}

@Composable
private fun FillKitTrigger(onClick: () -> Unit) {
    val gradient = Brush.linearGradient(
        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
    )
    Surface(
        modifier = Modifier
            .size(54.dp)
            .shadow(12.dp, CircleShape)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        shape = CircleShape,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier.background(gradient),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "⚡",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}
