package rocks.pkb.plektra.core.suggestions

import android.view.KeyEvent
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** An excluded word (an inserted one-time code) is never offered, learned or predicted from. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SuggestionControllerExcludedWordTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val store = UserNGramStore(RuntimeEnvironment.getApplication()).apply { clearAll() }
    private val repository = FakeDictionaryRepository().apply { isReady = true; addTestEntry("bar", 200) }
    private val controller = SuggestionController(
        context = context,
        assets = context.assets,
        settingsProvider = { SuggestionSettings(suggestionsEnabled = true, maxSuggestions = 3) },
        isExcludedWord = { it == CODE },
        onSuggestionsUpdated = { },
        currentLocale = Locale.GERMANY,
        dictionaryRepositoryFactory = { _, _, _, _, _ -> repository },
        nextWordPredictorOverride = NextWordPredictor(store)
    )

    @After
    fun tearDown() {
        controller.flushNextWordLearningForTests()
        controller.destroy()
        store.clearAll()
        store.close()
    }

    @Test
    fun excludedWordIsNeverOfferedOrLearned() {
        for (sentence in listOf(listOf(CODE, "bin"), listOf("ich", CODE), listOf("ich", CODE, "bin"))) {
            sentence.forEach { word ->
                word.forEach { controller.onCharacterCommitted(it.toString(), null) }
                if (word == CODE) assertNull("add-word offered for the code", controller.pendingAddWord())
                controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, null)
            }
        }
        controller.flushNextWordLearningForTests()
        val learned = listOf("__sentence_start__", "ich", CODE).flatMap { prefix ->
            store.predict(Locale.GERMANY.toLanguageTag(), prefix, 10).map { "$prefix -> ${it.word}" }
        }
        assertTrue("learned $learned", learned.none { CODE in it })
        assertEquals(emptyList<String>(), store.predict(Locale.GERMANY.toLanguageTag(), CODE, 10).map { it.word })
    }

    private companion object {
        const val CODE = "482913"
    }
}
