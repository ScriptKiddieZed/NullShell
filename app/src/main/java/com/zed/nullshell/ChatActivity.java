package com.zed.nullshell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** The Gemini chat screen: streaming replies, saved conversations, assistants, copy for messages / code / whole chats. */
public class ChatActivity extends Activity {
    private static final String K_LAST_CONV = "ai_last_conv";

    private final ChatStore store() { return ChatStore.get(this); }
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ChatStore.Assistant assistant;
    private long convId;
    private final List<ChatStore.Message> messages = new ArrayList<>();

    private ScrollView scroll;
    private LinearLayout list;
    private TextView titleView, subtitleView, status, sendBtn, emptyView;
    private EditText input;
    private int gen;
    private static final int REQ_PICK = 7741, REQ_SAVE = 7742;
    private final List<ChatAttachments.Pending> pending = new ArrayList<>();
    private android.widget.HorizontalScrollView pendingScroll;
    private LinearLayout pendingRow;
    private Map<Long, List<ChatStore.Attachment>> atts = new HashMap<>();

    // streaming state
    private volatile boolean streaming, destroyed;
    private GeminiClient.Cancel cancel;
    private final StringBuffer acc = new StringBuffer();
    private final AtomicBoolean renderPending = new AtomicBoolean();
    private ChatMarkdown.Body liveBody;
    private TextView liveTyping;
    private View errorRow;
    private ChatStore.Message partialMsg;     // a half reply saved after an error, replaced by Retry

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout[] barOut = new LinearLayout[1];
        LinearLayout root = AiUi.page(this, null, barOut);
        LinearLayout bar = barOut[0];

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(AiUi.dp(this, 6), 0, 0, 0);
        titles.setBackground(Ui.ripple(this, 0, 10));
        titles.setClickable(true);
        titleView = new TextView(this);
        titleView.setTextColor(Ui.TEXT);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitleView = new TextView(this);
        subtitleView.setTextColor(Ui.DIM);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(titleView);
        titles.addView(subtitleView);
        titles.setOnClickListener(v -> pickAssistant());
        bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        titles.setGravity(Gravity.CENTER_VERTICAL);

        TextView chats = Ui.iconButton(this, "☰");
        chats.setOnClickListener(v -> showChats());
        bar.addView(chats);
        TextView plus = Ui.iconButton(this, "+");
        plus.setOnClickListener(v -> newChat());
        bar.addView(plus);
        TextView more = Ui.iconButton(this, "⋮");
        more.setOnClickListener(v -> showMenu(v));
        bar.addView(more);

        scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(AiUi.dp(this, 10), AiUi.dp(this, 10), AiUi.dp(this, 10), AiUi.dp(this, 14));
        scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        emptyView = new TextView(this);
        emptyView.setTextColor(Ui.DIM);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(AiUi.dp(this, 32), AiUi.dp(this, 60), AiUi.dp(this, 32), 0);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        status = new TextView(this);
        status.setTextColor(Ui.DIM);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setPadding(AiUi.dp(this, 16), AiUi.dp(this, 4), AiUi.dp(this, 16), AiUi.dp(this, 2));
        status.setVisibility(View.GONE);
        root.addView(status);

        pendingScroll = new android.widget.HorizontalScrollView(this);
        pendingScroll.setHorizontalScrollBarEnabled(false);
        pendingScroll.setVisibility(View.GONE);
        pendingRow = new LinearLayout(this);
        pendingRow.setOrientation(LinearLayout.HORIZONTAL);
        pendingRow.setPadding(AiUi.dp(this, 10), AiUi.dp(this, 4), AiUi.dp(this, 10), 0);
        pendingScroll.addView(pendingRow);
        root.addView(pendingScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout inputBar = new LinearLayout(this);
        inputBar.setOrientation(LinearLayout.HORIZONTAL);
        inputBar.setGravity(Gravity.BOTTOM);
        inputBar.setPadding(AiUi.dp(this, 8), AiUi.dp(this, 6), AiUi.dp(this, 8), AiUi.dp(this, 8));
        android.widget.ImageView attach = ChatMarkdown.iconAction(this, R.drawable.ic_act_attach, "Attach a file", v -> attachMenu());
        LinearLayout.LayoutParams alp0 = new LinearLayout.LayoutParams(AiUi.dp(this, 44), AiUi.dp(this, 44));
        alp0.rightMargin = AiUi.dp(this, 2);
        attach.setPadding(AiUi.dp(this, 10), AiUi.dp(this, 10), AiUi.dp(this, 10), AiUi.dp(this, 10));
        inputBar.addView(attach, alp0);
        input = new EditText(this);
        input.setHint("Message");
        input.setHintTextColor(Ui.HINT);
        input.setTextColor(Ui.TEXT);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(6);
        input.setBackground(Ui.rounded(this, Ui.SURFACE2, 22));
        input.setPadding(AiUi.dp(this, 16), AiUi.dp(this, 10), AiUi.dp(this, 16), AiUi.dp(this, 10));
        inputBar.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sendBtn = new TextView(this);
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        sendBtn.setClickable(true);
        sendBtn.setOnClickListener(v -> { if (streaming) stopStreaming(); else send(); });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(AiUi.dp(this, 44), AiUi.dp(this, 44));
        slp.leftMargin = AiUi.dp(this, 6);
        inputBar.addView(sendBtn, slp);
        root.addView(inputBar);
        refreshSendButton();

        setContentView(root);
        Ui.applyInsets(root);

        long last = Prefs.sp(this).getLong(K_LAST_CONV, 0);
        ChatStore.Conversation c = last > 0 ? store().conversation(last) : null;
        if (c != null) openConversation(c.id);
        else {
            List<ChatStore.Assistant> all = store().assistants();
            if (all.isEmpty()) { AiUi.toast(this, "No assistants. Create one first."); finish(); return; }
            long existing = store().emptyConversation(all.get(0).id);
            openConversation(existing > 0 ? existing : store().newConversation(all.get(0).id));
        }
        if (!KeyVault.has(this)) askForKey();
    }

