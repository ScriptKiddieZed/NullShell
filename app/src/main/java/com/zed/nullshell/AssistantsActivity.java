package com.zed.nullshell;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/** Create, edit, duplicate and delete assistants (a name, a system prompt, and optional model / sampling settings). */
public class AssistantsActivity extends Activity {
    private LinearLayout content;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = AiUi.page(this, "Assistants", null);
        ScrollView sv = new ScrollView(this);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AiUi.dp(this, 14), AiUi.dp(this, 10), AiUi.dp(this, 14), AiUi.dp(this, 32));
        sv.addView(content);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Ui.applyInsets(root);
        build();
    }

    private void build() {
        content.removeAllViews();
        content.addView(AiUi.note(this, "Each assistant has its own instructions (system prompt). A chat belongs to one assistant; switch from the chat's title."));
        for (ChatStore.Assistant a : ChatStore.get(this).assistants()) content.addView(row(a));
        TextView add = Ui.button(this, "+  New assistant", Ui.PRIMARY);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) add.getLayoutParams();
        lp.topMargin = AiUi.dp(this, 10);
        add.setOnClickListener(v -> edit(new ChatStore.Assistant()));
        content.addView(add);
    }

    private View row(final ChatStore.Assistant a) {
        LinearLayout card = AiUi.card(this);
        card.setClickable(true);
        card.setBackground(Ui.ripple(this, Ui.SURFACE, 14));
        TextView t = new TextView(this);
        t.setText(a.label());
        t.setTextColor(Ui.TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        card.addView(t);
        TextView s = new TextView(this);
        String p = a.prompt.replaceAll("\\s+", " ");
        s.setText(p.isEmpty() ? "No instructions" : p);
        s.setMaxLines(2);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        s.setTextColor(Ui.DIM);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        card.addView(s);
        String extra = (a.model.isEmpty() ? "" : a.model) + (a.temp >= 0 ? (a.model.isEmpty() ? "" : "  ·  ") + "temp " + a.temp : "")
                + (a.maxTokens > 0 ? "  ·  max " + a.maxTokens : "");
        if (!extra.isEmpty()) {
            TextView x = new TextView(this);
            x.setText(extra);
            x.setTextColor(Ui.HINT);
            x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            x.setPadding(0, AiUi.dp(this, 3), 0, 0);
            card.addView(x);
        }
        card.setOnClickListener(v -> edit(a));
        card.setOnLongClickListener(v -> {
            new Dlg(this).setItems(new String[]{"Edit", "Duplicate", "Delete"}, (d, w) -> {
                if (w == 0) edit(a);
                else if (w == 1) {
                    ChatStore.Assistant c = new ChatStore.Assistant();
                    c.name = a.name + " copy"; c.emoji = a.emoji; c.prompt = a.prompt; c.model = a.model; c.temp = a.temp; c.maxTokens = a.maxTokens;
                    ChatStore.get(this).saveAssistant(c);
                    build();
                } else confirmDelete(a);
            }).show();
            return true;
        });
        return card;
    }

    private void confirmDelete(final ChatStore.Assistant a) {
        if (ChatStore.get(this).assistants().size() <= 1) { AiUi.toast(this, "Keep at least one assistant."); return; }
        new Dlg(this).setMessage("Delete “" + a.name + "” and all of its chats?")
                .setPositiveButton("Delete", (d, w) -> { ChatStore.get(this).deleteAssistant(a.id); build(); })
                .setNegativeButton("Cancel", null).show();
    }

    private void edit(final ChatStore.Assistant a) {
        final boolean isNew = a.id == 0;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(AiUi.dp(this, 20), AiUi.dp(this, 4), AiUi.dp(this, 20), 0);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        final EditText emoji = AiUi.field(this, "🙂", a.emoji, 1);
        emoji.setGravity(Gravity.CENTER);
        emoji.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        final EditText name = AiUi.field(this, "Name", a.name, 1);
        top.addView(emoji, new LinearLayout.LayoutParams(AiUi.dp(this, 56), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = AiUi.dp(this, 8);
        top.addView(name, nlp);
        box.addView(top);

        box.addView(AiUi.label(this, "Instructions (system prompt)"));
        final EditText prompt = AiUi.field(this, "How should this assistant behave?", a.prompt, 6);
        box.addView(prompt);

        final String[] model = {a.model};
        box.addView(AiUi.label(this, "Model"));
        final TextView modelBtn = Ui.button(this, "", Ui.SECONDARY);
        modelBtn.setText(a.model.isEmpty() ? "Default (" + ModelPicker.defaultModel(this) + ")" : a.model);
        modelBtn.setOnClickListener(v -> ModelPicker.show(this, model[0], "Use default (" + ModelPicker.defaultModel(this) + ")", id -> {
            model[0] = id;
            modelBtn.setText(id.isEmpty() ? "Default (" + ModelPicker.defaultModel(this) + ")" : id);
        }));
        box.addView(modelBtn);

        box.addView(AiUi.label(this, "Advanced (leave empty for the defaults)"));
        LinearLayout adv = new LinearLayout(this);
        adv.setOrientation(LinearLayout.HORIZONTAL);
        final EditText temp = AiUi.field(this, "Temperature 0–2", a.temp >= 0 ? String.valueOf(a.temp) : "", 1);
        temp.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        final EditText max = AiUi.field(this, "Max output tokens", a.maxTokens > 0 ? String.valueOf(a.maxTokens) : "", 1);
        max.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        adv.addView(temp, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlp.leftMargin = AiUi.dp(this, 8);
        adv.addView(max, mlp);
        box.addView(adv);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        final AlertDialog d = new Dlg(this).setTitle(isNew ? "New assistant" : "Edit assistant").setView(sv)
                .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty()) { AiUi.toast(this, "Give it a name."); return; }
            float tv = -1;
            String ts = temp.getText().toString().trim();
            if (!ts.isEmpty()) {
                try { tv = Float.parseFloat(ts); } catch (NumberFormatException e) { AiUi.toast(this, "Temperature must be a number."); return; }
                if (tv < 0 || tv > 2) { AiUi.toast(this, "Temperature must be between 0 and 2."); return; }
            }
            int mt = 0;
            String ms = max.getText().toString().trim();
            if (!ms.isEmpty()) {
                try { mt = Integer.parseInt(ms); } catch (NumberFormatException e) { AiUi.toast(this, "Max tokens must be a whole number."); return; }
                if (mt < 0) mt = 0;
            }
            String em = emoji.getText().toString().trim();
            a.name = n;
            a.emoji = em;
            a.prompt = prompt.getText().toString().trim();
            a.model = model[0];
            a.temp = tv;
            a.maxTokens = mt;
            ChatStore.get(this).saveAssistant(a);
            d.dismiss();
            build();
        });
    }
}
