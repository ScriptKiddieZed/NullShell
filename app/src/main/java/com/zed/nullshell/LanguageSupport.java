package com.zed.nullshell;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Which file types the editor knows, how to highlight them, and which language server serves them. Pure Java. */
final class LanguageSupport {
    private LanguageSupport() { }

    static final class Server {
        final String id, label, binary, install;
        final String[] argv;
        String runtime = "";         // "node" or "go": runtimes whose heap can be capped from outside
        Server(String id, String label, String binary, String[] argv, String install) {
            this.id = id; this.label = label; this.binary = binary; this.argv = argv; this.install = install;
        }
        Server runtime(String r) { this.runtime = r; return this; }
        String defaultCommand() { return String.join(" ", argv); }
    }

    static final class Lang {
        final String id, name;
        final String scope;          // TextMate scope, null = no highlighting
        final String serverId;       // null = no language server
        final String[] exts;
        final String lspId;          // languageId sent to the server
        Lang(String id, String name, String scope, String lspId, String serverId, String... exts) {
            this.id = id; this.name = name; this.scope = scope; this.lspId = lspId; this.serverId = serverId; this.exts = exts;
        }
    }

    private static final String NODE = "apt update && apt install -y nodejs npm && npm install -g ";

    private static final List<Server> SERVERS = Arrays.asList(
            new Server("pylsp", "Python (pylsp)", "pylsp", new String[]{"pylsp"},
                    "apt update && apt install -y python3-pylsp"),
            new Server("tsserver", "JavaScript / TypeScript", "typescript-language-server",
                    new String[]{"typescript-language-server", "--stdio"}, NODE + "typescript typescript-language-server").runtime("node"),
            new Server("bash", "Shell (bash-language-server)", "bash-language-server",
                    new String[]{"bash-language-server", "start"}, NODE + "bash-language-server").runtime("node"),
            new Server("clangd", "C / C++ (clangd)", "clangd", new String[]{"clangd", "-j=2", "--pch-storage=disk", "--malloc-trim"},
                    "apt update && apt install -y clangd"),
            new Server("json", "JSON", "vscode-json-language-server",
                    new String[]{"vscode-json-language-server", "--stdio"}, NODE + "vscode-langservers-extracted").runtime("node"),
            new Server("html", "HTML", "vscode-html-language-server",
                    new String[]{"vscode-html-language-server", "--stdio"}, NODE + "vscode-langservers-extracted").runtime("node"),
            new Server("css", "CSS", "vscode-css-language-server",
                    new String[]{"vscode-css-language-server", "--stdio"}, NODE + "vscode-langservers-extracted").runtime("node"),
            new Server("yaml", "YAML", "yaml-language-server",
                    new String[]{"yaml-language-server", "--stdio"}, NODE + "yaml-language-server").runtime("node"),
            new Server("gopls", "Go (gopls)", "gopls", new String[]{"gopls"},
                    "apt update && apt install -y gopls").runtime("go"),
            new Server("rust", "Rust (rust-analyzer)", "rust-analyzer", new String[]{"rust-analyzer"},
                    "apt update && apt install -y rust-analyzer"),
            new Server("intelephense", "PHP (Intelephense)", "intelephense", new String[]{"intelephense", "--stdio"},
                    NODE + "intelephense").runtime("node"),
            new Server("sqlls", "SQL (sql-language-server)", "sql-language-server",
                    new String[]{"sql-language-server", "up", "--method", "stdio"}, NODE + "sql-language-server").runtime("node"),
            new Server("dockerls", "Dockerfile (docker-langserver)", "docker-langserver",
                    new String[]{"docker-langserver", "--stdio"}, NODE + "dockerfile-language-server-nodejs").runtime("node"),
            new Server("solargraph", "Ruby (Solargraph)", "solargraph", new String[]{"solargraph", "stdio"},
                    "apt update && apt install -y ruby ruby-dev build-essential && gem install solargraph"));