    @Override protected void onResume() {
        super.onResume();
        if (assistant != null) {
            ChatStore.Assistant a = store().assistant(assistant.id);
            if (a == null || store().conversation(convId) == null) {
                abandonStream(false);
                List<ChatStore.Assistant> all = store().assistants();
                if (all.isEmpty()) { finish(); return; }
                long ex = store().emptyConversation(all.get(0).id);
                openConversation(ex > 0 ? ex : store().newConversation(all.get(0).id));
            } else {
                assistant = a;
                updateHeader();
            }
        }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (cancel != null) cancel.cancel();
        if (streaming && acc.length() > 0 && convId > 0) store().addMessage(convId, "model", acc.toString());
        ui.removeCallbacksAndMessages(null);
        if (isFinishing()) ChatAttachments.clearPendingFiles(this);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ conversation / header

    /** Stops a running reply for good (switching chats, deleting): the late result of the worker is ignored. */
    private void abandonStream(boolean save) {
        if (!streaming) return;
        if (cancel != null) cancel.cancel();
        if (save && acc.length() > 0 && convId > 0) store().addMessage(convId, "model", acc.toString());
        streaming = false;
        gen++;
        liveBody = null;
        liveTyping = null;
        status.setVisibility(View.GONE);
        refreshSendButton();
    }

    private void openConversation(long id) {
        abandonStream(true);
        ChatStore.Conversation c = store().conversation(id);
        if (c == null) return;
        ChatStore.Assistant a = store().assistant(c.assistantId);
        if (a == null) { List<ChatStore.Assistant> all = store().assistants(); if (all.isEmpty()) return; a = all.get(0); }
        convId = id;
        assistant = a;
        partialMsg = null;
        errorRow = null;
        Prefs.sp(this).edit().putLong(K_LAST_CONV, id).apply();
        messages.clear();
        messages.addAll(store().messages(id));
        atts = store().attachments(id);
        renderAll();
        updateHeader();
    }

    private String modelId() { return assistant != null && !assistant.model.isEmpty() ? assistant.model : ModelPicker.defaultModel(this); }

    private void updateHeader() {
        if (assistant == null) return;
        titleView.setText(assistant.label() + "  ▾");
        int chars = 0;
        for (ChatStore.Message m : messages) chars += m.text.length();
        chars += assistant.prompt.length();
        String tok = chars < 400 ? "" : "  ·  ~" + (chars / 4 >= 1000 ? (chars / 4000) + "." + ((chars / 400) % 10) + "k" : String.valueOf(chars / 4)) + " tokens";
        subtitleView.setText(modelId() + tok);
        emptyView.setText(assistant.emoji + "\n\n" + assistant.name + "\n\nType a message to start.");
    }

    private void pickAssistant() {
        final List<ChatStore.Assistant> all = store().assistants();
        String[] names = new String[all.size() + 2];
        for (int i = 0; i < all.size(); i++) names[i] = (all.get(i).id == assistant.id ? "✓  " : "") + all.get(i).label();
        names[all.size()] = "Model for this assistant…";
        names[all.size() + 1] = "Manage assistants…";
        new Dlg(this).setTitle("Assistant").setItems(names, (d, w) -> {
            if (w < all.size()) {
                ChatStore.Assistant a = all.get(w);
                if (a.id == assistant.id) return;
                long ex = store().emptyConversation(a.id);
                openConversation(ex > 0 ? ex : store().newConversation(a.id));
            } else if (w == all.size()) {
                pickModel();
            } else {
                startActivity(new Intent(this, AssistantsActivity.class));
            }
        }).show();
    }

    private void pickModel() {
        ModelPicker.show(this, assistant.model, "Use default (" + ModelPicker.defaultModel(this) + ")", id -> {
            assistant.model = id;
            store().saveAssistant(assistant);
            updateHeader();
        });
    }

    private void newChat() {
        abandonStream(true);
        long ex = store().emptyConversation(assistant.id);
        openConversation(ex > 0 ? ex : store().newConversation(assistant.id));
    }

    // ------------------------------------------------------------------ menu

    private void showMenu(View anchor) {
        TPopup pm = new TPopup(this, anchor);
        pm.getMenu().add(0, 1, 0, "Copy conversation").setEnabled(!messages.isEmpty());
        pm.getMenu().add(0, 2, 1, "Rename chat");
        pm.getMenu().add(0, 3, 2, "Clear messages").setEnabled(!messages.isEmpty());
        pm.getMenu().add(0, 4, 3, "Delete chat");
        pm.getMenu().add(0, 7, 4, "Share conversation").setEnabled(!messages.isEmpty());
        pm.getMenu().add(0, 8, 5, "Save as Markdown file").setEnabled(!messages.isEmpty());
        pm.getMenu().add(0, 5, 6, "Assistants");
        pm.getMenu().add(0, 6, 7, "AI settings");
        pm.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: copyConversation(); return true;
                case 2: renameChat(); return true;
                case 3: confirm("Clear all messages in this chat?", "Clear", () -> { store().clearMessages(convId); messages.clear(); renderAll(); updateHeader(); }); return true;
                case 4: confirm("Delete this chat?", "Delete", this::deleteChat); return true;
                case 7: shareConversation(); return true;
                case 8: saveConversation(); return true;
                case 5: startActivity(new Intent(this, AssistantsActivity.class)); return true;
                case 6: startActivity(new Intent(this, AiSettingsActivity.class)); return true;
                default: return false;
            }
        });
        pm.show();
    }

    private void confirm(String msg, String yes, Runnable r) {
        new Dlg(this).setMessage(msg).setPositiveButton(yes, (d, w) -> r.run()).setNegativeButton("Cancel", null).show();
    }

    private void deleteChat() {
        abandonStream(false);
        store().deleteConversation(convId);
        long ex = store().emptyConversation(assistant.id);
        openConversation(ex > 0 ? ex : store().newConversation(assistant.id));
    }

    private void renameChat() {
        ChatStore.Conversation c = store().conversation(convId);
        Ui.prompt(this, "Rename chat", "Title", c == null ? "" : c.title, "Save", t -> { store().renameConversation(convId, t.trim()); });
    }

    /** The whole chat as Markdown. */
    private String conversationText() {
        StringBuilder sb = new StringBuilder();
        ChatStore.Conversation c = store().conversation(convId);
        if (c != null && !c.title.isEmpty()) sb.append("# ").append(c.title).append("\n\n");
        for (ChatStore.Message m : messages) {
            sb.append(m.isUser() ? "**You:**\n" : "**" + assistant.name + ":**\n");
            List<ChatStore.Attachment> al = atts.get(m.id);
            if (al != null) for (ChatStore.Attachment a : al) sb.append("[file: ").append(a.name).append("]\n");
            sb.append(m.text).append("\n\n");
        }
        return sb.toString().trim();
    }

    private void copyConversation() {
        AiUi.copy(this, "conversation", conversationText());
        AiUi.toast(this, "Conversation copied");
    }

    // ------------------------------------------------------------------ chats list

    private void showChats() {
        final List<ChatStore.Conversation> all = store().conversations(-1);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final AlertDialog[] dlg = new AlertDialog[1];
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        int shown = 0;
        for (ChatStore.Conversation c : all) {
            if (store().messages(c.id).isEmpty() && c.id != convId) continue;
            shown++;
            ChatStore.Assistant a = store().assistant(c.assistantId);
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setPadding(AiUi.dp(this, 20), AiUi.dp(this, 10), AiUi.dp(this, 20), AiUi.dp(this, 10));
            r.setBackground(c.id == convId ? Ui.rounded(this, Ui.SURFACE, 10) : Ui.ripple(this, 0, 10));
            r.setClickable(true);
            TextView t = new TextView(this);
            t.setText(c.title.isEmpty() ? "New chat" : c.title);
            t.setTextColor(c.id == convId ? Ui.ACCENT : Ui.TEXT);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView s = new TextView(this);
            s.setText((a == null ? "" : a.label() + "  ·  ") + df.format(new Date(c.updated)));
            s.setTextColor(Ui.DIM);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            r.addView(t);
            r.addView(s);
            final long id = c.id;
            r.setOnClickListener(v -> { dlg[0].dismiss(); openConversation(id); });
            r.setOnLongClickListener(v -> {
                dlg[0].dismiss();
                new Dlg(this).setItems(new String[]{"Rename", "Delete"}, (d, w) -> {
                    if (w == 0) Ui.prompt(this, "Rename chat", "Title", c.title, "Save", nt -> store().renameConversation(id, nt.trim()));
                    else confirm("Delete this chat?", "Delete", () -> {
                        if (id == convId) abandonStream(false);
                        store().deleteConversation(id);
                        if (id == convId) deleteChatAfter();
                    });
                }).show();
                return true;
            });
            box.addView(r);
        }
        if (shown == 0) {
            TextView t = new TextView(this);
            t.setText("No chats yet.");
            t.setTextColor(Ui.DIM);
            t.setPadding(AiUi.dp(this, 20), AiUi.dp(this, 12), AiUi.dp(this, 20), AiUi.dp(this, 12));
            box.addView(t);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        dlg[0] = new Dlg(this).setTitle("Chats").setView(sv).setPositiveButton("New chat", (d, w) -> newChat()).setNegativeButton("Close", null).show();
    }

    private void deleteChatAfter() {
        long ex = store().emptyConversation(assistant.id);
        openConversation(ex > 0 ? ex : store().newConversation(assistant.id));
    }

    // ------------------------------------------------------------------ rendering

    private void renderAll() {
        list.removeAllViews();
        liveBody = null;
        liveTyping = null;
        errorRow = null;
        if (messages.isEmpty()) {
            if (emptyView.getParent() != null) ((ViewGroup) emptyView.getParent()).removeView(emptyView);
            list.addView(emptyView);
        }
        for (ChatStore.Message m : messages) list.addView(rowFor(m));
        scrollToEnd(true);
    }

    private View rowFor(final ChatStore.Message m) {
        if (m.isUser()) {
            LinearLayout wrap = new LinearLayout(this);
            wrap.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wlp.topMargin = AiUi.dp(this, 8);
            wrap.setLayoutParams(wlp);
            int fill = Theme.mix(Ui.SURFACE, Ui.ACCENT, 0.28f);
            List<ChatStore.Attachment> al = atts.get(m.id);
            if (al != null && !al.isEmpty()) wrap.addView(attachmentViews(al));
            TextView t = new TextView(this);
            t.setText(m.text);
            t.setTextColor(Theme.onColor(fill));
            t.setHighlightColor(Theme.withAlpha(Theme.onColor(fill), 0x44));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            t.setLineSpacing(0, 1.1f);
            t.setTextIsSelectable(true);                      // long-press to select and copy
            t.setBackground(Ui.rounded(this, fill, 16));
            t.setPadding(AiUi.dp(this, 14), AiUi.dp(this, 9), AiUi.dp(this, 14), AiUi.dp(this, 9));
            LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ulp.leftMargin = AiUi.dp(this, 44);
            ulp.gravity = Gravity.END;
            if (!m.text.isEmpty()) wrap.addView(t, ulp);
            LinearLayout actions = actionRow(m);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.gravity = Gravity.END;
            wrap.addView(actions, alp);
            return wrap;
        }
        ChatMarkdown.Body body = new ChatMarkdown.Body(this);
        body.setTerminalAction(this::pasteCode);
        body.setMarkdown(m.text);
        LinearLayout card = wrapAssistant(body);
        card.addView(actionRow(m));
        return card;
    }

    /** Icons under a message: copy, copy as plain text, regenerate, delete. */
    private LinearLayout actionRow(final ChatStore.Message m) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, AiUi.dp(this, 2), 0, 0);
        final android.widget.ImageView copy = ChatMarkdown.iconAction(this, R.drawable.ic_act_copy, "Copy", null);
        copy.setOnClickListener(v -> { AiUi.copy(this, "message", m.text); ChatMarkdown.flashCheck(copy, R.drawable.ic_act_copy); });
        row.addView(copy);
        if (!m.isUser()) {
            final android.widget.ImageView plain = ChatMarkdown.iconAction(this, R.drawable.ic_act_text, "Copy as plain text", null);
            plain.setOnClickListener(v -> { AiUi.copy(this, "message", MdBlocks.toPlain(m.text)); ChatMarkdown.flashCheck(plain, R.drawable.ic_act_text); });
            row.addView(plain);
        }
        row.addView(ChatMarkdown.iconAction(this, R.drawable.ic_act_refresh, m.isUser() ? "Send again" : "Regenerate", v -> regenerateFrom(m)));
        row.addView(ChatMarkdown.iconAction(this, R.drawable.ic_act_delete, "Delete", v -> deleteMessage(m)));
        return row;
    }

    /** Puts an assistant body in its card. */
    private LinearLayout wrapAssistant(ChatMarkdown.Body body) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.rounded(this, Ui.SURFACE, 16));
        card.setPadding(AiUi.dp(this, 14), AiUi.dp(this, 11), AiUi.dp(this, 14), AiUi.dp(this, 4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = AiUi.dp(this, 8);
        lp.rightMargin = AiUi.dp(this, 14);
        card.setLayoutParams(lp);
        card.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private void deleteMessage(ChatStore.Message m) {
        if (streaming) { AiUi.toast(this, "Wait for the reply to finish."); return; }
        store().deleteMessage(m.id);
        messages.remove(m);
        atts.remove(m.id);
        partialMsg = null;
        renderAll();
        updateHeader();
    }

    /** Asks again from this prompt (or from the prompt of this reply). Anything after that prompt is dropped. */
    private void regenerateFrom(final ChatStore.Message m) {
        if (streaming) { AiUi.toast(this, "Wait for the reply to finish."); return; }
        int idx = messages.indexOf(m);
        while (idx >= 0 && !messages.get(idx).isUser()) idx--;
        if (idx < 0) return;
        final int promptIdx = idx;
        int dropped = messages.size() - (promptIdx + 1);
        final boolean lastReply = dropped == 1 && !messages.get(messages.size() - 1).isUser();
        if (dropped == 0 || lastReply) { truncateAfter(promptIdx); return; }
        confirm("This asks again from here and removes the " + dropped + " messages after this prompt.", "Continue", () -> truncateAfter(promptIdx));
    }

    private void truncateAfter(int promptIdx) {
        while (messages.size() > promptIdx + 1) {
            ChatStore.Message last = messages.remove(messages.size() - 1);
            store().deleteMessage(last.id);
            atts.remove(last.id);
        }
        partialMsg = null;
        renderAll();
        updateHeader();
        startStream();
    }

    // ------------------------------------------------------------------ attachments

    private void attachMenu() {
        if (pending.size() >= AttachKind.MAX_PER_MESSAGE) { AiUi.toast(this, "At most " + AttachKind.MAX_PER_MESSAGE + " files per message."); return; }
        new Dlg(this).setTitle("Attach").setItems(new String[]{"Browse files (Kali and phone storage)", "Photos, Drive and other apps…"}, (d, w) -> {
            if (w == 0) {
                FilePick.request(this, false, null, path -> importAsync(() -> ChatAttachments.fromGuestPath(this, path)));
            } else {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try { startActivityForResult(i, REQ_PICK); } catch (RuntimeException e) { AiUi.toast(this, "No file picker available."); }
            }
        }).show();
    }

    private interface Importer { ChatAttachments.Pending run() throws IOException; }

    private void importAsync(final Importer job) {
        AiUi.toast(this, "Adding file…");
        new Thread(() -> {
            ChatAttachments.Pending p = null;
            String err = null;
            try { p = job.run(); } catch (IOException e) { err = e.getMessage(); } catch (RuntimeException e) { err = String.valueOf(e.getMessage()); }
            final ChatAttachments.Pending fp = p;
            final String fe = err;
            runOnUiThread(() -> {
                if (destroyed) { if (fp != null) fp.file.delete(); return; }
                if (fe != null) { AiUi.toast(this, fe); return; }
                long total = fp.size;
                for (ChatAttachments.Pending q : pending) total += q.size;
                if (pending.size() >= AttachKind.MAX_PER_MESSAGE) { fp.file.delete(); AiUi.toast(this, "At most " + AttachKind.MAX_PER_MESSAGE + " files per message."); return; }
                if (total > AttachKind.MAX_TOTAL) { fp.file.delete(); AiUi.toast(this, "Together the files are over " + AttachKind.humanSize(AttachKind.MAX_TOTAL) + ". Send fewer at a time."); return; }
                pending.add(fp);
                refreshPending();
            });
        }, "chat-import").start();
    }

    private void refreshPending() {
        pendingRow.removeAllViews();
        pendingScroll.setVisibility(pending.isEmpty() ? View.GONE : View.VISIBLE);
        for (final ChatAttachments.Pending p : pending) {
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setBackground(Ui.rounded(this, Ui.SURFACE2, 16));
            chip.setPadding(AiUi.dp(this, 12), 0, AiUi.dp(this, 4), 0);
            TextView t = new TextView(this);
            String n = p.name.length() > 24 ? p.name.substring(0, 21) + "…" : p.name;
            t.setText(n + "  ·  " + AttachKind.humanSize(p.size));
            t.setTextColor(Ui.TEXT);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            chip.addView(t);
            android.widget.ImageView x = ChatMarkdown.iconAction(this, R.drawable.ic_act_close, "Remove " + p.name, v -> {
                pending.remove(p);
                p.file.delete();
                refreshPending();
            });
            x.setPadding(AiUi.dp(this, 10), AiUi.dp(this, 10), AiUi.dp(this, 10), AiUi.dp(this, 10));
            chip.addView(x);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, AiUi.dp(this, 36));
            lp.rightMargin = AiUi.dp(this, 6);
            pendingRow.addView(chip, lp);
        }
    }

    /** Thumbnails for images and a small chip for other files, shown above a prompt. */
    private View attachmentViews(List<ChatStore.Attachment> al) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.END);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.END;
        col.setLayoutParams(clp);
        for (ChatStore.Attachment a : al) {
            LinearLayout.LayoutParams lp;
            if (a.isImage()) {
                final android.widget.ImageView iv = new android.widget.ImageView(this);
                iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                iv.setBackground(Ui.rounded(this, Ui.SURFACE2, 12));
                iv.setClipToOutline(true);
                iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), AiUi.dp(ChatActivity.this, 12)); }
                });
                iv.setContentDescription(a.name);
                loadThumb(a.file(), iv);
                lp = new LinearLayout.LayoutParams(AiUi.dp(this, 150), AiUi.dp(this, 150));
                lp.bottomMargin = AiUi.dp(this, 4);
                lp.gravity = Gravity.END;
                col.addView(iv, lp);
            } else {
                TextView t = new TextView(this);
                String n = a.name.length() > 30 ? a.name.substring(0, 27) + "…" : a.name;
                t.setText("📎  " + n + "  ·  " + AttachKind.humanSize(a.size));
                t.setTextColor(Ui.TEXT);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                t.setBackground(Ui.rounded(this, Ui.SURFACE2, 12));
                t.setPadding(AiUi.dp(this, 12), AiUi.dp(this, 7), AiUi.dp(this, 12), AiUi.dp(this, 7));
                lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = AiUi.dp(this, 4);
                lp.gravity = Gravity.END;
                col.addView(t, lp);
            }
        }
        return col;
    }

    private void loadThumb(final java.io.File f, final android.widget.ImageView iv) {
        new Thread(() -> {
            try {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
                int target = 360, sample = 1;
                while (o.outWidth / (sample * 2) >= target && o.outHeight / (sample * 2) >= target) sample *= 2;
                o.inJustDecodeBounds = false;
                o.inSampleSize = sample;
                final android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
                if (bmp != null) runOnUiThread(() -> { if (!destroyed) iv.setImageBitmap(bmp); });
            } catch (RuntimeException | OutOfMemoryError ignored) { }
        }, "thumb").start();
    }

    /**
     * The chat as the API wants it. Text and code files are written into the prompt, other files go along as inline data.
     * Everything is re-sent each turn (Gemini keeps no memory), so the newest files are kept and older ones dropped
     * with a note once the size limits are reached.
     */
    private List<GeminiClient.Msg> buildHistory(List<ChatStore.Message> msgs, Map<Long, List<ChatStore.Attachment>> map) {
        long binaryLeft = AttachKind.MAX_TOTAL;
        long textLeft = 2L * AttachKind.MAX_TEXT_CHARS;
        GeminiClient.Msg[] out = new GeminiClient.Msg[msgs.size()];
        for (int i = msgs.size() - 1; i >= 0; i--) {
            ChatStore.Message m = msgs.get(i);
            StringBuilder text = new StringBuilder(m.text);
            List<GeminiClient.Attach> binaries = new ArrayList<>();
            List<ChatStore.Attachment> al = map.get(m.id);
            if (al != null) for (ChatStore.Attachment a : al) {
                java.io.File f = a.file();
                if (!f.isFile()) { text.append("\n\n[Attachment ").append(a.name).append(" is no longer available]"); continue; }
                if (a.isText()) {
                    if (textLeft <= 0) { text.append("\n\n[Attachment ").append(a.name).append(" left out to keep the request small]"); continue; }
                    try {
                        byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
                        String content = new String(b, java.nio.charset.StandardCharsets.UTF_8);
                        textLeft -= Math.min(content.length(), AttachKind.MAX_TEXT_CHARS);
                        text.append("\n\n").append(AttachKind.asPromptText(a.name, content));
                    } catch (IOException | RuntimeException e) {
                        text.append("\n\n[Attachment ").append(a.name).append(" could not be read]");
                    }
                } else if (f.length() <= binaryLeft) {
                    binaryLeft -= f.length();
                    binaries.add(new GeminiClient.Attach(a.mime, f, a.name));
                } else {
                    text.append("\n\n[Attachment ").append(a.name).append(" left out to keep the request small]");
                }
            }
            out[i] = new GeminiClient.Msg(m.role, text.toString().trim(), binaries);
        }
        List<GeminiClient.Msg> list = new ArrayList<>();
        for (GeminiClient.Msg m : out) list.add(m);
        return list;
    }

    // ------------------------------------------------------------------ terminal, share, save

    private void pasteCode(String code) {
        MainActivity.pasteInTerminal(this, code);
        AiUi.toast(this, "Pasted into the terminal. Check it, then press Enter.");
    }

    private void shareConversation() {
        String text = conversationText();
        if (text.length() > 150000) { AiUi.toast(this, "Too long to share as text. Use “Save as Markdown file”."); return; }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        ChatStore.Conversation c = store().conversation(convId);
        if (c != null && !c.title.isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, c.title);
        startActivity(Intent.createChooser(i, "Share conversation"));
    }

    private void saveConversation() {
        ChatStore.Conversation c = store().conversation(convId);
        String base = c == null || c.title.isEmpty() ? "chat" : c.title.replaceAll("[^A-Za-z0-9 _-]", "").trim().replace(' ', '-');
        if (base.isEmpty()) base = "chat";
        if (base.length() > 40) base = base.substring(0, 40);
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/markdown");
        i.putExtra(Intent.EXTRA_TITLE, base + ".md");
        try { startActivityForResult(i, REQ_SAVE); } catch (RuntimeException e) { AiUi.toast(this, "No file picker available."); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (FilePick.handle(req, res, data)) return;
        if (req == REQ_PICK && res == RESULT_OK && data != null) {
            final List<android.net.Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) uris.add(data.getData());
            for (final android.net.Uri u : uris) importAsync(() -> ChatAttachments.fromUri(this, u));
            return;
        }
        if (req == REQ_SAVE && res == RESULT_OK && data != null && data.getData() != null) {
            try (java.io.OutputStream os = getContentResolver().openOutputStream(data.getData())) {
                if (os == null) throw new IOException("Couldn't open the file");
                os.write(conversationText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                AiUi.toast(this, "Saved");
            } catch (IOException | RuntimeException e) {
                AiUi.toast(this, "Couldn't save: " + e.getMessage());
            }
            return;
        }
        super.onActivityResult(req, res, data);
    }

    private void scrollToEnd(boolean force) {
        final boolean near = scroll.getChildAt(0) != null
                && scroll.getScrollY() + scroll.getHeight() >= scroll.getChildAt(0).getHeight() - AiUi.dp(this, 120);
        if (!force && !near) return;
        scroll.post(() -> scroll.scrollTo(0, list.getHeight()));
    }

    private void refreshSendButton() {
        sendBtn.setText(streaming ? "■" : "↑");
        sendBtn.setTextColor(Ui.ON_ACCENT);
        sendBtn.setBackground(Ui.rounded(this, Ui.ACCENT, 22));
    }

    // ------------------------------------------------------------------ sending

    private void askForKey() {
        new Dlg(this).setTitle("Gemini API key")
                .setMessage("Add your Gemini API key in AI settings to start chatting. You can create a free one in Google AI Studio.")
                .setPositiveButton("AI settings", (d, w) -> startActivity(new Intent(this, AiSettingsActivity.class)))
                .setNegativeButton("Later", null).show();
    }

    private void send() {
        final String text = input.getText().toString().trim();
        if (text.isEmpty() && pending.isEmpty()) return;
        if (!KeyVault.has(this)) { askForKey(); return; }
        if (!Prefs.sp(this).getBoolean("ai_privacy_ok", false)) {
            new Dlg(this).setTitle("Before you send")
                    .setMessage("Your messages go to Google's Gemini API. On the free tier Google may use them to improve its products, so don't send secrets or other people's private data.")
                    .setPositiveButton("OK, continue", (d, w) -> { Prefs.sp(this).edit().putBoolean("ai_privacy_ok", true).apply(); send(); })
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        input.setText("");
        if (emptyView.getParent() == list) list.removeView(emptyView);
        ChatStore.Message um = store().addMessage(convId, "user", text);
        messages.add(um);
        String firstName = "";
        List<ChatStore.Attachment> saved = new ArrayList<>();
        for (int i = 0; i < pending.size(); i++) {
            ChatAttachments.Pending p = pending.get(i);
            try {
                java.io.File dest = ChatAttachments.store(this, p, um.id, i);
                ChatStore.Attachment a = new ChatStore.Attachment();
                a.msgId = um.id; a.name = p.name; a.mime = p.mime; a.size = p.size; a.path = dest.getPath();
                a.id = store().addAttachment(um.id, p.name, p.mime, p.size, dest);
                saved.add(a);
                if (firstName.isEmpty()) firstName = p.name;
            } catch (IOException e) {
                AiUi.toast(this, "Couldn't keep " + p.name + ": " + e.getMessage());
            }
        }
        pending.clear();
        refreshPending();
        if (!saved.isEmpty()) atts.put(um.id, saved);
        list.addView(rowFor(um));
        ChatStore.Conversation c = store().conversation(convId);
        if (c != null && c.title.isEmpty()) {
            String t = (text.isEmpty() ? firstName : text).replaceAll("\\s+", " ");
            store().renameConversation(convId, t.length() > 42 ? t.substring(0, 42).trim() + "…" : t);
        }
        updateHeader();
        startStream();
    }

    private void startStream() {
        if (streaming) return;
        if (errorRow != null && errorRow.getParent() == list) list.removeView(errorRow);
        errorRow = null;
        final String key = KeyVault.get(this);
        final String model = modelId();
        final String system = assistant.prompt;
        final float temp = assistant.temp;
        final int maxTok = assistant.maxTokens;
        final List<ChatStore.Message> snapshot = new ArrayList<>(messages);
        final Map<Long, List<ChatStore.Attachment>> attSnapshot = new HashMap<>(atts);

        acc.setLength(0);
        final int myGen = ++gen;
        final long myConv = convId;
        streaming = true;
        refreshSendButton();
        status.setVisibility(View.GONE);

        liveBody = new ChatMarkdown.Body(this);
        liveBody.setTerminalAction(this::pasteCode);
        liveTyping = new TextView(this);
        liveTyping.setText("…");
        liveTyping.setTextColor(Ui.DIM);
        liveTyping.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        LinearLayout card = wrapAssistant(liveBody);
        card.addView(liveTyping, 0);
        list.addView(card);
        scrollToEnd(true);

        cancel = new GeminiClient.Cancel();
        final GeminiClient.Cancel myCancel = cancel;
        new Thread(() -> {
            String err = null;
            String partial = null;
            try {
                List<GeminiClient.Msg> history = buildHistory(snapshot, attSnapshot);
                GeminiClient.stream(key, model, system, history, temp, maxTok, new GeminiClient.Listener() {
                    @Override public void onText(String chunk) {
                        if (myGen != gen) return;
                        acc.append(chunk);
                        scheduleRender();
                    }
                    @Override public void onRetry(int attempt, int max, long delayMs, String reason) {
                        ui.post(() -> {
                            if (destroyed || myGen != gen) return;
                            status.setText("Gemini is busy (" + reason.toLowerCase() + "). Retrying " + attempt + "/" + max + "…");
                            status.setVisibility(View.VISIBLE);
                        });
                    }
                }, myCancel);
            } catch (GeminiClient.PartialException pe) {
                partial = pe.partial;
                err = GeminiClient.describe(pe);
            } catch (IOException e) {
                err = GeminiClient.describe(e);
            }
            final String fErr = err;
            ui.post(() -> { if (!destroyed && myGen == gen) finishStream(fErr); });
        }, "gemini-chat").start();
    }

    private void scheduleRender() {
        if (renderPending.compareAndSet(false, true)) ui.postDelayed(() -> {
            renderPending.set(false);
            if (destroyed || liveBody == null) return;
            String t = acc.toString();
            if (liveTyping != null && !t.isEmpty()) { ((ViewGroup) liveTyping.getParent()).removeView(liveTyping); liveTyping = null; }
            liveBody.setMarkdown(t);
            scrollToEnd(false);
        }, 70);
    }

    private void stopStreaming() {
        if (cancel != null) cancel.cancel();
    }

    private void finishStream(String error) {
        if (!streaming) return;
        streaming = false;
        status.setVisibility(View.GONE);
        String text = acc.toString();
        View liveCard = liveBody == null ? null : (View) liveBody.getParent();
        liveBody = null;
        liveTyping = null;
        partialMsg = null;
        if (!text.isEmpty()) {
            ChatStore.Message m = store().addMessage(convId, "model", text);
            messages.add(m);
            if (error != null) partialMsg = m;
        }
        if (liveCard != null) list.removeView(liveCard);
        if (!text.isEmpty()) list.addView(rowFor(messages.get(messages.size() - 1)));
        refreshSendButton();
        updateHeader();
        if (error != null) showError(error);
        scrollToEnd(true);
    }

    private void showError(String message) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(Ui.rounded(this, Theme.withAlpha(Ui.DANGER, 0x22), 14));
        row.setPadding(AiUi.dp(this, 14), AiUi.dp(this, 10), AiUi.dp(this, 14), AiUi.dp(this, 10));
        TextView t = new TextView(this);
        t.setText(message);
        t.setTextColor(Ui.DANGER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        row.addView(t);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, AiUi.dp(this, 8), 0, 0);
        TextView retry = Ui.key(this, "Retry");
        retry.setOnClickListener(v -> retryLast());
        TextView model = Ui.key(this, "Change model");
        model.setOnClickListener(v -> ModelPicker.show(this, assistant.model, "Use default (" + ModelPicker.defaultModel(this) + ")", id -> {
            assistant.model = id;
            store().saveAssistant(assistant);
            updateHeader();
            retryLast();
        }));
        TextView keyBtn = Ui.key(this, "AI settings");
        keyBtn.setOnClickListener(v -> startActivity(new Intent(this, AiSettingsActivity.class)));
        btns.addView(retry);
        btns.addView(model);
        if (message.contains("API key")) btns.addView(keyBtn);
        row.addView(btns);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = AiUi.dp(this, 8);
        list.addView(row, lp);
        errorRow = row;
    }

    private void retryLast() {
        if (streaming) return;
        if (partialMsg != null) {
            store().deleteMessage(partialMsg.id);
            messages.remove(partialMsg);
            partialMsg = null;
            renderAll();
        } else if (errorRow != null && errorRow.getParent() == list) {
            list.removeView(errorRow);
        }
        startStream();
    }
}
