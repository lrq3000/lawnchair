/* Copyright (C) 2026 Lawnchair. Licensed under the Apache License, Version 2.0. */
package app.lawnchair.model

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.data.iconoverride.IconOverrideRepository
import app.lawnchair.icons.IconPickerItem
import app.lawnchair.icons.IconType
import app.lawnchair.icons.LawnchairIconProvider
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.icons.LauncherIcons
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.pm.UserCache
import com.android.launcher3.util.ContentWriter
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.Executors.MODEL_EXECUTOR
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceCustomIconRegressionTest {
    @Test
    fun missingConfiguredResource_doesNotPersistStockBeforeTargetBecomesUnavailable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = WorkspaceAvailabilityIntegrationTest.TestContext(instrumentation.targetContext)
        val app = MAIN_EXECUTOR.submit<LauncherAppState> { LauncherAppState.getInstance(context) }.get()
        try {
            val component = ComponentName(instrumentation.context.packageName, WorkspaceAvailabilityIntegrationTest.TargetActivity::class.java.name)
            val key = ComponentKey(component, Process.myUserHandle())
            val selected = IconPickerItem("missing.selected.icon.pack", "missing_resource", "Custom selection", IconType.Normal)
            val overrides = IconOverrideRepository.INSTANCE.get(context)
            runBlocking {
                overrides.setOverride(key, selected)
                withTimeout(10_000) {
                    while (!overrides.overridesLoaded || overrides.overridesMap[key] != selected) delay(20)
                }
            }
            MODEL_EXECUTOR.submit {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
                val red = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
                val blob = ByteArrayOutputStream().apply { red.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
                val row = ContentValues().apply {
                    put(Favorites._ID, 90)
                    put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
                    put(Favorites.PROFILE_ID, UserCache.INSTANCE.get(context).getSerialNumberForUser(Process.myUserHandle()))
                    put(Favorites.INTENT, intent.toUri(0))
                    put(Favorites.TITLE, "Last custom label")
                    put(Favorites.ICON, blob)
                    put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
                    put(Favorites.SCREEN, 7)
                    put(Favorites.RESTORED, 0)
                }
                app.model.modelDbController.insert(Favorites.TABLE_NAME, row)
                val item = WorkspaceItemInfo().apply {
                    id = 90
                    user = Process.myUserHandle()
                    this.intent = intent
                    title = "Last custom label"
                    bitmap = LauncherIcons.obtain(context).use { it.createIconBitmap(red) }
                }
                val provider = app.iconProvider as LawnchairIconProvider
                assertFalse(provider.getWorkspaceIconSource(component, item.user, app.invariantDeviceProfile.fillResIconDpi).isUsable)
                app.model.workspaceAvailability.refreshAppearance(item)
                assertEquals(Color.RED, item.bitmap.icon.getPixel(item.bitmap.icon.width / 2, item.bitmap.icon.height / 2))
                val writer = ContentWriter(context)
                item.onAddToDatabase(writer)
                assertFalse(writer.getValues(context).containsKey(Favorites.ICON))
                app.model.workspaceAvailability.markUnavailable(item)
                app.model.modelDbController.query(Favorites.TABLE_NAME, null, "_id=90", null, null).use {
                    assertTrue(it.moveToFirst())
                    assertArrayEquals(blob, it.getBlob(it.getColumnIndexOrThrow(Favorites.ICON)))
                }
            }.get()
        } finally {
            MODEL_EXECUTOR.submit { }.get()
            MAIN_EXECUTOR.submit { context.onDestroy() }.get()
        }
    }
}
