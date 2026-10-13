# NullShell

A rootless Kali Linux environment and development workspace for Android (ARM64) — terminal, code editor, in-app browser, file manager and an AI assistant, all in one APK, no root required.

![banner](https://github.com/ScriptKiddieZed/NullShell/blob/124f7b95837b9612afc9f379acb893f020b4fd5e/banner.png)

NullShell runs a full Kali Linux userspace through [PRoot](https://proot-me.github.io/), directly from the app. Kali Linux is a trademark of OffSec; NullShell is not affiliated with or endorsed by it.

## Features

### Terminal
- A real Kali Linux userspace (ARM64), launched through PRoot — install packages with `apt`, run the tools you already know.
- Background jobs run as a foreground service, so long commands keep going while you switch apps.
- A pure-Java terminal fast path, scrollback limits and other memory controls so long sessions stay responsive on phones.

### Code editor
- Built on [sora-editor](https://github.com/Rosemoe/sora-editor), with TextMate-grammar syntax highlighting and Language Server Protocol support.
- Highlighting and/or an LSP-backed language server (for diagnostics, completion and formatting) for: Python, JavaScript, TypeScript, JSON, HTML, CSS, XML, Markdown, Shell, C, C++, YAML, Java, Kotlin, Lua, Go, Rust, PHP, Ruby, SQL, Dockerfile, Makefile, INI/config files and Diff/patch files.
- Language servers install on demand into the Kali userspace (e.g. `pylsp`, `typescript-language-server`, `clangd`, `gopls`, `rust-analyzer`, `intelephense`, `solargraph`), with per-server memory caps and idle shutdown.
- Large-file mode and tab unloading keep memory in check when editing big projects.
- Open text files from other apps directly into the NullShell editor, and jump from the editor to "show in file manager".

### In-app browser
- Tabbed WebView browser with history, and app-private CA-certificate trust so you can point it at your own intercepting proxy (mitmproxy, Caido, Burp, etc.) without touching the system trust store.
- A desktop-style developer tools panel: Elements, Console, Network, Sources, Application, Security, Performance and a Device (user-agent/viewport) emulation panel, with a code-wrap toggle in the Elements/Sources views.

### Custom commands (snippets)
- Save frequently used shell commands as reusable, named snippets with a one-tap run dialog.
- Flexible variables in a command template: typed/picked file and directory inputs, filters (`{url|host}`), derived defaults, masked secret fields (`{pass*}`), multi-select choices (`{tags+,:a|b}`), and conditional blocks (`{if ...}`) so one snippet can adapt to how you fill it in.

### File manager
- A native file manager (browse, move, copy, rename, delete, open-with) covering both the app's own storage and the Kali rootfs.

### AI chat
- An in-app assistant powered by the Gemini API: multiple assistants with their own system prompts, streamed responses, model picker, file attachments, and saved conversations.
- Per-message actions (copy, copy as plain text, regenerate, delete) and long-press text selection.
- Paste a shell code block straight from a chat into the terminal, or export a conversation as Markdown.

### Appearance
- 16 built-in themes covering the terminal, editor and UI, each with its own matching launcher icon (with an optional "match theme" switch), adjustable accent colour and corner roundness, and automatic day/night switching.

### Networking
- A Network screen to view and copy your device's IP and Wi-Fi gateway, and discover other devices on the same Wi-Fi network or your phone's hotspot.

## Building from source

NullShell is a standard Android Gradle project (Java, no Kotlin in the app module) and is primarily developed on-device with [CodeAssist](https://github.com/tyron12233/CodeAssist), but builds the same way with Android Studio or plain Gradle.

Requirements: JDK 17, Android SDK (compileSdk 35), and a device or emulator running Android 8.0 (API 26) or newer.

```bash
git clone https://github.com/<your-username>/NullShell.git
cd NullShell
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. A GitHub Actions workflow (`.github/workflows/build.yml`) builds the same target on every push and pull request against `main`, and uploads the APK as a build artifact.

### Native PRoot runtime (required to run Kali, not needed just to build)

The APK needs these prebuilt ARM64 native libraries under `app/src/main/jniLibs/arm64-v8a/` before it can actually start a Kali session:

```text
app/src/main/jniLibs/arm64-v8a/
    libproot.so
    libproot-loader.so
    libproot-loader32.so   (optional)
    libtalloc.so
    libandroid-shmem.so
```

These are PRoot, talloc and libandroid-shmem binaries built for Termux, and are **not redistributed in this repository** (they're GPL-2.0+/LGPL-3.0/BSD-licensed prebuilt artifacts, not NullShell's own code). Generate them yourself, on an Android device, using Termux:

```bash
pkg update
pkg install proot libtalloc libandroid-shmem
bash scripts/prepare-proot-from-termux.sh
```

`scripts/prepare-proot-from-termux.sh` copies the Termux binaries into `jniLibs/arm64-v8a` and patches PRoot's `DT_NEEDED` entry from `libtalloc.so.2` to `libtalloc.so`, since Android extracts packaged native libraries under a flat `nativeLibraryDir`. Run `scripts/verify-native-runtime.sh` afterwards to confirm all required files are present.

Without this step, the app still compiles and installs, but terminal sessions can't start — this is exactly what the CI build in this repository produces (a verified, compiling build, without the native runtime bundled).

## License

NullShell's own source code is released under the [MIT License](LICENSE).

NullShell bundles and depends on third-party software under their own licenses — sora-editor (LGPL-2.1), Eclipse LSP4J (EPL-2.0), Apache Commons Compress/XZ, bundled TextMate grammars, JetBrains Mono, and the PRoot/talloc/libandroid-shmem native runtime (GPL-2.0+/LGPL-3.0/BSD, not redistributed). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the full list.
