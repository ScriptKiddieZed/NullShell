package com.zed.nullshell;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.List;

/** Gemini API key, default model, assistants and data. */
public class AiSettingsActivity extends Activity {
    private LinearLayout content;
    private TextView keyState, modelBtn, testResult;
    private EditText keyField;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = AiUi.page(this, "AI settings", null);
        ScrollView sv = new ScrollView(this);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AiUi.dp(this, 14), AiUi.dp(this, 6), AiUi.dp(this, 14), AiUi.dp(this, 32));
        sv.addView(content);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Ui.applyInsets(root);
        build();
    }

    @Override protected void onResume() {
        super.onResume();
        if (modelBtn != null) modelBtn.setText(ModelPicker.defaultModel(this));
    }

    private void build() {
        content.removeAllViews();

        content.addView(AiUi.heading(this, "Gemini API key"));
        LinearLayout kc = AiUi.card(this);
        keyState = new TextView(this);
        keyState.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        kc.addView(keyState);
        keyField = AiUi.field(this, "Paste API key (AIza…)", "", 1);
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        kc.addView(keyField);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, AiUi.dp(this, 8), 0, 0);
        TextView save = Ui.key(this, "Save");
        save.setOnClickListener(v -> {
            String k = keyField.getText().toString().trim();
            if (k.isEmpty()) { AiUi.toast(this, "Paste a key first."); return; }
            KeyVault.set(this, k);
            keyField.setText("");
            refreshKeyState();
            AiUi.toast(this, "Key saved");
        });
        TextView test = Ui.key(this, "Test");
        test.setOnClickListener(v -> testKey());
        TextView remove = Ui.key(this, "Remove");
        remove.setOnClickListener(v -> new Dlg(this).setMessage("Remove the saved API key?")
                .setPositiveButton("Remove", (d, w) -> { KeyVault.set(this, ""); refreshKeyState(); })
                .setNegativeButton("Cancel", null).show());
        TextView get = Ui.key(this, "Get a key");
        get.setOnClickListener(v -> BrowserActivity.open(this, "https://aistudio.google.com/apikey"));
        row.addView(save);
        row.addView(test);
        row.addView(remove);
        row.addView(get);
        kc.addView(row);
        testResult = new TextView(this);
        testResult.setTextColor(Ui.DIM);
        testResult.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        testResult.setPadding(0, AiUi.dp(this, 6), 0, 0);
        testResult.setVisibility(View.GONE);
        kc.addView(testResult);
        content.addView(kc);
        content.addView(AiUi.note(this, "The key is encrypted with a key from the Android Keystore and only ever sent to Google."));
        refreshKeyState();

        content.addView(AiUi.heading(this, "Default model"));
        LinearLayout mc = AiUi.card(this);
        modelBtn = Ui.button(this, ModelPicker.defaultModel(this), Ui.SECONDARY);
        modelBtn.setOnClickListener(v -> ModelPicker.show(this, ModelPicker.defaultModel(this), null, id -> {
            if (!id.isEmpty()) { ModelPicker.setDefaultModel(this, id); modelBtn.setText(id); }
        }));
        mc.addView(modelBtn);
        content.addView(mc);
        content.addView(AiUi.note(this, "The list is fetched from the API. Assistants can use their own model instead. “-latest” names follow Google's newest version; flash-lite-latest is usually the most available."));

        content.addView(AiUi.heading(this, "Assistants"));
        TextView as = Ui.button(this, "Manage assistants", Ui.SECONDARY);
        as.setOnClickListener(v -> startActivity(new Intent(this, AssistantsActivity.class)));
        LinearLayout ac = AiUi.card(this);
        ac.addView(as);
        content.addView(ac);

        content.addView(AiUi.heading(this, "Privacy and data"));
        LinearLayout pc = AiUi.card(this);
        TextView p = new TextView(this);
        p.setText("Messages are sent to Google's Gemini API. On the free tier Google may use them to improve its products. Chats are stored only on this phone.");
        p.setTextColor(Ui.DIM);
        p.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        pc.addView(p);
        TextView wipe = Ui.button(this, "Delete all chats", Ui.DANGER_STYLE);
        LinearLayout.LayoutParams wl = (LinearLayout.LayoutParams) wipe.getLayoutParams();
        wl.topMargin = AiUi.dp(this, 10);
        wipe.setOnClickListener(v -> new Dlg(this).setMessage("Delete every chat with every assistant? Assistants stay.")
                .setPositiveButton("Delete", (d, w) -> {
                    ChatStore.get(this).deleteEverything();
                    Prefs.sp(this).edit().remove("ai_last_conv").apply();
                    AiUi.toast(this, "All chats deleted");
                }).setNegativeButton("Cancel", null).show());
        pc.addView(wipe);
        content.addView(pc);
    }

    private void refreshKeyState() {
        String k = KeyVault.get(this);
        if (k.isEmpty()) {
            keyState.setText("No key saved");
            keyState.setTextColor(Ui.DANGER);
        } else {
            keyState.setText("Key saved: " + KeyVault.masked(k));
            keyState.setTextColor(Ui.ACCENT);
        }
    }

    private void testKey() {
        String typed = keyField.getText().toString().trim();
        final String k = typed.isEmpty() ? KeyVault.get(this) : typed;
        if (k.isEmpty()) { AiUi.toast(this, "No key to test."); return; }
        testResult.setVisibility(View.VISIBLE);
        testResult.setTextColor(Ui.DIM);
        testResult.setText("Testing…");
        new Thread(() -> {
            String msg;
            boolean ok = false;
            try {
                List<GeminiClient.Model> m = GeminiClient.listModels(k);
                ok = true;
                msg = "Key works. " + m.size() + " models available.";
            } catch (IOException e) {
                msg = GeminiClient.describe(e);
            }
            final String fm = msg;
            final boolean fok = ok;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                testResult.setText(fm);
                testResult.setTextColor(fok ? Ui.ACCENT : Ui.DANGER);
            });
        }, "key-test").start();
    }
}
