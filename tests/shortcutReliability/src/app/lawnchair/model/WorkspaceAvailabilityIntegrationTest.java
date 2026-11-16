/* Copyright (C) 2026 Lawnchair. Licensed under the Apache License, Version 2.0. */
package app.lawnchair.model;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.MODEL_EXECUTOR;
import static org.junit.Assert.*;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Process;
import android.util.LongSparseArray;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.model.BgDataModel;
import com.android.launcher3.model.LoaderCursor;
import com.android.launcher3.model.ModelDbController;
import com.android.launcher3.model.SdCardAvailableReceiver;
import com.android.launcher3.model.UserManagerState;
import com.android.launcher3.model.WorkspaceItemProcessor;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.pm.UserCache;
import com.android.launcher3.util.ContentWriter;
import com.android.launcher3.util.MainThreadInitializedObject;
import com.android.launcher3.util.MainThreadInitializedObject.SandboxContext;
import com.android.launcher3.util.PackageManagerHelper;
import com.android.launcher3.util.SafeCloseable;
import com.android.launcher3.widget.WidgetInflater;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Real cursor, favorites database, model, journal, package callbacks, icon decode, and receiver; no mirrored policy or mock model. */
@RunWith(AndroidJUnit4.class)
public class WorkspaceAvailabilityIntegrationTest {
    public static class TargetActivity extends Activity { }

    private TestContext mContext;
    private LauncherAppState mApp;
    private ModelDbController mDb;
    private long mSerial;
    private byte[] mIcon;

    @Before
    public void setUp() throws Exception {
        mContext = new TestContext(InstrumentationRegistry.getInstrumentation().getTargetContext());
        mApp = MAIN_EXECUTOR.submit(() -> LauncherAppState.getInstance(mContext)).get();
        mDb = mApp.getModel().getModelDbController();
        mSerial = UserCache.INSTANCE.get(mContext).getSerialNumberForUser(Process.myUserHandle());
        Bitmap icon = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        icon.eraseColor(Color.RED);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        icon.compress(Bitmap.CompressFormat.PNG, 100, out);
        mIcon = out.toByteArray();
    }

    @After
    public void tearDown() throws Exception {
        MODEL_EXECUTOR.submit(() -> { }).get();
        MAIN_EXECUTOR.submit(mContext::onDestroy).get();
        // The isolated test database is in memory. The per-test journal directory is retained under the app cache for diagnosis.
    }

    @Test
    public void bootCompleteAbsence_keepsWorkspaceIdentityIntentAndCustomAppearance() throws Exception {
        MODEL_EXECUTOR.submit(() -> {
            Intent original = missingIntent();
            insert(73, original, Favorites.CONTAINER_DESKTOP, 7, 4, mSerial);
            BgDataModel model = new BgDataModel();
            WorkspaceItemInfo item = load(73, model);
            assertNotNull(item);
            assertSame(item, model.itemsIdMap.get(73));
            assertEquals(7, item.screenId);
            assertEquals(1, item.cellX);
            assertEquals(2, item.cellY);
            assertEquals(4, item.rank);
            assertEquals(original.toUri(0), item.intent.toUri(0));
            assertEquals("Saved custom label", item.title.toString());
            assertTrue(WorkspaceAvailability.isUnavailable(item));
            assertFalse(mApp.getIconCache().isDefaultIcon(item.bitmap, item.user));
            assertTrue(mApp.getModel().getWorkspaceAvailability().hasFavorite(73));
        }).get();
    }

    @Test
    public void packageDisappearsBetweenPositiveResolutionAndProcessorCheck_preservesOriginal() throws Exception {
        assertProcessorLookupRace(false);
    }

    @Test
    public void activityDisappearsBetweenPositiveResolutionAndFallbackCheck_preservesOriginal() throws Exception {
        assertProcessorLookupRace(true);
    }

