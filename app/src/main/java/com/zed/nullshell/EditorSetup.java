package com.zed.nullshell;

import android.content.Context;
import android.graphics.Typeface;

import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel;
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver;

import org.eclipse.tm4e.core.registry.IThemeSource;

/** One-time TextMate setup: grammars and themes come from assets/textmate. */
final class EditorSetup {
    private EditorSetup() { }

    private static boolean done;
    private static Typeface mono;

    static synchronized void ensure(Context ctx) {
        if (done) return;
        Context app = ctx.getApplicationContext();
        FileProviderRegistry.getInstance().addFileProvider(new AssetsFileResolver(app.getAssets()));
        ThemeRegistry themes = ThemeRegistry.getInstance();
        for (int i = 0; i < Prefs.ED_THEMES.length; i++) {
            String name = Prefs.ED_THEMES[i];
            if (name.equals("auto")) continue;
            String path = "textmate/" + name + ".json";
            try {
                ThemeModel model = new ThemeModel(IThemeSource.fromInputStream(
                        FileProviderRegistry.getInstance().tryGetInputStream(path), path, null), name);
                model.setDark(!name.equals("quietlight"));
                themes.loadTheme(model);
            } catch (Exception ignored) { /* a broken theme file only removes that theme from the list */ }
        }
        try { GrammarRegistry.getInstance().loadGrammars("textmate/languages.json"); } catch (Exception ignored) { }
        done = true;
    }

    /** Switches the global theme if the user chose another one. */
    static void applyTheme(Context ctx) {
        ThemeRegistry r = ThemeRegistry.getInstance();
        String wanted = Prefs.edTheme(ctx);
        ThemeModel current = r.getCurrentThemeModel();
        if (current == null || !wanted.equals(current.getName())) {
            if (!r.setTheme(wanted)) r.setTheme("nullshell");
        }
    }

    static synchronized Typeface mono(Context ctx) {
        if (mono == null) {
            try { mono = Typeface.createFromAsset(ctx.getAssets(), "JetBrainsMono-Regular.ttf"); }
            catch (RuntimeException e) { mono = Typeface.MONOSPACE; }
        }
        return mono;
    }
}
