package com.zed.nullshell;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Gemini REST client (no Android classes, so it can be tested on a desktop JVM).
 * List models, and stream a chat reply with retry/backoff for "overloaded" and rate-limit errors.
 */
final class GeminiClient {
    static String baseUrl = "https://generativelanguage.googleapis.com/v1beta/";
    static final String DEFAULT_MODEL = "gemini-flash-lite-latest";

    static final class Model {
        final String id, display;
        final int inputLimit, outputLimit;
        Model(String id, String display, int inputLimit, int outputLimit) { this.id = id; this.display = display; this.inputLimit = inputLimit; this.outputLimit = outputLimit; }
    }

    /** A file sent inline with a message. */
    static final class Attach {
        final String mime;
        final java.io.File file;
        final String name;
        Attach(String mime, java.io.File file, String name) { this.mime = mime; this.file = file; this.name = name; }
    }

    static final class Msg {
        final String role;   // "user" or "model"
        final String text;
        final List<Attach> attachments;
        Msg(String role, String text) { this(role, text, null); }
        Msg(String role, String text, List<Attach> attachments) {
            this.role = role; this.text = text == null ? "" : text;
            this.attachments = attachments == null ? new ArrayList<Attach>() : attachments;
        }
    }

    static final class ApiException extends IOException {
        final int code;
        final String status;
        ApiException(int code, String status, String message) { super(message); this.code = code; this.status = status == null ? "" : status; }
        boolean retryable() { return code == 429 || code == 500 || code == 502 || code == 503 || code == 504; }
        boolean badKey() { return code == 400 && getMessage() != null && getMessage().toLowerCase().contains("api key") || code == 401 || code == 403; }
    }

    interface Listener {
        void onText(String chunk);
        void onRetry(int attempt, int maxAttempts, long delayMs, String reason);
    }

    /** Lets the caller stop a running request. */
    static final class Cancel {
        volatile boolean cancelled;
        volatile HttpURLConnection conn;
        void cancel() {
            cancelled = true;
            HttpURLConnection c = conn;
            if (c != null) { try { c.disconnect(); } catch (RuntimeException ignored) { } }
        }
    }

    // ------------------------------------------------------------------ models

