/* Copyright (C) 2026 Lawnchair. Licensed under the Apache License, Version 2.0. */
package app.lawnchair.model;

import java.util.function.BooleanSupplier;

/** One loader item's availability observation; later conflicting lookups cannot authorize fallback or removal. */
public final class WorkspaceTargetState {
    private int mDecision;
    private final Runnable mPreserve;

    public WorkspaceTargetState(int decision, Runnable preserve) {
        mDecision = decision;
        mPreserve = preserve;
    }

    public int getDecision() {
        return mDecision;
    }

    public boolean recheck(BooleanSupplier lookup) {
        if (mDecision == WorkspaceAvailabilityStore.KEEP) return false;
        try {
            boolean enabled = lookup.getAsBoolean();
            if (!enabled && mDecision == WorkspaceAvailabilityStore.AVAILABLE) unresolved();
            return enabled;
        } catch (RuntimeException e) {
            unresolved();
            return false;
        }
    }

    public void unresolved() {
        if (mDecision != WorkspaceAvailabilityStore.KEEP) {
            mDecision = WorkspaceAvailabilityStore.KEEP;
            mPreserve.run();
        }
    }
}
