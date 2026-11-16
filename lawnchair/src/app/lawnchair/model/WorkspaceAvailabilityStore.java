/* Copyright (C) 2026 Lawnchair. Licensed under the Apache License, Version 2.0. */
package app.lawnchair.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;

/** Durable ordinary-workspace availability policy; Android boundaries are injected. */
public final class WorkspaceAvailabilityStore {
    public static final int REMOVE = 0;
    public static final int KEEP = 1;
    public static final int AVAILABLE = 2;
    private static final int MAGIC = 0x4c435741;
    private static final int VERSION = 2;
    private static final int MAX_RECORDS = 100000;
    private final Persistence mPersistence;
    private final Map<String, PackageState> mPackages = new HashMap<>();
    private final Map<String, Row> mRecovering = new HashMap<>();
    private final Map<String, Row> mRemovals = new LinkedHashMap<>();
    private boolean mWriteFailed;

    private static final class PackageState {
        boolean known;
        boolean external;
        boolean recovering;
        String uuid;
        String publicUuid;
        long generation;
    }

    public interface Persistence {
        byte[] read() throws IOException;
        void write(byte[] bytes) throws IOException;
    }

    public static final class Row {
        public final String database;
        public final int id;
        public final long serial;
        public final String packageName;
        public final String component;
        public final String intent;
        public final long modified;
        public final String incarnation;

        public Row(String db, int id, long serial, String pkg, String component,
                String intent, long modified, String incarnation) {
            database = db;
            this.id = id;
            this.serial = serial;
            packageName = pkg;
            this.component = component;
            this.intent = intent;
            this.modified = modified;
            this.incarnation = incarnation == null ? "" : incarnation;
        }

        private String identity() {
            return database + "\n" + id + "\n" + serial + "\n" + component;
        }

        private String fingerprint() {
            return identity() + "\n" + modified + "\n" + intent + "\n" + incarnation;
        }
    }

    public WorkspaceAvailabilityStore(Persistence persistence) {
        mPersistence = persistence;
        try {
            byte[] data = persistence.read();
            if (data != null) read(data);
        } catch (IOException | RuntimeException e) {
            // Unknown history is deliberately different from known internal storage. Never purge on a failed read/migration.
            mPackages.clear();
            mRecovering.clear();
            mRemovals.clear();
        }
    }

    /** Only a positive resolution of THIS saved component ends recovery; volume state and time are not inputs. */
    public int evaluate(Row row, boolean exactTargetResolves) {
        retryPendingWrites();
        if (mRemovals.containsKey(row.fingerprint())) return REMOVE;
        String key = packageKey(row.serial, row.packageName);
        PackageState state = mPackages.get(key);
        if (exactTargetResolves) {
            if (mRecovering.remove(row.identity()) != null) {
                if (state != null && !hasRecoveringItem(row.serial, row.packageName)) {
                    state.recovering = false;
                }
                save();
            }
            return AVAILABLE;
        }
        if (!mWriteFailed && state != null && state.known && !state.external && !state.recovering
                && !mRecovering.containsKey(row.identity())) return REMOVE;
        if (!mRecovering.containsKey(row.identity())) {
            mRecovering.put(row.identity(), row);
            save();
        }
        return KEEP;
    }

    /** Call only while ApplicationInfo is accessible. A failed package query must never classify it as internal. */
    public void observe(long serial, String pkg, boolean external, String uuid, String publicUuid) {
        retryPendingWrites();
        String key = packageKey(serial, pkg);
        PackageState state = mPackages.get(key);
        if (state != null && state.known && state.external == external && Objects.equals(state.uuid, uuid)
                && Objects.equals(state.publicUuid, publicUuid)) return;
        if (state == null) state = new PackageState();
        state.known = true;
        state.external = external;
        state.uuid = uuid;
        state.publicUuid = publicUuid;
        mPackages.put(key, state);
        save();
    }

    public void unavailable(long serial, String pkg) {
        PackageState state = mPackages.computeIfAbsent(packageKey(serial, pkg), k -> {
            PackageState unknown = new PackageState();
            unknown.external = true; // Unavailable callback is positive evidence of a recovery condition, not an internal install.
            return unknown;
        });
        state.recovering = true;
        state.generation++;
        save();
    }

    public void availabilityEvent(long serial, String pkg) {
        PackageState state = mPackages.computeIfAbsent(packageKey(serial, pkg), k -> new PackageState());
        state.generation++;
        save();
    }

    /** Journal exact old favorites before deleting them; a later installation cannot inherit these row fingerprints. */
    public void uninstall(long serial, String pkg, List<Row> rows) {
        PackageState state = mPackages.computeIfAbsent(packageKey(serial, pkg), k -> new PackageState());
        state.generation++;
        state.recovering = false;
        state.known = false;
        state.external = false;
        state.uuid = null;
        state.publicUuid = null;
        mRecovering.entrySet().removeIf(entry -> entry.getValue().serial == serial
                && entry.getValue().packageName.equals(pkg));
        for (Row row : rows) {
            if (row.serial == serial && row.packageName.equals(pkg)) {
                mRemovals.put(row.fingerprint(), row);
            }
        }
        save();
    }