    private void assertProcessorLookupRace(boolean activityRace) throws Exception {
        LauncherApps apps = org.mockito.Mockito.spy(mContext.getSystemService(LauncherApps.class));
        String pkg = InstrumentationRegistry.getInstrumentation().getContext().getPackageName();
        ComponentName component = new ComponentName(pkg, TargetActivity.class.getName());
        if (activityRace) {
            org.mockito.Mockito.doReturn(true, false).when(apps).isActivityEnabled(component, Process.myUserHandle());
        } else {
            org.mockito.Mockito.doReturn(true, false).when(apps).isPackageEnabled(pkg, Process.myUserHandle());
        }
        mContext.mServices.put(mContext.getSystemServiceName(LauncherApps.class), apps);
        MODEL_EXECUTOR.submit(() -> {
            Intent original = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(component).putExtra("original-extra", "must-survive-fallback");
            insert(86, original, Favorites.CONTAINER_DESKTOP, 7, 0, mSerial);
            WorkspaceItemInfo item = load(86, new BgDataModel());
            assertNotNull(item);
            assertEquals(original.toUri(0), item.intent.toUri(0));
            assertTrue(WorkspaceAvailability.isUnavailable(item));
            try (Cursor c = mDb.query(Favorites.TABLE_NAME, null, Favorites._ID + "=86", null, null)) {
                assertTrue(c.moveToFirst());
                assertEquals(original.toUri(0), c.getString(c.getColumnIndexOrThrow(Favorites.INTENT)));
            }
        }).get();
    }

    @Test
    public void missingFolderAndDockChildren_remainInTheirContainers() throws Exception {
        MODEL_EXECUTOR.submit(() -> {
            insertFolder(88);
            insert(74, missingIntent(), 88, 0, 7, mSerial);
            insert(75, missingIntent(), Favorites.CONTAINER_HOTSEAT, 1, 1, mSerial);
            BgDataModel model = new BgDataModel();
            loadItem(88, model);
            WorkspaceItemInfo folderChild = load(74, model);
            WorkspaceItemInfo dockChild = load(75, model);
            assertNotNull(folderChild);
            assertNotNull(dockChild);
            assertEquals(88, folderChild.container);
            assertEquals(7, folderChild.rank);
            assertTrue(model.collections.get(88).getContents().contains(folderChild));
            assertEquals(Favorites.CONTAINER_HOTSEAT, dockChild.container);
        }).get();
    }

