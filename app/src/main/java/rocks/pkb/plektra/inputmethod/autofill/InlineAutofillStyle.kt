package rocks.pkb.plektra.inputmethod.autofill

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.core.graphics.ColorUtils
import rocks.pkb.plektra.R
import rocks.pkb.plektra.inputmethod.ui.KeyboardThemeColors

/**
 * Turns Plektra theme tokens into the style the autofill service renders its chips with. The
 * service draws outside Plektra, so textures cannot follow: every surface is a solid color.
 */
internal object InlineAutofillStyle {
    private const val CHIP_HORIZONTAL_PADDING_DP = 12f
    private const val TITLE_TEXT_SIZE_SP = 14f
    private const val SUBTITLE_TEXT_SIZE_SP = 12f
    private const val SUBTITLE_ALPHA = 0xB3

    fun hostColor(theme: KeyboardThemeColors?): Int = opaque(theme?.background ?: Color.BLACK)

    /** Suggestion surfaces may be translucent over the bar; the chip shows the blended result. */
    fun chipColor(theme: KeyboardThemeColors): Int =
        ColorUtils.compositeColors(theme.suggestion, hostColor(theme))

    fun textColor(theme: KeyboardThemeColors): Int = opaque(theme.textAndIcons)

    @RequiresApi(Build.VERSION_CODES.R)
    fun build(context: Context, theme: KeyboardThemeColors): Bundle {
        val padding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, CHIP_HORIZONTAL_PADDING_DP, context.resources.displayMetrics
        ).toInt()
        val chip = ViewStyle.Builder()
            .setBackground(Icon.createWithResource(context, R.drawable.inline_autofill_chip).setTint(chipColor(theme)))
            .setPadding(padding, 0, padding, 0)
            .build()
        val text = textColor(theme)
        val style = InlineSuggestionUi.newStyleBuilder()
            .setChipStyle(chip)
            .setSingleIconChipStyle(chip)
            .setTitleStyle(TextViewStyle.Builder().setTextColor(text).setTextSize(TITLE_TEXT_SIZE_SP).build())
            .setSubtitleStyle(
                TextViewStyle.Builder()
                    .setTextColor(ColorUtils.setAlphaComponent(text, SUBTITLE_ALPHA))
                    .setTextSize(SUBTITLE_TEXT_SIZE_SP)
                    .build()
            )
            .build()
        return UiVersions.newStylesBuilder().addStyle(style).build()
    }

    private fun opaque(color: Int): Int = ColorUtils.setAlphaComponent(color, 0xFF)
}
