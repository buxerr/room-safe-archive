# Room Safe Archive

A dependency-free Kotlin/JVM core for encrypted, versioned SQLite snapshot archives. It adds AES-GCM authenticated encryption, a bounded manifest, SHA-256 verification, restore preview, and a temporary validated snapshot file. It does not open or copy a live Room database.

## Build and test

The source uses only the Kotlin standard library and JDK crypto/file APIs. Run ./gradlew check (Windows: gradlew.bat check).

## Export flow

1. Ask Room or SQLite for a consistent snapshot using the application's supported lifecycle and checkpoint procedure. Do not copy only the main database file while WAL mode has pending data.
2. Call RoomArchive.export(snapshotFile, archiveFile, databaseId, schemaVersion, key).
3. Store the generated AES key separately from the archive. RoomArchive.generateKey creates a random 256-bit key; applications should wrap or store it using their platform's key-management design.

## Restore flow

Call RoomArchive.openForRestore after the user chooses an archive. The library verifies the complete GCM tag and snapshot digest before returning a RestorePlan. Inspect plan.info for a preview, pass plan.snapshotFile into the application's Room restore and migration flow, and close the plan promptly. The snapshot is a temporary plaintext file; protect the temporary directory and do not expose it to other apps.

The archive layer never replaces the live database. The host app owns transaction boundaries, Room lifecycle coordination, migration hooks, and rollback if restore fails.

## Format and limits

RMA1 uses a fixed header, a random 96-bit GCM nonce, and encrypted manifest-plus-snapshot payload. The manifest stores archive format, database id, schema version, creation time, byte length, and SHA-256. The default snapshot limit is 1 GiB and can be lowered by the caller. Keys are user-held application inputs; passwords are not accepted or derived by this library.

## Scope

This is an initial JVM archive core, not a drop-in Android Room plugin. It does not provide Room builder integration, Android Keystore wrappers, cloud sync, or automatic migrations. A consistent source snapshot and a safe app-specific restore strategy remain required.

MIT licensed. See LICENSE.
