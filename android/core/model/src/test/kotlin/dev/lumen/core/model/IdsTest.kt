package dev.lumen.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UlidTest {

    @Test
    fun `a ulid has 26 characters from the crockford alphabet`() {
        val ulid = Ulid.encode(1_700_000_000_000L, ByteArray(Ulid.RANDOM_BYTES))

        assertThat(ulid).hasLength(Ulid.LENGTH)
        assertThat(Ulid.isValid(ulid)).isTrue()
    }

    @Test
    fun `a ulid carries its creation timestamp`() {
        val timestamp = 1_700_000_000_000L

        val ulid = Ulid.encode(timestamp, ByteArray(Ulid.RANDOM_BYTES) { it.toByte() })

        assertThat(Ulid.timestampOf(ulid)).isEqualTo(timestamp)
    }

    @Test
    fun `a ulid rejects the ambiguous letters`() {
        val base = "0123456789ABCDEFGHJKMNPQR"
        assertThat(base).hasLength(Ulid.LENGTH)

        listOf("I", "L", "O", "U").forEach { character ->
            val candidate = base.replaceFirst('0', character)
            assertThat(candidate).hasLength(Ulid.LENGTH)
            assertThat(Ulid.isValid(candidate)).isFalse()
        }
    }

    @Test
    fun `a ulid rejects the wrong length`() {
        assertThat(Ulid.isValid("")).isFalse()
        assertThat(Ulid.isValid("01")).isFalse()
        assertThat(Ulid.isValid("0".repeat(27))).isFalse()
    }

    @Test
    fun `a ulid rejects a random byte array of the wrong size`() {
        val failure = runCatching { Ulid.encode(0L, ByteArray(9)) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a timestamp beyond the ulid range is rejected`() {
        val failure = runCatching {
            Ulid.encode(Ulid.MAX_TIMESTAMP + 1, ByteArray(Ulid.RANDOM_BYTES))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }
}

class UlidGeneratorTest {

    @Test
    fun `identifiers generated in the same millisecond are strictly increasing`() {
        val clock = ManualClock(1_700_000_000_000L)
        val generator = UlidGenerator(randomSource = CountingRandomSource(), clock = clock)

        val identifiers = (1..1_000).map { generator.next() }

        assertThat(identifiers).containsNoDuplicates()
        assertThat(identifiers).isInOrder()
    }

    @Test
    fun `identifiers remain increasing when the clock advances`() {
        val clock = ManualClock(1_700_000_000_000L)
        val generator = UlidGenerator(randomSource = CountingRandomSource(), clock = clock)

        val first = generator.next()
        clock.advance(1)
        val second = generator.next()

        assertThat(second > first).isTrue()
    }

    @Test
    fun `an identifier carries the generation time`() {
        val clock = ManualClock(1_700_000_123_456L)
        val generator = UlidGenerator(randomSource = CountingRandomSource(), clock = clock)

        val identifier = generator.next()

        assertThat(Ulid.timestampOf(identifier)).isEqualTo(1_700_000_123_456L)
    }

    private class CountingRandomSource : RandomSource {
        private var counter = 0

        override fun nextBytes(destination: ByteArray) {
            for (index in destination.indices) {
                destination[index] = (counter and 0xFF).toByte()
            }
            counter++
        }
    }
}

class IdentifierTest {

    @Test
    fun `a run id accepts a ulid and rejects anything else`() {
        val ulid = Ulid.encode(1_700_000_000_000L, ByteArray(Ulid.RANDOM_BYTES))

        assertThat(RunId(ulid).value).isEqualTo(ulid)
        assertThat(runCatching { RunId("not-a-ulid") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a step id rejects an invalid value`() {
        assertThat(runCatching { StepId("nope") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `an approval id rejects an invalid value`() {
        assertThat(runCatching { ApprovalId("nope") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
