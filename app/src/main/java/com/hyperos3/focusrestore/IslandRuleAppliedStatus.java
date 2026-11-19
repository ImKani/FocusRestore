/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;

/** App 内的短期应用状态，仅用于展示最近确认的快照修订。 */
final class IslandRuleAppliedStatus {
    static final String METHOD = "report_island_rule_snapshot";
    private static IBinder token;
    private static long revision;
    private static long expires;
    private static String projectedDigest;

    private IslandRuleAppliedStatus() {}

    static Bundle encode(IBinder process, IslandRulePublisher.Applied applied) {
        Bundle value = new Bundle();
        value.putBinder("process", process);
        if (applied.head != null) {
            value.putString("session", applied.head.session);
            value.putLong("revision", applied.head.revision);
            value.putLong("expires", applied.expires);
        }
        return value;
    }

    static synchronized void report(Bundle value, boolean projection) {
        if (value == null) return;
        IBinder process = value.getBinder("process");
        if (process == null || !process.isBinderAlive()) return;
        long now = SystemClock.elapsedRealtime();
        long deadline = value.getLong("expires");
        long received = value.getLong("revision");
        String session = value.getString("session");
        boolean validHead = projection ? received == 1 && session != null && session.matches("[0-9a-f]{64}")
                : received > 0 && IslandRuleProvider.SESSION.equals(session);
        if (validHead && deadline > now && deadline - now <= IslandRulePublisher.LEASE_MS) {
            token = process;
            revision = projection ? 0 : received;
            projectedDigest = projection ? session : null;
            expires = deadline;
        } else if (token == process) {
            token = null;
            revision = 0;
            projectedDigest = null;
            expires = 0;
        }
    }

    static synchronized boolean confirmsProjection(byte[] approvedProjection) {
        return projectedDigest != null && approvedProjection != null && token != null && token.isBinderAlive()
                && SystemClock.elapsedRealtime() < expires
                && projectedDigest.equals(IslandRuleRepository.digest(approvedProjection));
    }

    static synchronized boolean confirms(long savedRevision) {
        return savedRevision > 0 && savedRevision == revision && token != null && token.isBinderAlive()
                && SystemClock.elapsedRealtime() < expires;
    }
}
