/* Copyright (C) 2026 Lawnchair. Licensed under the Apache License, Version 2.0. */
package app.lawnchair.model;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class WorkspaceBindingGenerationTest {
    public static void main(String[] args) throws Exception {
        WorkspaceBindingGeneration generation = new WorkspaceBindingGeneration();
        WorkspaceBindingGeneration.Token old = generation.capture();
        CountDownLatch enteredBind = new CountDownLatch(1);
        CountDownLatch continueBind = new CountDownLatch(1);
        AtomicInteger bindId = new AtomicInteger();
        AtomicInteger removedIconBinds = new AtomicInteger();
        AtomicInteger healthyBinds = new AtomicInteger();
        List<Runnable> queuedUi = java.util.Collections.synchronizedList(new ArrayList<>());
        // One callback was already queued; the old binder itself is paused after entry and before claiming another bind ID.
        queuedUi.add(() -> { if (old.isCurrent()) removedIconBinds.incrementAndGet(); });
        Thread loader = new Thread(() -> {
            enteredBind.countDown();
            try { continueBind.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
            if (old.claim(bindId::incrementAndGet)) {
                queuedUi.add(() -> { if (old.isCurrent()) removedIconBinds.incrementAndGet(); });
            }
        });
        loader.start();
        enteredBind.await();
        generation.invalidate(); // Confirmed uninstall receipt, not eventual model-task execution.
        WorkspaceBindingGeneration.Token replacement = generation.capture();
        if (!replacement.claim(bindId::incrementAndGet)) throw new AssertionError("Healthy replacement must claim a bind");
        queuedUi.add(() -> { if (replacement.isCurrent() && bindId.get() == 1) healthyBinds.incrementAndGet(); });
        continueBind.countDown();
        loader.join();
        for (Runnable callback : queuedUi) callback.run();
        if (removedIconBinds.get() != 0 || healthyBinds.get() != 1 || bindId.get() != 1) {
            throw new AssertionError("Late uninstall must fence queued/entered old binds without letting stale bind-ID allocation cancel healthy replacement");
        }
        System.out.println("PASS 1 latch-controlled production binding-generation race test");
    }
}
