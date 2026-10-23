package com.v2ray.ang.ui.compose

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * Цвета пунктов выпадающего меню.
 *
 * Подложка у всех наших меню фирменная тёмная (Brand.menu), и цвет текста
 * обязан быть от неё же. По умолчанию Material берёт его из темы, и при
 * светлой теме получалось тёмное по тёмному — пункты были на экране, но
 * прочитать их было нельзя.
 */
@Composable
fun appMenuItemColors() = MenuDefaults.itemColors(
    textColor = Brand.onDark,
    leadingIconColor = Brand.onDarkMuted,
    trailingIconColor = Brand.onDarkMuted,
    disabledTextColor = Brand.disconnected,
    disabledLeadingIconColor = Brand.disconnected,
    disabledTrailingIconColor = Brand.disconnected,
)

@Composable
fun <T> AppDropdownMenuItems(
    items: List<T>,
    labelRes: (T) -> Int,
    onSelected: (T) -> Unit
) {
    items.forEach { item ->
        DropdownMenuItem(
            text = { Text(stringResource(labelRes(item))) },
            colors = appMenuItemColors(),
            onClick = { onSelected(item) }
        )
    }
}
