package rocks.pkb.plektra.inputmethod.autofill

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.util.TypedValue
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import rocks.pkb.plektra.SettingsManager

/**
 * Android's IME inline autofill: password managers render their own suggestions as opaque views
 * that the keyboard bar hosts. Plektra never sees the suggested values.
 */
internal object InlineAutofill {
    /** Android recommends at most five for performance. */
    const val MAX_SUGGESTIONS = 4
    private const val CHIP_HEIGHT_DP = 36f
    private const val CHIP_MIN_WIDTH_DP = 48f

    /**
     * Autofill hides its own dropdown as soon as the IME returns a request, and ignores what the
     * IME answers to the response, so a request is offered only when a bar host can draw the chips.
     */
    fun offersRequest(sdkInt: Int, enabled: Boolean, hostAvailable: Boolean): Boolean =
        sdkInt >= Build.VERSION_CODES.R && enabled && hostAvailable

    @RequiresApi(Build.VERSION_CODES.R)
    fun createRequest(
        context: Context,
        uiExtras: Bundle,
        hostAvailable: Boolean,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): InlineSuggestionsRequest? {
        if (!offersRequest(sdkInt, SettingsManager.getInlineAutofillEnabled(context), hostAvailable)) return null
        if (UiVersions.INLINE_UI_VERSION_1 !in UiVersions.getVersions(uiExtras)) return null
        return buildRequest(context)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    internal fun buildRequest(context: Context): InlineSuggestionsRequest {
        val metrics = context.resources.displayMetrics
        val height = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, CHIP_HEIGHT_DP, metrics).toInt()
        val minWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, CHIP_MIN_WIDTH_DP, metrics).toInt()
        val styles = suggestionStyle(
            SettingsManager.getEffectiveKeyboardTheme(context, SettingsManager.KeyboardThemeTarget.HARDWARE)
        )
        val spec = InlinePresentationSpec.Builder(
            Size(minWidth, height),
            Size(maxOf(minWidth, metrics.widthPixels / 2), height)
        ).setStyle(styles).build()
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(MAX_SUGGESTIONS)
            .build()
    }

    /**
     * Solid chip artwork from the theme's own colours. Texture bitmaps stay out of the bundle,
     * including a whole-keyboard photo that would otherwise make every surface transparent.
     */
    /**
     * The chip setters live on [ViewStyle.BaseBuilder], which AndroidX marks library-only.
     * They are still the IME style API: the bundle they write is what autofill renders.
     */
    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.R)
    internal fun suggestionStyle(theme: SettingsManager.KeyboardThemeSettings): Bundle {
        val chipColor = solidColor(theme.suggestion, solidColor(theme.normalKey, OPAQUE_CHIP_FALLBACK))
        val textColor = theme.textAndIcons
        fun chipStyle() = ViewStyle.Builder()
            .setBackground(solidChipIcon(chipColor, theme.keyCornerRadiusRatio))
            .setPadding(CHIP_PADDING_PX, 0, CHIP_PADDING_PX, 0)
            .build()
        fun iconStyle() = ImageViewStyle.Builder().setTintList(ColorStateList.valueOf(textColor)).build()
        val title = TextViewStyle.Builder().setTextColor(textColor).setTextSize(16f).build()
        val subtitle = TextViewStyle.Builder().setTextColor(textColor).setTextSize(14f).build()
        return UiVersions.newStylesBuilder()
            .addStyle(
                InlineSuggestionUi.newStyleBuilder()
                    .setChipStyle(chipStyle())
                    .setSingleIconChipStyle(chipStyle())
                    .setTitleStyle(title)
                    .setSubtitleStyle(subtitle)
                    .setStartIconStyle(iconStyle())
                    .setEndIconStyle(iconStyle())
                    .setSingleIconChipIconStyle(iconStyle())
                    .build()
            )
            .build()
    }

    internal fun solidColor(color: Int, fallback: Int): Int = when {
        Color.alpha(color) != 0 -> color
        Color.alpha(fallback) != 0 -> fallback
        else -> OPAQUE_CHIP_FALLBACK
    }

    private fun solidChipIcon(color: Int, cornerRadiusRatio: Float): Icon {
        val width = 48
        val height = 36
        val radius = (height * cornerRadiusRatio.coerceIn(0f, 0.5f)).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                bitmap.setPixel(x, y, if (outsideRoundedCorner(x, y, width, height, radius)) Color.TRANSPARENT else color)
            }
        }
        return Icon.createWithBitmap(bitmap)
    }

    private fun outsideRoundedCorner(x: Int, y: Int, width: Int, height: Int, radius: Int): Boolean {
        if (radius <= 0) return false
        val centerX = when {
            x < radius -> radius
            x >= width - radius -> width - 1 - radius
            else -> return false
        }
        val centerY = when {
            y < radius -> radius
            y >= height - radius -> height - 1 - radius
            else -> return false
        }
        val dx = x - centerX
        val dy = y - centerY
        return dx * dx + dy * dy > radius * radius
    }

    private const val CHIP_PADDING_PX = 12
    private const val OPAQUE_CHIP_FALLBACK = 0xFF2B3138.toInt()
}
