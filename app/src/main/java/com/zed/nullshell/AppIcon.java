package com.zed.nullshell;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

/**
 * Launcher icon choice. Each theme has its own launcher entry (an activity-alias in the manifest); exactly one is
 * enabled at a time. Changing it can make the launcher refresh or briefly hide the app, and pinned shortcuts to the
 * old icon disappear, which is how Android handles icon switching.
 */
final class AppIcon {
    private AppIcon() { }

    static final String K_ICON = "launcher_icon", K_FOLLOW = "launcher_icon_follow";

    static String key(String themeId) { return themeId.replace('-', '_'); }

    static String current(Context c) {
        String id = Prefs.sp(c).getString(K_ICON, "nullshell");
        return Theme.byId(id).id;
    }

    static boolean follow(Context c) { return Prefs.sp(c).getBoolean(K_FOLLOW, false); }

    static void setFollow(Context c, boolean on) { Prefs.sp(c).edit().putBoolean(K_FOLLOW, on).apply(); }

    /** The icon as an image, or null if the resource is missing. */
    static Drawable preview(Context c, String themeId) {
        int res = c.getResources().getIdentifier("ic_launcher_" + key(themeId), "mipmap", c.getPackageName());
        if (res == 0) return null;
        try { return c.getDrawable(res); } catch (RuntimeException e) { return null; }
    }

    /** Switches the launcher icon. The new entry is enabled before the old ones are turned off so the app never vanishes. */
    static void apply(Context c, String themeId) {
        Theme target = Theme.byId(themeId);
        Prefs.sp(c).edit().putString(K_ICON, target.id).apply();
        PackageManager pm = c.getPackageManager();
        try {
            pm.setComponentEnabledSetting(alias(c, target.id), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            for (Theme t : Theme.ALL) {
                if (t.id.equals(target.id)) continue;
                pm.setComponentEnabledSetting(alias(c, t.id), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            }
        } catch (RuntimeException ignored) { }
    }

    private static ComponentName alias(Context c, String themeId) {
        return new ComponentName(c.getPackageName(), "com.zed.nullshell.Icon_" + key(themeId));
    }
}
