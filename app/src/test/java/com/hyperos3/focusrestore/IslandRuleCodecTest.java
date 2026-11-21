/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

/** C1 纯 JVM 输入边界和不可变快照测试；不执行通知解析或规则。 */
public class IslandRuleCodecTest {
    private final IslandRuleCodec codec = new IslandRuleCodec();
    private static String base() {
        return "{\"ruleVersion\":2,\"meta\":{\"packageName\":\"com.example.app\","
                + "\"templateType\":\"baseInfo\",\"ruleAuthor\":\"ImKani\","
                + "\"ruleDescription\":\"\",\"createdAt\":\"2026-11-18T00:00:00+08:00\"},"
                + "\"match\":{\"all\":[{\"field\":\"business\",\"op\":\"equals\",\"value\":\"train\"}]},"
                + "\"extract\":[{\"id\":\"title\",\"path\":\"/baseInfo/title\"}],"
                + "\"display\":{\"order\":[{\"slot\":\"title\",\"ref\":\"title\"}]}}";
    }
    private IslandRuleCodec.DecodeResult decode(String json) { return codec.decode(json.getBytes(StandardCharsets.UTF_8)); }
    private IslandRule good(String json) {
        IslandRuleCodec.DecodeResult result = decode(json);
        assertTrue(result.error == null ? "success" : result.error.code + " " + result.error.path + " " + result.error.reason, result.isSuccess());
        return result.rule;
    }
    private void bad(IslandRuleCodec.Code code, String json) {
        IslandRuleCodec.DecodeResult result = decode(json);
        assertFalse(result.isSuccess()); assertNull(result.rule); assertNotNull(result.error);
        assertEquals(result.error.reason, code, result.error.code);
    }
    private static String repeat(String text, int count) {
        StringBuilder out = new StringBuilder(); for (int i = 0; i < count; i++) out.append(text); return out.toString();
    }
    private static String annotation(String value) { return base().replace("\"ruleVersion\":2", "\"x-note\":" + value + ",\"ruleVersion\":2"); }
    private static String array(int count) { return "[" + (count == 0 ? "" : "0" + repeat(",0", count - 1)) + "]"; }
    private static String keys(int count) {
        List<String> pairs = new ArrayList<>(); for (int i = 0; i < count; i++) pairs.add("\"k" + i + "\":null");
        return "{" + String.join(",", pairs) + "}";
    }
    private static Set<String> structuralPaths() {
        return new HashSet<>(Arrays.asList(
                "/stepInfo/step",
                "/progressInfo/progress", "/progressInfo/progressInfo/progress",
                "/multiProgressInfo/progress", "/multiProgressInfo/progressInfo/progress",
                "/param_island/bigIslandArea/progressTextInfo/progress",
                "/param_island/bigIslandArea/progressTextInfo/progressInfo/progress",
                "/param_island/bigIslandArea/fixedWidthDigitInfo/progress",
                "/param_island/bigIslandArea/fixedWidthDigitInfo/progressInfo/progress",
                "/param_island/bigIslandArea/sameWidthDigitInfo/progress",
                "/param_island/bigIslandArea/sameWidthDigitInfo/progressInfo/progress"));
    }
    private static String sample(String name) throws IOException { return new String(Files.readAllBytes(Paths.get("docs", "samples", name)), StandardCharsets.UTF_8); }

    @Test public void realPositiveDecodesWithoutExecutingOrKeepingAnnotations() throws Exception {
        IslandRule rule = good(sample("rule-travel.valid.json"));
        assertEquals("com.miui.personalassistant", rule.meta.packageName);
        assertEquals("param_island", rule.meta.templateType); assertEquals(2, rule.extract.size());
        assertEquals("trainNo", rule.extract.get(0).id); assertTrue(rule.display.dedupe);
        assertEquals("·", rule.display.separator);
    }
    @Test public void sevenCounterexamplesHaveContractPrimaryCodes() throws Exception {
        String[] files = {"unknown-field", "path", "slot", "regex", "capacity", "forbidden", "template-path"};
        IslandRuleCodec.Code[] codes = {IslandRuleCodec.Code.L1_UNKNOWN_FIELD, IslandRuleCodec.Code.L1_PATH_ENUM,
                IslandRuleCodec.Code.L1_SLOT_ENUM, IslandRuleCodec.Code.L1_OPERATOR_ENUM, IslandRuleCodec.Code.L1_FILE_SIZE,
                IslandRuleCodec.Code.L1_FORBIDDEN_FIELD, IslandRuleCodec.Code.L1_TEMPLATE_PATH};
        for (int i = 0; i < files.length; i++) bad(codes[i], sample(String.format("invalid-%02d-%s.json", i + 1, files[i])));
    }
    @Test public void mixedSelectorAllowsOnlyRegisteredPathsAndKeepsBindingOrder() {
        String single = base().replace("\"path\":\"/baseInfo/title\"}",
                "\"path\":\"/baseInfo/title\"},{\"id\":\"progress\",\"path\":\"/progressInfo/progress\"}");
        bad(IslandRuleCodec.Code.L1_TEMPLATE_PATH, single);
        String mixed = single.replace("\"baseInfo\"", "\"mixed\"");
        IslandRule rule = good(mixed);
        assertEquals("/baseInfo/title", rule.extract.get(0).path);
        assertEquals("/progressInfo/progress", rule.extract.get(1).path);
        for (String unregistered : Arrays.asList("/paramIsland/title", "/baseInfo", "/baseInfo/*", "/baseInfo/0/title"))
            bad(IslandRuleCodec.Code.L1_PATH_ENUM, mixed.replace("/baseInfo/title", unregistered));
    }

