package com.zed.nullshell;

import java.util.Locale;

/** Decides what to do with a file attached to a chat message. Pure Java (testable on a desktop JVM). */
final class AttachKind {
    private AttachKind() { }

    static final long MAX_FILE = 14L * 1024 * 1024;        // per file, inline
    static final long MAX_TOTAL = 14L * 1024 * 1024;       // per request, all attachments together
    static final int MAX_TEXT_CHARS = 300_000;             // per text file
    static final int MAX_PER_MESSAGE = 6;
    static final String TEXT = "text/plain";

    /** A mime type Gemini accepts inline, "text/plain" for files read as text, or null when not recognised by name. */
    static String mimeFor(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String ext = dot >= 0 ? n.substring(dot + 1) : "";
        switch (ext) {
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "webp": return "image/webp";
            case "heic": return "image/heic";
            case "heif": return "image/heif";
            case "pdf": return "application/pdf";
            case "mp3": return "audio/mp3";
            case "wav": return "audio/wav";
            case "aac": return "audio/aac";
            case "ogg": case "oga": return "audio/ogg";
            case "flac": return "audio/flac";
            case "aiff": case "aif": return "audio/aiff";
            case "m4a": return "audio/aac";
            case "mp4": return "video/mp4";
            case "mov": return "video/mov";
            case "webm": return "video/webm";
            case "mpeg": case "mpg": return "video/mpeg";
            case "avi": return "video/avi";
            case "wmv": return "video/wmv";
            case "3gp": return "video/3gpp";
            case "flv": return "video/x-flv";
            default: break;
        }
        if (isTextExtension(ext) || isTextName(n)) return TEXT;
        return null;
    }

    private static boolean isTextName(String n) {
        String base = n.substring(n.lastIndexOf('/') + 1);
        switch (base) {
            case "makefile": case "dockerfile": case "readme": case "license": case "changelog": case ".gitignore": case ".bashrc": case ".zshrc": case ".profile": return true;
            default: return false;
        }
    }

    private static boolean isTextExtension(String e) {
        switch (e) {
            case "txt": case "md": case "markdown": case "rst": case "log": case "csv": case "tsv": case "json": case "jsonl": case "xml": case "yaml": case "yml":
            case "toml": case "ini": case "cfg": case "conf": case "env": case "properties": case "gradle": case "html": case "htm": case "css": case "scss": case "js": case "mjs":
            case "ts": case "tsx": case "jsx": case "java": case "kt": case "kts": case "c": case "h": case "cpp": case "cc": case "hpp": case "cs": case "go": case "rs": case "py":
            case "rb": case "php": case "pl": case "lua": case "sh": case "bash": case "zsh": case "fish": case "ps1": case "bat": case "sql": case "swift": case "dart": case "r":
            case "tex": case "diff": case "patch": case "nmap": case "gnmap": case "pcap_txt": case "lock": case "svg": case "vue": case "svelte": case "asm": case "s": case "nse":
                return true;
            default: return false;
        }
    }

    /** For files whose name says nothing: the first bytes look like UTF-8 text (no NUL bytes, mostly printable). */
    static boolean looksLikeText(byte[] head, int len) {
        if (len <= 0) return true;
        int bad = 0;
        for (int i = 0; i < len; i++) {
            int b = head[i] & 0xFF;
            if (b == 0) return false;
            if (b < 9 || (b > 13 && b < 32)) bad++;
        }
        if (bad * 50 > len) return false;
        // must decode as UTF-8 (a cut multi-byte tail at the end of the sample is fine)
        int cut = len;
        for (int k = 0; k < 3 && cut > 0; k++) { if ((head[cut - 1] & 0xC0) == 0x80 || (head[cut - 1] & 0xC0) == 0xC0) cut--; else break; }
        try {
            java.nio.charset.CharsetDecoder d = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
            d.decode(java.nio.ByteBuffer.wrap(head, 0, cut));
            return true;
        } catch (java.nio.charset.CharacterCodingException e) {
            return false;
        }
    }

    static boolean isImage(String mime) { return mime != null && mime.startsWith("image/"); }

    static String humanSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        return String.format(Locale.US, "%.1f MB", b / 1048576.0);
    }

    /** A file's text wrapped in a fence the content can't break, for sending as part of the prompt. */
    static String asPromptText(String name, String content) {
        boolean truncated = content.length() > MAX_TEXT_CHARS;
        if (truncated) content = content.substring(0, MAX_TEXT_CHARS);
        String fence = "```";
        while (content.contains(fence)) fence += "`";
        return "[Attached file: " + name + "]\n" + fence + "\n" + content + (content.endsWith("\n") ? "" : "\n") + fence
                + (truncated ? "\n(The file was cut after " + MAX_TEXT_CHARS + " characters.)" : "");
    }
}
