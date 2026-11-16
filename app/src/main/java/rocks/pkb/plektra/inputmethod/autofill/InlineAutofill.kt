package rocks.pkb.plektra.inputmethod.autofill

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.util.TypedValue
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
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
        val styles = UiVersions.newStylesBuilder()
            .addStyle(InlineSuggestionUi.newStyleBuilder().build())
            .build()
        val spec = InlinePresentationSpec.Builder(
            Size(minWidth, height),
            Size(maxOf(minWidth, metrics.widthPixels / 2), height)
        ).setStyle(styles).build()
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(MAX_SUGGESTIONS)
            .build()
    }
}
