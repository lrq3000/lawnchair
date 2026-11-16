/* Copyright (C) 2026 Lawnchair. Licensed under the Apache License, Version 2.0. */
package app.lawnchair.model;

import java.util.function.BooleanSupplier;

/** Shared model-worker dispatch for deferred high-resolution requests; availability is checked when work executes. */
public final class WorkspaceIconLoad {
    private WorkspaceIconLoad() { }

    public static void run(BooleanSupplier unavailable, Runnable savedAppearance, Runnable liveAppearance) {
        if (unavailable.getAsBoolean()) savedAppearance.run();
        else liveAppearance.run();
    }
}
