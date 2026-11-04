/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;
import org.junit.Test;
import static org.junit.Assert.*;
public class VisibilityOwnershipTest {
    static final class Target { int visibility; Target(int value) { visibility=value; } }
    final VisibilityOwnership<Target> owner=new VisibilityOwnership<>(new VisibilityOwnership.Access<Target>() {
        public int get(Target view) { return view.visibility; }
        public void set(Target view,int value) { view.visibility=owner.nativeWrite(view,value); }
    });
    @Test public void releaseUsesLatestNativeIntentAndIsIdempotent() {
        Target view=new Target(0); owner.constrain(view,8);
        view.visibility=owner.nativeWrite(view,4); assertEquals(8,view.visibility);
        owner.release(view); assertEquals(4,view.visibility);
        view.visibility=8; owner.release(view); assertEquals(8,view.visibility);
    }
    @Test public void nativeGoneIsNotAnOwnedHide() {
        Target view=new Target(8); owner.release(view); assertEquals(8,view.visibility);
        owner.constrain(view,8); owner.releaseAll(); assertEquals(8,view.visibility);
    }
    @Test public void replacingHostCannotRestoreOrConstrainOldObjects() {
        Target old=new Target(4), next=new Target(0);
        owner.constrain(old,8); owner.releaseAll(); owner.constrain(next,8);
        old.visibility=owner.nativeWrite(old,0); assertEquals(0,old.visibility);
        next.visibility=owner.nativeWrite(next,4); owner.releaseAll(); assertEquals(4,next.visibility);
    }
}
