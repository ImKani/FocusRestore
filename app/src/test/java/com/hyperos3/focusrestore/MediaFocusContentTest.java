package com.hyperos3.focusrestore;

import android.app.Notification;
import org.junit.Test;
import static org.junit.Assert.assertNull;

public class MediaFocusContentTest {
    @Test public void emptyNotificationIsIgnored() {
        assertNull(MediaFocusContent.from(new Notification()));
    }
}
