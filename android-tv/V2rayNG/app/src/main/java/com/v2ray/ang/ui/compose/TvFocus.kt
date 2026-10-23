package com.v2ray.ang.ui.compose

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Подсветка фокуса для пульта.
 *
 * На телевизоре палец не показывает, где ты: это делает фокус. Рябь
 * Material, которой хватает телефону, на экране в двух метрах почти не
 * видна — и человек жмёт стрелки, не понимая, что выбрано. Поэтому всё
 * кликабельное в фокусе получает жёлтую рамку и светлую подложку; нажатие
 * — лёгкую подложку, как прежде.
 *
 * Подключается в AppTheme через LocalIndication: её берёт любой
 * Modifier.clickable без своей indication.
 */
object TvFocusIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        TvFocusNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = javaClass.hashCode()
}

private class TvFocusNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
    private var focused = false
    private var pressed = false

    override fun onAttach() {
        coroutineScope.launch {
            val focuses = mutableListOf<FocusInteraction.Focus>()
            val presses = mutableListOf<PressInteraction.Press>()
            source.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> focuses += interaction
                    is FocusInteraction.Unfocus -> focuses -= interaction.focus
                    is PressInteraction.Press -> presses += interaction
                    is PressInteraction.Release -> presses -= interaction.press
                    is PressInteraction.Cancel -> presses -= interaction.press
                }
                focused = focuses.isNotEmpty()
                pressed = presses.isNotEmpty()
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (focused) {
            val radius = CornerRadius(14.dp.toPx())
            drawRoundRect(Color.White.copy(alpha = 0.10f), cornerRadius = radius)
            val stroke = 3.dp.toPx()
            drawRoundRect(
                color = Brand.accent,
                topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                cornerRadius = radius,
                style = Stroke(width = stroke),
            )
        } else if (pressed) {
            drawRect(Color.White.copy(alpha = 0.08f))
        }
    }
}
