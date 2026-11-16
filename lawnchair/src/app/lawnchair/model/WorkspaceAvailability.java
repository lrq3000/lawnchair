/* Copyright (C) 2026 Lawnchair. Licensed under the Apache License, Version 2.0. */
package app.lawnchair.model;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.drawable.Drawable;
import android.database.Cursor;
import android.os.Environment;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.util.Log;

import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.model.BgDataModel;
import com.android.launcher3.model.LoaderCursor;
import com.android.launcher3.model.ModelDbController;
import com.android.launcher3.model.PackageUpdatedTask;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.IconRequestInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.pm.UserCache;
import com.android.launcher3.util.ContentWriter;
import com.android.launcher3.icons.BitmapInfo;
import com.android.launcher3.icons.BaseIconFactory;
import com.android.launcher3.icons.LauncherIcons;
import app.lawnchair.icons.LawnchairIconProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Model-thread owner of availability history, exact-target queries, appearance snapshots, and uninstall replay. */
public final class WorkspaceAvailability {
    private static final String TAG = "WorkspaceAvailability";
    private final LauncherAppState mApp;
    private final Context mContext;
    private final ModelDbController mDb;
    private WorkspaceAvailabilityStore mStore;
    private String mStoreIdentity;
    private String mIdentity;

    public WorkspaceAvailability(LauncherAppState app, ModelDbController db) {
        mApp = app;
        mContext = app.getContext();
        mDb = db;
    }

    private WorkspaceAvailabilityStore store() {
        if (mStore == null || !identity().equals(mStoreIdentity)) {
            mStoreIdentity = identity();
            // No-backup metadata cannot apply stale device storage mappings after a layout is restored elsewhere.
            String token = mStoreIdentity.substring(mStoreIdentity.lastIndexOf(':') + 1);
            AtomicFile file = new AtomicFile(new File(mContext.getNoBackupFilesDir(), "workspace-availability-v1-" + token));
            mStore = new WorkspaceAvailabilityStore(new WorkspaceAvailabilityStore.Persistence() {
                @Override
                public byte[] read() throws IOException {
                    try {
                        return file.readFully();
                    } catch (FileNotFoundException e) {
                        return null;
                    }
                }

                @Override
                public void write(byte[] bytes) throws IOException {
                    FileOutputStream output = file.startWrite();
                    try {
                        output.write(bytes);
                        file.finishWrite(output);
                    } catch (IOException | RuntimeException e) {
                        file.failWrite(output);
                        throw e;
                    }
                }
            });
        }
        return mStore;
    }

    public void beginLoad() {
        mIdentity = mDb.getWorkspaceIdentity();
        applyPendingRemovals(null);
    }

    private String identity() {
        if (mIdentity == null) mIdentity = mDb.getWorkspaceIdentity();
        return mIdentity;
    }

    public int evaluate(LoaderCursor cursor, Intent original) {
        WorkspaceAvailabilityStore.Row row = row(cursor, original);
        LauncherActivityInfo activity = resolveExact(original, cursor.user);
        if (activity != null) observe(activity, cursor.serialNumber);
        else if (isProfileUnavailable(cursor.user)) store().unavailable(cursor.serialNumber, row.packageName);
        return store().evaluate(row, activity != null);
    }

    /** Positive and negative lookups in the same load disagreed; record recovery before any fallback/delete can run. */
    public void preserve(LoaderCursor cursor, Intent original) {
        WorkspaceAvailabilityStore.Row row = row(cursor, original);
        store().unavailable(row.serial, row.packageName);
        store().evaluate(row, false);
    }

    public int evaluate(WorkspaceItemInfo item) {
        WorkspaceAvailabilityStore.Row row = findRow(item);
        if (row == null) return WorkspaceAvailabilityStore.REMOVE;
        LauncherActivityInfo activity = resolveExact(item.intent, item.user);
        if (activity != null) observe(activity, row.serial);
        else if (isProfileUnavailable(item.user)) store().unavailable(row.serial, row.packageName);
        return store().evaluate(row, activity != null);
    }

    public static boolean isOrdinaryApplication(ItemInfo item) {
        return item instanceof WorkspaceItemInfo && item.itemType == Favorites.ITEM_TYPE_APPLICATION
                && ((WorkspaceItemInfo) item).status == 0 && !((WorkspaceItemInfo) item).isArchived();
    }

