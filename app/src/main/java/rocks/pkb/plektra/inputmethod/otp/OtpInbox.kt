package rocks.pkb.plektra.inputmethod.otp

import java.security.MessageDigest

internal enum class OtpSource { SMS, NOTIFICATION, HELPER }

/** Main-thread, memory-only inbox. Remember fingerprints (not used/replaced plaintext) for dedupe. */
internal class OtpInbox(private val enabled: (OtpSource) -> Boolean, private val now: () -> Long) {
    class Entry(val code: String, val expiresAt: Long, source: OtpSource) {
        val sources = mutableSetOf(source)
    }

    private val seen = mutableMapOf<List<Byte>, Long>()
    private var active: Entry? = null

    fun current(): Entry? {
        val time = now()
        seen.values.removeAll { it <= time }
        active?.sources?.retainAll { enabled(it) }
        if (active?.let { it.expiresAt <= time || it.sources.isEmpty() } == true) active = null
        return active
    }

    fun offer(source: OtpSource, code: String): Boolean {
        if (!enabled(source) || !validCode.matches(code)) return false
        val previous = current()
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(code.toByteArray()).toList()
        if (seen.containsKey(fingerprint)) {
            if (previous?.code == code) previous.sources += source
            return false
        }
        val expiresAt = now() + WINDOW_MS
        if (seen.size >= MAX_RECENT) seen.remove(seen.keys.first())
        seen[fingerprint] = expiresAt
        active = Entry(code, expiresAt, source)
        return true
    }

    fun use(entry: Entry, commit: (String) -> Boolean): Boolean {
        if (current() !== entry || !commit(entry.code)) return false
        if (active === entry) active = null
        return true
    }

    fun nextExpiry(): Long? = seen.values.minOrNull()

    private companion object {
        const val WINDOW_MS = 120_000L
        const val MAX_RECENT = 64
        val validCode = Regex("[a-zA-Z0-9]{4,10}")
    }
}
