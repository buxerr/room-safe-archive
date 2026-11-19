package dev.buxerr.roomarchive

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.nio.file.Files

class ArchiveException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class ArchiveInfo(
    val databaseId: String,
    val schemaVersion: Int,
    val createdAtEpochMillis: Long,
    val snapshotBytes: Long,
    val sha256: String,
)

class RestorePlan internal constructor(
    val snapshotFile: File,
    val info: ArchiveInfo,
) : AutoCloseable {
    override fun close() {
        if (snapshotFile.exists() && !snapshotFile.delete()) {
            throw ArchiveException("Could not delete the temporary restore snapshot.")
        }
    }
}

/**
 * Encrypted archive format RMA1. The caller supplies a consistent SQLite snapshot;
 * this class never reads or mutates a live Room database.
 */
object RoomArchive {
    private val MAGIC = byteArrayOf('R'.code.toByte(), 'M'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte())
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val MAX_MANIFEST_BYTES = 4096
    private val random = SecureRandom()
    private val databaseIdPattern = Regex("[A-Za-z0-9._-]{1,128}")
    private val manifestPattern = Regex(
        """^\{"formatVersion":1,"databaseId":"([A-Za-z0-9._-]{1,128})","schemaVersion":(0|[1-9][0-9]*),"createdAtEpochMillis":(0|[1-9][0-9]*),"snapshotBytes":([1-9][0-9]*),"sha256":"([0-9a-f]{64})"\}$"""
    )

    fun generateKey(): SecretKey {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return SecretKeySpec(bytes, "AES")
    }

    fun keyFromBytes(bytes: ByteArray): SecretKey {
        require(bytes.size == 16 || bytes.size == 24 || bytes.size == 32) {
            "AES keys must contain 16, 24, or 32 bytes."
        }
        return SecretKeySpec(bytes.copyOf(), "AES")
    }

    fun export(
        snapshotFile: File,
        destination: File,
        databaseId: String,
        schemaVersion: Int,
        key: SecretKey,
        createdAtEpochMillis: Long = System.currentTimeMillis(),
        maxSnapshotBytes: Long = 1024L * 1024 * 1024,
    ): ArchiveInfo {
        validateMetadata(databaseId, schemaVersion, createdAtEpochMillis)
        validateKey(key)
        require(maxSnapshotBytes > 0) { "maxSnapshotBytes must be positive." }
        if (!snapshotFile.isFile || !snapshotFile.canRead()) {
            throw ArchiveException("Snapshot file is missing or unreadable.")
        }
        if (snapshotFile.canonicalFile == destination.canonicalFile) {
            throw ArchiveException("Archive destination cannot be the snapshot file.")
        }
        if (destination.exists()) {
            throw ArchiveException("Archive destination already exists.")
        }
        val parent = destination.absoluteFile.parentFile
            ?: throw ArchiveException("Archive destination has no parent directory.")
        if (!parent.isDirectory || !parent.canWrite()) {
            throw ArchiveException("Archive destination directory is not writable.")
        }
        val snapshotLength = snapshotFile.length()
        if (snapshotLength <= 0 || snapshotLength > maxSnapshotBytes) {
            throw ArchiveException("Snapshot size is outside the configured limit.")
        }

        val expectedHash = digestFile(snapshotFile, maxSnapshotBytes)
        val info = ArchiveInfo(databaseId, schemaVersion, createdAtEpochMillis, snapshotLength, expectedHash)
        val manifest = encodeManifest(info)
        val part = File(parent, destination.name + ".part-" + UUID.randomUUID())
        try {
            writeEncrypted(snapshotFile, part, manifest, key, expectedHash, snapshotLength, maxSnapshotBytes)
            try {
                Files.move(part.toPath(), destination.toPath())
            } catch (error: Exception) {
                throw ArchiveException("Could not safely publish the archive file.", error)
            }
            return info
        } finally {
            if (part.exists()) part.delete()
        }
    }

    fun preview(
        archiveFile: File,
        key: SecretKey,
        maxArchiveBytes: Long = 1024L * 1024 * 1024 + 1024 * 1024,
        maxSnapshotBytes: Long = 1024L * 1024 * 1024,
    ): ArchiveInfo = openForRestore(archiveFile, key, maxArchiveBytes, maxSnapshotBytes).use { it.info }