    @Test
    public void evictedLowResFolderChild_asyncHighResRequestUsesSavedCustomImageAndBindsFolderPage() throws Exception {
        BgDataModel model = new BgDataModel();
        WorkspaceItemInfo child = MODEL_EXECUTOR.submit(() -> {
            insertFolder(88);
            insert(74, missingIntent(), 88, 0, 7, mSerial);
            loadItem(88, model);
            WorkspaceItemInfo item = load(74, model);
            item.bitmap = com.android.launcher3.icons.BitmapInfo.LOW_RES_INFO;
            item.title = "stale low-res label";
            mApp.getIconCache().removeIconsForPkg("missing.external.app", item.user);
            return item;
        }).get();
        CompletableFuture<com.android.launcher3.model.data.ItemInfoWithIcon> rebound = new CompletableFuture<>();
        MAIN_EXECUTOR.submit(() -> mApp.getIconCache().updateIconInBackground(rebound::complete, child)).get();
        assertSame(child, rebound.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(child.bitmap.isNullOrLowRes());
        assertEquals("Saved custom label", child.title.toString());
        assertEquals(Color.RED, child.bitmap.icon.getPixel(child.bitmap.icon.getWidth() / 2, child.bitmap.icon.getHeight() / 2));
        java.util.Set<Integer> boundIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.Set<Integer> boundPages = java.util.concurrent.ConcurrentHashMap.newKeySet();
        BgDataModel.Callbacks callbacks = new BgDataModel.Callbacks() {
            @Override public com.android.launcher3.util.IntSet getPagesToBindSynchronously(com.android.launcher3.util.IntArray pages) {
                com.android.launcher3.util.IntSet result = new com.android.launcher3.util.IntSet();
                result.add(7);
                return result;
            }
            @Override public void bindItems(java.util.List<ItemInfo> items, boolean animate) {
                for (ItemInfo item : items) boundIds.add(item.id);
            }
            @Override public void bindScreens(com.android.launcher3.util.IntArray screens) {
                for (int i = 0; i < screens.size(); i++) boundPages.add(screens.get(i));
            }
        };
        MODEL_EXECUTOR.submit(() -> new com.android.launcher3.model.BaseLauncherBinder(mApp, model,
                new com.android.launcher3.model.AllAppsList(mApp.getIconCache(), new com.android.launcher3.AppFilter(mContext)),
                new BgDataModel.Callbacks[] {callbacks}).bindWorkspace(true, false)).get();
        MAIN_EXECUTOR.submit(() -> { }).get();
        assertTrue(boundIds.contains(88));
        assertTrue(boundPages.contains(7));
        assertTrue(model.collections.get(88).getContents().contains(child));
    }

    @Test
    public void receiverNegativeLookup_doesNotBecomeConfirmedUninstall() throws Exception {
        MODEL_EXECUTOR.submit(() -> {
            insert(76, missingIntent(), Favorites.CONTAINER_DESKTOP, 7, 0, mSerial);
            load(76, new BgDataModel());
            new SdCardAvailableReceiver(mApp, new HashSet<>()).onReceive(mContext,
                    new Intent(Intent.ACTION_BOOT_COMPLETED));
        }).get();
        MODEL_EXECUTOR.submit(() -> assertTrue(mApp.getModel().getWorkspaceAvailability().hasFavorite(76))).get();
    }

    @Test
    public void confirmedUninstallWhileUnloaded_deletesCorrectProfileBeforeDiscard() throws Exception {
        MODEL_EXECUTOR.submit(() -> {
            insert(77, missingIntent(), Favorites.CONTAINER_DESKTOP, 7, 0, mSerial);
            insert(78, missingIntent(), Favorites.CONTAINER_DESKTOP, 7, 0, mSerial + 1000);
        }).get();
        assertFalse(mApp.getModel().isModelLoaded());
        mApp.getModel().newModelCallbacks().onPackageRemoved("missing.external.app", Process.myUserHandle());
        MODEL_EXECUTOR.submit(() -> {
            assertFalse(mApp.getModel().getWorkspaceAvailability().hasFavorite(77));
            assertTrue(mApp.getModel().getWorkspaceAvailability().hasFavorite(78));
        }).get();
    }

    @Test
    public void serviceRestart_restoresUnavailableAppearanceFromFavorites() throws Exception {
        MODEL_EXECUTOR.submit(() -> {
            insert(79, missingIntent(), Favorites.CONTAINER_DESKTOP, 7, 0, mSerial);
            load(79, new BgDataModel());
            WorkspaceAvailability restarted = new WorkspaceAvailability(mApp, mDb);
            restarted.beginLoad();
            WorkspaceItemInfo item = load(79, new BgDataModel());
            assertEquals(WorkspaceAvailabilityStore.KEEP, restarted.evaluate(item));
            assertEquals("Saved custom label", item.title.toString());
            assertFalse(mApp.getIconCache().isDefaultIcon(item.bitmap, item.user));
        }).get();
    }

    @Test
    public void exactTargetRecovery_updatesSameItemAndClearsOnlyUnavailable() throws Exception {
        MODEL_EXECUTOR.submit(() -> {
            String testPackage = InstrumentationRegistry.getInstrumentation().getContext().getPackageName();
            Intent original = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(testPackage, TargetActivity.class.getName()))
                    .putExtra("original", "kept");
            insert(80, original, Favorites.CONTAINER_DESKTOP, 7, 0, mSerial);
            BgDataModel model = new BgDataModel();
            WorkspaceItemInfo item = load(80, model);
            assertNotNull(item);
            item.runtimeStatusFlags |= WorkspaceItemInfo.FLAG_DISABLED_NOT_AVAILABLE
                    | WorkspaceItemInfo.FLAG_DISABLED_QUIET_USER | WorkspaceItemInfo.FLAG_DISABLED_SUSPENDED;
            mApp.getModel().getWorkspaceAvailability().revalidate(model);
            assertSame(item, model.itemsIdMap.get(80));
            assertFalse(WorkspaceAvailability.isUnavailable(item));
            assertTrue((item.runtimeStatusFlags & WorkspaceItemInfo.FLAG_DISABLED_QUIET_USER) != 0);
            assertTrue((item.runtimeStatusFlags & WorkspaceItemInfo.FLAG_DISABLED_SUSPENDED) != 0);
            assertEquals(original.toUri(0), item.intent.toUri(0));
            assertTrue(mApp.getModel().getWorkspaceAvailability().hasFavorite(80));
        }).get();
    }

