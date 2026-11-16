/* Copyright (C) 2026 Lawnchair. Licensed under the Apache License, Version 2.0. */
package app.lawnchair.model;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

/** Runs the actual production policy and codec without Android, Gradle, mocks, or a device. */
public final class WorkspaceAvailabilityStoreTest {
    private static int count;

    public static void main(String[] args) throws Exception {
        missingMigrationTargetSurvivesRestart();
        knownInternalKeepsNormalPolicy();
        unavailableInternalEntersRecovery();
        externalHistorySurvivesRestart();
        exactComponentRequired();
        profilesAreIndependent();
        databaseAndRowReuseAreIndependent();
        confirmedUninstallWinsOverResolution();
        interruptedRemovalIsIdempotent();
        reinstallDoesNotInheritOldRemoval();
        corruptMetadataFailsSafe();
        unsupportedVersionFailsSafe();
        unreadableMetadataFailsSafe();
        failedAtomicWriteRetainsPreviousState();
        storageMappingIsNotInvented();
        availabilityEventIsDurableWithoutKnownPackage();
        uninstallRetiresRecoveryForPreviouslyDeletedRows();
        longIntentUninstallSurvivesRestart();
        unchangedRecoveryRetriesTransientWriteFailure();
        unchangedObservationRetriesTransientWriteFailure();
        dirtyInternalHistoryCannotAuthorizeDeletion();
        delayedUninstallCannotMatchReusedIdEvenWithIdenticalTimestamp();
        System.out.println("PASS " + count + " production availability policy/store tests");
    }

    private static void missingMigrationTargetSurvivesRestart() {
        Disk disk = new Disk();
        WorkspaceAvailabilityStore store = new WorkspaceAvailabilityStore(disk);
        equal(WorkspaceAvailabilityStore.KEEP, store.evaluate(row(1, 10, "Main"), false));
        store = new WorkspaceAvailabilityStore(disk);
        equal(WorkspaceAvailabilityStore.KEEP, store.evaluate(row(1, 10, "Main"), false));
        count++;
    }

    private static void knownInternalKeepsNormalPolicy() {
        WorkspaceAvailabilityStore store = new WorkspaceAvailabilityStore(new Disk());
        store.observe(10, "pkg", false, "internal", null);
        equal(WorkspaceAvailabilityStore.REMOVE, store.evaluate(row(1, 10, "Main"), false));
        count++;
    }

    private static void unavailableInternalEntersRecovery() {
        WorkspaceAvailabilityStore store = new WorkspaceAvailabilityStore(new Disk());
        store.observe(10, "pkg", false, "internal", null);
        store.unavailable(10, "pkg");
        equal(WorkspaceAvailabilityStore.KEEP, store.evaluate(row(1, 10, "Main"), false));
        count++;
    }

    private static void externalHistorySurvivesRestart() {
        Disk disk = new Disk();
        WorkspaceAvailabilityStore store = new WorkspaceAvailabilityStore(disk);
        store.observe(10, "pkg", true, "adopted-uuid", null);
        equal(WorkspaceAvailabilityStore.AVAILABLE, store.evaluate(row(1, 10, "Main"), true));
        store = new WorkspaceAvailabilityStore(disk);
        equal(WorkspaceAvailabilityStore.KEEP, store.evaluate(row(1, 10, "Main"), false));
        count++;
    }

    private static void exactComponentRequired() {
        WorkspaceAvailabilityStore store = new WorkspaceAvailabilityStore(new Disk());
        WorkspaceAvailabilityStore.Row main = row(1, 10, "Main");
        WorkspaceAvailabilityStore.Row other = row(2, 10, "Other");
        store.evaluate(main, false);
        store.evaluate(other, false);
        equal(WorkspaceAvailabilityStore.AVAILABLE, store.evaluate(main, true));
        equal(WorkspaceAvailabilityStore.KEEP, store.evaluate(other, false));
        check(store.isRecovering(10, "pkg"), "Another activity must remain recovering");
        count++;
    }