    @Test public void defaultsAndUnknownMetadataRemainExplicit() {
        IslandRule rule = good(base()); assertNull(rule.meta.deviceModel); assertNull(rule.meta.protocolVersion);
        assertFalse(rule.extract.get(0).required); assertTrue(rule.extract.get(0).replace.isEmpty());
        assertFalse(rule.display.dedupe); assertEquals("·", rule.display.separator);
        good(base().replace("\"templateType\"", "\"deviceModel\":\"test\",\"notificationTag\":null,\"templateType\""));
    }
    @Test public void annotationsAllowedAtEveryObjectBoundary() {
        String json = base().replace("{", "{\"x-note\":{\"ordinary-data\":[1,true,null,\"value\"]},");
        IslandRule rule = good(json); assertEquals(1, rule.match.size());
        good(annotation("{\"intent\":\"inert annotation data\"}"));
        good(base().replace("\"meta\":{", "\"meta\":{\"systemUiVersion\":{\"name\":\"test\",\"code\":1,\"x-note\":null},"));
        good(base().replace("\"path\":\"/baseInfo/title\"", "\"path\":\"/baseInfo/title\",\"replace\":[{\"from\":\"a\",\"to\":\"b\",\"x-note\":1}]"));
    }
    @Test public void ordinaryUnknownFieldsRejectedAtEveryObjectBoundary() {
        String[] targets = {"\"ruleVersion\":2", "\"packageName\":", "\"all\":", "\"field\":", "\"id\":", "\"order\":", "\"slot\":"};
        for (String target : targets) bad(IslandRuleCodec.Code.L1_UNKNOWN_FIELD, base().replace(target, "\"unexpected\":null," + target));
        bad(IslandRuleCodec.Code.L1_UNKNOWN_FIELD, base().replace("\"meta\":{", "\"meta\":{\"systemUiVersion\":{\"name\":\"test\",\"code\":1,\"oops\":2},"));
        bad(IslandRuleCodec.Code.L1_UNKNOWN_FIELD, base().replace("\"path\":\"/baseInfo/title\"", "\"path\":\"/baseInfo/title\",\"replace\":[{\"from\":\"a\",\"to\":\"b\",\"oops\":1}]"));
    }
    @Test public void forbiddenFieldsAndDisabledRegexStayRejected() {
        bad(IslandRuleCodec.Code.L1_FORBIDDEN_FIELD, base().replace("\"order\":", "\"url\":\"https://example.invalid\",\"order\":"));
        bad(IslandRuleCodec.Code.L1_OPERATOR_ENUM, base().replace("\"equals\"", "\"regex\""));
        good(base().replace("\"train\"", "\"regex intent code\""));
    }
    @Test public void duplicateKeysIncludingEscapedAndAnnotationsRejected() {
        bad(IslandRuleCodec.Code.L1_DUPLICATE_KEY, base().replace("\"ruleVersion\":2", "\"ruleVersion\":1,\"ruleVersion\":2"));
        bad(IslandRuleCodec.Code.L1_DUPLICATE_KEY, base().replace("\"ruleVersion\":2", "\"ruleVersion\":2,\"rule\\u0056ersion\":1"));
        bad(IslandRuleCodec.Code.L1_DUPLICATE_KEY, annotation("{\"a\":null,\"a\":1}"));
    }
    @Test public void strictUtf8AndBomRejected() {
        byte[][] invalid = {{(byte)0xc0,(byte)0xaf}, {(byte)0xed,(byte)0xa0,(byte)0x80}, {(byte)0xe2,(byte)0x82}, {(byte)0xff}};
        for (byte[] bytes : invalid) assertEquals(IslandRuleCodec.Code.L1_ENCODING, codec.decode(bytes).error.code);
        bad(IslandRuleCodec.Code.L1_ENCODING, "\ufeff" + base());
        good(annotation("\"\ud83d\ude80\""));
    }
    @Test public void jsonGrammarDoesNotAcceptPermissiveExtensions() {
        String[] documents = {"", " ", "[]", "null", "{} trailing", "// comment\n" + base(), base() + "{}",
                base().replace("\"ruleVersion\":2", "\"ruleVersion\":01"), base().replace("\"ruleVersion\":2", "\"ruleVersion\":+1"),
                annotation("NaN"), annotation("Infinity"), annotation(".1"), annotation("1."), annotation("1e"),
                annotation("[1,]"), annotation("{\"a\":1,}"), annotation("'single'"), annotation("\"line\nfeed\""),
                annotation("\"\\x41\""), annotation("\"\\uD800\""), annotation("\"\\uDC00\""), annotation("\"\\uGGGG\""), annotation("tru")};
        for (String document : documents) assertFalse(document, decode(document).isSuccess());
    }
    @Test public void annotationNumbersAreBoundedTokensWithoutFloatExponentRestrictions() {
        good(annotation("1e309")); good(annotation("1e2147483648"));
        good(annotation("-1e-" + repeat("9", 4000)));
        good(annotation("1e" + repeat("9", 4094)));
        bad(IslandRuleCodec.Code.L1_CAPACITY, annotation("1e" + repeat("9", 4095)));
        good(base().replace("\"ruleVersion\":2", "\"ruleVersion\":200e-2"));
        bad(IslandRuleCodec.Code.L1_VERSION, base().replace("\"ruleVersion\":2", "\"ruleVersion\":1e2147483648"));
        bad(IslandRuleCodec.Code.L1_TYPE, base().replace("\"ruleVersion\":2", "\"ruleVersion\":1e-2147483648"));
        good(base().replace("\"meta\":{", "\"meta\":{\"protocolVersion\":-0.0e999999999999,"));
    }
    @Test public void jsonDepthEightAllowedNineRejectedEvenInsideAnnotation() {
        good(annotation(repeat("[", 7) + "0" + repeat("]", 7)));
        bad(IslandRuleCodec.Code.L1_CAPACITY, annotation(repeat("[", 8) + "0" + repeat("]", 8)));
        good(annotation(repeat("{\"a\":", 7) + "0" + repeat("}", 7)));
        bad(IslandRuleCodec.Code.L1_CAPACITY, annotation(repeat("{\"a\":", 8) + "0" + repeat("}", 8)));
    }
    @Test public void objectKeyLimitCountsAnnotations() {
        good(annotation(keys(64))); bad(IslandRuleCodec.Code.L1_CAPACITY, annotation(keys(65)));
        StringBuilder root = new StringBuilder();
        for (int i = 0; i < 59; i++) root.append("\"x-").append(i).append("\":null,");
        good(base().replace("\"ruleVersion\":2", root + "\"ruleVersion\":2"));
        bad(IslandRuleCodec.Code.L1_CAPACITY, base().replace("\"ruleVersion\":2", root + "\"x-last\":null,\"ruleVersion\":2"));
    }
    @Test public void arrayLimitCountsAnnotations() { good(annotation(array(32))); bad(IslandRuleCodec.Code.L1_CAPACITY, annotation(array(33))); }
    @Test public void keyAndStringUtf16LimitsCountDecodedEscapesAndEmoji() {
        good(annotation("{\"" + repeat("k", 256) + "\":0}"));
        bad(IslandRuleCodec.Code.L1_CAPACITY, annotation("{\"" + repeat("k", 257) + "\":0}"));
        good(annotation("\"" + repeat("a", 4096) + "\""));
        bad(IslandRuleCodec.Code.L1_CAPACITY, annotation("\"" + repeat("a", 4097) + "\""));
        good(annotation("\"" + repeat("\\u0061", 4096) + "\""));
        good(annotation("\"" + repeat("\ud83d\ude80", 2048) + "\""));
        bad(IslandRuleCodec.Code.L1_CAPACITY, annotation("\"" + repeat("\ud83d\ude80", 2049) + "\""));
    }
    @Test public void byteLimitIncludesWhitespaceAndUtf8AnnotationBytes() {
        String json = annotation("\"中文\""); int size = json.getBytes(StandardCharsets.UTF_8).length;
        good(json + repeat(" ", 65536 - size));
        bad(IslandRuleCodec.Code.L1_FILE_SIZE, json + repeat(" ", 65537 - size));
    }
    @Test public void boundedStreamConsumesAtMost65537AndDoesNotCloseCaller() {
        final int[] consumed = {0}; final boolean[] closed = {false};
        InputStream stream = new InputStream() {
            public int read() { consumed[0]++; return ' '; }
            public void close() { closed[0] = true; }
        };
        assertEquals(IslandRuleCodec.Code.L1_FILE_SIZE, codec.decode(stream).error.code);
        assertEquals(65537, consumed[0]); assertFalse(closed[0]);
        assertTrue(codec.decode(new ByteArrayInputStream(base().getBytes(StandardCharsets.UTF_8))).isSuccess());
    }
    @Test public void streamErrorsAndZeroLengthReadsAreStructured() {
        assertEquals(IslandRuleCodec.Code.L1_IO, codec.decode(new InputStream() { public int read() throws IOException { throw new IOException("private input"); } }).error.code);
        assertEquals(IslandRuleCodec.Code.L1_INTERNAL, codec.decode(new InputStream() { public int read() { throw new IllegalStateException("private input"); } }).error.code);
        final ByteArrayInputStream data = new ByteArrayInputStream(base().getBytes(StandardCharsets.UTF_8));
        InputStream zero = new InputStream() { public int read() { return data.read(); } public int read(byte[] b,int o,int l) { return 0; } };
        assertTrue(codec.decode(zero).isSuccess());
        assertEquals(IslandRuleCodec.Code.L1_TYPE, codec.decode((InputStream)null).error.code);
        assertEquals(IslandRuleCodec.Code.L1_TYPE, codec.decode((byte[])null).error.code);
    }
    @Test public void strictTypesDoNotCoerceStringsBooleansNumbersOrNull() {
        bad(IslandRuleCodec.Code.L1_TYPE, base().replace("\"ruleVersion\":2", "\"ruleVersion\":\"1\""));
        bad(IslandRuleCodec.Code.L1_TYPE, base().replace("\"ruleVersion\":2", "\"ruleVersion\":true"));
        bad(IslandRuleCodec.Code.L1_TYPE, base().replace("\"ruleVersion\":2", "\"ruleVersion\":2.5"));
        bad(IslandRuleCodec.Code.L1_TYPE, base().replace("\"value\":\"train\"", "\"value\":1"));
        bad(IslandRuleCodec.Code.L1_TYPE, base().replace("\"path\":\"/baseInfo/title\"", "\"path\":\"/baseInfo/title\",\"required\":\"true\""));
        bad(IslandRuleCodec.Code.L1_TYPE, base().replace("\"order\":", "\"dedupe\":1,\"order\":"));
        bad(IslandRuleCodec.Code.L1_TYPE, base().replace("\"ruleAuthor\":\"ImKani\"", "\"ruleAuthor\":null"));
        bad(IslandRuleCodec.Code.L1_TYPE, base().replace("\"meta\":{", "\"meta\":{\"protocolVersion\":\"1\","));
        good(base().replace("\"ruleVersion\":2", "\"ruleVersion\":2.0"));
        good(base().replace("\"ruleVersion\":2", "\"ruleVersion\":2e0"));
    }
    @Test public void currentVersionAcceptsHandwrittenConditionsWithOmittedType() {
        IslandRule rule = good(base());
        assertEquals(2, rule.ruleVersion);
        assertNull(rule.notificationType);
        assertEquals("business", rule.match.get(0).field);
        assertEquals("train", rule.match.get(0).value);
        bad(IslandRuleCodec.Code.L1_TYPE, base().replace("\"match\":{", "\"match\":{\"notificationType\":null,"));
    }
    @Test public void versionAndRequiredFieldsHaveExplicitErrors() {
        bad(IslandRuleCodec.Code.L1_VERSION, base().replace("\"ruleVersion\":2", "\"ruleVersion\":3"));
        bad(IslandRuleCodec.Code.L1_VERSION, base().replace("\"ruleVersion\":2", "\"ruleVersion\":1"));
        bad(IslandRuleCodec.Code.L1_REQUIRED, base().replace("\"ruleVersion\":2,", ""));
        bad(IslandRuleCodec.Code.L1_REQUIRED, base().replace("\"ruleAuthor\":\"ImKani\",", ""));
    }
    @Test public void datePackageAndMetadataNumericBounds() {
        String stamp = "2026-11-18T00:00:00+08:00";
        for (String invalid : Arrays.asList("2026-02-30T00:00:00Z", "2026-11-18T24:00:00Z", "2026-11-18T00:00:00+24:00", "2026-11-18T00:00:60Z", "0000-01-01T00:00:00Z", "2026-11-18T00:00Z")) bad(IslandRuleCodec.Code.L1_TYPE, base().replace(stamp, invalid));
        good(base().replace(stamp, "2024-02-29t23:59:59.123456789012z"));
        good(base().replace(stamp, "2026-11-18T00:00:00-23:59"));
        bad(IslandRuleCodec.Code.L1_TYPE, base().replace("com.example.app", "bad package"));
        good(base().replace("\"meta\":{", "\"meta\":{\"systemUiVersion\":{\"name\":\"\",\"code\":9007199254740991},\"protocolVersion\":2147483647,"));
        bad(IslandRuleCodec.Code.L1_CAPACITY, base().replace("\"meta\":{", "\"meta\":{\"systemUiVersion\":{\"name\":\"\",\"code\":9007199254740992},"));
        bad(IslandRuleCodec.Code.L1_CAPACITY, base().replace("\"meta\":{", "\"meta\":{\"protocolVersion\":2147483648,"));
    }
    @Test public void noAliasesOrArbitraryPathSegmentsAreAccepted() {
        for (String path : Arrays.asList("/paramIsland/title", "/baseInfo/*", "/baseInfo/0", "/baseInfo/../title", "/baseInfo/~1title", "/a/b/c/d/e/f/g/h", "/a/b/c/d/e/f/g/h/i")) bad(IslandRuleCodec.Code.L1_PATH_ENUM, base().replace("/baseInfo/title", path));
        // 第8段仍需有限枚举匹配，不会因深度合法自动获得读取权限。
        assertTrue(decode(base().replace("/baseInfo/title", "/a/b/c/d/e/f/g/h/i")).error.reason.contains("segments"));
        assertFalse(decode(base().replace("/baseInfo/title", "/a/b/c/d/e/f/g/h")).error.reason.contains("segments"));
    }
    @Test public void all106RegisteredPathsDecodeInTheirOwnGroup() {
        for (IslandRulePaths.Entry entry : IslandRulePaths.all()) {
            String json = base().replace("\"templateType\":\"baseInfo\"", "\"templateType\":\"" + entry.templateGroup + "\"").replace("/baseInfo/title", entry.path);
            good(json);
        }
    }
    @Test public void classificationSourcesAndSchemaEnumerationAgree() throws Exception {
        Set<String> paths = new HashSet<>(); Set<String> groups = new HashSet<>();
        List<String> source = Files.readAllLines(Paths.get("app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java"), StandardCharsets.UTF_8);
        for (IslandRulePaths.Entry entry : IslandRulePaths.all()) {
            assertTrue(paths.add(entry.path)); groups.add(entry.templateGroup);
            assertEquals(structuralPaths().contains(entry.path) ? IslandRulePaths.SecurityLevel.STRUCTURAL
                    : IslandRulePaths.SecurityLevel.FREE_TEXT, entry.securityLevel);
            assertTrue(IslandRulePaths.isRuntimeAllowed(entry)); assertSame(entry, IslandRulePaths.find(entry.path));
            assertEquals("app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java", entry.sourceFile);
            assertTrue(entry.sourceLine > 0 && entry.sourceLine <= source.size());
            assertFalse(source.get(entry.sourceLine - 1).trim().isEmpty());
            assertTrue(entry.path.split("/", -1).length - 1 <= 8);
        }
        assertEquals(106, paths.size()); assertEquals(14, groups.size());
        String schema = new String(Files.readAllBytes(Paths.get("docs/rule.schema.json")), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("\"(/[^\"]*)\"").matcher(schema); Set<String> schemaPaths = new HashSet<>();
        while (matcher.find()) schemaPaths.add(matcher.group(1)); assertEquals(schemaPaths, paths);
        String doc = new String(Files.readAllBytes(Paths.get("docs/island-rule-paths.md")), StandardCharsets.UTF_8);
        for (String path : paths) assertTrue(path, doc.contains("`" + path + "`"));
        assertNull(IslandRulePaths.find("/paramIsland/title")); assertFalse(IslandRulePaths.isRuntimeAllowed(null));
    }
    @Test public void captureExportPolicyLabelsPreserveStructuralAndPlaceholderFreeText() {
        // C1 只断言策略标签，不在测试中伪造导出器或运行期脱敏实现。
        int structural = 0, freeText = 0, unsafe = 0;
        for (IslandRulePaths.Entry entry : IslandRulePaths.all()) {
            if (structuralPaths().contains(entry.path)) {
                assertEquals("capture/export preserves structural: " + entry.path,
                        IslandRulePaths.SecurityLevel.STRUCTURAL, entry.securityLevel);
                structural++;
            } else {
                assertEquals("capture/export placeholders free text: " + entry.path,
                        IslandRulePaths.SecurityLevel.FREE_TEXT, entry.securityLevel);
                freeText++;
            }
            if (entry.securityLevel == IslandRulePaths.SecurityLevel.UNSAFE) unsafe++;
        }
        assertEquals(11, structural); assertEquals(95, freeText); assertEquals(0, unsafe);
    }
    @Test public void travelSampleFreeTextLabelsDoNotChangeDeclaredRuntimeExpectation() throws Exception {
        IslandRule rule = good(sample("rule-travel.valid.json"));
        for (ExtractSpec binding : rule.extract) {
            assertEquals(IslandRulePaths.SecurityLevel.FREE_TEXT, IslandRulePaths.find(binding.path).securityLevel);
        }
        assertEquals("/param_island/bigIslandArea/imageTextInfoLeft/textInfo/title", rule.extract.get(0).path);
        assertEquals("/param_island/bigIslandArea/textInfo/title", rule.extract.get(1).path);
        assertEquals("trainNo", rule.display.order.get(0).ref); assertEquals("gate", rule.display.order.get(1).ref);
        assertEquals("D8396·检票口", "D8396" + rule.display.separator + "检票口");
        assertTrue(sample("notification-travel.real.json").contains("\"customText\": \"D8396·检票口\""));
    }
    @Test public void counterexample05TestsFileBytesWhileFieldCapacityHasIndependentCoverage() throws Exception {
        byte[] oversized = Files.readAllBytes(Paths.get("docs/samples/invalid-05-capacity.json"));
        assertEquals(67324, oversized.length); assertTrue(oversized.length > 65536);
        assertEquals(IslandRuleCodec.Code.L1_FILE_SIZE, codec.decode(oversized).error.code);
        String fieldOverflow = base().replace("\"ruleDescription\":\"\"", "\"ruleDescription\":\"" + repeat("a", 2049) + "\"");
        assertTrue(fieldOverflow.getBytes(StandardCharsets.UTF_8).length < 65536);
        bad(IslandRuleCodec.Code.L1_CAPACITY, fieldOverflow);
        good(base().replace("\"ruleDescription\":\"\"", "\"ruleDescription\":\"" + repeat("a", 2048) + "\""));
    }
    @Test public void ignoredAnnotationPayloadAloneCountsTowardExactUtf8FileByteLimit() {
        assertTrue(base().getBytes(StandardCharsets.UTF_8).length < 65536);
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < 15; i++) parts.add("\"" + repeat("a", 4096) + "\"");
        String emptyLast = annotation("[" + String.join(",", parts) + ",\"\"]");
        int remaining = 65536 - emptyLast.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(remaining > 0 && remaining < 4096);
        parts.add("\"" + repeat("中", remaining / 3) + repeat("a", remaining % 3) + "\"");
        String atLimit = annotation("[" + String.join(",", parts) + "]");
        assertEquals(65536, atLimit.getBytes(StandardCharsets.UTF_8).length); good(atLimit);
        // 只在将被忽略的注解中增加一个ASCII字节，业务五段及空白均不变。
        String last = parts.get(15);
        parts.set(15, last.substring(0, last.length() - 1) + "a\"");
        String overLimit = annotation("[" + String.join(",", parts) + "]");
        assertEquals(65537, overLimit.getBytes(StandardCharsets.UTF_8).length);
        bad(IslandRuleCodec.Code.L1_FILE_SIZE, overLimit);
    }
    @Test public void ignoredAnnotationContainersCountRootPlusSevenButRejectRootPlusEight() {
        assertEquals(1, good(annotation("0")).extract.size());
        String atLimit = annotation(repeat("{\"note\":", 7) + "null" + repeat("}", 7));
        String overLimit = annotation(repeat("{\"note\":", 8) + "null" + repeat("}", 8));
        good(atLimit);
        assertTrue(overLimit.getBytes(StandardCharsets.UTF_8).length < 65536);
        bad(IslandRuleCodec.Code.L1_CAPACITY, overLimit);
    }
    @Test public void unsafeAndUnregisteredEntriesCannotEnterRuntimeAllowlist() throws Exception {
        Constructor<IslandRulePaths.Entry> constructor = IslandRulePaths.Entry.class.getDeclaredConstructor(String.class, String.class, IslandRulePaths.SecurityLevel.class, String.class, int.class);
        constructor.setAccessible(true);
        IslandRulePaths.Entry unsafe = constructor.newInstance("/baseInfo/title", "baseInfo", IslandRulePaths.SecurityLevel.UNSAFE, "test", 1);
        IslandRulePaths.Entry unregistered = constructor.newInstance("/baseInfo/title", "baseInfo", IslandRulePaths.SecurityLevel.FREE_TEXT, "test", 1);
        assertFalse(IslandRulePaths.isRuntimeAllowed(unsafe)); assertFalse(IslandRulePaths.isRuntimeAllowed(unregistered));
    }
    @Test public void schemaObjectsAllPermitOnlyAnnotationExtensionPattern() throws Exception {
        String schema = new String(Files.readAllBytes(Paths.get("docs/rule.schema.json")), StandardCharsets.UTF_8);
        assertEquals(10, schema.split("\\\"additionalProperties\\\": false", -1).length - 1);
        assertEquals(10, schema.split("\\\"patternProperties\\\"", -1).length - 1);
        assertTrue(schema.contains("\"^x-\": true"));
    }
    @Test public void fieldSpecificStringAndArrayBoundsAreStricterThanReaderBounds() {
        good(base().replace("\"ruleDescription\":\"\"", "\"ruleDescription\":\"" + repeat("a", 2048) + "\""));
        bad(IslandRuleCodec.Code.L1_CAPACITY, base().replace("\"ruleDescription\":\"\"", "\"ruleDescription\":\"" + repeat("a", 2049) + "\""));
        good(base().replace("\"train\"", "\"" + repeat("a", 256) + "\""));
        bad(IslandRuleCodec.Code.L1_CAPACITY, base().replace("\"train\"", "\"" + repeat("a", 257) + "\""));
        String clause = "{\"field\":\"scene\",\"op\":\"contains\",\"value\":\"n%d\"}";
        List<String> clauses = new ArrayList<>(); for (int i = 0; i < 16; i++) clauses.add(String.format(clause, i));
        String original = "{\"field\":\"business\",\"op\":\"equals\",\"value\":\"train\"}";
        good(base().replace(original, String.join(",", clauses)));
        clauses.add(String.format(clause, 16)); bad(IslandRuleCodec.Code.L1_CAPACITY, base().replace(original, String.join(",", clauses)));
        bad(IslandRuleCodec.Code.L1_CAPACITY, base().replace(original, original + "," + original));
        String replacement = "{\"from\":\"a\",\"to\":\"b\"}";
        good(base().replace("\"path\":\"/baseInfo/title\"", "\"path\":\"/baseInfo/title\",\"replace\":[" + replacement + repeat("," + replacement, 7) + "]"));
        bad(IslandRuleCodec.Code.L1_CAPACITY, base().replace("\"path\":\"/baseInfo/title\"", "\"path\":\"/baseInfo/title\",\"replace\":[" + replacement + repeat("," + replacement, 8) + "]"));
    }
    @Test public void extractAndDisplayArraysKeepTheirOwnBounds() {
        String binding = "{\"id\":\"title\",\"path\":\"/baseInfo/title\"}";
        List<String> bindings = new ArrayList<>();
        for (int i = 0; i < 32; i++) bindings.add(binding.replace("\"title\"", "\"id" + i + "\""));
        good(base().replace(binding, String.join(",", bindings)));
        bindings.add(binding); bad(IslandRuleCodec.Code.L1_CAPACITY, base().replace(binding, String.join(",", bindings)));
        String projection = "{\"slot\":\"title\",\"ref\":\"title\"}";
        good(base().replace(projection, projection + repeat("," + projection, 15)));
        bad(IslandRuleCodec.Code.L1_CAPACITY, base().replace(projection, projection + repeat("," + projection, 16)));
        for (String slot : Arrays.asList("title", "subtitle", "progress", "extra")) good(base().replace("\"slot\":\"title\"", "\"slot\":\"" + slot + "\""));
        for (String slot : Arrays.asList("icon", "timer")) bad(IslandRuleCodec.Code.L1_SLOT_ENUM, base().replace("\"slot\":\"title\"", "\"slot\":\"" + slot + "\""));
    }
    @Test public void replacementIdAndSeparatorBoundaries() {
        String path = "\"path\":\"/baseInfo/title\"";
        String replacement = ",\"replace\":[{\"from\":\"" + repeat("a", 128) + "\",\"to\":\"" + repeat("b", 128) + "\"}]";
        good(base().replace(path, path + replacement));
        bad(IslandRuleCodec.Code.L1_CAPACITY, base().replace(path, path + replacement.replace(repeat("a", 128), repeat("a", 129))));
        bad(IslandRuleCodec.Code.L1_CAPACITY, base().replace(path, path + ",\"replace\":[{\"from\":\"\",\"to\":\"b\"}]"));
        good(base().replace("\"id\":\"title\"", "\"id\":\"" + repeat("a", 32) + "\""));
        bad(IslandRuleCodec.Code.L1_CAPACITY, base().replace("\"id\":\"title\"", "\"id\":\"" + repeat("a", 33) + "\""));
        good(base().replace("\"order\":", "\"separator\":\"" + repeat("s", 32) + "\",\"order\":"));
        bad(IslandRuleCodec.Code.L1_CAPACITY, base().replace("\"order\":", "\"separator\":\"" + repeat("s", 33) + "\",\"order\":"));
    }
    @Test public void errorPrecedenceIsStableAndAnnotationNumbersUseMathematicalEquality() {
        String mixed = base().replace("\"ruleVersion\":2", "\"unexpected\":1,\"ruleVersion\":2").replace("\"order\":", "\"intent\":1,\"order\":");
        bad(IslandRuleCodec.Code.L1_FORBIDDEN_FIELD, mixed);
        bad(IslandRuleCodec.Code.L1_VERSION, mixed.replace("\"ruleVersion\":2", "\"ruleVersion\":3"));
        bad(IslandRuleCodec.Code.L1_DUPLICATE_KEY, mixed.replace("\"ruleVersion\":2", "\"ruleVersion\":1,\"ruleVersion\":2"));
        String original = "{\"field\":\"business\",\"op\":\"equals\",\"value\":\"train\"}";
        String first = original.replace("{", "{\"x-n\":1,");
        String second = original.replace("{", "{\"x-n\":100e-2,");
        bad(IslandRuleCodec.Code.L1_CAPACITY, base().replace(original, first + "," + second));
    }
    @Test public void diagnosticsNeverEchoUnknownKeysOrIoMessages() {
        IslandRuleCodec.DecodeResult result = decode(base().replace("\"ruleVersion\":2", "\"private-user-name\":null,\"ruleVersion\":2"));
        assertFalse(result.error.path.contains("private")); assertFalse(result.error.reason.contains("private"));
        assertTrue(result.error.offset >= -1);
    }
    @Test public void modelsAndRuleSetAreDefensiveImmutableSnapshots() {
        IslandRule rule = good(base());
        try { rule.match.clear(); fail(); } catch (UnsupportedOperationException expected) { }
        try { rule.extract.clear(); fail(); } catch (UnsupportedOperationException expected) { }
        try { rule.display.order.clear(); fail(); } catch (UnsupportedOperationException expected) { }
        try { IslandRulePaths.all().clear(); fail(); } catch (UnsupportedOperationException expected) { }
        List<MatchClause> original = new ArrayList<>(rule.match);
        IslandRule copied = new IslandRule(IslandRule.CURRENT_VERSION, rule.meta, original, rule.extract, rule.display); original.clear(); assertEquals(1, copied.match.size());
        List<IslandRuleSet.Entry> entries = new ArrayList<>(); entries.add(new IslandRuleSet.Entry("local", 1, true, rule, 600));
        IslandRuleSet set = new IslandRuleSet(2, entries); entries.clear(); assertEquals(1, set.entries.size()); assertEquals(0, set.entries.get(0).priority);
        try { set.entries.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }
    @Test public void ruleSetEnforcesActiveFileTotalAndIdentityBounds() {
        IslandRule rule = good(base()); List<IslandRuleSet.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 50; i++) entries.add(new IslandRuleSet.Entry("id" + i, 0, true, i, rule, 1));
        IslandRuleSet set = new IslandRuleSet(entries); assertEquals(50, set.entries.size());
        entries.add(new IslandRuleSet.Entry("extra", 0, true, rule, 1));
        try { new IslandRuleSet(entries); fail(); } catch (IllegalArgumentException expected) { }
        entries.clear(); for (int i = 0; i < 32; i++) entries.add(new IslandRuleSet.Entry("id" + i, 0, false, rule, 65536));
        new IslandRuleSet(entries); entries.add(new IslandRuleSet.Entry("extra", 0, false, rule, 1));
        try { new IslandRuleSet(entries); fail(); } catch (IllegalArgumentException expected) { }
        try { new IslandRuleSet.Entry("id", 0, true, rule, 65537); fail(); } catch (IllegalArgumentException expected) { }
        try { new IslandRuleSet(Arrays.asList(new IslandRuleSet.Entry("same", 0, false, rule, 1), new IslandRuleSet.Entry("same", 0, false, rule, 1))); fail(); } catch (IllegalArgumentException expected) { }
    }
}
