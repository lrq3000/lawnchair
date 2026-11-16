/* Copyright (C) 2026 Lawnchair. Licensed under the Apache License, Version 2.0. */
package app.lawnchair.model;

public final class WorkspaceAppUpdateTest {
    public static void main(String[] args) {
        IncomingUpdate item = new IncomingUpdate();
        WorkspaceAppUpdate.reconcile(item);
        if (item.progress != 73 || !item.nonResizeable || !item.archived || !item.appearanceUpdated) {
            throw new AssertionError("Preserving an AVAILABLE ordinary target must not skip progress/resizability/archive reconciliation");
        }
        if (!"original-intent".equals(item.intent) || item.id != 73 || item.otherDisabledFlags != 12) {
            throw new AssertionError("Metadata reconciliation must not replace row identity/intent or unrelated disabled states");
        }
        System.out.println("PASS 1 production successful-app-update orchestration test");
    }

    private static final class IncomingUpdate implements WorkspaceAppUpdate.Target {
        final int id = 73;
        final String intent = "original-intent";
        final int otherDisabledFlags = 12;
        int progress;
        boolean nonResizeable;
        boolean archived;
        boolean appearanceUpdated;
        public void updateProgress() { progress = 73; }
        public void updateResizability() { nonResizeable = true; }
        public void updateArchival() { archived = true; }
        public void updateAppearance() { appearanceUpdated = true; }
    }
}