    private static void profilesAreIndependent() {
        WorkspaceAvailabilityStore store = new WorkspaceAvailabilityStore(new Disk());
        store.observe(10, "pkg", false, null, null);
        store.unavailable(11, "pkg");
        equal(WorkspaceAvailabilityStore.REMOVE, store.evaluate(row(1, 10, "Main"), false));
        equal(WorkspaceAvailabilityStore.KEEP, store.evaluate(row(2, 11, "Main"), false));
        count++;
    }

    private static void databaseAndRowReuseAreIndependent() {
        WorkspaceAvailabilityStore store = new WorkspaceAvailabilityStore(new Disk());
        store.observe(10, "pkg", false, null, null);
        store.evaluate(row(1, 10, "Main"), true);
        WorkspaceAvailabilityStore.Row old = row(1, 10, "Main");
        store.uninstall(10, "pkg", Collections.singletonList(old));
        WorkspaceAvailabilityStore.Row newDb = new WorkspaceAvailabilityStore.Row(
                "different-db", 1, 10, "pkg", "Main", "original-intent", 100, "row-token");
        equal(WorkspaceAvailabilityStore.AVAILABLE, store.evaluate(newDb, true));
        equal(WorkspaceAvailabilityStore.REMOVE, store.evaluate(old, true));
        count++;
    }

    private static void confirmedUninstallWinsOverResolution() {
        Disk disk = new Disk();
        WorkspaceAvailabilityStore store = new WorkspaceAvailabilityStore(disk);
        WorkspaceAvailabilityStore.Row row = row(1, 10, "Main");
        store.evaluate(row, false);
        store.uninstall(10, "pkg", Collections.singletonList(row));
        store = new WorkspaceAvailabilityStore(disk);
        equal(WorkspaceAvailabilityStore.REMOVE, store.evaluate(row, true));
        equal(1, store.removals().size());
        count++;
    }

    private static void interruptedRemovalIsIdempotent() {
        Disk disk = new Disk();
        WorkspaceAvailabilityStore store = new WorkspaceAvailabilityStore(disk);
        WorkspaceAvailabilityStore.Row row = row(1, 10, "Main");
        store.uninstall(10, "pkg", Collections.singletonList(row));
        // Favorites deletion may have succeeded before journal acknowledgement/process death.
        store = new WorkspaceAvailabilityStore(disk);
        equal(WorkspaceAvailabilityStore.REMOVE, store.evaluate(row, false));
        store.acknowledge(row);
        store = new WorkspaceAvailabilityStore(disk);
        equal(0, store.removals().size());
        count++;
    }

    private static void reinstallDoesNotInheritOldRemoval() {
        WorkspaceAvailabilityStore store = new WorkspaceAvailabilityStore(new Disk());
        WorkspaceAvailabilityStore.Row old = row(1, 10, "Main");
        store.uninstall(10, "pkg", Collections.singletonList(old));
        store.observe(10, "pkg", true, "uuid", null);
        WorkspaceAvailabilityStore.Row newer = new WorkspaceAvailabilityStore.Row(
                "db", 1, 10, "pkg", "Main", "original-intent", 200, "new-insert-token");
        equal(WorkspaceAvailabilityStore.AVAILABLE, store.evaluate(newer, true));
        equal(WorkspaceAvailabilityStore.REMOVE, store.evaluate(old, true));
        count++;
    }

    private static void corruptMetadataFailsSafe() {
        Disk disk = new Disk();
        disk.bytes = new byte[] {0, 1, 2};
        WorkspaceAvailabilityStore store = new WorkspaceAvailabilityStore(disk);
        equal(WorkspaceAvailabilityStore.KEEP, store.evaluate(row(1, 10, "Main"), false));
        count++;
    }

