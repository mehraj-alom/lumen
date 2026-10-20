package dev.lumen.core.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.sqlcipher.database.SupportFactory
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The state database.
 *
 * The database holds the observation store, which contains screen content, so
 * it is encrypted at rest with SQLCipher. The passphrase never leaves the
 * Keystore: it is generated once, wrapped by a Keystore-held AES key, and
 * unwrapped on demand.
 */
@Database(
    entities = [
        RunEntity::class,
        PlanEntity::class,
        StepEntity::class,
        ObservationEntity::class,
        ApprovalEntity::class,
        BudgetLedgerEntity::class,
        SettingEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
public abstract class LumenDatabase : RoomDatabase() {
    public abstract fun runs(): RunDao
    public abstract fun plans(): PlanDao
    public abstract fun steps(): StepDao
    public abstract fun observations(): ObservationDao
    public abstract fun approvals(): ApprovalDao
    public abstract fun budgetLedger(): BudgetLedgerDao
    public abstract fun settings(): SettingsDao

    public companion object {
        /** The name of the database file inside the sandbox. */
        public const val DATABASE_NAME: String = "state/lumen.db"

        /**
         * Opens the database, encrypted.
         *
         * The passphrase is fetched from [keyProvider], which holds it wrapped
         * by a Keystore key.
         */
        public fun open(
            context: Context,
            keyProvider: DatabaseKeyProvider,
        ): LumenDatabase {
            val passphrase = keyProvider.passphrase()
            val factory = SupportFactory(passphrase, null, true)

            return Room.databaseBuilder(context, LumenDatabase::class.java, DATABASE_NAME)
                .openHelperFactory(factory)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
        }
    }
}

/**
 * The database passphrase, held by the Keystore.
 *
 * The passphrase is a random 32-byte value. On first use it is generated,
 * encrypted with a Keystore AES key, and stored in the shared preferences.
 * On every later use it is decrypted. The plaintext passphrase is never
 * written to disk and never leaves the process.
 */
public class DatabaseKeyProvider(private val context: Context) {

    /** The passphrase, generating and storing it on first use. */
    public fun passphrase(): ByteArray {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val wrapped = preferences.getString(KEY_WRAPPED, null)

        if (wrapped == null) {
            val generated = generatePassphrase()
            preferences.edit().putString(KEY_WRAPPED, wrap(generated)).apply()
            return generated
        }

        return unwrap(wrapped)
    }

    /** Removes the stored passphrase. The database becomes unreadable. */
    public fun forget(): Unit {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_WRAPPED)
            .apply()
    }

    private fun generatePassphrase(): ByteArray = ByteArray(PASSPHRASE_BYTES).also { bytes ->
        java.security.SecureRandom().nextBytes(bytes)
    }

    private fun wrap(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)

        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    private fun unwrap(wrapped: String): ByteArray {
        val bytes = Base64.decode(wrapped, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val ciphertext = bytes.copyOfRange(IV_BYTES, bytes.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    public companion object {
        private const val PREFERENCES_NAME = "lumen_database_key"
        private const val KEY_WRAPPED = "wrapped_passphrase"
        private const val KEY_ALIAS = "lumen.database.passphrase"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PASSPHRASE_BYTES = 32
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}

/** The SQLCipher open helper factory, exposed for tests. */
public fun sqlCipherFactory(passphrase: ByteArray): SupportSQLiteOpenHelper.Factory =
    SupportFactory(passphrase, null, true)