    public List<Row> removals() {
        retryPendingWrites();
        return new ArrayList<>(mRemovals.values());
    }

    /** Revalidation can be semantically unchanged after a transient failure; persistence still has unfinished work. */
    public void retryPendingWrites() {
        if (mWriteFailed) save();
    }

    public void acknowledge(Row row) {
        if (mRemovals.remove(row.fingerprint()) != null) save();
    }

    public boolean isRecovering(long serial, String pkg) {
        PackageState state = mPackages.get(packageKey(serial, pkg));
        return (state != null && state.recovering) || hasRecoveringItem(serial, pkg);
    }

    private boolean hasRecoveringItem(long serial, String pkg) {
        for (Row row : mRecovering.values()) {
            if (row.serial == serial && row.packageName.equals(pkg)) return true;
        }
        return false;
    }

    public String publicVolume(long serial, String pkg) {
        PackageState state = mPackages.get(packageKey(serial, pkg));
        return state == null ? null : state.publicUuid;
    }

    public boolean hasWriteFailure() {
        return mWriteFailed;
    }

    private static String packageKey(long serial, String pkg) {
        return serial + ":" + pkg;
    }

    private void save() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(mPackages.size());
            for (Map.Entry<String, PackageState> entry : mPackages.entrySet()) {
                PackageState state = entry.getValue();
                out.writeUTF(entry.getKey());
                out.writeBoolean(state.known);
                out.writeBoolean(state.external);
                out.writeBoolean(state.recovering);
                writeNullable(out, state.uuid);
                writeNullable(out, state.publicUuid);
                out.writeLong(state.generation);
            }
            writeRows(out, mRecovering);
            writeRows(out, mRemovals);
            out.flush();
            CRC32 checksum = new CRC32();
            checksum.update(bytes.toByteArray());
            out.writeLong(checksum.getValue());
            out.flush();
            mPersistence.write(bytes.toByteArray());
            mWriteFailed = false;
        } catch (IOException | RuntimeException e) {
            // Retain the in-memory event and retry even on stable observations/revalidation. Atomic persistence retains the previous complete snapshot.
            mWriteFailed = true;
        }
    }

    private void read(byte[] bytes) throws IOException {
        if (bytes.length < 16 || bytes.length > 16 * 1024 * 1024) throw new IOException("Invalid length");
        CRC32 checksum = new CRC32();
        checksum.update(bytes, 0, bytes.length - 8);
        DataInputStream tail = new DataInputStream(new ByteArrayInputStream(bytes, bytes.length - 8, 8));
        if (tail.readLong() != checksum.getValue()) throw new IOException("Invalid checksum");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, bytes.length - 8));
        if (in.readInt() != MAGIC) throw new IOException("Unknown schema");
        int version = in.readInt();
        if (version < 1 || version > VERSION) throw new IOException("Unknown schema");
        int size = count(in);
        for (int i = 0; i < size; i++) {
            String key = in.readUTF();
            PackageState state = new PackageState();
            state.known = in.readBoolean();
            state.external = in.readBoolean();
            state.recovering = in.readBoolean();
            state.uuid = readNullable(in);
            state.publicUuid = readNullable(in);
            state.generation = in.readLong();
            mPackages.put(key, state);
        }
        readRows(in, mRecovering, false, version);
        readRows(in, mRemovals, true, version);
        if (in.available() != 0) throw new IOException("Trailing data");
    }

    private static void writeRows(DataOutputStream out, Map<String, Row> rows) throws IOException {
        out.writeInt(rows.size());
        for (Row row : rows.values()) {
            out.writeUTF(row.database);
            out.writeInt(row.id);
            out.writeLong(row.serial);
            out.writeUTF(row.packageName);
            out.writeUTF(row.component);
            // Launch intents with extras can exceed DataOutputStream.writeUTF's 64-KiB limit while remaining valid Android intents.
            byte[] intent = row.intent.getBytes(StandardCharsets.UTF_8);
            out.writeInt(intent.length);
            out.write(intent);
            out.writeLong(row.modified);
            out.writeUTF(row.incarnation);
        }
    }

    private static void readRows(DataInputStream in, Map<String, Row> rows, boolean removal, int version) throws IOException {
        int size = count(in);
        for (int i = 0; i < size; i++) {
            Row row = new Row(in.readUTF(), in.readInt(), in.readLong(), in.readUTF(),
                    in.readUTF(), readIntent(in), in.readLong(), version >= 2 ? in.readUTF() : "");
            rows.put(removal ? row.fingerprint() : row.identity(), row);
        }
    }

    private static int count(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_RECORDS) throw new IOException("Invalid record count");
        return count;
    }

    private static String readIntent(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > in.available()) throw new IOException("Invalid intent length");
        byte[] intent = new byte[length];
        in.readFully(intent);
        return new String(intent, StandardCharsets.UTF_8);
    }

    private static void writeNullable(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) out.writeUTF(value);
    }

    private static String readNullable(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }
}
