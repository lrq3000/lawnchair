/* Copyright (C) 2026 Lawnchair. Licensed under the Apache License, Version 2.0. */
package app.lawnchair.model;

public final class WorkspaceIconSourceTest {
    public static void main(String[] args) {
        String[] durable = {"CUSTOM-red"};
        WorkspaceIconSource<String> missing = WorkspaceIconSource.resolve(true, () -> null, () -> "STOCK-fullres-nondefault");
        if (missing.isUsable()) durable[0] = missing.getIcon();
        if (!"CUSTOM-red".equals(durable[0]) || missing.isUsable()) {
            throw new AssertionError("A resolved app's failed selected icon resource must not replace the last custom snapshot with stock fallback");
        }
        WorkspaceIconSource<String> present = WorkspaceIconSource.resolve(true, () -> "CUSTOM-blue", () -> "STOCK");
        if (!present.isUsable() || !"CUSTOM-blue".equals(present.getIcon())) throw new AssertionError("Loaded selection must be usable");
        WorkspaceIconSource<String> normal = WorkspaceIconSource.resolve(false, () -> null, () -> "STOCK");
        if (!normal.isUsable() || !"STOCK".equals(normal.getIcon())) throw new AssertionError("Unconfigured stock icons retain normal behavior");
        System.out.println("PASS 3 production icon-provenance tests");
    }
}