    @Test
    public void unavailableDatabaseWrite_doesNotReplaceGoodLabelOrIcon() throws Exception {
        MODEL_EXECUTOR.submit(() -> {
            insert(81, missingIntent(), Favorites.CONTAINER_DESKTOP, 7, 0, mSerial);
            WorkspaceItemInfo item = load(81, new BgDataModel());
            item.title = "";
            item.bitmap = mApp.getIconCache().getDefaultIcon(item.user);
            ContentWriter writer = new ContentWriter(mContext);
            item.onAddToDatabase(writer);
            ContentValues values = writer.getValues(mContext);
            assertFalse(values.containsKey(Favorites.TITLE));
            assertFalse(values.containsKey(Favorites.ICON));
            try (Cursor c = mDb.query(Favorites.TABLE_NAME, null, Favorites._ID + "=81", null, null)) {
                assertTrue(c.moveToFirst());
                assertEquals("Saved custom label", c.getString(c.getColumnIndexOrThrow(Favorites.TITLE)));
                assertArrayEquals(mIcon, c.getBlob(c.getColumnIndexOrThrow(Favorites.ICON)));
            }
        }).get();
    }

    @Test
    public void identityMigration_isNonDestructiveAndResetDoesNotReuseOldIdentity() throws Exception {
        MODEL_EXECUTOR.submit(() -> {
            insert(82, missingIntent(), Favorites.CONTAINER_DESKTOP, 7, 0, mSerial);
            int version = mDb.getDb().getVersion();
            String identity = mDb.getWorkspaceIdentity();
            assertTrue(mApp.getModel().getWorkspaceAvailability().hasFavorite(82));
            assertEquals(version, mDb.getDb().getVersion());
            assertEquals(identity, mDb.getWorkspaceIdentity());
            mDb.createEmptyDB();
            assertNotEquals(identity, mDb.getWorkspaceIdentity());
        }).get();
    }

    @Test
    public void updateAvailabilityAndForeground_keepOriginalItemWithoutDuplicate() throws Exception {
        MODEL_EXECUTOR.submit(() -> {
            insert(83, missingIntent(), Favorites.CONTAINER_DESKTOP, 7, 0, mSerial);
            mDb.clearEmptyDbFlag();
        }).get();
        BgDataModel.Callbacks callbacks = new BgDataModel.Callbacks() { };
        MAIN_EXECUTOR.submit(() -> mApp.getModel().addCallbacksAndLoad(callbacks)).get();
        MODEL_EXECUTOR.submit(() -> { }).get();
        CompletableFuture<BgDataModel> loaded = new CompletableFuture<>();
        mApp.getModel().enqueueModelUpdateTask((controller, data, apps) -> loaded.complete(data));
        BgDataModel model = loaded.get(10, java.util.concurrent.TimeUnit.SECONDS);
        WorkspaceItemInfo original = (WorkspaceItemInfo) model.itemsIdMap.get(83);
        assertNotNull(original);
        mApp.getModel().newModelCallbacks().onPackagesAvailable(new String[] {"missing.external.app"},
                Process.myUserHandle(), false);
        mApp.getModel().validateModelDataOnResume();
        MODEL_EXECUTOR.submit(() -> {
            assertSame(original, model.itemsIdMap.get(83));
            assertTrue(WorkspaceAvailability.isUnavailable(original));
            try (Cursor c = mDb.query(Favorites.TABLE_NAME, null, null, null, null)) {
                assertEquals(1, c.getCount());
            }
            mApp.getModel().onWorkspaceBackground();
        }).get();
        MAIN_EXECUTOR.submit(() -> mApp.getModel().removeCallbacks(callbacks)).get();
    }