    static List<Model> listModels(String key) throws IOException {
        List<Model> out = new ArrayList<>();
        String token = null;
        for (int page = 0; page < 10; page++) {
            String url = baseUrl + "models?pageSize=200" + (token == null ? "" : "&pageToken=" + enc(token));
            String body = get(url, key);
            try {
                JSONObject o = new JSONObject(body);
                JSONArray arr = o.optJSONArray("models");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.getJSONObject(i);
                    JSONArray methods = m.optJSONArray("supportedGenerationMethods");
                    boolean ok = false;
                    if (methods != null) for (int k = 0; k < methods.length(); k++) if ("generateContent".equals(methods.optString(k))) ok = true;
                    if (!ok) continue;
                    String id = m.optString("name", "");
                    if (id.startsWith("models/")) id = id.substring(7);
                    if (id.isEmpty()) continue;
                    out.add(new Model(id, m.optString("displayName", id), m.optInt("inputTokenLimit", 0), m.optInt("outputTokenLimit", 0)));
                }
                token = o.optString("nextPageToken", "");
            } catch (JSONException e) {
                throw new IOException("Unexpected reply from the models endpoint");
            }
            if (token == null || token.isEmpty()) break;
        }
        return out;
    }

    // ------------------------------------------------------------------ chat

    /**
     * Streams a reply. Returns the full text. Retries before any text has arrived; once text is flowing, a failure is
     * thrown (the caller decides what to keep).
     */
    static String stream(String key, String model, String system, List<Msg> history, float temperature, int maxTokens,
                         Listener l, Cancel cancel) throws IOException {
        String body = buildBody(system, history, temperature, maxTokens);
        int attempt = 0;
        while (true) {
            attempt++;
            StringBuilder got = new StringBuilder();
            try {
                streamOnce(key, model, body, l, cancel, got);
                return got.toString();
            } catch (IOException e) {
                if (cancel != null && cancel.cancelled) return got.toString();
                boolean retry = got.length() == 0 && attempt < maxAttempts(e);
                if (!retry) {
                    if (got.length() > 0) throw new PartialException(got.toString(), e);
                    throw e;
                }
                long delay = Math.min(12000, 1500L * attempt * attempt);
                if (l != null) l.onRetry(attempt + 1, maxAttempts(e), delay, e instanceof ApiException ? ((ApiException) e).status : "network");
                sleep(delay, cancel);
                if (cancel != null && cancel.cancelled) return "";
            }
        }
    }

    /** The reply stopped half way; what arrived so far is in {@link #partial}. */
    static final class PartialException extends IOException {
        final String partial;
        PartialException(String partial, IOException cause) { super(cause.getMessage(), cause); this.partial = partial; }
    }

    private static int maxAttempts(IOException e) {
        if (e instanceof ApiException) {
            ApiException a = (ApiException) e;
            if (!a.retryable()) return 1;
            return a.code == 429 ? 2 : 4;
        }
        return 3;   // network hiccup
    }

    private static void sleep(long ms, Cancel c) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c != null && c.cancelled) return;
            try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }
    }

    static String buildBody(String system, List<Msg> history, float temperature, int maxTokens) throws IOException {
        try {
            JSONObject root = new JSONObject();
            if (system != null && !system.trim().isEmpty()) {
                root.put("system_instruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", system))));
            }
            // The API wants the first turn from the user; neighbouring turns by the same side are merged.
            List<Msg> merged = new ArrayList<>();
            for (Msg m : history) {
                if (m.text.isEmpty() && m.attachments.isEmpty()) continue;
                Msg last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
                if (last != null && last.role.equals(m.role)) {
                    List<Attach> all = new ArrayList<>(last.attachments);
                    all.addAll(m.attachments);
                    String t = last.text.isEmpty() ? m.text : m.text.isEmpty() ? last.text : last.text + "\n\n" + m.text;
                    merged.set(merged.size() - 1, new Msg(m.role, t, all));
                } else merged.add(m);
            }
            while (!merged.isEmpty() && !"user".equals(merged.get(0).role)) merged.remove(0);
            JSONArray contents = new JSONArray();
            for (Msg m : merged) {
                JSONArray parts = new JSONArray();
                for (Attach at : m.attachments) {
                    byte[] data;
                    try { data = java.nio.file.Files.readAllBytes(at.file.toPath()); }
                    catch (IOException | RuntimeException e) { parts.put(new JSONObject().put("text", "[Attachment " + at.name + " could not be read]")); continue; }
                    String b64 = java.util.Base64.getEncoder().encodeToString(data);
                    parts.put(new JSONObject().put("inline_data", new JSONObject().put("mime_type", at.mime).put("data", b64)));
                }
                if (!m.text.isEmpty()) parts.put(new JSONObject().put("text", m.text));
                contents.put(new JSONObject().put("role", m.role).put("parts", parts));
            }
            root.put("contents", contents);
            JSONObject gc = new JSONObject();
            if (temperature >= 0) gc.put("temperature", temperature);
            if (maxTokens > 0) gc.put("maxOutputTokens", maxTokens);
            if (gc.length() > 0) root.put("generationConfig", gc);
            return root.toString();
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    private static void streamOnce(String key, String model, String body, Listener l, Cancel cancel, StringBuilder got) throws IOException {
        URL url = new URL(baseUrl + "models/" + enc(model) + ":streamGenerateContent?alt=sse");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        if (cancel != null) cancel.conn = c;
        try {
            c.setConnectTimeout(20000);
            c.setReadTimeout(90000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("x-goog-api-key", key);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "text/event-stream");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = c.getOutputStream()) { os.write(bytes); }
            int code = c.getResponseCode();
            if (code != 200) throw apiError(code, c.getErrorStream());
            String blockReason = null, finishReason = null;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (cancel != null && cancel.cancelled) return;
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || data.equals("[DONE]")) continue;
                    JSONObject o;
                    try { o = new JSONObject(data); } catch (JSONException e) { continue; }
                    JSONObject err = o.optJSONObject("error");
                    if (err != null) throw new ApiException(err.optInt("code", 500), err.optString("status"), err.optString("message", "Error"));
                    JSONObject pf = o.optJSONObject("promptFeedback");
                    if (pf != null && pf.has("blockReason")) blockReason = pf.optString("blockReason");
                    JSONArray cands = o.optJSONArray("candidates");
                    if (cands == null || cands.length() == 0) continue;
                    JSONObject cand = cands.optJSONObject(0);
                    if (cand == null) continue;
                    if (cand.has("finishReason")) finishReason = cand.optString("finishReason");
                    JSONObject content = cand.optJSONObject("content");
                    JSONArray parts = content == null ? null : content.optJSONArray("parts");
                    if (parts == null) continue;
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject p = parts.optJSONObject(i);
                        if (p == null || p.optBoolean("thought", false)) continue;
                        String t = p.optString("text", "");
                        if (t.isEmpty()) continue;
                        got.append(t);
                        if (l != null) l.onText(t);
                    }
                }
            }
            if (got.length() == 0 && !(cancel != null && cancel.cancelled)) {
                if (blockReason != null) throw new ApiException(400, "BLOCKED", "Blocked by Gemini (" + blockReason + "). Try rephrasing.");
                if (finishReason != null && !finishReason.equals("STOP")) throw new ApiException(400, "BLOCKED", "No reply (" + finishReason + ").");
                throw new ApiException(503, "EMPTY", "The model returned an empty reply.");
            }
        } finally {
            c.disconnect();
        }
    }

    private static ApiException apiError(int code, InputStream err) {
        String msg = "HTTP " + code, status = "";
        try {
            String s = err == null ? "" : readAll(err);
            JSONObject e = new JSONObject(s).optJSONObject("error");
            if (e != null) {
                msg = e.optString("message", msg);
                status = e.optString("status", "");
            } else if (!s.isEmpty()) msg = s.length() > 300 ? s.substring(0, 300) : s;
        } catch (IOException | JSONException ignored) { }
        return new ApiException(code, status, msg);
    }

    private static String get(String url, String key) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestProperty("x-goog-api-key", key);
            int code = c.getResponseCode();
            if (code != 200) throw apiError(code, c.getErrorStream());
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (java.io.UnsupportedEncodingException e) { return s; }
    }

    /** A short, human message for an error (what to show in the chat). */
    static String describe(IOException e) {
        if (e instanceof PartialException && e.getCause() instanceof IOException) e = (IOException) e.getCause();
        if (e instanceof ApiException) {
            ApiException a = (ApiException) e;
            if (a.badKey()) return "The API key was rejected. Check it in AI settings.";
            if (a.code == 503) return "Gemini is overloaded right now (503). Try again, or pick another model.";
            if (a.code == 429) return "Rate limit or quota reached (429). Wait a bit, or pick another model.\n" + a.getMessage();
            if (a.code == 404) return "That model isn't available for this key (404). Pick another model.";
            return a.getMessage();
        }
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException) return "No connection to Google. Check your internet.";
        if (e instanceof java.net.SocketTimeoutException) return "The request timed out.";
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
