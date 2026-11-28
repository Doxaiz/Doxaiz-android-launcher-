package com.doxaiz.launcher;

/** Regression tests for the notification-badge visibility toggle and mode. */
public final class NotificationBadgePolicyTests {

    private static int n;

    private static void check(boolean actual, boolean expected, String label) {
        if (actual != expected) {
            throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
        }
        n++;
    }

    public static void main(String[] args) {
        check(NotificationBadgePolicy.isEnabled(true, Prefs.NOTIF_BADGE_DOT),
                true, "dot mode is enabled by default");
        check(NotificationBadgePolicy.isEnabled(true, Prefs.NOTIF_BADGE_APP_ICONS),
                true, "app-icon mode can be enabled");
        check(NotificationBadgePolicy.isEnabled(true, Prefs.NOTIF_BADGE_NUMBERS),
                true, "numeric mode can be enabled");
        check(NotificationBadgePolicy.isEnabled(false, Prefs.NOTIF_BADGE_DOT),
                false, "user toggle disables badges");
        check(NotificationBadgePolicy.isEnabled(true, Prefs.NOTIF_BADGE_NONE),
                false, "none mode disables badges");

        System.out.println("NOTIFICATION_BADGE_POLICY_TESTS=" + n);
    }
}
