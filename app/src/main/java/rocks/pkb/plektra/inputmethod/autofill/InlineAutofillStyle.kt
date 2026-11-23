package rocks.pkb.plektra.inputmethod.autofill

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
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
    const val CHIP_HORIZONTAL_PADDING_DP = 12f
    /** Chips sit this far inside the hosting row, top and bottom. */
    const val CHIP_VERTICAL_INSET_DP = 4f
    private const val LOCAL_CHIP_ICON_DP = 18f
    private const val LOCAL_CHIP_ICON_GAP_DP = 6f
    const val MIN_TOUCH_TARGET_DP = 48f
    const val TITLE_TEXT_SIZE_SP = 14f
    private const val SUBTITLE_TEXT_SIZE_SP = 12f
    private const val SUBTITLE_ALPHA = 0xB3

    fun hostColor(theme: KeyboardThemeColors?): Int = opaque(theme?.background ?: Color.BLACK)

    /** Suggestion surfaces may be translucent over the bar; the chip shows the blended result. */
    fun chipColor(theme: KeyboardThemeColors): Int =
        ColorUtils.compositeColors(theme.suggestion, hostColor(theme))

    fun textColor(theme: KeyboardThemeColors): Int = opaque(theme.textAndIcons)

    // ViewStyle.Builder is public but inherits its setters from a @RestrictTo base class.
    @SuppressLint("RestrictedApi")
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

    /**
     * A chip Plektra draws itself (one-time codes) with the tokens [build] gives the autofill
     * service: chip shape and color, title color and size, horizontal padding. The view fills the
     * row's height so all of it is the touch target; the chip is drawn inset like service chips.
     */
    fun styleLocalChip(view: TextView, theme: KeyboardThemeColors?) {
        val metrics = view.resources.displayMetrics
        fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, metrics).toInt()
        val chipColor = theme?.let(::chipColor) ?: Color.DKGRAY
        val text = theme?.let(::textColor) ?: Color.WHITE
        val chip = (requireNotNull(AppCompatResources.getDrawable(view.context, R.drawable.inline_autofill_chip))
            .mutate() as GradientDrawable).apply { setColor(chipColor) }
        view.background = InsetDrawable(chip, 0, dp(CHIP_VERTICAL_INSET_DP), 0, dp(CHIP_VERTICAL_INSET_DP))
        view.setPadding(dp(CHIP_HORIZONTAL_PADDING_DP), 0, dp(CHIP_HORIZONTAL_PADDING_DP), 0)
        view.minWidth = dp(MIN_TOUCH_TARGET_DP)
        view.minimumWidth = dp(MIN_TOUCH_TARGET_DP)
        view.gravity = Gravity.CENTER
        view.setSingleLine(true)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_TEXT_SIZE_SP)
        view.setTextColor(text)
        view.compoundDrawablePadding = dp(LOCAL_CHIP_ICON_GAP_DP)
        view.compoundDrawablesRelative[0]?.let { icon ->
            icon.mutate().setTint(text)
            icon.setBounds(0, 0, dp(LOCAL_CHIP_ICON_DP), dp(LOCAL_CHIP_ICON_DP))
            view.setCompoundDrawablesRelative(icon, null, null, null)
        }
    }

    private fun opaque(color: Int): Int = ColorUtils.setAlphaComponent(color, 0xFF)
}
