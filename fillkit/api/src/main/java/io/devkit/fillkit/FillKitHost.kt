package io.devkit.fillkit

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.devkit.fillkit.runtime.FillKitRuntimeProvider

/**
 * Scopes FillKit registrations to [formId]. In a release build without
 * `fillkit-debug`, this simply renders [content].
 */
@Composable
fun FillKitHost(
    formId: String,
    modifier: Modifier = Modifier,
    config: FillKitConfig = FillKitConfig(),
    controller: FillKitController? = null,
    content: @Composable () -> Unit,
) {
    require(formId.isNotBlank()) { "FillKit formId cannot be blank" }
    FillKitRuntimeProvider.current.Host(formId, modifier, config, controller, content)
}
