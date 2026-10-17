/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 媒体简短显示的字段顺序与正文拼装回归。 */
public class MediaBriefFieldsTest {
    @Test
    public void parseEmptyMeansAllOffAndMalformedFallsBackToDefault() {
        assertTrue(MediaBriefFields.parse(null).isEmpty());
        assertTrue(MediaBriefFields.parse("").isEmpty());
        assertTrue(MediaBriefFields.parse("   ").isEmpty());
        assertEquals(MediaBriefFields.defaultOrder(), MediaBriefFields.parse("abc"));
        assertEquals(MediaBriefFields.defaultOrder(), MediaBriefFields.parse("x,y"));
    }

    @Test
    public void parsePreservesOrderDropsUnknownAndDeduplicates() {
        assertEquals(Arrays.asList(4, 0, 2), MediaBriefFields.parse("4,0,2"));
        assertEquals(Arrays.asList(0, 2), MediaBriefFields.parse("0,0,2"));
        assertEquals(Arrays.asList(0), MediaBriefFields.parse("0,9"));
        assertEquals(Arrays.asList(1), MediaBriefFields.parse(" 1 "));
    }

    @Test
    public void normalizeAndEncodeRoundTrip() {
        List<Integer> fields = Arrays.asList(2, 0, 4, 2, -1, 7, 0);
        assertEquals(Arrays.asList(2, 0, 4), MediaBriefFields.normalize(fields));
        assertEquals("2,0,4", MediaBriefFields.encode(fields));
        assertEquals(Arrays.asList(2, 0, 4), MediaBriefFields.parse(MediaBriefFields.encode(fields)));
        assertEquals("", MediaBriefFields.encode(Collections.<Integer>emptyList()));
    }

    @Test
    public void fullTextMatchesLegacyBehaviorAndBriefSelectsOnlyRequestedFields() {
        MediaFocusContent media = media("Song", "Album", "Artist");
        // 未开启简短显示时返回完整正文，与旧行为一致。
        assertEquals("Song · Album · Artist", media.assembleText(false, null, null));
        assertEquals("Song", media.assembleText(true, Arrays.asList(MediaBriefFields.TITLE), null));
        assertEquals("Song · Artist", media.assembleText(true,
                Arrays.asList(MediaBriefFields.TITLE, MediaBriefFields.TEXT), null));
        assertEquals("", media.assembleText(true, Collections.<Integer>emptyList(), null));
        // 播放状态没有读到播放态时该字段为空，不产生多余分隔符。
        assertEquals("Song", media.assembleText(true,
                Arrays.asList(MediaBriefFields.TITLE, MediaBriefFields.PLAY_STATE), null));
    }

    @Test
    public void briefFieldOrderFollowsSelectionAndEmptyFieldsAreSkipped() {
        MediaFocusContent media = media("Song", null, "Artist");
        assertEquals("Artist · Song", media.assembleText(true,
                Arrays.asList(MediaBriefFields.TEXT, MediaBriefFields.TITLE), null));
        // 副标题为空被跳过，其余字段按顺序拼接。
        assertEquals("Song · Artist", media.assembleText(true,
                Arrays.asList(MediaBriefFields.TITLE, MediaBriefFields.SUBTEXT, MediaBriefFields.TEXT), null));
    }

    private static MediaFocusContent media(String title, String subText, String text) {
        // JVM 单测的 android.jar 对 Bundle.putString 抛 "not mocked"，直接经私有构造器构造，
        // 避免依赖未 mock 的 Bundle API。
        try {
            java.lang.reflect.Constructor<MediaFocusContent> constructor =
                    MediaFocusContent.class.getDeclaredConstructor(String.class, String.class,
                            String.class, String.class, String.class,
                            android.media.session.MediaSession.Token.class,
                            boolean.class, boolean.class);
            constructor.setAccessible(true);
            String summary = join(join(title, subText), text);
            return constructor.newInstance(summary, title, subText, text,
                    "com.example.music", null, false, false);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String join(String left, String right) {
        if (left == null || left.length() == 0) return right;
        if (right == null || right.length() == 0 || left.equals(right)) return left;
        return left + " · " + right;
    }
}
