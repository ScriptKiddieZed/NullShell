package com.zed.nullshell;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.widget.TextView;

/** AlertDialog.Builder that paints the dialog in the current theme (background, text, button colours). */
class Dlg extends AlertDialog.Builder {
    Dlg(Context c) { super(c); }

    @Override public AlertDialog create() {
        final AlertDialog d = super.create();
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(Ui.rounded(getContext(), Ui.SURFACE2, 16));
        d.setOnShowListener(x -> style(d));
        return d;
    }

    @Override public AlertDialog show() {
        AlertDialog d = super.show();
        style(d);
        return d;
    }

    static void style(AlertDialog d) {
        try {
            Context c = d.getContext();
            View title = d.findViewById(c.getResources().getIdentifier("alertTitle", "id", "android"));
            if (title instanceof TextView) ((TextView) title).setTextColor(Ui.TEXT);
            View msg = d.findViewById(android.R.id.message);
            if (msg instanceof TextView) ((TextView) msg).setTextColor(Ui.TEXT);
            int[] ids = {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL};
            for (int id : ids) {
                TextView b = d.getButton(id);
                if (b != null) b.setTextColor(Ui.ACCENT);
            }
        } catch (RuntimeException ignored) { }
    }
}