    private static void unsupportedVersionFailsSafe() {
        Disk disk = new Disk();
        WorkspaceAvailabilityStore store = new WorkspaceAvailabilityStore(disk);
        store.observe(10, "pkg", false, null, null);
        // Header is magic followed by schema version; a future schema cannot justify deletion.
        disk.bytes[7] = 99;
        java.util.zip.CRC32 checksum = new java.util.zip.CRC32();
        checksum.update(disk.bytes, 0, disk.bytes.length - 8);
        java.nio.ByteBuffer.wrap(disk.bytes, disk.bytes.length - 8, 8).putLong(checksum.getValue());
        store = new WorkspaceAvailabilityStore(disk);
        equal(WorkspaceAvailabilityStore.KEEP, store.evaluate(row(1, 10, "Main"), false));
        count++;
    }

    private static void unreadableMetadataFailsSafe() {
        Disk disk = new Disk();
        disk.failRead = true;
        WorkspaceAvailabilityStore store = new WorkspaceAvailabilityStore(disk);
        equal(WorkspaceAvailabilityStore.KEEP, store.evaluate(row(1, 10, "Main"), false));
        count++;
    }

    private static void failedAtomicWriteRetainsPreviousState() {
        Disk disk = new Disk();
        WorkspaceAvailabilityStore store = new WorkspaceAvailabilityStore(disk);
        store.observe(10, "pkg", true, "uuid", null);
        byte[] before = disk.bytes.clone();
        disk.failWrite = true;
        store.unavailable(10, "pkg");
        check(Arrays.equals(before, disk.bytes), "Interrupted commit must retain previous bytes");
        store = new WorkspaceAvailabilityStore(disk);
        equal(WorkspaceAvailabilityStore.KEEP, store.evaluate(row(1, 10, "Main"), false));
        count++;
    }

    private static void storageMappingIsNotInvented() {
        WorkspaceAvailabilityStore store = new WorkspaceAvailabilityStore(new Disk());
        store.observe(10, "pkg", true, "adopted-uuid", null);
        equal(null, store.publicVolume(10, "pkg"));
        store.observe(10, "pkg", true, "aabb-ccdd", "aabb-ccdd");
        equal("aabb-ccdd", store.publicVolume(10, "pkg"));
        equal(WorkspaceAvailabilityStore.KEEP, store.evaluate(row(1, 10, "Main"), false));
        count++;
    }

    private static void availabilityEventIsDurableWithoutKnownPackage() {
        Disk disk = new Disk();
        WorkspaceAvailabilityStore store = new WorkspaceAvailabilityStore(disk);
        store.availabilityEvent(10, "pkg");
        check(disk.bytes != null, "An availability event must be persisted even before the model first loads");
        store = new WorkspaceAvailabilityStore(disk);
        equal(WorkspaceAvailabilityStore.KEEP, store.evaluate(row(1, 10, "Main"), false));
        count++;
    }

    private static void uninstallRetiresRecoveryForPreviouslyDeletedRows() {
        Disk disk = new Disk();
        WorkspaceAvailabilityStore store = new WorkspaceAvailabilityStore(disk);
        store.evaluate(row(1, 10, "Main"), false);
        // A user may have removed this unavailable icon before the real uninstall arrives.
        store.uninstall(10, "pkg", Collections.emptyList());
        store = new WorkspaceAvailabilityStore(disk);
        check(!store.isRecovering(10, "pkg"), "Confirmed uninstall must retire recovery even for rows already removed by the user");
        count++;
    }

    private static void longIntentUninstallSurvivesRestart() {
        Disk disk = new Disk();
        WorkspaceAvailabilityStore store = new WorkspaceAvailabilityStore(disk);
        store.observe(10, "pkg", false, null, null);
        WorkspaceAvailabilityStore.Row row = new WorkspaceAvailabilityStore.Row("db", 1, 10,
                "pkg", "Main", "intent-extra=" + "x".repeat(70000), 100, "row-token");
        store.uninstall(10, "pkg", Collections.singletonList(row));
        store = new WorkspaceAvailabilityStore(disk);
        equal(WorkspaceAvailabilityStore.REMOVE, store.evaluate(row, true));
        equal(1, store.removals().size());
        count++;
    }

