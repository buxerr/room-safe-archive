# Android Room Database Backup and Restore Archive

A dependency-free Kotlin/JVM library for encrypted, versioned archives of Android Room and SQLite database snapshots. It verifies AES-GCM authentication and a SHA-256 snapshot digest, provides restore preview, and writes a temporary validated snapshot for the host app to restore.

This is an archive layer for Room apps, not a plugin that copies a live database. Your app must create a consistent SQLite snapshot and own the Room lifecycle, database replacement, migration, and rollback steps.

## Build from source

The library uses Kotlin standard-library and JDK crypto/file APIs, requires JDK 21, and has no Android dependency. From a checkout, run:

```sh
git clone https://github.com/buxerr/room-safe-archive.git
cd room-safe-archive
./gradlew check
```

On Windows, use `gradlew.bat check`.

## Export an encrypted Room snapshot

1. Ask Room or SQLite for a consistent snapshot using the lifecycle and checkpoint procedure supported by your app. In WAL mode, copying only the main database file can omit committed data that is still in the WAL.
2. Call `RoomArchive.export(snapshotFile, archiveFile, databaseId, schemaVersion, key)`.
3. Store the AES key separately from the archive. `RoomArchive.generateKey()` creates a random 256-bit key; wrap or store it with your app's platform key-management design.

## Preview and restore

Call `RoomArchive.openForRestore(archiveFile, key)` after the user selects an archive. The library verifies the GCM tag and snapshot digest before returning a `RestorePlan`. Show `plan.info` as a preview, pass `plan.snapshotFile` into the app's restore and migration flow, and close the plan promptly.

The validated snapshot is temporary plaintext. Protect its temporary directory and do not expose it to other apps. The archive library never replaces the live Room database.

## Archive format and limits

RMA1 contains a fixed header and an encrypted manifest-plus-snapshot payload. The manifest records archive format, database ID, schema version, creation time, byte length, and SHA-256 digest. The default snapshot limit is 1 GiB and callers can lower it. Keys are supplied by the application; passwords are not accepted or derived.

For background on database snapshot handling, see [SQLite write-ahead logging](https://www.sqlite.org/wal.html) and the [Room persistence library](https://developer.android.com/training/data-storage/room).

## Scope and limitations

This is an initial JVM archive core, not a drop-in Android Room plugin. It does not provide Room builder integration, Android Keystore wrappers, cloud sync, or automatic migrations. A consistent source snapshot and an app-specific restore strategy remain the caller's responsibility.

## License

MIT. See [LICENSE](LICENSE).
