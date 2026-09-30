package com.hyperos3.focusrestore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Source-level guard for the settings screen state plumbing.
 *
 * <p>Every {@code pending*} field has to travel three paths: it must be loaded from the store, be
 * written into the saved activity state, and be read back from it. Dropping one of those steps is
 * invisible to the compiler and once shipped a build in which the island conversion switch looked
 * disabled after every restart, so all three paths are asserted here.
 */
public class SettingsActivityStateTest {
    private static final Pattern PENDING_FIELD = Pattern.compile("\\bpending[A-Z]\\w*\\b");
    private static final Pattern BUNDLE_PUT =
            Pattern.compile("outState\\.put\\w+\\(\"(m3\\.[^\"]+)\"");
    private static final Pattern BUNDLE_GET =
            Pattern.compile("state\\.get\\w+\\(\"(m3\\.[^\"]+)\"");
    /** Only the field declaration block is scanned; local variables are not settings. */
    private static final String FIELD_BLOCK_START = "private boolean pendingManual";
    private static final String FIELD_BLOCK_END = "private Set<String> pendingTimeoutExemptPackages";

    @Test
    public void everyPendingSettingIsLoadedSavedAndRestored() throws Exception {
        String source = readSettingsActivitySource();

        int start = source.indexOf(FIELD_BLOCK_START);
        int end = source.indexOf(FIELD_BLOCK_END);
        assertTrue("pending field declaration block must exist", start >= 0 && end > start);
        String fieldBlock = source.substring(start, source.indexOf(';', end) + 1);

        Set<String> fields = new TreeSet<>();
        Matcher fieldMatcher = PENDING_FIELD.matcher(fieldBlock);
        while (fieldMatcher.find()) fields.add(fieldMatcher.group());
        assertTrue("settings screen should declare pending fields", fields.size() > 10);

        for (String field : fields) {
            assertTrue(field + " is never loaded from the stored settings",
                    Pattern.compile("\\b" + field + " = [^;]*settings\\.").matcher(source).find());
        }

        Set<String> written = keys(BUNDLE_PUT, source);
        Set<String> restored = keys(BUNDLE_GET, source);
        assertTrue("saved activity state should carry keys", written.size() > 10);
        assertEquals("saved and restored activity state keys must match", written, restored);

        // The two settings regressions that actually shipped.
        assertTrue("island conversion switch must be part of the pending fields",
                fields.contains("pendingIslandCompat"));
        assertTrue("island conversion switch must survive a restart", written.contains("m3.island"));
        assertTrue("focus text mode must survive a restart",
                written.contains("m3.islandTextMode"));
    }

    private static Set<String> keys(Pattern pattern, String source) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }

    private static String readSettingsActivitySource() throws Exception {
        String relative = "src/main/java/com/hyperos3/focusrestore/SettingsActivity.java";
        File directory = new File(System.getProperty("user.dir", "."));
        StringBuilder tried = new StringBuilder();
        for (int depth = 0; depth < 4 && directory != null; depth++) {
            for (File base : new File[]{directory, new File(directory, "app")}) {
                File candidate = new File(base, relative);
                if (candidate.isFile()) {
                    return new String(Files.readAllBytes(candidate.toPath()),
                            StandardCharsets.UTF_8);
                }
                tried.append(candidate.getPath()).append(", ");
            }
            directory = directory.getParentFile();
        }
        throw new AssertionError("SettingsActivity.java not found; tried " + tried);
    }
}