    /**
     * Decrypts and validates the complete archive before returning a temporary
     * snapshot. Close the plan promptly after passing the file to the app's restore flow.
     */
    fun openForRestore(
        archiveFile: File,
        key: SecretKey,
        maxArchiveBytes: Long = 1024L * 1024 * 1024 + 1024 * 1024,
        maxSnapshotBytes: Long = 1024L * 1024 * 1024,
    ): RestorePlan {
        validateKey(key)
        require(maxArchiveBytes > MAGIC.size + NONCE_BYTES + TAG_BITS / 8) {
            "maxArchiveBytes is too small."
        }
        require(maxSnapshotBytes > 0) { "maxSnapshotBytes must be positive." }
        if (!archiveFile.isFile || !archiveFile.canRead()) {
            throw ArchiveException("Archive file is missing or unreadable.")
        }
        if (archiveFile.length() > maxArchiveBytes) {
            throw ArchiveException("Archive exceeds the configured size limit.")
        }
        val snapshotTemp = File.createTempFile("room-restore-", ".sqlite")
        try {
            val info = decryptAndValidate(archiveFile, snapshotTemp, key, maxSnapshotBytes)
            return RestorePlan(snapshotTemp, info)
        } catch (error: Exception) {
            snapshotTemp.delete()
            if (error is ArchiveException) throw error
            throw ArchiveException("Archive could not be authenticated or decoded.", error)
        }
    }

    private fun writeEncrypted(
        snapshotFile: File,
        part: File,
        manifest: ByteArray,
        key: SecretKey,
        expectedHash: String,
        expectedBytes: Long,
        maxSnapshotBytes: Long,
    ) {
        val nonce = ByteArray(NONCE_BYTES)
        random.nextBytes(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(MAGIC)
        val copiedDigest = MessageDigest.getInstance("SHA-256")
        var copiedBytes = 0L

        FileOutputStream(part).use { raw ->
            raw.write(MAGIC)
            raw.write(nonce)
            CipherOutputStream(raw, cipher).use { encrypted ->
                encrypted.write(intBytes(manifest.size))
                encrypted.write(manifest)
                FileInputStream(snapshotFile).use { source ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        copiedBytes += count
                        if (copiedBytes > maxSnapshotBytes) {
                            throw ArchiveException("Snapshot changed beyond the configured size limit while archiving.")
                        }
                        copiedDigest.update(buffer, 0, count)
                        encrypted.write(buffer, 0, count)
                    }
                }
                if (copiedBytes != expectedBytes || hex(copiedDigest.digest()) != expectedHash) {
                    throw ArchiveException("Snapshot changed while the archive was being written.")
                }
            }
        }
    }

    private fun decryptAndValidate(
        archiveFile: File,
        snapshotTemp: File,
        key: SecretKey,
        maxSnapshotBytes: Long,
    ): ArchiveInfo {
        BufferedInputStream(FileInputStream(archiveFile)).use { raw ->
            val magic = ByteArray(MAGIC.size)
            readFully(raw, magic)
            if (!magic.contentEquals(MAGIC)) throw ArchiveException("Unknown archive format or version.")
            val nonce = ByteArray(NONCE_BYTES)
            readFully(raw, nonce)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(MAGIC)
            val prefix = ByteArrayOutputStream(MAX_MANIFEST_BYTES + 4)
            val digest = MessageDigest.getInstance("SHA-256")
            var info: ArchiveInfo? = null
            var output: FileOutputStream? = null
            var snapshotBytesWritten = 0L

            fun acceptPlaintext(cleartext: ByteArray) {
                var offset = 0
                while (info == null && offset < cleartext.size) {
                    val prefixSize = prefix.size()
                    val requiredSize = if (prefixSize < 4) {
                        4
                    } else {
                        val length = bytesInt(prefix.toByteArray().copyOfRange(0, 4))
                        if (length !in 1..MAX_MANIFEST_BYTES) {
                            throw ArchiveException("Archive manifest length is invalid.")
                        }
                        4 + length
                    }
                    val count = minOf(requiredSize - prefixSize, cleartext.size - offset)
                    prefix.write(cleartext, offset, count)
                    offset += count
                    if (prefix.size() == requiredSize && requiredSize > 4) {
                        val bytes = prefix.toByteArray()
                        val parsed = parseManifest(bytes.copyOfRange(4, bytes.size))
                        if (parsed.snapshotBytes > maxSnapshotBytes) {
                            throw ArchiveException("Snapshot exceeds the configured size limit.")
                        }
                        info = parsed
                        output = FileOutputStream(snapshotTemp)
                        prefix.reset()
                    }
                }
                val parsed = info
                if (parsed != null && offset < cleartext.size) {
                    val count = cleartext.size - offset
                    if (count.toLong() > parsed.snapshotBytes - snapshotBytesWritten) {
                        throw ArchiveException("Archive contains unexpected trailing data.")
                    }
                    val sink = output ?: throw ArchiveException("Restore snapshot output is unavailable.")
                    sink.write(cleartext, offset, count)
                    digest.update(cleartext, offset, count)
                    snapshotBytesWritten += count
                }
            }

            val encryptedBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
            try {
                while (true) {
                    val count = raw.read(encryptedBuffer)
                    if (count < 0) break
                    val cleartext = cipher.update(encryptedBuffer, 0, count)
                    if (cleartext != null && cleartext.isNotEmpty()) acceptPlaintext(cleartext)
                }
                val finalCleartext = cipher.doFinal()
                if (finalCleartext.isNotEmpty()) acceptPlaintext(finalCleartext)
            } finally {
                output?.close()
            }

            val parsed = info ?: throw ArchiveException("Archive manifest is truncated.")
            if (snapshotBytesWritten != parsed.snapshotBytes) {
                throw ArchiveException("Archive snapshot is truncated.")
            }
            if (!MessageDigest.isEqual(hexBytes(parsed.sha256), digest.digest())) {
                throw ArchiveException("Snapshot checksum does not match the manifest.")
            }
            return parsed
        }
    }

