package com.v2ray.ang.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.AppDialog
import com.v2ray.ang.ui.compose.Brand
import com.v2ray.ang.util.Utils

/**
 * Номер аккаунта, который ещё не сохранили.
 *
 * Номер — единственный способ войти, поэтому закрыть окно насовсем
 * можно только словами «Я сохранил»; «Позже» покажет его снова при
 * следующем запуске.
 */
@Composable
fun AccountNumberDialog(
    number: String,
    onSaved: () -> Unit,
    onLater: () -> Unit,
) {
    val context = LocalContext.current
    AppDialog(
        onDismiss = onLater,
        title = stringResource(R.string.account_number_title),
        confirmText = stringResource(R.string.account_number_saved),
        onConfirm = onSaved,
        dismissText = stringResource(R.string.account_number_later),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = number,
                modifier = Modifier.fillMaxWidth(),
                color = Brand.onDark,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.account_number_text),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { Utils.setClipboard(context, number.filter(Char::isDigit)) }) {
                Text(stringResource(R.string.account_copy), color = Brand.accent)
            }
        }
    }
}
