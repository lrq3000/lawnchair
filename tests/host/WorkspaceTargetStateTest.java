/* Copyright (C) 2026 Lawnchair. Licensed under the Apache License, Version 2.0. */
package app.lawnchair.model;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the production loader observation state with deliberately changing package/activity lookup boundaries. */
public final class WorkspaceTargetStateTest {
    public static void main(String[] args) {
        checkDisagreement(false);
        checkDisagreement(true);
        checkFinalResolutionDisagreement();
        System.out.println("PASS 3 production loader TOCTOU tests");
    }

    private static void checkDisagreement(boolean activityDisappears) {
        Disk disk = new Disk();
        WorkspaceAvailabilityStore store = new WorkspaceAvailabilityStore(disk);
        WorkspaceAvailabilityStore.Row row = new WorkspaceAvailabilityStore.Row(
                "db", 73, 10, "pkg", "pkg/Main", "original-intent-with-extras", 100);
        store.observe(10, "pkg", true, "uuid", null);
        int first = store.evaluate(row, true); // Exact component is initially registered.
        WorkspaceTargetState state = new WorkspaceTargetState(first, () -> {
            store.unavailable(10, "pkg");
            store.evaluate(row, false);
        });
        AtomicInteger queries = new AtomicInteger();
        if (activityDisappears && !state.recheck(() -> queries.incrementAndGet() == 1)) {
            throw new AssertionError("First package observation should resolve");
        }
        boolean enabled = state.recheck(() -> { queries.incrementAndGet(); return false; });
        if (enabled || state.getDecision() != WorkspaceAvailabilityStore.KEEP) {
            throw new AssertionError("Conflicting " + (activityDisappears ? "activity" : "package")
                    + " lookup must latch KEEP before fallback/deletion");
        }
        WorkspaceAvailabilityStore restarted = new WorkspaceAvailabilityStore(disk);
        if (restarted.evaluate(row, false) != WorkspaceAvailabilityStore.KEEP) {
            throw new AssertionError("Disagreement must durably protect the original row/component");
        }
    }

    private static void checkFinalResolutionDisagreement() {
        AtomicInteger preserved = new AtomicInteger();
        WorkspaceTargetState state = new WorkspaceTargetState(WorkspaceAvailabilityStore.AVAILABLE,
                preserved::incrementAndGet);
        state.unresolved();
        if (state.getDecision() != WorkspaceAvailabilityStore.KEEP || preserved.get() != 1) {
            throw new AssertionError("Late LoaderCursor resolution failure must preserve the saved target");
        }
    }

    private static final class Disk implements WorkspaceAvailabilityStore.Persistence {
        byte[] bytes;
        public byte[] read() throws IOException { return bytes; }
        public void write(byte[] next) throws IOException { bytes = next.clone(); }
    }
}
