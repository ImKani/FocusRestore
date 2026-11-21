/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class IslandNotificationTypeTest {
    private static final String PACKAGE = "com.example.notifications";
    private static final String IDENTITY = "\"protocol\":1,\"business\":\"train\",\"scene\":\"travel\",";
    private static final String RAW = "{" + IDENTITY + "\"baseInfo\":{\"title\":\"原样本\"},"
            + "\"param_island\":{\"content\":\"原正文\"}}";
    private static final IslandRulePaths.Entry TITLE = IslandRulePaths.find("/baseInfo/title");
    private static final IslandRulePaths.Entry CONTENT = IslandRulePaths.find("/param_island/content");

    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    @Test public void typeIgnoresBodyAndKeyOrderButChecksPackageIdentityAndCompleteStructure() {
        IslandNotificationType type = type(RAW);
        IslandRuleSet.Entry exact = entry(candidate(type, CONTENT, TITLE));
        String updated = "{\"param_island\":{\"content\":\"更新正文\"},\"scene\":\"travel\","
                + "\"baseInfo\":{\"title\":\"新标题\"},\"business\":\"train\",\"protocol\":1e0}";
        assertEquals(type, type(updated));
        assertEquals("更新正文 · 新标题", execute(exact, PACKAGE, updated).text);
        assertEquals(IslandRuleExecutor.Status.FALLBACK, execute(exact, "com.example.different", updated).status);
        for (String other : Arrays.asList(updated.replace("travel", "return"), updated.replace("train", "flight"),
                updated.replace("1e0", "2"), updated.replace("\"scene\":\"travel\",", ""),
                updated.replace("\"新标题\"", "\"  \""), updated.replace("\"新标题\"", "\"新标题\",\"content\":\"新增字段\""))) {
            assertEquals(IslandRuleExecutor.Status.FALLBACK, execute(exact, PACKAGE, other).status);
        }
        assertEquals(type, entry(candidate(type, TITLE, CONTENT)).rule.notificationType);
    }

    @Test public void structureOnlyUsesSelectedContainerAndRootLegacyPaths() {
        String raw = "{\"business\":\"非协议标识\",\"param_v2\":{\"baseInfo\":{\"title\":\"容器\",\"content\":{}},"
                + "\"progressInfo\":{\"progress\":0},\"param_island\":{\"title\":false},\"title\":\"非 root\"},"
                + "\"param_v3\":{\"baseInfo\":{\"specialTitle\":\"未选容器\"}},\"title\":\"根标题\","
                + "\"baseInfo\":{\"content\":\"根内容\"}}";
        IslandNotificationType type = type(raw);
        assertNull(type.business);
        assertNull(type.scene);
        assertNull(type.protocol);
        assertEquals(Arrays.asList(TITLE.path, "/param_island/title", "/progressInfo/progress", "/title"), type.paths);
        IslandRuleSet.Entry exact = entry(candidate(type, TITLE));
        assertEquals("容器", execute(exact, PACKAGE, raw).text);
        assertEquals("更新容器", execute(exact, PACKAGE, raw.replace("\"容器\"", "\"更新容器\"")).text);
        assertEquals(IslandRuleExecutor.Status.FALLBACK, execute(exact, PACKAGE, raw.replace("false", "null")).status);
        IslandRuleSet.Entry untyped = entry(candidate(null, TITLE));
        assertEquals(2, untyped.rule.ruleVersion);
        assertNull(untyped.rule.notificationType);
        assertEquals("容器", execute(untyped, PACKAGE, raw.replace("false", "null")).text);
        assertEquals(IslandRuleExecutor.Status.FALLBACK, execute(exact, PACKAGE, raw.replace("非协议标识", "train")).status);
    }

    @Test public void strictSnapshotAndSharedProjectionPreserveTypeAndPortableRebuild() throws Exception {
        IslandNotificationType type = type(RAW);
        IslandCaptureEditor.Candidate original = candidate(type, CONTENT, TITLE);
        String document = new String(original.bytes(), StandardCharsets.UTF_8);
        assertFalse(document.contains("原样本"));
        assertFalse(document.contains("原正文"));
        byte[] reordered = document.replace("\"/baseInfo/title\",\"/param_island/content\"",
                "\"/param_island/content\",\"/baseInfo/title\"").getBytes(StandardCharsets.UTF_8);
        assertFalse(Arrays.equals(reordered, original.bytes()));
        byte[] portable = IslandPortableRule.rebuild(reordered, new IslandRuleCodec().decode(reordered).rule);
        assertEquals(type, new IslandRuleCodec().decode(portable).rule.notificationType);
        IslandRuleRepository repository = new IslandRuleRepository(folder.newFolder().toPath(), ignored -> { });
        try (IslandRuleRepository.Pending pending = repository.prepare(new ByteArrayInputStream(portable), null, true, 0, null)) {
            repository.commit(pending, false, null);
        }
        try (IslandRuleRepository.ReadView view = repository.openRead()) {
            assertEquals(type, new IslandRuleCodec().decode(view.read(view.entries.get(0))).rule.notificationType);
            byte[] wire = IslandRuleProjection.snapshot(view).payload();
            assertEquals(3, wire[4]);
            IslandRuleProjection.Row row = IslandRuleProjection.decode(wire).get(0);
            assertEquals(type, row.notificationType);
            assertEquals(Arrays.asList(CONTENT.path, TITLE.path), row.paths);
            IslandRuleSet.Entry projected = entry(candidate(row.notificationType, CONTENT, TITLE));
            assertEquals("原正文 · 原样本", execute(projected, PACKAGE, RAW).text);
            assertEquals(IslandRuleExecutor.Status.FALLBACK,
                    execute(projected, PACKAGE, RAW.replace("\"原正文\"", "null")).status);
            wire[8] ^= 1;
            assertThrows(IOException.class, () -> IslandRuleProjection.decode(wire));
        }
    }

    @Test public void registrySizedSignatureAndTypedVersionRestrictionsAreValidated() {
        List<String> allPaths = new ArrayList<>();
        for (IslandRulePaths.Entry registered : IslandRulePaths.all()) {
            if (IslandRulePaths.isRuntimeAllowed(registered)) allPaths.add(registered.path);
        }
        assertTrue(allPaths.size() > 32);
        IslandNotificationType full = IslandNotificationType.create(null, null, null, allPaths);
        assertEquals(full, entry(candidate(full, TITLE)).rule.notificationType);
        String valid = new String(candidate(type(RAW), TITLE).bytes(), StandardCharsets.UTF_8);
        assertFalse(decode(valid.replace("\"ruleVersion\":2", "\"ruleVersion\":1")).isSuccess());
        assertFalse(decode(valid.replace("\"equals\"", "\"startsWith\"")).isSuccess());
        assertFalse(decode(valid.replace("\"/baseInfo/title\",\"/param_island/content\"",
                "\"/param_island/content\"")).isSuccess());
        assertFalse(decode(valid.replace("\"/baseInfo/title\",\"/param_island/content\"",
                "\"/baseInfo/title\",\"/baseInfo/title\"")).isSuccess());
        assertFalse(decode(valid.replace("\"protocol\":\"1\"", "\"protocol\":\"2147483648\"")).isSuccess());
    }

    @Test public void verifiedTravelPayloadKeepsTypeWhenTrainTextChanges() throws Exception {
        byte[] fixture = Files.readAllBytes(Paths.get("docs", "samples", "notification-travel.real.json"));
        Map<?, ?> sample = (Map<?, ?>) IslandExportJson.parse(new String(fixture, StandardCharsets.UTF_8));
        String raw = (String) sample.get("rawParam");
        String pkg = (String) sample.get("packageName");
        String left = "/param_island/bigIslandArea/imageTextInfoLeft/textInfo/title";
        String right = "/param_island/bigIslandArea/textInfo/title";
        IslandNotificationType type = new IslandRuleContextAdapter().adapt(pkg, raw).context.notificationType;
        assertEquals("1", type.protocol);
        assertEquals("train", type.business);
        assertEquals("personal-assistant-travel", type.scene);
        assertEquals(Arrays.asList("/aodTitle", left, right), type.paths);
        IslandCaptureEditor.Candidate candidate = IslandCaptureEditor.candidate(pkg,
                Arrays.asList(IslandRulePaths.find(left), IslandRulePaths.find(right)), 0, type);
        assertNotNull(candidate);
        IslandRuleSet.Entry exact = entry(candidate);
        assertEquals("D8396 · 检票口", execute(exact, pkg, raw).text);
        assertEquals("D1234 · 检票口", execute(exact, pkg, raw.replace("D8396", "D1234")).text);
        assertEquals(IslandRuleExecutor.Status.FALLBACK,
                execute(exact, pkg, raw.replace("personal-assistant-travel", "another-scene")).status);
    }

    private static IslandRuleCodec.DecodeResult decode(String value) {
        return new IslandRuleCodec().decode(value.getBytes(StandardCharsets.UTF_8));
    }

    private static IslandNotificationType type(String raw) {
        return new IslandRuleContextAdapter().adapt(PACKAGE, raw).context.notificationType;
    }

    private static IslandCaptureEditor.Candidate candidate(IslandNotificationType type, IslandRulePaths.Entry... selected) {
        IslandCaptureEditor.Candidate candidate = IslandCaptureEditor.candidate(PACKAGE, Arrays.asList(selected), 0, type);
        assertNotNull(candidate);
        return candidate;
    }

    private static IslandRuleSet.Entry entry(IslandCaptureEditor.Candidate candidate) {
        byte[] bytes = candidate.bytes();
        IslandRuleCodec.DecodeResult result = new IslandRuleCodec().decode(bytes);
        assertTrue(result.isSuccess());
        return new IslandRuleSet.Entry("selected", 1, true, result.rule, bytes.length);
    }

    private static IslandRuleExecutor.Result execute(IslandRuleSet.Entry entry, String pkg, String raw) {
        IslandRuleContext context = new IslandRuleContextAdapter().adapt(pkg, raw, false).context;
        return new IslandRuleExecutor().execute(Collections.singletonList(entry), context, raw);
    }
}
