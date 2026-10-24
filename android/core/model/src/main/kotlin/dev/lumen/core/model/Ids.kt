package dev.lumen.core.model

import java.security.SecureRandom
import kotlinx.serialization.Serializable

/*
 * The bit arithmetic of the Crockford base32 encoder, named once at file scope
 * so that the encoder and the 80-bit increment of [UlidGenerator] read against
 * the same vocabulary. The values are fixed by the ULID format, not chosen.
 */

/** A base32 character carries five bits. */
private const val BITS_PER_CHAR: Int = 5

/** The five bits a single base32 character holds. */
private const val CHAR_MASK: Int = 0x1F

/** The same mask for the 64-bit timestamp, which is shifted as a Long. */
private const val TIMESTAMP_CHAR_MASK: Long = 0x1FL

/** A random byte contributes eight bits to the accumulator. */
private const val BITS_PER_BYTE: Int = 8

/** The unsigned value of one byte. */
private const val BYTE_MASK: Int = 0xFF

/** The characters the 48-bit timestamp occupies. */
private const val TIMESTAMP_CHARS: Int = 10

/** The characters the 80 random bits occupy. */
private const val RANDOM_CHARS: Int = 16

/** The index of the first random character, right after the timestamp prefix. */
private const val RANDOM_START: Int = TIMESTAMP_CHARS

/**
 * A 26-character ULID: a 48-bit millisecond timestamp followed by 80 bits of
 * randomness, encoded with Crockford's base32 alphabet.
 *
 * ULIDs are used for every identifier in the system because they sort
 * lexicographically by creation time, which makes a cursor-paginated event
 * stream and a time-ordered step list the same thing (invariant I8).
 */
public object Ulid {

    /** Crockford's base32 alphabet. Excludes I, L, O and U to avoid ambiguity. */
    public const val ENCODING: String = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** The number of characters in a ULID. */
    public const val LENGTH: Int = 26

    /** The number of random bytes in a ULID. */
    public const val RANDOM_BYTES: Int = 10

    /** The largest timestamp a ULID can carry: 2^48 - 1 milliseconds. */
    public const val MAX_TIMESTAMP: Long = 281_474_976_710_655L

    /**
     * Encodes a timestamp and 10 random bytes into a ULID.
     *
     * @param timestampMs the creation time in Unix epoch milliseconds
     * @param randomness exactly [RANDOM_BYTES] bytes of entropy
     */
    public fun encode(timestampMs: Long, randomness: ByteArray): String {
        require(randomness.size == RANDOM_BYTES) {
            "a ULID needs exactly $RANDOM_BYTES random bytes, got ${randomness.size}"
        }
        require(timestampMs in 0..MAX_TIMESTAMP) {
            "timestamp $timestampMs is outside the ULID range"
        }

        val chars = CharArray(LENGTH)

        var timestamp = timestampMs
        for (index in TIMESTAMP_CHARS - 1 downTo 0) {
            chars[index] = ENCODING[(timestamp and TIMESTAMP_CHAR_MASK).toInt()]
            timestamp = timestamp ushr BITS_PER_CHAR
        }

        var bits = 0
        var accumulator = 0
        var source = 0
        for (index in 0 until RANDOM_CHARS) {
            if (bits < BITS_PER_CHAR) {
                accumulator = (accumulator shl BITS_PER_BYTE) or (randomness[source].toInt() and BYTE_MASK)
                source++
                bits += BITS_PER_BYTE
            }
            bits -= BITS_PER_CHAR
            chars[RANDOM_START + index] = ENCODING[(accumulator ushr bits) and CHAR_MASK]
        }

        return String(chars)
    }

    /**
     * Whether [value] is a syntactically valid ULID.
     *
     * The check is deliberately cheap: it runs on every identifier that arrives
     * over IPC, so it must not allocate.
     */
    public fun isValid(value: String): Boolean {
        if (value.length != LENGTH) return false
        return value.all { character -> ENCODING.indexOf(character) >= 0 }
    }

    /**
     * The creation timestamp encoded in [value], or `null` when the value is
     * not a valid ULID.
     */
    public fun timestampOf(value: String): Long? {
        if (!isValid(value)) return null
        var timestamp = 0L
        for (index in 0 until TIMESTAMP_CHARS) {
            val digit = ENCODING.indexOf(value[index])
            if (digit < 0) return null
            timestamp = (timestamp shl BITS_PER_CHAR) or digit.toLong()
        }
        return timestamp
    }
}

/** A source of random bytes. Injectable so that id generation is testable. */
public fun interface RandomSource {
    /** Fills [destination] with random bytes. */
    public fun nextBytes(destination: ByteArray)
}

/** The default [RandomSource], backed by the platform's secure random. */
public object SecureRandomSource : RandomSource {
    private val secureRandom = SecureRandom()

    override fun nextBytes(destination: ByteArray) {
        secureRandom.nextBytes(destination)
    }
}

/**
 * Generates ULIDs that are strictly increasing within a single millisecond.
 *
 * Two ULIDs created in the same millisecond must still sort correctly, so the
 * random component is incremented rather than redrawn. The increment is a
 * 80-bit big-endian addition with carry.
 */
public class UlidGenerator(private val randomSource: RandomSource = SecureRandomSource, private val clock: LumenClock = SystemLumenClock) {
    private var lastTimestamp: Long = -1
    private var lastRandomness: ByteArray = ByteArray(Ulid.RANDOM_BYTES)

    /** Generates the next identifier. */
    public fun next(): String {
        val now = clock.nowMs()
        if (now > lastTimestamp) {
            lastTimestamp = now
            lastRandomness = ByteArray(Ulid.RANDOM_BYTES).also(randomSource::nextBytes)
        } else {
            val incremented = increment(lastRandomness)
            // The 80-bit counter cannot realistically overflow, but if it ever
            // does, the sequence restarts from fresh entropy rather than
            // silently repeating an earlier value.
            lastRandomness = incremented ?: ByteArray(Ulid.RANDOM_BYTES).also(randomSource::nextBytes)
        }
        return Ulid.encode(lastTimestamp, lastRandomness)
    }

    /** Adds one to an 80-bit big-endian value, or returns null on overflow. */
    private fun increment(value: ByteArray): ByteArray? {
        for (index in value.lastIndex downTo 0) {
            val next = (value[index].toInt() and BYTE_MASK) + 1
            value[index] = (next and BYTE_MASK).toByte()
            if (next <= BYTE_MASK) return value
        }
        return null
    }
}

/** A run identifier. */
@JvmInline
@Serializable
public value class RunId(public val value: String) {
    init {
        require(Ulid.isValid(value)) { "'$value' is not a valid run id" }
    }

    override fun toString(): String = value
}

/** A step identifier. */
@JvmInline
@Serializable
public value class StepId(public val value: String) {
    init {
        require(Ulid.isValid(value)) { "'$value' is not a valid step id" }
    }

    override fun toString(): String = value
}

/** An approval identifier. */
@JvmInline
@Serializable
public value class ApprovalId(public val value: String) {
    init {
        require(Ulid.isValid(value)) { "'$value' is not a valid approval id" }
    }

    override fun toString(): String = value
}
