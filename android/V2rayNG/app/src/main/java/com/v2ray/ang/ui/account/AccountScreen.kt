package com.v2ray.ang.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.handler.MobileBackend
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.Brand
import com.v2ray.ang.ui.compose.ConfirmDialog
import com.v2ray.ang.util.Utils

@Composable
fun AccountScreen(
    viewModel: AccountViewModel,
    onBackClick: () -> Unit,
    onLinkClick: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }

    if (confirmLogout) {
        ConfirmDialog(
            title = stringResource(R.string.account_logout_title),
            message = stringResource(R.string.account_logout_text),
            confirmText = stringResource(R.string.account_logout),
            onConfirm = viewModel::logout,
            onDismiss = { confirmLogout = false },
        )
    }

    Scaffold(
        topBar = {
            AppTopBar(title = stringResource(R.string.title_account), onBackClick = onBackClick)
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            if (state.number.isNotEmpty()) {
                NumberCard(number = state.number, busy = state.busy, onLogout = { confirmLogout = true })
                Spacer(Modifier.height(16.dp))
            }

            LoginCard(
                loggedIn = state.number.isNotEmpty(),
                state = state,
                draft = draft,
                onDraftChange = { value ->
                    draft = value.filter(Char::isDigit).take(MobileBackend.ACCOUNT_NUMBER_LENGTH)
                },
                onSubmit = {
                    viewModel.login(draft)
                },
            )

            Spacer(Modifier.height(16.dp))
            Card {
                Title(stringResource(R.string.account_qr_title))
                Hint(stringResource(R.string.account_qr_hint))
                Spacer(Modifier.height(14.dp))
                PrimaryButton(text = stringResource(R.string.account_qr_action), onClick = onLinkClick)
            }

            if (state.number.isEmpty()) {
                Spacer(Modifier.height(16.dp))
                Card {
                    Title(stringResource(R.string.account_new_title))
                    Hint(stringResource(R.string.account_new_hint))
                    if (state.createError) {
                        Spacer(Modifier.height(8.dp))
                        Notice(stringResource(R.string.backend_network_error))
                    }
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton(
                        text = stringResource(R.string.account_welcome_trial),
                        enabled = !state.busy,
                        onClick = viewModel::createWithTrial,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NumberCard(number: String, busy: Boolean, onLogout: () -> Unit) {
    val context = LocalContext.current
    Card {
        Title(stringResource(R.string.account_number_label))
        Spacer(Modifier.height(10.dp))
        Text(
            text = number,
            modifier = Modifier.fillMaxWidth(),
            color = Brand.onDark,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Hint(stringResource(R.string.account_number_hint))
        Spacer(Modifier.height(14.dp))
        PrimaryButton(
            text = stringResource(R.string.account_copy),
            onClick = { Utils.setClipboard(context, number.filter(Char::isDigit)) },
        )
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            text = stringResource(R.string.account_logout),
            enabled = !busy,
            onClick = onLogout,
        )
    }
}

@Composable
private fun LoginCard(
    loggedIn: Boolean,
    state: AccountViewModel.UiState,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Card {
        Title(
            stringResource(if (loggedIn) R.string.account_login_other_title else R.string.account_login_title)
        )
        Hint(stringResource(R.string.account_login_hint))
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("0000 0000 0000 0000", color = Brand.onDarkMuted) },
            visualTransformation = GroupsOfFour,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Brand.onDark,
                unfocusedTextColor = Brand.onDark,
                focusedBorderColor = Brand.accent,
                unfocusedBorderColor = Brand.divider,
                cursorColor = Brand.accent,
            ),
        )

        val error = when (state.loginError) {
            AccountViewModel.LoginError.INVALID -> R.string.account_login_invalid
            AccountViewModel.LoginError.NOT_FOUND -> R.string.account_login_not_found
            AccountViewModel.LoginError.TOO_MANY_ATTEMPTS -> R.string.account_login_too_many
            AccountViewModel.LoginError.OFFLINE -> R.string.backend_network_error
            null -> null
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Notice(stringResource(it))
        }

        Spacer(Modifier.height(14.dp))
        if (state.busy) {
            Box(Modifier.fillMaxWidth(), Alignment.Center) {
                CircularProgressIndicator(color = Brand.accent, modifier = Modifier.size(24.dp))
            }
        } else {
            PrimaryButton(
                text = stringResource(R.string.account_login_action),
                enabled = draft.length == MobileBackend.ACCOUNT_NUMBER_LENGTH,
                onClick = onSubmit,
            )
        }
    }
}

/** Номер на экране — группами по четыре цифры, как его показывают везде. */
private val GroupsOfFour = VisualTransformation { text ->
    val digits = text.text
    val grouped = digits.chunked(4).joinToString(" ")
    TransformedText(
        AnnotatedString(grouped),
        object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                offset + (offset - 1).coerceAtLeast(0) / 4

            override fun transformedToOriginal(offset: Int): Int =
                (offset - offset / 5).coerceIn(0, digits.length)
        },
    )
}

@Composable
private fun Title(text: String) {
    Text(text = text, color = Brand.onDark, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text = text, color = Brand.onDarkMuted, fontSize = 13.sp)
}

@Composable
private fun Notice(text: String) {
    Text(text = text, color = Brand.onDarkMuted, fontSize = 12.sp)
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brand.card)
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(if (enabled) Brand.accentMuted else Brand.surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) Brand.accent else Brand.onDarkMuted,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}
