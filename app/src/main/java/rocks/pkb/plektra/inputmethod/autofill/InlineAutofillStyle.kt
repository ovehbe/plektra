package rocks.pkb.plektra.inputmethod.autofill

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Icon
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.autofill.R as AutofillR
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

    private fun paddingPx(context: Context) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, CHIP_HORIZONTAL_PADDING_DP, context.resources.displayMetrics
    ).toInt()

    // Local content has no autofill service/RemoteViews. Inflate the AndroidX inline v1
    // template directly (also works on API 29) and apply the same title and chip tokens.
    fun localChip(parent: ViewGroup, code: String): View {
        val themed = ContextThemeWrapper(parent.context, AutofillR.style.Theme_AutofillInlineSuggestion)
        return LayoutInflater.from(themed).inflate(AutofillR.layout.autofill_inline_suggestion, parent, false).apply {
            findViewById<TextView>(AutofillR.id.autofill_inline_suggestion_title).apply {
                text = code
                textDirection = View.TEXT_DIRECTION_LTR
                visibility = View.VISIBLE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            }
        }
    }

    fun themeLocalChip(chip: View, theme: KeyboardThemeColors) {
        chip.setBackgroundResource(R.drawable.inline_autofill_chip)
        chip.background.mutate().setTint(chipColor(theme))
        val padding = paddingPx(chip.context)
        chip.setPadding(padding, 0, padding, 0)
        chip.findViewById<TextView>(AutofillR.id.autofill_inline_suggestion_title).setTextColor(textColor(theme))
    }

    // ViewStyle.Builder is public but inherits its setters from a @RestrictTo base class.
    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.R)
    fun build(context: Context, theme: KeyboardThemeColors): Bundle {
        val padding = paddingPx(context)
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