    private fun encodeManifest(info: ArchiveInfo): ByteArray {
        val text = "{\"formatVersion\":1,\"databaseId\":\"" + info.databaseId +
            "\",\"schemaVersion\":" + info.schemaVersion +
            ",\"createdAtEpochMillis\":" + info.createdAtEpochMillis +
            ",\"snapshotBytes\":" + info.snapshotBytes +
            ",\"sha256\":\"" + info.sha256 + "\"}"
        return text.toByteArray(Charsets.UTF_8).also {
            if (it.size > MAX_MANIFEST_BYTES) throw ArchiveException("Archive manifest is too large.")
        }
    }

    private fun parseManifest(bytes: ByteArray): ArchiveInfo {
        val text = String(bytes, Charsets.UTF_8)
        val match = manifestPattern.matchEntire(text)
            ?: throw ArchiveException("Archive manifest is malformed or unsupported.")
        val schemaVersion = match.groupValues[2].toIntOrNull()
            ?: throw ArchiveException("Archive schema version is outside the supported range.")
        val createdAt = match.groupValues[3].toLongOrNull()
            ?: throw ArchiveException("Archive timestamp is outside the supported range.")
        val snapshotBytes = match.groupValues[4].toLongOrNull()
            ?: throw ArchiveException("Archive snapshot size is outside the supported range.")
        if (createdAt < 0 || snapshotBytes <= 0) throw ArchiveException("Archive metadata is invalid.")
        return ArchiveInfo(match.groupValues[1], schemaVersion, createdAt, snapshotBytes, match.groupValues[5])
    }

    private fun validateMetadata(databaseId: String, schemaVersion: Int, createdAtEpochMillis: Long) {
        if (!databaseIdPattern.matches(databaseId)) {
            throw IllegalArgumentException("databaseId may contain only letters, digits, dot, underscore, and dash.")
        }
        require(schemaVersion >= 0) { "schemaVersion must not be negative." }
        require(createdAtEpochMillis >= 0) { "createdAtEpochMillis must not be negative." }
    }

    private fun validateKey(key: SecretKey) {
        if (!key.algorithm.equals("AES", ignoreCase = true)) {
            throw IllegalArgumentException("Archive encryption requires an AES key.")
        }
        val length = key.encoded?.size ?: throw IllegalArgumentException("AES key must be exportable to the cipher.")
        require(length == 16 || length == 24 || length == 32) { "AES keys must contain 16, 24, or 32 bytes." }
    }

    private fun digestFile(file: File, maxBytes: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                totalBytes += count
                if (totalBytes > maxBytes) {
                    throw ArchiveException("Snapshot changed beyond the configured size limit while hashing.")
                }
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    private fun readFully(input: java.io.InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw ArchiveException("Archive is truncated.")
            offset += count
        }
    }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun bytesInt(bytes: ByteArray): Int =
        ((bytes[0].toInt() and 0xff) shl 24) or
            ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or
            (bytes[3].toInt() and 0xff)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun hexBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
