package io.devkit.fillkit

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.autofill.FillableData
import androidx.compose.ui.autofill.createFromText

enum class FillTargetKind { Callback, TextFieldState, Semantics }

/** The single mutation boundary used by every FillKit entry point. */
interface FillTarget<T : Any> {
    val kind: FillTargetKind
    val currentValue: T?
    fun fill(value: T)
    fun clear(): Boolean
}

class CallbackFillTarget<T : Any>(
    override val currentValue: T?,
    private val onFill: (T) -> Unit,
    private val onClear: (() -> Unit)? = null,
) : FillTarget<T> {
    override val kind = FillTargetKind.Callback
    override fun fill(value: T) = onFill(value)
    override fun clear(): Boolean {
        onClear?.invoke() ?: return false
        return true
    }
}

class TextFieldStateFillTarget(
    private val state: TextFieldState,
    private val normalize: (String) -> String = { it },
) : FillTarget<String> {
    override val kind = FillTargetKind.TextFieldState
    override val currentValue: String get() = state.text.toString()
    override fun fill(value: String) = state.setTextAndPlaceCursorAtEnd(normalize(value))
    override fun clear(): Boolean { state.clearText(); return true }
}

class SemanticsFillTarget(
    override val currentValue: String?,
    private val onFillData: (FillableData) -> Boolean,
) : FillTarget<String> {
    override val kind = FillTargetKind.Semantics
    override fun fill(value: String) {
        FillableData.createFromText(value)?.let(onFillData)
    }
    override fun clear(): Boolean {
        val data = FillableData.createFromText("") ?: return false
        return onFillData(data)
    }
}
