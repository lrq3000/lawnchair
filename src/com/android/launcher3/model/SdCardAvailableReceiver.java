/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.model;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherModel;
import com.android.launcher3.util.PackageUserKey;

import java.util.Set;

/** Legacy entry point for revalidation only; boot completion never establishes package removal. */
public class SdCardAvailableReceiver extends BroadcastReceiver {

    private final LauncherModel mModel;

    public SdCardAvailableReceiver(LauncherAppState app, Set<PackageUserKey> packages) {
        mModel = app.getModel();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // A boot or storage broadcast is a query trigger, never a confirmed uninstall.
        mModel.revalidateWorkspaceAvailability();
    }
}
