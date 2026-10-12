package com.zed.nullshell;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.Menu;
import android.view.View;
import android.widget.PopupMenu;

/**
 * The system popup menu (so text size, row height and spacing stay exactly what they always were),
 * painted in the colours of the current app theme through a per-theme style (see res/values/menu_styles.xml).
 */
final class TPopup {
    private final PopupMenu inner;

    TPopup(Context c, View anchor) {
        Context themed = c;
        try {
            String key = Theme.current.id.replace('-', '_');
            int style = c.getResources().getIdentifier("MenuTheme_" + key, "style", c.getPackageName());
            if (style != 0) themed = new ContextThemeWrapper(c, style);
        } catch (RuntimeException ignored) { }
        inner = new PopupMenu(themed, anchor);
    }

    Menu getMenu() { return inner.getMenu(); }

    void setOnMenuItemClickListener(PopupMenu.OnMenuItemClickListener l) { inner.setOnMenuItemClickListener(l); }

    void show() { inner.show(); }

    void dismiss() { inner.dismiss(); }
}
