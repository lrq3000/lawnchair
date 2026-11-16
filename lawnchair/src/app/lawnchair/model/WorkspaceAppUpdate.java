/* Copyright (C) 2026 Lawnchair. Licensed under the Apache License, Version 2.0. */
package app.lawnchair.model;

/** Successful package reconciliation remains independent of preserving a workspace target's identity/intent. */
public final class WorkspaceAppUpdate {
    private WorkspaceAppUpdate() { }

    public interface Target {
        void updateProgress();
        void updateResizability();
        void updateArchival();
        void updateAppearance();
    }

    public static void reconcile(Target target) {
        target.updateProgress();
        target.updateResizability();
        target.updateArchival();
        target.updateAppearance();
    }
}