    public static boolean isUnavailable(WorkspaceItemInfo item) {
        return (item.runtimeStatusFlags & WorkspaceItemInfo.FLAG_DISABLED_NOT_AVAILABLE) != 0;
    }

    /** A package name, another activity, or an accessible volume does not establish this component's availability. */
    private LauncherActivityInfo resolveExact(Intent intent, UserHandle user) {
        ComponentName target = intent == null ? null : intent.getComponent();
        if (target == null || user == null) return null;
        try {
            LauncherApps apps = mContext.getSystemService(LauncherApps.class);
            if (!apps.isPackageEnabled(target.getPackageName(), user)
                    || !apps.isActivityEnabled(target, user)) return null;
            LauncherActivityInfo result = apps.resolveActivity(intent, user);
            return result != null && target.equals(result.getComponentName()) ? result : null;
        } catch (RuntimeException e) {
            Log.w(TAG, "Target lookup unresolved", e);
            return null;
        }
    }

    private boolean isProfileUnavailable(UserHandle user) {
        try {
            UserManager manager = mContext.getSystemService(UserManager.class);
            return !manager.isUserUnlocked(user) || manager.isQuietModeEnabled(user);
        } catch (RuntimeException e) {
            return true;
        }
    }

    private void observe(LauncherActivityInfo activity, long serial) {
        ApplicationInfo app = activity.getApplicationInfo();
        String uuid = app.storageUuid == null ? null : app.storageUuid.toString();
        boolean external = (app.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0
                || (app.storageUuid != null && !StorageManager.UUID_DEFAULT.equals(app.storageUuid));
        store().observe(serial, app.packageName, external, uuid, publicVolume(uuid));
    }

    private String publicVolume(String uuid) {
        if (uuid == null) return null;
        try {
            StorageManager manager = mContext.getSystemService(StorageManager.class);
            for (StorageVolume volume : manager.getStorageVolumes()) {
                // API 29 exposes a public/shared view, not a general adopted/private-volume mapping. Do not derive mappings from paths or unrelated mounted volumes.
                if (uuid.equalsIgnoreCase(volume.getUuid())
                        && (Environment.MEDIA_MOUNTED.equals(volume.getState())
                        || Environment.MEDIA_MOUNTED_READ_ONLY.equals(volume.getState()))) {
                    return volume.getUuid();
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Storage mapping unresolved", e);
        }
        return null;
    }

    /** Called before the model's discard gate, including from the loader's pre-bind checkpoint. */
    public Set<Integer> recordEvent(int op, UserHandle user, String[] packages) {
        long serial = UserCache.INSTANCE.get(mContext).getSerialNumberForUser(user);
        Set<Integer> removedIds = new HashSet<>();
        List<WorkspaceAvailabilityStore.Row> rows = rows();
        for (String pkg : packages) {
            List<WorkspaceAvailabilityStore.Row> affected = new ArrayList<>();
            for (WorkspaceAvailabilityStore.Row row : rows) {
                if (row.serial == serial && row.packageName.equals(pkg)) affected.add(row);
            }
            if (op == PackageUpdatedTask.OP_REMOVE) {
                store().uninstall(serial, pkg, affected);
                for (WorkspaceAvailabilityStore.Row row : affected) removedIds.add(row.id);
            } else if (op == PackageUpdatedTask.OP_UNAVAILABLE) {
                store().unavailable(serial, pkg);
                for (WorkspaceAvailabilityStore.Row row : affected) store().evaluate(row, false);
            } else if (op == PackageUpdatedTask.OP_ADD || op == PackageUpdatedTask.OP_UPDATE) {
                // Persist an availability observation, but do not clear recovery without exact-target resolution.
                store().availabilityEvent(serial, pkg);
            }
        }
        if (store().hasWriteFailure()) Log.e(TAG, "Availability journal write failed; retaining in-memory events");
        return removedIds;
    }

    /** Replay conditional deletes idempotently. Placement edits and newly installed rows are never broad package deletes. */
    public Set<Integer> applyPendingRemovals(BgDataModel model) {
        mIdentity = mDb.getWorkspaceIdentity();
        Set<Integer> removed = new HashSet<>();
        for (WorkspaceAvailabilityStore.Row row : store().removals()) {
            if (!identity().equals(row.database)) continue;
            if (row.incarnation.isEmpty()) {
                // Pre-incarnation journal entries cannot safely identify a possibly reused row; never turn uncertain migration metadata into a purge.
                store().acknowledge(row);
                continue;
            }
            mDb.delete(Favorites.TABLE_NAME,
                    Favorites._ID + "=? AND " + Favorites.PROFILE_ID + "=? AND "
                            + Favorites.INTENT + "=? AND " + Favorites.MODIFIED + "=? AND "
                            + Favorites.ITEM_TYPE + "=0 AND " + Favorites.RESTORED + "=0 AND EXISTS "
                            + "(SELECT 1 FROM workspace_item_generation WHERE item_id=favorites._id AND token=?)",
                    new String[] {Integer.toString(row.id), Long.toString(row.serial), row.intent,
                            Long.toString(row.modified), row.incarnation});
            if (model != null) {
                ItemInfo item = model.itemsIdMap.get(row.id);
                if (isOrdinaryApplication(item) && item.getTargetComponent() != null
                        && row.component.equals(item.getTargetComponent().flattenToString())
                        && row.serial == UserCache.INSTANCE.get(mContext).getSerialNumberForUser(item.user)
                        && findRow((WorkspaceItemInfo) item) == null) {
                    model.removeItem(mContext, item);
                    removed.add(row.id);
                }
            }
            store().acknowledge(row);
        }
        return removed;
    }

    /** Revalidate the existing model objects. Only the storage-related NOT_AVAILABLE bit is changed. */
    public List<WorkspaceItemInfo> revalidate(BgDataModel model) {
        store().retryPendingWrites();
        List<WorkspaceItemInfo> changed = new ArrayList<>();
        synchronized (model) {
            for (ItemInfo info : model.itemsIdMap) {
                if (!isOrdinaryApplication(info)) continue;
                WorkspaceItemInfo item = (WorkspaceItemInfo) info;
                int decision = evaluate(item);
                if (decision == WorkspaceAvailabilityStore.KEEP) {
                    if (!isUnavailable(item)) changed.add(item);
                    markUnavailable(item);
                } else if (decision == WorkspaceAvailabilityStore.AVAILABLE) {
                    if (isUnavailable(item)) {
                        item.runtimeStatusFlags &= ~WorkspaceItemInfo.FLAG_DISABLED_NOT_AVAILABLE;
                        refreshAppearance(item);
                        changed.add(item);
                    }
                }
            }
        }
        return changed;
    }

    public boolean hasRecovery(BgDataModel model) {
        for (ItemInfo info : model.itemsIdMap) {
            if (isOrdinaryApplication(info) && isUnavailable((WorkspaceItemInfo) info)) return true;
        }
        return false;
    }

    public void markUnavailable(WorkspaceItemInfo item) {
        boolean hydrate = !isUnavailable(item) || item.bitmap == null || item.bitmap.isNullOrLowRes();
        item.runtimeStatusFlags |= WorkspaceItemInfo.FLAG_DISABLED_NOT_AVAILABLE;
        if (hydrate) restoreUnavailableAppearance(item);
    }

    /** Model thread only: the durable favorites snapshot is authoritative when an asynchronous folder request cannot read its APK. */
    public void restoreUnavailableAppearance(WorkspaceItemInfo item) {
        try (Cursor c = mDb.query(Favorites.TABLE_NAME, new String[] {Favorites.TITLE, Favorites.ICON},
                Favorites._ID + "=? AND " + Favorites.PROFILE_ID + "=? AND " + Favorites.INTENT + "=?",
                new String[] {Integer.toString(item.id), Long.toString(UserCache.INSTANCE.get(mContext)
                        .getSerialNumberForUser(item.user)), item.intent.toUri(0)}, null)) {
            if (!c.moveToFirst()) return;
            String title = c.getString(0);
            WorkspaceItemInfo saved = new WorkspaceItemInfo(item);
            byte[] blob = c.getBlob(1);
            if (blob != null && new IconRequestInfo<>(saved, null, blob, false).loadWorkspaceIcon(mContext)
                    && saved.bitmap != null && !saved.bitmap.isNullOrLowRes()
                    && !mApp.getIconCache().isDefaultIcon(saved.bitmap, item.user)) item.bitmap = saved.bitmap;
            if (!TextUtils.isEmpty(title)) item.title = title;
            item.contentDescription = mContext.getPackageManager().getUserBadgedLabel(item.title, item.user);
        } catch (RuntimeException e) {
            Log.w(TAG, "Saved appearance unavailable; retaining the displayed image", e);
        }
    }

    public boolean hasWorkspaceApplication(String pkg, UserHandle user) {
        long serial = UserCache.INSTANCE.get(mContext).getSerialNumberForUser(user);
        for (WorkspaceAvailabilityStore.Row row : rows()) {
            if (row.serial == serial && row.packageName.equals(pkg)) return true;
        }
        return false;
    }

    public boolean hasFavorite(int id) {
        try (Cursor c = mDb.query(Favorites.TABLE_NAME, new String[] {Favorites._ID},
                Favorites._ID + "=?", new String[] {Integer.toString(id)}, null)) {
            return c.moveToNext();
        }
    }

    /** Load a full-resolution normal icon through Lawnchair's existing custom-label/custom-icon pipeline, never a disabled rendering. */
    public void refreshAppearance(WorkspaceItemInfo item) {
        if (resolveExact(item.intent, item.user) == null) return;
        WorkspaceItemInfo candidate = new WorkspaceItemInfo(item);
        try {
            mApp.getIconCache().getTitleAndIcon(candidate, false);
        } catch (RuntimeException e) {
            Log.w(TAG, "Appearance query failed; retaining last valid snapshot", e);
            return;
        }
        BitmapInfo validIcon = validWorkspaceIcon(mContext, item.getTargetComponent(), item.user, candidate.bitmap);
        if (validIcon == null) {
            restoreUnavailableAppearance(item);
            return;
        }
        candidate.bitmap = validIcon;
        if (!TextUtils.isEmpty(candidate.title)) {
            item.title = candidate.title;
            item.contentDescription = candidate.contentDescription;
        }
        if (candidate.bitmap != null && !candidate.bitmap.isNullOrLowRes()
                && !mApp.getIconCache().isDefaultIcon(candidate.bitmap, item.user)) item.bitmap = candidate.bitmap;
        cacheAppearance(item);
    }

    /** Migration may find an icon only in IconCache, before favorites has ever held a durable full-resolution snapshot. */
    public void loadCachedAppearance(WorkspaceItemInfo item) {
        if (item.bitmap != null && !item.bitmap.isNullOrLowRes()
                && !mApp.getIconCache().isDefaultIcon(item.bitmap, item.user)) return;
        WorkspaceItemInfo candidate = new WorkspaceItemInfo(item);
        try {
            mApp.getIconCache().getTitleAndIcon(candidate, false);
            candidate.bitmap = validWorkspaceIcon(mContext, item.getTargetComponent(), item.user, candidate.bitmap);
            if (candidate.bitmap != null && !candidate.bitmap.isNullOrLowRes()
                    && !mApp.getIconCache().isDefaultIcon(candidate.bitmap, item.user)) item.bitmap = candidate.bitmap;
            if (TextUtils.isEmpty(item.title) && !TextUtils.isEmpty(candidate.title)) item.title = candidate.title;
            item.contentDescription = mContext.getPackageManager().getUserBadgedLabel(item.title, item.user);
            persistAppearance(item); // Preserve a valid migration cache before later package-cache eviction, even though the target is currently missing.
        } catch (RuntimeException e) {
            Log.w(TAG, "Icon cache unavailable; retaining favorites snapshot", e);
        }
    }

    /** Reject a configured source that did not load; when it does load, rasterize that drawable rather than trusting a possibly stock cache entry. */
    public static BitmapInfo validWorkspaceIcon(Context context, ComponentName component, UserHandle user, BitmapInfo candidate) {
        LauncherAppState app = LauncherAppState.getInstance(context);
        if (component == null || !(app.getIconProvider() instanceof LawnchairIconProvider)) return candidate;
        try {
            WorkspaceIconSource<Drawable> source = ((LawnchairIconProvider) app.getIconProvider())
                    .getWorkspaceIconSource(component, user, app.getInvariantDeviceProfile().fillResIconDpi);
            if (!source.isUsable()) return null;
            if (!source.isConfigured()) return candidate;
            try (LauncherIcons icons = LauncherIcons.obtain(context)) {
                return icons.createBadgedIconBitmap(source.getIcon(), new BaseIconFactory.IconOptions().setUser(user));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Configured workspace icon unresolved; retaining last custom snapshot", e);
            return null;
        }
    }

    public void cacheAppearance(WorkspaceItemInfo item) {
        if (!isOrdinaryApplication(item) || isUnavailable(item) || item.id == ItemInfo.NO_ID) return;
        persistAppearance(item);
    }

    private void persistAppearance(WorkspaceItemInfo item) {
        if (!isOrdinaryApplication(item) || item.id == ItemInfo.NO_ID) return;
        if (TextUtils.isEmpty(item.title) && (item.bitmap == null || item.bitmap.isNullOrLowRes()
                || mApp.getIconCache().isDefaultIcon(item.bitmap, item.user))) return;
        ContentWriter writer = new ContentWriter(mContext, new ContentWriter.CommitParams(mDb,
                Favorites._ID + "=? AND " + Favorites.PROFILE_ID + "=? AND " + Favorites.INTENT + "=?",
                new String[] {Integer.toString(item.id), Long.toString(UserCache.INSTANCE.get(mContext)
                        .getSerialNumberForUser(item.user)), item.intent.toUri(0)}));
        if (!TextUtils.isEmpty(item.title)) writer.put(Favorites.TITLE, item.title);
        if (item.bitmap != null && !item.bitmap.isNullOrLowRes()
                && !mApp.getIconCache().isDefaultIcon(item.bitmap, item.user)) writer.putIcon(item.bitmap, item.user, item.getTargetComponent());
        try {
            writer.commit();
        } catch (RuntimeException e) {
            Log.w(TAG, "Appearance snapshot write failed; retaining previous favorites values", e);
        }
    }

    public void cacheLoadedItems(BgDataModel model) {
        for (ItemInfo info : model.itemsIdMap) {
            if (isOrdinaryApplication(info) && !isUnavailable((WorkspaceItemInfo) info)) {
                // Folder children may never have had a full-resolution in-memory icon; persist one before the APK disappears.
                WorkspaceItemInfo item = (WorkspaceItemInfo) info;
                refreshAppearance(item.usingLowResIcon() ? new WorkspaceItemInfo(item) : item);
            }
        }
    }

    private WorkspaceAvailabilityStore.Row row(LoaderCursor cursor, Intent original) {
        int modifiedIndex = cursor.getColumnIndex(Favorites.MODIFIED);
        return new WorkspaceAvailabilityStore.Row(identity(), cursor.id, cursor.serialNumber,
                original.getComponent().getPackageName(), original.getComponent().flattenToString(),
                original.toUri(0), modifiedIndex < 0 ? 0 : cursor.getLong(modifiedIndex), mDb.getWorkspaceItemGeneration(cursor.id));
    }

    private WorkspaceAvailabilityStore.Row findRow(WorkspaceItemInfo item) {
        try (Cursor c = mDb.query(Favorites.TABLE_NAME, null, Favorites._ID + "=?",
                new String[] {Integer.toString(item.id)}, null)) {
            return c.moveToNext() ? readRow(c) : null;
        }
    }

    private List<WorkspaceAvailabilityStore.Row> rows() {
        mIdentity = mDb.getWorkspaceIdentity();
        List<WorkspaceAvailabilityStore.Row> result = new ArrayList<>();
        try (Cursor c = mDb.query(Favorites.TABLE_NAME, null,
                Favorites.ITEM_TYPE + "=? AND " + Favorites.RESTORED + "=0",
                new String[] {Integer.toString(Favorites.ITEM_TYPE_APPLICATION)}, null)) {
            while (c.moveToNext()) {
                WorkspaceAvailabilityStore.Row row = readRow(c);
                if (row != null) result.add(row);
            }
        }
        return result;
    }

    private WorkspaceAvailabilityStore.Row readRow(Cursor c) {
        try {
            String savedIntent = c.getString(c.getColumnIndexOrThrow(Favorites.INTENT));
            Intent intent = Intent.parseUri(savedIntent, 0);
            ComponentName component = intent.getComponent();
            if (component == null) return null;
            return new WorkspaceAvailabilityStore.Row(identity(), c.getInt(c.getColumnIndexOrThrow(Favorites._ID)),
                    c.getLong(c.getColumnIndexOrThrow(Favorites.PROFILE_ID)), component.getPackageName(),
                    component.flattenToString(), savedIntent, c.getLong(c.getColumnIndexOrThrow(Favorites.MODIFIED)),
                    mDb.getWorkspaceItemGeneration(c.getInt(c.getColumnIndexOrThrow(Favorites._ID))));
        } catch (Exception e) {
            // Corrupt intents remain on the loader's existing validation path, never a guessed package match.
            return null;
        }
    }
}
