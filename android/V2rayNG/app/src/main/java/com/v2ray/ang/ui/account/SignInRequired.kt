package com.v2ray.ang.ui.account

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R

/**
 * Экран открыт без аккаунта: объясняем, зачем он нужен, и ведём войти.
 * Вместо «нет связи» — её-то как раз есть, нет входа.
 *
 * @param secondary вторая кнопка: например, поддержка в Telegram, куда
 *                  можно написать и без аккаунта.
 */
@Composable
internal fun SignInRequiredCard(
    hint: String,
    onSignIn: () -> Unit,
    secondary: Pair<String, () -> Unit>? = null,
) {
    Card {
        Title(stringResource(R.string.signin_required_title))
        Hint(hint)
        Spacer(Modifier.height(14.dp))
        PrimaryButton(text = stringResource(R.string.signin_required_action), onClick = onSignIn)
        secondary?.let { (text, onClick) ->
            Spacer(Modifier.height(10.dp))
            PrimaryButton(text = text, onClick = onClick)
        }
    }
}
