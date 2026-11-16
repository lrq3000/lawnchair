/* Copyright (C) 2026 Lawnchair. Licensed under the Apache License, Version 2.0. */
package app.lawnchair.model;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WorkspaceIconLoadTest {
    public static void main(String[] args) {
        Map<Integer, String> favorites = new HashMap<>();
        favorites.put(88, "persisted folder on page 7");
        favorites.put(74, "custom-highres-child-with-saved-label");
        Map<Integer, String> evictableIconCache = new HashMap<>();
        evictableIconCache.put(74, "custom-highres-child-with-saved-label");
        String[] display = {"lowres-child"};
        AtomicBoolean unavailable = new AtomicBoolean(false);
        Runnable queuedHighRes = () -> WorkspaceIconLoad.run(unavailable::get,
                () -> display[0] = favorites.get(74),
                () -> display[0] = evictableIconCache.getOrDefault(74, "generic-stock-empty-label"));
        evictableIconCache.clear();
        unavailable.set(true);
        queuedHighRes.run(); // Simulates an already-queued folder-open high-res request after package-cache eviction.
        if (!"custom-highres-child-with-saved-label".equals(display[0]) || !favorites.containsKey(88)) {
            throw new AssertionError("Unavailable deferred folder loads must use the durable snapshot, never the evicted live cache");
        }
        System.out.println("PASS 1 production deferred folder-icon dispatch test");
    }
}
