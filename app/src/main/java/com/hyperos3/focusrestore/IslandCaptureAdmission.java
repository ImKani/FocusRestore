/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Iterator;

/** OS3 主线程交汇点：仅配对同代次、同一 SBN 实例的活动事件与焦点判定。 */
final class IslandCaptureAdmission<E, S> {
    private final ArrayDeque<Seen<E, S>> pending = new ArrayDeque<>();
    private long generation;

    private static final class Seen<E, S> {
        final WeakReference<S> source;
        final WeakReference<E> entry;
        final boolean admitted;
        Seen(S source, E entry, boolean admitted) {
            this.source = new WeakReference<>(source);
            this.entry = new WeakReference<>(entry);
            this.admitted = admitted;
        }
    }

    boolean post(long epoch, S source, E entry) {
        Seen<E, S> prior = take(epoch, source);
        if (prior != null && prior.admitted) return true;
        remember(new Seen<>(source, entry, false));
        return false;
    }

    E decide(long epoch, S source, boolean accepted) {
        Seen<E, S> prior = take(epoch, source);
        if (!accepted) return null;
        E entry = prior == null ? null : prior.entry.get();
        if (entry == null) remember(new Seen<>(source, null, true));
        return entry;
    }

    private Seen<E, S> take(long epoch, S source) {
        if (generation != epoch) {
            pending.clear();
            generation = epoch;
        }
        Iterator<Seen<E, S>> it = pending.iterator();
        while (it.hasNext()) {
            Seen<E, S> item = it.next();
            S candidate = item.source.get();
            if (candidate == null || candidate == source) it.remove();
            if (candidate == source) return item;
        }
        return null;
    }

    private void remember(Seen<E, S> item) {
        if (pending.size() >= IslandCapturePreview.MAX_RECORDS) pending.removeFirst();
        pending.addLast(item);
    }
}