    private static void unchangedRecoveryRetriesTransientWriteFailure() {
        Disk disk = new Disk();
        WorkspaceAvailabilityStore store = new WorkspaceAvailabilityStore(disk);
        store.observe(10, "pkg", false, "internal", null);
        disk.failWrite = true;
        store.unavailable(10, "pkg");
        equal(WorkspaceAvailabilityStore.KEEP, store.evaluate(row(1, 10, "Main"), false));
        disk.failWrite = false;
        equal(WorkspaceAvailabilityStore.KEEP, store.evaluate(row(1, 10, "Main"), false));
        store = new WorkspaceAvailabilityStore(disk);
        equal(WorkspaceAvailabilityStore.KEEP, store.evaluate(row(1, 10, "Main"), false));
        count++;
    }

    private static void unchangedObservationRetriesTransientWriteFailure() {
        Disk disk = new Disk();
        WorkspaceAvailabilityStore store = new WorkspaceAvailabilityStore(disk);
        store.observe(10, "pkg", false, "internal", null);
        disk.failWrite = true;
        store.unavailable(10, "pkg");
        store.evaluate(row(1, 10, "Main"), false);
        disk.failWrite = false;
        store.observe(10, "pkg", false, "internal", null);
        store = new WorkspaceAvailabilityStore(disk);
        equal(WorkspaceAvailabilityStore.KEEP, store.evaluate(row(1, 10, "Main"), false));
        count++;
    }

    private static void dirtyInternalHistoryCannotAuthorizeDeletion() {
        Disk disk = new Disk();
        WorkspaceAvailabilityStore store = new WorkspaceAvailabilityStore(disk);
        disk.failWrite = true;
        store.observe(10, "pkg", false, "internal", null);
        equal(WorkspaceAvailabilityStore.KEEP, store.evaluate(row(1, 10, "Main"), false));
        check(store.hasWriteFailure(), "Still-failing writes must remain visible");
        count++;
    }

    private static void delayedUninstallCannotMatchReusedIdEvenWithIdenticalTimestamp() {
        Disk disk = new Disk();
        WorkspaceAvailabilityStore store = new WorkspaceAvailabilityStore(disk);
        WorkspaceAvailabilityStore.Row old = new WorkspaceAvailabilityStore.Row("db", 99, 10,
                "pkg", "Main", "identical-intent", 100, "first-insert-token");
        WorkspaceAvailabilityStore.Row fresh = new WorkspaceAvailabilityStore.Row("db", 99, 10,
                "pkg", "Main", "identical-intent", 100, "second-insert-token");
        store.uninstall(10, "pkg", Collections.singletonList(old));
        store.availabilityEvent(10, "pkg");
        store = new WorkspaceAvailabilityStore(disk);
        equal(WorkspaceAvailabilityStore.AVAILABLE, store.evaluate(fresh, true));
        equal(WorkspaceAvailabilityStore.REMOVE, store.evaluate(old, true));
        count++;
    }

    private static WorkspaceAvailabilityStore.Row row(int id, long serial, String component) {
        return new WorkspaceAvailabilityStore.Row("db", id, serial, "pkg", component,
                "original-intent", 100, "row-token");
    }

    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class Disk implements WorkspaceAvailabilityStore.Persistence {
        byte[] bytes;
        boolean failRead;
        boolean failWrite;

        public byte[] read() throws IOException {
            if (failRead) throw new IOException("unreadable");
            return bytes;
        }

        public void write(byte[] next) throws IOException {
            if (failWrite) throw new IOException("interrupted atomic commit");
            bytes = next.clone();
        }
    }
}
