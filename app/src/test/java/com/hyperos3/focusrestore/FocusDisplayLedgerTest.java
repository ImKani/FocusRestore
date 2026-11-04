/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;
import org.junit.Test;
import static org.junit.Assert.*;
public class FocusDisplayLedgerTest {
    long now;
    @Test public void waitingDoesNotStartAndUpdatesDoNotExtend() {
        FocusDisplayLedger ledger=new FocusDisplayLedger(()->now);
        now=90000; assertEquals(1000,ledger.remaining("k",1,1000));
        assertTrue(ledger.displayed("k",1)); now+=500;
        assertFalse(ledger.displayed("k",1)); assertEquals(500,ledger.remaining("k",1,1000));
        now+=10000; assertEquals(0,ledger.remaining("k",1,1000));
        assertEquals(Long.MAX_VALUE,ledger.remaining("k",1,Long.MAX_VALUE));
        assertEquals(0,ledger.remaining("k",1,1000));
    }
    @Test public void newGenerationAndStaleRemovalAreIsolated() {
        FocusDisplayLedger ledger=new FocusDisplayLedger(()->now);
        ledger.displayed("k",1); now=5000;
        assertTrue(ledger.displayed("k",2)); now+=400;
        ledger.removed("k",1); assertEquals(600,ledger.remaining("k",2,1000));
        ledger.removed("k",2); assertEquals(1000,ledger.remaining("k",2,1000));
    }
    @Test public void largeLiveSetCannotReviveExpiredGeneration() {
        FocusDisplayLedger ledger=new FocusDisplayLedger(()->now);
        for (int i=0;i<500;i++) ledger.displayed("k"+i,1);
        now=100000;
        for (int i=0;i<500;i++) assertEquals(0,ledger.remaining("k"+i,1,1000));
    }
}
