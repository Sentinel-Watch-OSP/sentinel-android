package com.samourai.sentinel.data.db

import com.google.gson.reflect.TypeToken
import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.helpers.fromJSON
import com.samourai.sentinel.helpers.toJSON
import com.samourai.sentinel.ui.utils.logThreadInfo
import com.samourai.wallet.crypto.AESUtil
import com.samourai.wallet.util.CharSequenceX
import timber.log.Timber
import java.io.File

/**
 * Raised when an existing payload file could not be decrypted or parsed.
 *
 * This is deliberately distinct from "the payload is empty". Conflating the two
 * was the root cause of wallets being silently erased: a failed decrypt looked
 * exactly like "the user has zero collections", and that empty state was then
 * written back over the real data.
 */
class PayloadReadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * For managing encrypted and non encrypted app payload files
 * this includes info like collections and its related pubKeys
 * Class itself will handle encryption and decryption when the pin code is set
 */
class PayloadRecord(private val location: String, val name: String) {

    val file = File("${this.location}${File.separatorChar}$name")

    /** Rotated copy of the last known-good payload, used for recovery. */
    private val backupFile = File("${this.location}${File.separatorChar}$name.bak")

    private val tempFile = File("${this.location}${File.separatorChar}$name.tmp")

    inline fun <reified T> write(value: T, replace: Boolean = false) {
        logThreadInfo("Record write")
        if (file.exists()) {
            if (replace) {
                val data = this.read<T>()
                if (data is ArrayList<*>) {
                    (data as ArrayList<*>).addAll(value as Collection<Nothing>)
                    writeToFile(data.toJSON().toString())
                } else {
                    if (value != null) {
                        writeToFile(value.toJSON().toString())
                    }
                }
            } else {
                writeToFile(value?.toJSON().toString())
            }
        } else {
            if (value != null) {
                writeToFile(value.toJSON().toString())
            }
        }
    }

    /**
     * Lenient read. Returns null both when the file is absent and when it cannot
     * be decoded.
     *
     * Prefer [readOrThrow] for any caller that would otherwise treat null as
     * "empty" and persist that assumption.
     */
    inline fun <reified T> read(): T? {
        val itemType = object : TypeToken<T>() {}.type
        return if (file.exists()) {
            try {
                val string = decrypt(file.readText())
                return fromJSON<T>(string, itemType)
            } catch (e: Exception) {
                Timber.e(e)
                null
            }
        } else {
            null
        }
    }

    /**
     * Strict read.
     *
     * - Returns null only when no payload has ever been written.
     * - Throws [PayloadReadException] when a payload exists but cannot be
     *   decrypted or parsed, after attempting to fall back to the `.bak` copy.
     */
    inline fun <reified T> readOrThrow(): T? {
        val itemType = object : TypeToken<T>() {}.type
        if (!file.exists()) return null

        val raw = try {
            file.readText()
        } catch (e: Exception) {
            throw PayloadReadException("Unable to read $name", e)
        }

        // An empty file is treated as corrupt rather than as "no data", since a
        // successful write always produces at least a JSON literal.
        if (raw.isNotBlank()) {
            try {
                val decrypted = decrypt(raw)
                val parsed = fromJSON<T>(decrypted, itemType)
                if (parsed != null) return parsed
            } catch (e: Exception) {
                Timber.e(e, "Primary payload $name unreadable, attempting backup")
            }
        }

        // Primary is unusable. Try the last known-good backup before giving up.
        val recovered = tryReadBackup<T>(itemType)
        if (recovered != null) {
            Timber.w("Recovered $name from backup")
            return recovered
        }

        throw PayloadReadException(
            "Payload $name exists but could not be decrypted or parsed. " +
                "Refusing to treat this as empty data."
        )
    }

    /** @suppress internal helper for [readOrThrow] */
    inline fun <reified T> tryReadBackup(itemType: java.lang.reflect.Type): T? {
        val backup = File("${location()}${File.separatorChar}$name.bak")
        if (!backup.exists()) return null
        return try {
            fromJSON<T>(decrypt(backup.readText()), itemType)
        } catch (e: Exception) {
            Timber.e(e, "Backup for $name also unreadable")
            null
        }
    }

    /** @suppress exposes the private location for inline functions */
    fun location(): String = location

    /**
     * Atomically persists [value].
     *
     * Writes to a temp file, then renames over the target. `File.writeText`
     * truncates in place, so a failure partway through a direct write could
     * leave a truncated or empty payload - which previously presented as
     * "all wallets deleted".
     */
    fun writeToFile(value: String) {
        val encrypted = encrypt(value)

        if (!file.parentFile.exists()) {
            file.parentFile.mkdirs()
        }

        // Preserve the current good payload before we touch anything.
        if (file.exists() && file.length() > 0) {
            try {
                file.copyTo(backupFile, overwrite = true)
            } catch (e: Exception) {
                Timber.e(e, "Could not refresh backup for $name")
            }
        }

        try {
            tempFile.writeText(encrypted)

            // Verify the temp file is readable before we let it replace the original.
            if (tempFile.readText() != encrypted) {
                throw PayloadReadException("Verification of staged write for $name failed")
            }

            if (!tempFile.renameTo(file)) {
                // renameTo can fail across some filesystems; fall back to a copy.
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw PayloadReadException("Unable to write $name", e)
        }
    }

    private fun encrypt(value: String): String {
        val pin = AccessFactory.getInstance(null).pin
        if (!pin.isNullOrEmpty()) {
            return AESUtil.encryptSHA256(value, CharSequenceX(pin), AESUtil.DefaultPBKDF2HMACSHA256Iterations)
        }
        // Writing plaintext while a PIN is configured produces a file that can
        // never be decrypted on the next launch. Fail loudly instead.
        if (AccessFactory.getInstance(null).isPinProtected) {
            throw PayloadReadException(
                "Refusing to write $name unencrypted while a PIN is configured"
            )
        }
        return value
    }

    fun decrypt(value: String): String {
        val pin = AccessFactory.getInstance(null).pin

        if (!pin.isNullOrEmpty()) {
            return AESUtil.decryptSHA256(value, CharSequenceX(pin), AESUtil.DefaultPBKDF2HMACSHA256Iterations)
        }

        return value
    }

}
