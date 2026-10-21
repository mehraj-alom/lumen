package dev.lumen.core.brain

import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.pow

/**
 * The one retry implementation.
 *
 * Retries live here and nowhere else, so that backoff, the attempt count
 * and the decision about what is worth retrying are all in one place. An
 * ad-hoc retry loop in a caller is how a request turns into a storm.
 */
public class RetryPolicy(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    private val multiplier: Double = DEFAULT_MULTIPLIER,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    private val isRetryable: (Throwable) -> Boolean = { true },
) {
    init {
        require(maxAttempts > 0) { "there must be at least one attempt" }
        require(initialDelayMs >= 0) { "the initial delay cannot be negative" }
        require(multiplier >= 1.0) { "the backoff multiplier cannot shrink the delay" }
    }

    /**
     * Runs [block] until it succeeds or the attempts run out.
     *
     * @param block receives the attempt number, starting at one
     */
    public suspend fun <T> execute(block: suspend (attempt: Int) -> T): T {
        var attempt = 1
        var delayMs = initialDelayMs

        while (true) {
            try {
                return block(attempt)
            } catch (error: Throwable) {
                if (attempt >= maxAttempts || !isRetryable(error)) throw error
                delay(delayMs)
                delayMs = min((delayMs * multiplier).toLong(), maxDelayMs)
                attempt++
            }
        }
    }

    /** The delay before [attempt], for a test or a log line. */
    public fun delayFor(attempt: Int): Long {
        var delayMs = initialDelayMs
        repeat((attempt - 1).coerceAtLeast(0)) {
            delayMs = min((delayMs * multiplier).toLong(), maxDelayMs)
        }
        return delayMs
    }

    public companion object {
        /** The default number of attempts, including the first. */
        public const val DEFAULT_MAX_ATTEMPTS: Int = 3

        /** The default delay before the second attempt. */
        public const val DEFAULT_INITIAL_DELAY_MS: Long = 500L

        /** The default backoff multiplier. */
        public const val DEFAULT_MULTIPLIER: Double = 2.0

        /** The ceiling on the delay between attempts. */
        public const val DEFAULT_MAX_DELAY_MS: Long = 8_000L
    }
}
