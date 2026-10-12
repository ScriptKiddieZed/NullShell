package com.zed.nullshell;

import android.app.Activity;
import android.app.Application;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;

import java.util.WeakHashMap;

/**
 * Loads the theme before any screen exists, paints the window and system bars for every activity, and
 * rebuilds screens that were created under an older look when the theme changes.
 */
public class NullShellApp extends Application {
    private final WeakHashMap<Activity, Integer> built = new WeakHashMap<>();

    @Override public void onCreate() {
        super.onCreate();
        Theme.load(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) {
                Theme.load(a);                                       // follows the system day/night setting when "automatic" is on
                a.setTheme(Ui.light ? R.style.AppThemeLight : R.style.AppTheme);
                paintWindow(a);                                      // colours only: safe before the decor view exists
                built.put(a, Theme.generation);
            }
            @Override public void onActivityResumed(Activity a) {
                Integer g = built.get(a);
                if (g != null && g != Theme.generation && !a.isFinishing()) a.recreate();
            }
            @Override public void onActivityStarted(Activity a) { paintIcons(a); }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { built.remove(a); }
        });
    }

    @SuppressWarnings("deprecation")
    static void paintWindow(Activity a) {
        Window w = a.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Ui.BG));
        w.setStatusBarColor(Ui.BG);
        w.setNavigationBarColor(Ui.BG);
    }

    /** Light/dark system bar icons. Needs the decor view, so it runs from onStart, never from onCreate. */
    @SuppressWarnings("deprecation")
    static void paintIcons(Activity a) {
        Window w = a.getWindow();
        try {
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(Ui.light ? mask : 0, mask);
            }
        } else {
            View d = w.getDecorView();
            int f = d.getSystemUiVisibility();
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
            d.setSystemUiVisibility(Ui.light ? (f | flags) : (f & ~flags));
        }
        } catch (RuntimeException ignored) { }
    }
}
