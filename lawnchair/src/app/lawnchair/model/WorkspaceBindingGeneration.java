/* Copyright (C) 2026 Lawnchair. Licensed under the Apache License, Version 2.0. */
package app.lawnchair.model;

/** A frozen binding lease is invalidated at callback receipt, independently of loader cancellation or later bind IDs. */
public final class WorkspaceBindingGeneration {
    private volatile long mGeneration;

    public Token capture() { return new Token(mGeneration); }
    public synchronized void invalidate() { mGeneration++; }

    public final class Token {
        private final long mCaptured;
        private Token(long captured) { mCaptured = captured; }
        public boolean isCurrent() { return mCaptured == mGeneration; }
        public boolean claim(Runnable bindIdAllocation) {
            synchronized (WorkspaceBindingGeneration.this) {
                if (!isCurrent()) return false;
                bindIdAllocation.run();
                return true;
            }
        }
    }
}
