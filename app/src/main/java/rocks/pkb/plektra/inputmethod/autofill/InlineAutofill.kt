package rocks.pkb.plektra.inputmethod.autofill

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.util.TypedValue
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.ui.KeyboardThemeColors

/**
 * Android's IME inline autofill: password managers render their own suggestions as opaque views
 * that the keyboard bar hosts. Plektra never sees the suggested values.
 */
internal object InlineAutofill {
    /** Android recommends at most five for performance. */
    const val MAX_SUGGESTIONS = 4
    private const val CHIP_MIN_WIDTH_DP = 48f
    private const val CHIP_MAX_SCREEN_WIDTH_FRACTION = 2f / 3f
    private val WRAP_CONTENT = Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /**
     * Autofill hides its own dropdown as soon as the IME returns a request, and ignores what the
     * IME answers to the response, so a request is offered only when a bar host can draw the chips.
     */
    fun offersRequest(sdkInt: Int, enabled: Boolean, hostAvailable: Boolean): Boolean =
        sdkInt >= Build.VERSION_CODES.R && enabled && hostAvailable

    /** [chipHeightPx] is the height of the bar row that would host the chips, null when none is on screen. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun createRequest(
        context: Context,
        uiExtras: Bundle,
        chipHeightPx: Int?,
        sdkInt: Int = Build.VERSION.SDK_INT,
        theme: () -> KeyboardThemeColors
    ): InlineSuggestionsRequest? {
        if (!offersRequest(sdkInt, SettingsManager.getInlineAutofillEnabled(context), chipHeightPx != null)) return null
        if (UiVersions.INLINE_UI_VERSION_1 !in UiVersions.getVersions(uiExtras)) return null
        return buildRequest(context, theme(), chipHeightPx!!)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    internal fun buildRequest(context: Context, theme: KeyboardThemeColors, chipHeightPx: Int): InlineSuggestionsRequest {
        val metrics = context.resources.displayMetrics
        val minWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, CHIP_MIN_WIDTH_DP, metrics).toInt()
        val maxWidth = maxOf(minWidth, (metrics.widthPixels * CHIP_MAX_SCREEN_WIDTH_FRACTION).toInt())
        val spec = InlinePresentationSpec.Builder(Size(minWidth, chipHeightPx), Size(maxWidth, chipHeightPx))
            .setStyle(InlineAutofillStyle.build(context, theme))
            .build()
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(MAX_SUGGESTIONS)
            .build()
    }

    /** Only the pinned flag is read; the content stays with the autofill service. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun pendingChips(context: Context, suggestions: List<InlineSuggestion>): List<PendingInlineChip> =
        suggestions.map { suggestion ->
            PendingInlineChip(suggestion.info.isPinned) { done ->
                suggestion.inflate(context, WRAP_CONTENT, context.mainExecutor) { view -> done(view) }
            }
        }
}
