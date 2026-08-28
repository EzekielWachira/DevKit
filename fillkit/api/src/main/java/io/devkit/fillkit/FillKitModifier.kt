package io.devkit.fillkit

import androidx.compose.ui.Modifier
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.observeReads
import io.devkit.fillkit.runtime.FillKitField
import io.devkit.fillkit.runtime.FillKitRegistry
import io.devkit.fillkit.runtime.LocalFillKitRegistry

/** Registers this UI element with the nearest [FillKitHost]. */
fun <T : Any> Modifier.fillKit(
    id: String,
    type: FillType<T>,
    value: T?,
    onFill: (T) -> Unit,
    label: String? = null,
    group: String? = null,
    onClear: (() -> Unit)? = null,
    generator: FillGenerator<T>? = null,
): Modifier {
    require(id.isNotBlank()) { "FillKit field id cannot be blank" }
    return this then FillKitElement(id, label, group, type, value, onFill, onClear, generator)
}

private class FillKitElement<T : Any>(
    private val id: String,
    private val label: String?,
    private val group: String?,
    private val type: FillType<T>,
    private val value: T?,
    private val onFill: (T) -> Unit,
    private val onClear: (() -> Unit)?,
    private val generator: FillGenerator<T>?,
) : ModifierNodeElement<FillKitNode<T>>() {
    override fun create() = FillKitNode(id, label, group, type, value, onFill, onClear, generator)

    override fun update(node: FillKitNode<T>) {
        node.update(id, label, group, type, value, onFill, onClear, generator)
    }

    override fun equals(other: Any?): Boolean = other is FillKitElement<*> &&
        id == other.id && label == other.label && group == other.group &&
        type == other.type && value == other.value && onFill === other.onFill &&
        onClear === other.onClear && generator === other.generator

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (label?.hashCode() ?: 0)
        result = 31 * result + (group?.hashCode() ?: 0)
        result = 31 * result + type.hashCode()
        result = 31 * result + (value?.hashCode() ?: 0)
        result = 31 * result + System.identityHashCode(onFill)
        result = 31 * result + System.identityHashCode(onClear)
        result = 31 * result + System.identityHashCode(generator)
        return result
    }
}

private class FillKitNode<T : Any>(
    private var id: String,
    private var label: String?,
    private var group: String?,
    private var type: FillType<T>,
    private var value: T?,
    private var onFill: (T) -> Unit,
    private var onClear: (() -> Unit)?,
    private var generator: FillGenerator<T>?,
) : Modifier.Node(), CompositionLocalConsumerModifierNode, ObserverModifierNode {
    private var registry: FillKitRegistry? = null

    override fun onAttach() {
        onObservedReadsChanged()
    }

    override fun onObservedReadsChanged() {
        observeReads {
            val latest = currentValueOf(LocalFillKitRegistry)
            if (latest !== registry) {
                registry?.unregister(this)
                registry = latest
                registry?.register(this, field())
            }
        }
    }

    override fun onDetach() {
        registry?.unregister(this)
        registry = null
    }

    fun update(
        id: String,
        label: String?,
        group: String?,
        type: FillType<T>,
        value: T?,
        onFill: (T) -> Unit,
        onClear: (() -> Unit)?,
        generator: FillGenerator<T>?,
    ) {
        this.id = id
        this.label = label
        this.group = group
        this.type = type
        this.value = value
        this.onFill = onFill
        this.onClear = onClear
        this.generator = generator
        registry?.update(this, field())
    }

    private fun field() = FillKitField(id, label, group, type, value, onFill, onClear, generator)
}
