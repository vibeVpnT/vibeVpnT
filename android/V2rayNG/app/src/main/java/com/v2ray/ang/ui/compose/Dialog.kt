package com.v2ray.ang.ui.compose

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.v2ray.ang.R

/**
 * Общий вид всех попапов приложения.
 *
 * Все они устроены одинаково: тёмная подложка меню, скруглённые углы,
 * действие акцентным цветом, отказ — приглушённым. Отдельный цвет или
 * форму в экранах не задают, чтобы попапы не разъезжались между собой.
 *
 * По пустому месту попап не закрывается. Промах мимо кнопки — обычное
 * дело на телефоне, а цена промаха разная: потерянный наполовину
 * введённый адрес подписки или непрочитанное предложение. Закрыть можно
 * кнопкой или системной «назад».
 */
@Composable
fun AppDialog(
    onDismiss: () -> Unit,
    title: String? = null,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    confirmIcon: @Composable (() -> Unit)? = null,
    confirmModifier: Modifier = Modifier,
    dismissText: String? = null,
    dismissModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        properties = DialogProperties(dismissOnClickOutside = false),
        shape = RoundedCornerShape(20.dp),
        containerColor = Brand.menu,
        titleContentColor = Brand.onDark,
        textContentColor = Brand.onDark,
        title = title?.let {
            {
                Text(it, style = MaterialTheme.typography.titleMedium, color = Brand.onDark)
            }
        },
        text = content,
        confirmButton = {
            if (confirmText != null && onConfirm != null) {
                TextButton(
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    modifier = confirmModifier,
                ) {
                    confirmIcon?.invoke()
                    if (confirmIcon != null) Spacer(Modifier.width(8.dp))
                    Text(
                        confirmText,
                        color = if (confirmEnabled) Brand.accent else Brand.onDarkMuted,
                    )
                }
            }
        },
        dismissButton = dismissText?.let { text ->
            {
                TextButton(onClick = onDismiss, modifier = dismissModifier) {
                    Text(text, color = Brand.onDarkMuted)
                }
            }
        },
    )
}

/** Поля ввода внутри попапов — в тех же цветах, что и всё остальное. */
@Composable
fun dialogFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Brand.onDark,
    unfocusedTextColor = Brand.onDark,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedBorderColor = Brand.accent,
    unfocusedBorderColor = Brand.divider,
    focusedLabelColor = Brand.accent,
    unfocusedLabelColor = Brand.onDarkMuted,
    cursorColor = Brand.accent,
    selectionColors = TextSelectionColors(
        handleColor = Brand.accent,
        backgroundColor = Brand.accent.copy(alpha = 0.4f),
    ),
)

@Composable
fun ConfirmDialog(
    title: String? = null,
    message: String,
    confirmText: String = stringResource(R.string.action_ok),
    dismissText: String? = stringResource(R.string.action_cancel),
    confirmIcon: @Composable (() -> Unit)? = null,
    messageTextAlign: TextAlign = TextAlign.Start,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    // Фокус нужен пульту: на телевизоре попап открывается без курсора, и
    // без этого непонятно, по чему нажимать.
    val confirmFocusRequester = remember { FocusRequester() }
    val dismissFocusRequester = remember { FocusRequester() }

    LaunchedEffect(dismissText) {
        if (dismissText != null) dismissFocusRequester.requestFocus()
        else confirmFocusRequester.requestFocus()
    }

    AppDialog(
        onDismiss = onDismiss,
        title = title,
        confirmText = confirmText,
        onConfirm = { onConfirm(); onDismiss() },
        confirmIcon = confirmIcon,
        confirmModifier = Modifier.focusRequester(confirmFocusRequester),
        dismissText = dismissText,
        dismissModifier = Modifier.focusRequester(dismissFocusRequester),
    ) {
        Text(
            text = message,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = messageTextAlign
        )
    }
}

@Composable
fun DeleteConfirmDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    itemName: String? = null,
) {
    ConfirmDialog(
        message = if (itemName.isNullOrBlank()) message else "$message\n\n$itemName",
        messageTextAlign = TextAlign.Center,
        confirmText = stringResource(R.string.action_delete),
        confirmIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_delete_24dp),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        },
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

data class InputField(
    val label: String,
    val value: String,
    val singleLine: Boolean = true,
    val visualTransformation: VisualTransformation = VisualTransformation.None
)

@Composable
fun InputDialog(
    title: String,
    fields: List<InputField>,
    onFieldChange: (Int, String) -> Unit,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AppDialog(
        onDismiss = onDismiss,
        title = title,
        confirmText = confirmText,
        onConfirm = onConfirm,
        dismissText = dismissText,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            fields.forEachIndexed { index, field ->
                OutlinedTextField(
                    value = field.value,
                    onValueChange = { onFieldChange(index, it) },
                    label = { Text(field.label) },
                    singleLine = false,
                    maxLines = 5,
                    visualTransformation = field.visualTransformation,
                    colors = dialogFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun QRCodeDialog(
    bitmap: Bitmap?,
    onDismiss: () -> Unit
) {
    if (bitmap == null) return
    AppDialog(
        onDismiss = onDismiss,
        confirmText = stringResource(R.string.action_close),
        onConfirm = onDismiss,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.acc_qr_code),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        )
    }
}

/**
 * When showRadio is true, displays RadioButton (single selection mode);
 * otherwise, plain clickable list mode.
 * The selectedOption parameter is used to highlight the selected item only when showRadio is true.
 */
@Composable
fun <T> SelectListDialog(
    options: List<T>,
    optionText: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    title: String? = null,
    selectedOption: T? = null,
    showRadio: Boolean = false
) {
    AppDialog(
        onDismiss = onDismiss,
        title = title,
        dismissText = stringResource(R.string.action_cancel),
    ) {
        LazyColumn {
            items(options) { option ->
                val isSelected = option == selectedOption
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (showRadio) Modifier.selectable(
                                selected = isSelected,
                                onClick = { onSelected(option) },
                                role = Role.RadioButton
                            ) else Modifier.clickable { onSelected(option) }
                        )
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showRadio) {
                        RadioButton(
                            selected = isSelected,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = optionText(option),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
