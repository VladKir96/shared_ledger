package dev.sharedledger.presentation

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.sharedledger.R
import dev.sharedledger.domain.calculate
import dev.sharedledger.domain.canonicalAmountEdit
import dev.sharedledger.domain.groupAmount
import dev.sharedledger.domain.parseCurrencyInput
import dev.sharedledger.domain.storage
import kotlinx.coroutines.launch
import java.math.BigDecimal

@Composable
internal fun AmountField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    tag: String,
    enabled: Boolean = true,
    isError: Boolean = false,
    calculatorValue: (BigDecimal) -> BigDecimal? = { parseCurrencyInput(it.storage())?.takeIf { amount -> amount.signum() > 0 } }
) {
    var showCalculator by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    MonetaryTextField(label, value, onChange, modifier.testTag(tag), enabled = enabled, isError = isError,
        trailingIcon = {
            IconButton({
                keyboard?.hide()
                focus.clearFocus()
                showCalculator = true
            }, Modifier.testTag("$tag-calculator"), enabled = enabled) {
                Icon(Icons.Outlined.Calculate, stringResource(R.string.editor_calculator))
            }
        })
    if (showCalculator) AmountCalculator(value, calculatorValue, onDismiss = { showCalculator = false }) {
        onChange(it)
        showCalculator = false
    }
}

private const val operators = "+−×÷"
private val calculatorRows = listOf(
    listOf("C", "⌫", "÷", "×"), listOf("7", "8", "9", "−"), listOf("4", "5", "6", "+"),
    listOf("1", "2", "3", "="), listOf("000", "0", ".")
)

@Composable
private fun AmountCalculator(
    initial: String,
    validate: (BigDecimal) -> BigDecimal?,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit
) {
    var expression by rememberSaveable { mutableStateOf(canonicalAmountEdit(initial).orEmpty()) }
    var evaluated by rememberSaveable { mutableStateOf(false) }
    var invalidPaste by rememberSaveable { mutableStateOf(false) }
    val result = remember(expression) { calculate(expression) }
    val applicable = result?.let(validate)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    fun press(key: String) {
        invalidPaste = false
        when (key) {
            "C" -> { expression = ""; evaluated = false }
            "⌫" -> { expression = expression.dropLast(1); evaluated = false }
            "=" -> result?.let { expression = it.storage(); evaluated = true }
            else -> {
                val operator = key.length == 1 && key[0] in operators
                val value = if (evaluated && !operator) "" else expression
                val token = value.takeLastWhile { it !in operators + "-" }
                expression = when {
                    operator && value.isEmpty() -> value
                    operator && value.last() in operators -> value.dropLast(1) + key
                    key == "." && '.' in token -> value
                    key == "." && token.isEmpty() -> value + "0."
                    key == "000" && (token.isEmpty() || token == "0") -> if (token.isEmpty()) value + "0" else value
                    else -> value + key
                }.takeIf { it.length <= 120 } ?: expression
                evaluated = false
            }
        }
    }

    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.editor_calculator)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(calculatorNumber.replace(expression) { groupAmount(it.value) }.ifEmpty { "0" }, Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium)
                Text(result?.storage()?.let(::groupAmount) ?: "—",
                    Modifier.fillMaxWidth().testTag("calculator-result"),
                    textAlign = TextAlign.End, style = MaterialTheme.typography.headlineSmall, maxLines = 2)
                Row(Modifier.align(Alignment.End)) {
                    IconButton({ result?.let { amount -> scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Amount", amount.storage())))
                    } } }, enabled = result != null) {
                        Icon(Icons.Outlined.ContentCopy, stringResource(R.string.editor_copy_amount))
                    }
                    IconButton({ scope.launch {
                        val pasted = clipboard.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString()
                            ?.let(::canonicalAmountEdit)
                        invalidPaste = pasted == null
                        if (pasted != null) { expression = pasted; evaluated = false }
                    } }) {
                        Icon(Icons.Outlined.ContentPaste, stringResource(R.string.editor_paste_amount))
                    }
                }
                if (invalidPaste || expression.isNotEmpty() && expression.last() !in operators && applicable == null) {
                    Text(stringResource(R.string.editor_calculator_invalid), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                calculatorRows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { key ->
                            FilledTonalButton({ press(key) }, Modifier.weight(1f).heightIn(min = 46.dp)
                                .testTag("calculator-key-$key"), contentPadding = PaddingValues(2.dp)) {
                                when (key) {
                                    "⌫" -> Icon(Icons.AutoMirrored.Outlined.Backspace, stringResource(R.string.editor_backspace))
                                    else -> Text(key)
                                }
                            }
                        }
                    }
                }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(tr("Cancel")) } },
        confirmButton = { TextButton({ applicable?.let { onApply(it.storage()) } },
            Modifier.testTag("calculator-apply"), enabled = applicable != null) {
            Text(stringResource(R.string.editor_apply_amount))
        } })
}

private val calculatorNumber = Regex("[0-9]+(?:\\.[0-9]*)?")