    @Test
    public void healthyOrdinaryUpdate_reconcilesProgressResizeAndArchiveWithoutReplacingIdentity() throws Exception {
        String pkg = InstrumentationRegistry.getInstrumentation().getContext().getPackageName();
        ComponentName component = new ComponentName(pkg, TargetActivity.class.getName());
        Intent original = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component).putExtra("unchanged", "saved-intent");
        MODEL_EXECUTOR.submit(() -> {
            insert(87, original, Favorites.CONTAINER_DESKTOP, 7, 0, mSerial);
            mDb.clearEmptyDbFlag();
        }).get();
        BgDataModel.Callbacks callbacks = new BgDataModel.Callbacks() { };
        MAIN_EXECUTOR.submit(() -> mApp.getModel().addCallbacksAndLoad(callbacks)).get();
        MODEL_EXECUTOR.submit(() -> { }).get();
        CompletableFuture<BgDataModel> loaded = new CompletableFuture<>();
        mApp.getModel().enqueueModelUpdateTask((controller, data, apps) -> loaded.complete(data));
        BgDataModel model = loaded.get(10, java.util.concurrent.TimeUnit.SECONDS);
        WorkspaceItemInfo item = (WorkspaceItemInfo) model.itemsIdMap.get(87);
        assertNotNull(item);
        LauncherApps apps = org.mockito.Mockito.spy(mContext.getSystemService(LauncherApps.class));
        android.content.pm.LauncherActivityInfo activity = org.mockito.Mockito.spy(apps.resolveActivity(original, item.user));
        if (com.android.launcher3.Utilities.ATLEAST_S) org.mockito.Mockito.doReturn(0.73f).when(activity).getLoadingProgress();
        if (com.android.launcher3.Utilities.ATLEAST_V) {
            android.content.pm.ActivityInfo changed = new android.content.pm.ActivityInfo(activity.getActivityInfo());
            changed.isArchived = true;
            org.mockito.Mockito.doReturn(changed).when(activity).getActivityInfo();
        }
        org.mockito.Mockito.doReturn(java.util.Collections.singletonList(activity)).when(apps).getActivityList(pkg, item.user);
        org.mockito.Mockito.doReturn(activity).when(apps).resolveActivity(org.mockito.ArgumentMatchers.any(Intent.class), org.mockito.ArgumentMatchers.eq(item.user));
        mContext.mServices.put(mContext.getSystemServiceName(LauncherApps.class), apps);
        com.android.launcher3.util.ApiWrapper wrapper = org.mockito.Mockito.mock(com.android.launcher3.util.ApiWrapper.class);
        org.mockito.Mockito.when(wrapper.isNonResizeableActivity(activity)).thenReturn(true);
        mContext.putObject(com.android.launcher3.util.ApiWrapper.INSTANCE, wrapper);
        MODEL_EXECUTOR.submit(() -> item.runtimeStatusFlags |= WorkspaceItemInfo.FLAG_DISABLED_QUIET_USER
                | WorkspaceItemInfo.FLAG_DISABLED_SUSPENDED).get();
        mApp.getModel().newModelCallbacks().onPackageChanged(pkg, item.user);
        MODEL_EXECUTOR.submit(() -> {
            assertSame(item, model.itemsIdMap.get(87));
            assertEquals(original.toUri(0), item.intent.toUri(0));
            assertTrue(item.isNonResizeable());
            assertEquals(com.android.launcher3.Utilities.ATLEAST_S ? 73 : 100, item.getProgressLevel());
            if (com.android.launcher3.Utilities.ATLEAST_V && com.android.launcher3.Flags.enableSupportForArchiving()) assertTrue(item.isArchived());
            assertTrue((item.runtimeStatusFlags & WorkspaceItemInfo.FLAG_DISABLED_QUIET_USER) != 0);
            assertTrue((item.runtimeStatusFlags & WorkspaceItemInfo.FLAG_DISABLED_SUSPENDED) != 0);
        }).get();
        MAIN_EXECUTOR.submit(() -> mApp.getModel().removeCallbacks(callbacks)).get();
    }

    @Test
    public void uninstallThenReinstall_doesNotApplyOldRemovalToNewRow() throws Exception {
        MODEL_EXECUTOR.submit(() -> insert(84, missingIntent(), Favorites.CONTAINER_DESKTOP, 7, 0, mSerial)).get();
        mApp.getModel().newModelCallbacks().onPackageRemoved("missing.external.app", Process.myUserHandle());
        MODEL_EXECUTOR.submit(() -> {
            assertFalse(mApp.getModel().getWorkspaceAvailability().hasFavorite(84));
            insert(85, missingIntent(), Favorites.CONTAINER_DESKTOP, 7, 0, mSerial);
        }).get();
        mApp.getModel().newModelCallbacks().onPackageAdded("missing.external.app", Process.myUserHandle());
        MODEL_EXECUTOR.submit(() -> {
            assertFalse(mApp.getModel().getWorkspaceAvailability().hasFavorite(84));
            assertTrue(mApp.getModel().getWorkspaceAvailability().hasFavorite(85));
        }).get();
    }

    private WorkspaceItemInfo load(int id, BgDataModel model) {
        return (WorkspaceItemInfo) loadItem(id, model);
    }

    private ItemInfo loadItem(int id, BgDataModel model) {
        UserManagerState users = new UserManagerState();
        users.allUsers.put(mSerial, Process.myUserHandle());
        try (LoaderCursor c = new LoaderCursor(mDb.query(Favorites.TABLE_NAME, null,
                Favorites._ID + "=?", new String[] {Integer.toString(id)}, null), mApp, users,
                PackageManagerHelper.INSTANCE.get(mContext), null)) {
            assertTrue(c.moveToNext());
            new WorkspaceItemProcessor(c, null, UserCache.INSTANCE.get(mContext), users,
                    mContext.getSystemService(LauncherApps.class), new HashSet<>(), new HashMap<>(),
                    mApp, model, new HashMap<>(), new HashMap<>(), true,
                    new WidgetInflater(mContext), PackageManagerHelper.INSTANCE.get(mContext),
                    new ArrayList<>(), new LongSparseArray<>(), new ArrayList<>()).processItem();
        }
        return model.itemsIdMap.get(id);
    }

    private void insertFolder(int id) {
        ContentValues row = new ContentValues();
        row.put(Favorites._ID, id);
        row.put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_FOLDER);
        row.put(Favorites.PROFILE_ID, mSerial);
        row.put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP);
        row.put(Favorites.SCREEN, 7);
        row.put(Favorites.CELLX, 1);
        row.put(Favorites.CELLY, 2);
        row.put(Favorites.TITLE, "Persisted folder");
        assertTrue(mDb.insert(Favorites.TABLE_NAME, row) >= 0);
    }

    private void insert(int id, Intent intent, int container, int screen, int rank, long serial) {
        ContentValues row = new ContentValues();
        row.put(Favorites._ID, id);
        row.put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION);
        row.put(Favorites.PROFILE_ID, serial);
        row.put(Favorites.INTENT, intent.toUri(0));
        row.put(Favorites.TITLE, "Saved custom label");
        row.put(Favorites.ICON, mIcon);
        row.put(Favorites.CONTAINER, container);
        row.put(Favorites.SCREEN, screen);
        row.put(Favorites.CELLX, 1);
        row.put(Favorites.CELLY, 2);
        row.put(Favorites.RANK, rank);
        row.put(Favorites.RESTORED, 0);
        row.put(Favorites.SPANX, 1);
        row.put(Favorites.SPANY, 1);
        assertTrue(mDb.insert(Favorites.TABLE_NAME, row) >= 0);
    }

    private Intent missingIntent() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName("missing.external.app", "missing.external.app.Main"))
                .putExtra("original-extra", "kept");
    }

    static final class TestContext extends SandboxContext {
        private final java.util.Map<String, Object> mServices = new java.util.HashMap<>();
        private final File mJournalDirectory;
        private final String mPreferencesPrefix = "shortcut-reliability-" + UUID.randomUUID() + "-";

        TestContext(Context context) {
            super(context);
            mJournalDirectory = new File(context.getCacheDir(), "shortcut-reliability-" + UUID.randomUUID());
            if (!mJournalDirectory.mkdirs()) throw new IllegalStateException("Cannot isolate test journal");
        }

        @Override
        public File getNoBackupFilesDir() {
            return mJournalDirectory;
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return super.getSharedPreferences(mPreferencesPrefix + name, mode);
        }

        @Override
        public Object getSystemService(String name) {
            return mServices.containsKey(name) ? mServices.get(name) : super.getSystemService(name);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends SafeCloseable> T createObject(MainThreadInitializedObject<T> object) {
            if (object == LauncherAppState.INSTANCE) return (T) new LauncherAppState(this, null);
            return super.createObject(object);
        }
    }
}