    private static final List<Lang> LANGS = Arrays.asList(
            new Lang("python", "Python", "source.python", "python", "pylsp", "py", "pyw"),
            new Lang("javascript", "JavaScript", "source.js", "javascript", "tsserver", "js", "mjs", "cjs", "jsx"),
            new Lang("typescript", "TypeScript", "source.ts", "typescript", "tsserver", "ts", "tsx", "mts", "cts"),
            new Lang("json", "JSON", "source.json", "json", "json", "json", "jsonc", "webmanifest"),
            new Lang("html", "HTML", "text.html.basic", "html", "html", "html", "htm"),
            new Lang("css", "CSS", "source.css", "css", "css", "css"),
            new Lang("xml", "XML", "text.xml", "xml", null, "xml", "xsd", "svg", "plist", "xhtml"),
            new Lang("markdown", "Markdown", "text.html.markdown", "markdown", null, "md", "markdown"),
            new Lang("shell", "Shell", "source.shell", "shellscript", "bash", "sh", "bash", "zsh", "ksh", "bashrc", "profile", "zshrc", "bash_aliases"),
            new Lang("c", "C", "source.c", "c", "clangd", "c", "h"),
            new Lang("cpp", "C++", "source.cpp", "cpp", "clangd", "cpp", "cc", "cxx", "c++", "hpp", "hh", "hxx", "h++"),
            new Lang("yaml", "YAML", "source.yaml", "yaml", "yaml", "yml", "yaml"),
            new Lang("java", "Java", "source.java", "java", null, "java"),
            new Lang("kotlin", "Kotlin", "source.kotlin", "kotlin", null, "kt", "kts"),
            new Lang("lua", "Lua", "source.lua", "lua", null, "lua"),
            new Lang("go", "Go", "source.go", "go", "gopls", "go"),
            new Lang("rust", "Rust", "source.rust", "rust", "rust", "rs"),
            new Lang("php", "PHP", "source.php", "php", "intelephense", "php", "phtml", "php5", "php7"),
            new Lang("ruby", "Ruby", "source.ruby", "ruby", "solargraph", "rb", "rbw", "gemspec", "rake", "ru", "rakefile", "gemfile"),
            new Lang("sql", "SQL", "source.sql", "sql", "sqlls", "sql"),
            new Lang("dockerfile", "Dockerfile", "source.dockerfile", "dockerfile", "dockerls", "dockerfile"),
            new Lang("makefile", "Makefile", "source.makefile", "makefile", null, "makefile", "mk", "mak", "gnumakefile"),
            new Lang("ini", "INI", "source.ini", "ini", null, "ini", "cfg", "conf", "properties"),
            new Lang("diff", "Diff / Patch", "source.diff", "diff", null, "diff", "patch"));

    static List<Server> servers() { return SERVERS; }
    static List<Lang> languages() { return LANGS; }

    static Server server(String id) {
        if (id == null) return null;
        for (Server s : SERVERS) if (s.id.equals(id)) return s;
        return null;
    }

    /** The language for a file name, or null for plain text. Dotfiles such as ".bashrc" match by their name. */
    static Lang forFile(String fileName) {
        String n = fileName.toLowerCase(Locale.US);
        String ext;
        int dot = n.lastIndexOf('.');
        ext = dot >= 0 ? n.substring(dot + 1) : n;
        for (Lang l : LANGS) for (String e : l.exts) if (e.equals(ext) || e.equals(n)) return l;
        return null;
    }

    /** Command line for a server: the user's override if set, else the default. */
    static List<String> argv(Server s, String override) {
        if (override != null && !override.trim().isEmpty()) return new ArrayList<>(Arrays.asList(override.trim().split("\\s+")));
        return new ArrayList<>(Arrays.asList(s.argv));
    }

    /** Environment that caps a server's heap, for the runtimes that honour it. Empty for the others or when {@code mb} is 0. */
    static List<String> memoryEnv(Server s, int mb) {
        List<String> out = new ArrayList<>();
        if (mb <= 0) return out;
        if ("node".equals(s.runtime)) out.add("NODE_OPTIONS=--max-old-space-size=" + mb);
        else if ("go".equals(s.runtime)) out.add("GOMEMLIMIT=" + mb + "MiB");
        return out;
    }

    /** First word of a command line, used to check that the program exists. */
    static String binary(Server s, String override) {
        return argv(s, override).get(0);
    }
}
