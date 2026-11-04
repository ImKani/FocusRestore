/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;
import java.util.IdentityHashMap;
import java.util.Map;

/** 约束只覆盖当前模块所有的对象；原生写入始终更新解除约束后的目标。 */
final class VisibilityOwnership<T> {
    interface Access<T> { int get(T target); void set(T target,int value); }
    private static final class State {
        int nativeValue, applied;
        State(int nativeValue,int applied) { this.nativeValue=nativeValue; this.applied=applied; }
    }
    private final Access<T> access;
    private final Map<T,State> states=new IdentityHashMap<>();
    private boolean writing;
    VisibilityOwnership(Access<T> access) { this.access=access; }
    synchronized void constrain(T target,int visibility) {
        if (target == null) return;
        State state=states.get(target);
        if (state == null) { state=new State(access.get(target),visibility); states.put(target,state); }
        state.applied=visibility;
        write(target,visibility);
    }
    synchronized int nativeWrite(T target,int desired) {
        State state=states.get(target);
        if (writing || state == null) return desired;
        state.nativeValue=desired;
        return state.applied;
    }
    synchronized void release(T target) {
        State state=states.remove(target);
        if (state != null) write(target,state.nativeValue);
    }
    synchronized void releaseAll() {
        for (T target : new java.util.ArrayList<>(states.keySet())) release(target);
    }
    private void write(T target,int value) {
        if (access.get(target) == value) return;
        boolean previous=writing; writing=true;
        try { access.set(target,value); } finally { writing=previous; }
    }
}
