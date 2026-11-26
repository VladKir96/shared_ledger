package dev.sharedledger.presentation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import dev.sharedledger.domain.amountGroupingOffsets
import dev.sharedledger.domain.canonicalAmountEdit
import dev.sharedledger.domain.isCanonicalAmountEdit

@OptIn(ExperimentalFoundationApi::class)
private val monetaryInput = InputTransformation {
    for (index in changes.changeCount - 1 downTo 0) {
        val range = changes.getRange(index)
        val inserted = asCharSequence().subSequence(range.min, range.max).toString()
        val canonical = canonicalAmountEdit(inserted)
        if (canonical == null) {
            revertAllChanges()
            return@InputTransformation
        }
        if (canonical != inserted) replace(range.min, range.max, canonical)
    }
    if (!isCanonicalAmountEdit(asCharSequence().toString())) revertAllChanges()
}

private val monetaryOutput = OutputTransformation {
    for (offset in amountGroupingOffsets(asCharSequence())) replace(offset, offset, ",")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MonetaryTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    trailingIcon: (@Composable () -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Decimal
) {
    val state = rememberTextFieldState(value)
    val latestValue by rememberUpdatedState(value)
    val onChangeLatest = remember { mutableStateOf(onChange, referentialEqualityPolicy()) }
    SideEffect { onChangeLatest.value = onChange }
    LaunchedEffect(state) {
        var lastValue = latestValue
        var lastText = lastValue
        if (state.text.toString() != lastText) state.setTextAndPlaceCursorAtEnd(lastText)
        snapshotFlow { latestValue to state.text.toString() }.collect { (incoming, text) ->
            val externalUpdate = incoming != lastValue && incoming != lastText
            lastValue = incoming
            if (externalUpdate) {
                // A recalculated suggestion or calculator result is already reflected in the editor.
                lastText = incoming
                if (text != incoming) state.setTextAndPlaceCursorAtEnd(incoming)
            } else if (text != lastText) {
                lastText = text
                onChangeLatest.value(text)
            }
        }
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val requester = remember { BringIntoViewRequester() }
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val keyboardHeight = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(focused, keyboardHeight) {
        if (focused) requester.bringIntoView()
    }
    val scheme = MaterialTheme.colorScheme
    BasicTextField(state,
        modifier = modifier.padding(top = 8.dp)
            .defaultMinSize(minHeight = OutlinedTextFieldDefaults.MinHeight)
            .bringIntoViewRequester(requester).onFocusChanged { focused = it.isFocused },
        enabled = enabled, lineLimits = TextFieldLineLimits.SingleLine,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = .38f)),
        cursorBrush = SolidColor(if (isError) scheme.error else scheme.primary),
        interactionSource = interaction, inputTransformation = monetaryInput, outputTransformation = monetaryOutput,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        onKeyboardAction = { focus.clearFocus(); keyboard?.hide() },
        decorator = { input ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = state.text.toString(), innerTextField = input, enabled = enabled, singleLine = true,
                visualTransformation = VisualTransformation.None, interactionSource = interaction,
                isError = isError, label = { Text(label) }, trailingIcon = trailingIcon
            )
        }
    )
}
