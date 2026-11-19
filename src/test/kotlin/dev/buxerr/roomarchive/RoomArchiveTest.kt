package dev.buxerr.roomarchive

import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec

object RoomArchiveTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val directory = Files.createTempDirectory("room-archive-test-").toFile()
        try {
            roundTripAndPreview(directory)
            rejectsWrongKeysAndTampering(directory)
            enforcesLimitsAndDoesNotOverwrite(directory)
            validatesInputs(directory)
            println("RoomArchive: 4 test groups passed")
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun roundTripAndPreview(directory: File) {
        val snapshot = File(directory, "source.db")
        val content = "SQLite format 3\u0000private-row".toByteArray() +
            ByteArray(32_000) { (it % 251).toByte() }
        snapshot.writeBytes(content)
        val archive = File(directory, "backup.rma")
        val key = RoomArchive.generateKey()
        val exported = RoomArchive.export(snapshot, archive, "main-db", 12, key, 1234L)
        check(exported.snapshotBytes == content.size.toLong())
        check(exported.schemaVersion == 12)
        check(!archive.readBytes().toString(Charsets.ISO_8859_1).contains("SQLite format"))
        val preview = RoomArchive.preview(archive, key)
        check(preview == exported)
        val plan = RoomArchive.openForRestore(archive, key)
        try {
            check(plan.info.databaseId == "main-db")
            check(plan.snapshotFile.readBytes().contentEquals(content))
        } finally {
            val temporarySnapshot = plan.snapshotFile
            plan.close()
            check(!temporarySnapshot.exists())
        }
    }

    private fun rejectsWrongKeysAndTampering(directory: File) {
        val source = File(directory, "tamper-source.db").apply { writeText("valid database snapshot") }
        val archive = File(directory, "tamper.rma")
        val key = RoomArchive.generateKey()
        RoomArchive.export(source, archive, "profile", 3, key, 99L)
        val wrongKey = RoomArchive.generateKey()
        check(runCatching { RoomArchive.preview(archive, wrongKey) }.isFailure)

        val bytes = archive.readBytes()
        bytes[bytes.lastIndex - 1] = (bytes[bytes.lastIndex - 1].toInt() xor 0x20).toByte()
        val damaged = File(directory, "damaged.rma").apply { writeBytes(bytes) }
        check(runCatching { RoomArchive.preview(damaged, key) }.isFailure)

        val tagTamperedBytes = archive.readBytes()
        tagTamperedBytes[tagTamperedBytes.lastIndex] = (tagTamperedBytes.last().toInt() xor 0x01).toByte()
        val tagTampered = File(directory, "tag-tampered.rma").apply { writeBytes(tagTamperedBytes) }
        check(runCatching { RoomArchive.preview(tagTampered, key) }.isFailure)

        val truncated = File(directory, "truncated.rma").apply { writeBytes(archive.readBytes().copyOf(archive.length().toInt() - 5)) }
        check(runCatching { RoomArchive.preview(truncated, key) }.isFailure)
    }

    private fun enforcesLimitsAndDoesNotOverwrite(directory: File) {
        val source = File(directory, "limited.db").apply { writeBytes(ByteArray(128) { 7 }) }
        val archive = File(directory, "limited.rma")
        check(runCatching { RoomArchive.export(source, archive, "db", 1, RoomArchive.generateKey(), 1L, 64) }.isFailure)
        check(!archive.exists())

        val key = RoomArchive.generateKey()
        RoomArchive.export(source, archive, "db", 1, key, 1L)
        val overwrite = runCatching { RoomArchive.export(source, archive, "db", 1, key, 1L) }
        check(overwrite.isFailure)
        check(RoomArchive.preview(archive, key).snapshotBytes == 128L)
        check(runCatching { RoomArchive.openForRestore(archive, key, maxSnapshotBytes = 64) }.isFailure)
    }

    private fun validatesInputs(directory: File) {
        val source = File(directory, "invalid.db").apply { writeText("db") }
        check(runCatching { RoomArchive.keyFromBytes(ByteArray(10)) }.isFailure)
        check(runCatching {
            RoomArchive.export(source, File(directory, "bad-id.rma"), "bad/id", 0, RoomArchive.generateKey(), 0L)
        }.isFailure)
        check(runCatching {
            RoomArchive.export(source, File(directory, "bad-version.rma"), "db", -1, RoomArchive.generateKey(), 0L)
        }.isFailure)
        check(runCatching {
            RoomArchive.export(source, source, "db", 0, RoomArchive.generateKey(), 0L)
        }.isFailure)
        val wrongAlgorithm = SecretKeySpec(ByteArray(16), "DES")
        check(runCatching {
            RoomArchive.export(source, File(directory, "wrong-key.rma"), "db", 0, wrongAlgorithm, 0L)
        }.isFailure)
    }
}
