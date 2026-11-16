/* Copyright (C) 2026 Lawnchair. Licensed under the Apache License, Version 2.0. */
package app.lawnchair.model;

import java.util.function.Supplier;

/** Provenance-bearing icon result: an unresolved configured source is not a successfully loaded stock icon. */
public final class WorkspaceIconSource<T> {
    private final boolean mConfigured;
    private final T mIcon;

    private WorkspaceIconSource(boolean configured, T icon) {
        mConfigured = configured;
        mIcon = icon;
    }

    public static <T> WorkspaceIconSource<T> resolve(boolean configured, Supplier<T> selected, Supplier<T> stock) {
        return new WorkspaceIconSource<>(configured, configured ? selected.get() : stock.get());
    }

    public boolean isConfigured() { return mConfigured; }
    public boolean isUsable() { return !mConfigured || mIcon != null; }
    public T getIcon() { return mIcon; }
}
