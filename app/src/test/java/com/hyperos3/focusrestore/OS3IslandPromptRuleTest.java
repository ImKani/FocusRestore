/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public class OS3IslandPromptRuleTest {
    @Test public void copiedBeanKeepsOldContentWhenRuleArrivesAndWhenFieldsChange() {
        String copiedContent = "车次·检票口·站台";
        HyperOS3FocusRestoreHook.AppliedIslandText builtin = rendered(copiedContent, false);
        assertTrue(builtin.canReuse(copiedContent, null));
        assertFalse(builtin.canReuse(copiedContent, "检票口"));
        HyperOS3FocusRestoreHook.AppliedIslandText filtered = rendered("检票口", true);
        assertTrue(filtered.canReuse(copiedContent, "检票口"));
        assertFalse(filtered.canReuse(copiedContent, "站台·检票口"));
    }

    @Test public void revocationAndCompactModeDropRuleEvenIfBeanStillHasItsText() {
        HyperOS3FocusRestoreHook.AppliedIslandText filtered = rendered("站台", true);
        assertFalse(filtered.canReuse("站台", null));
        HyperOS3FocusRestoreHook.AppliedIslandText builtin = rendered("站台", false);
        assertFalse(builtin.canReuse("站台", "站台"));
        assertFalse(builtin.canReuse("新内容", null));
    }

    @Test public void viewResultFollowsCurrentNotificationEvenAfterRomChangesBeanText() {
        Object sbn = new Object();
        IslandRuleSetLoader.Snapshot snapshot = IslandRuleSetLoader.unloaded(7);
        HyperOS3FocusRestoreHook.RuleTextCacheEntry result = new HyperOS3FocusRestoreHook.RuleTextCacheEntry(
                sbn, "first payload", snapshot, "距离 100m");
        assertTrue(result.matches(sbn, sbn, snapshot, "first payload"));
        assertFalse(rendered("内置全文", false).canReuse("ROM 新正文", result.text));
        assertFalse(result.matches(new Object(), sbn, snapshot, "first payload"));
        assertFalse(result.matches(sbn, null, snapshot, "first payload"));
        assertFalse(result.matches(sbn, sbn, IslandRuleSetLoader.unloaded(8), "first payload"));
        assertFalse(result.matches(sbn, sbn, snapshot, "new payload"));
    }

    @Test public void romViewCopyIsOnlyAConsumerOfTheWritingBeansRequest() throws Exception {
        // Android 接线守卫：与上面实际运行的复用判定分开，防止把排队重新放回每次刷新。
        String hook = new String(Files.readAllBytes(Paths.get("app/src/main/java/com/hyperos3/focusrestore/HyperOS3FocusRestoreHook.java")), StandardCharsets.UTF_8);
        String renderer = hook.substring(hook.indexOf("private boolean preferConvertedIslandText("),
                hook.indexOf("private void refreshConvertedPromptTexts()"));
        assertTrue(renderer.contains("cachedIslandRuleText(data)"));
        assertFalse(renderer.contains("scheduleIslandRuleText("));
        assertFalse(renderer.contains("scheduleOS3BeanRule("));
        String owner = hook.substring(hook.indexOf("private void patchBean("), hook.indexOf("private boolean applyConvertedBeanIcon("));
        int write = owner.indexOf("XposedHelpers.setObjectField(bean, \"content\", islandText.text)");
        assertTrue(write >= 0 && owner.indexOf("scheduleOS3BeanRule(bean, data)") > write);
        assertFalse(owner.contains("displayIslandContent(data)"));
        String finish = hook.substring(hook.indexOf("private void finishIslandRuleText("), hook.indexOf("private void debugRuleMatch("));
        assertFalse(finish.contains("setObjectField(owner"));
        assertFalse(finish.contains("lastConvertedContent"));
        assertTrue(finish.indexOf("isIslandRuleRequestCurrent(state)") < finish.indexOf("islandRuleTextCache.put("));
        assertTrue(finish.indexOf("islandRuleTextCache.put(") < finish.indexOf("refreshConvertedPromptTexts()"));
    }

    private static HyperOS3FocusRestoreHook.AppliedIslandText rendered(String text, boolean fromRule) {
        return new HyperOS3FocusRestoreHook.AppliedIslandText(text, text, true, fromRule);
    }
}
