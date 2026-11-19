/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class IslandRulePublisherTest {
    private final AtomicLong clock = new AtomicLong(100);
    private final IslandRulePublisher publisher = new IslandRulePublisher(clock::get);

    private static IslandRulePublisher.Head head(String session, long revision) {
        return new IslandRulePublisher.Head(session, revision);
    }

    private IslandRulePublisher.Task begin(IslandRulePublisher.Head target) {
        IslandRulePublisher.Task task = publisher.begin();
        assertFalse(publisher.current().loaded);
        assertTrue(publisher.target(task, target));
        return task;
    }

    private IslandRuleSetLoader.Snapshot empty(IslandRulePublisher.Task task, long revision) {
        return publisher.load(task, revision, Collections.emptyList());
    }

    @Test public void committedEmptyIsValidButUninitializedAndUnknownHeadsAreNot() {
        IslandRulePublisher.Task task = publisher.begin();
        assertFalse(publisher.target(task, head("app", 0)));
        assertFalse(publisher.target(task, null));
        IslandRulePublisher.Head target = head("app", 9);
        assertTrue(publisher.target(task, target));
        assertFalse(publisher.finish(task, target, empty(task, 8)));
        assertTrue(publisher.finish(task, target, empty(task, 9)));
        assertTrue(publisher.current().loaded);
        assertEquals(9, publisher.current().ruleSet.revision);
        assertTrue(publisher.current().orderedActiveEntries.isEmpty());
    }

    @Test public void remainderOfPartiallyLoadedCandidateNeverBecomesVisible() throws Exception {
        byte[] valid = Files.readAllBytes(Paths.get("docs", "samples", "rule-travel.valid.json"));
        IslandRulePublisher.Head target = head("app", 1);
        IslandRulePublisher.Task task = begin(target);
        IslandRuleSetLoader.Snapshot partial = publisher.load(task, 1, Arrays.asList(
                new IslandRuleSetLoader.Source("good", 1, true, () -> new ByteArrayInputStream(valid)),
                new IslandRuleSetLoader.Source("bad", 1, true, () -> new ByteArrayInputStream(new byte[]{'x'}))));
        assertTrue(partial.loaded);
        assertEquals(1, partial.orderedActiveEntries.size());
        assertEquals(1, partial.failures.size());
        assertFalse(publisher.finish(task, target, partial));
        assertFalse(publisher.current().loaded);
        assertTrue(publisher.current().orderedActiveEntries.isEmpty());
    }

    @Test public void reconnectAndLateCompletionBothRequireCurrentSessionAndGeneration() {
        IslandRulePublisher.Head oldApp = head("old", 1), newApp = head("new", 1);
        IslandRulePublisher.Task stale = begin(oldApp);
        assertFalse(publisher.finish(stale, newApp, empty(stale, 1)));
        IslandRulePublisher.Task fresh = begin(newApp);
        assertFalse(publisher.finish(fresh, newApp, empty(stale, 1)));
        assertTrue(publisher.finish(fresh, newApp, empty(fresh, 1)));
        assertFalse(publisher.finish(stale, oldApp, empty(stale, 1)));
        publisher.fail(stale);
        assertSame(newApp, publisher.applied());
    }

    @Test public void lateNotificationTriggersFreshLookupAndCannotSupplyRevision() {
        IslandRulePublisher.Head prior = head("app", 1), latest = head("app", 2);
        IslandRulePublisher.Task stale = begin(prior);
        publisher.invalidate();
        assertFalse(publisher.target(stale, prior));
        IslandRulePublisher.Task fresh = begin(latest);
        assertFalse(publisher.finish(stale, prior, empty(stale, 1)));
        assertTrue(publisher.finish(fresh, latest, empty(fresh, 2)));
        publisher.invalidate();
        IslandRulePublisher.Task recheck = begin(latest);
        assertTrue(publisher.finish(recheck, latest, empty(recheck, 2)));
        assertEquals(2, publisher.current().ruleSet.revision);
    }

    @Test public void sameHeadRejectsPartialAndStaleCandidatesWithoutExtendingLease() {
        IslandRulePublisher.Head target = head("app", 2);
        IslandRulePublisher.Task stale = begin(target);
        IslandRuleSetLoader.Snapshot staleCandidate = empty(stale, 2);
        IslandRulePublisher.Task first = begin(target);
        IslandRuleSetLoader.Snapshot verified = empty(first, 2);
        assertTrue(publisher.finish(first, target, verified));
        long originalExpiry = publisher.status().expires;

        clock.addAndGet(15_000);
        IslandRulePublisher.Task retry = publisher.begin();
        assertTrue(publisher.target(retry, target));
        IslandRuleSetLoader.Snapshot rejected = publisher.load(retry, 2, Collections.singletonList(
                new IslandRuleSetLoader.Source("broken", 1, true, () -> new ByteArrayInputStream(new byte[]{'x'}))));
        assertFalse(rejected.failures.isEmpty());
        assertFalse(publisher.finish(retry, target, rejected));
        assertFalse(publisher.finish(retry, target, staleCandidate));
        assertSame(verified, publisher.current());
        assertEquals(originalExpiry, publisher.status().expires);
        clock.set(originalExpiry);
        assertFalse(publisher.current().loaded);
        assertNull(publisher.status().head);
    }

    @Test public void fullyVerifiedSameHeadRenewsLeaseAndKeepsPublishedSnapshotIdentity() {
        IslandRulePublisher.Head target = head("app", 2);
        IslandRulePublisher.Task first = begin(target);
        IslandRuleSetLoader.Snapshot verified = empty(first, 2);
        assertTrue(publisher.finish(first, target, verified));
        long originalExpiry = publisher.status().expires;
        clock.addAndGet(15_000);
        IslandRulePublisher.Task retry = publisher.begin();
        assertTrue(publisher.target(retry, target));
        assertTrue(publisher.finish(retry, target, empty(retry, 2)));
        assertSame(verified, publisher.current());
        assertEquals(originalExpiry + 15_000, publisher.status().expires);
    }

    @Test public void failedPeriodicRefreshKeepsCurrentSnapshotUntilLeaseExpires() {
        IslandRulePublisher.Head head = head("app", 4);
        IslandRulePublisher.Task first = begin(head);
        assertTrue(publisher.finish(first, head, empty(first, 4)));
        clock.addAndGet(15_000);
        IslandRulePublisher.Task retry = publisher.begin();
        assertTrue(publisher.current().loaded);
        publisher.fail(retry);
        assertTrue(publisher.current().loaded);
        clock.set(publisher.status().expires);
        assertFalse(publisher.current().loaded);
    }

    @Test public void missedNotificationsRecoverByReconciliationAndLeaseExpiresWithoutIt() {
        IslandRulePublisher.Head prior = head("app", 1), latest = head("app", 2);
        IslandRulePublisher.Task first = begin(prior);
        assertTrue(publisher.finish(first, prior, empty(first, 1)));
        clock.addAndGet(15_000);
        IslandRulePublisher.Task poll = publisher.begin();
        assertTrue(publisher.current().loaded);
        assertTrue(publisher.target(poll, latest));
        assertSame("previous verified snapshot remains live while polling", prior,
                publisher.applied());
        assertTrue(publisher.current().loaded);
        assertTrue(publisher.finish(poll, latest, empty(poll, 2)));
        assertEquals(2, publisher.current().ruleSet.revision);
        clock.set(publisher.status().expires);
        assertFalse(publisher.current().loaded);
        assertNull(publisher.applied());
        IslandRulePublisher.Task reconnect = begin(latest);
        clock.set(reconnect.deadline);
        assertFalse(publisher.finish(reconnect, latest, empty(reconnect, 2)));
        assertFalse(publisher.current().loaded);
    }
}
