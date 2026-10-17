# Third-party software in NullShell

| Component | Used for | License |
|---|---|---|
| [sora-editor](https://github.com/Rosemoe/sora-editor) (`editor`, `language-textmate`, `oniguruma-native`, `editor-lsp`) | code editor, syntax highlighting, language-server client | LGPL-2.1 |
| [Eclipse LSP4J](https://github.com/eclipse-lsp4j/lsp4j) | language-server protocol types | EPL-2.0 |
| Apache Commons Compress, XZ for Java | reading / writing rootfs archives | Apache-2.0, public domain |
| TextMate grammars and themes in `app/src/main/assets/textmate` (Java, Kotlin, Python, XML, HTML, JavaScript, Markdown, Lua; Darcula, Quiet Light, Solarized Dark, Ayu Dark) | syntax highlighting | copied from the sora-editor demo app; each grammar keeps the license of its upstream project (mostly MIT) |
| `nullshell.json` theme and the Shell, JSON, YAML, C and CSS grammars | syntax highlighting | written for NullShell |
| JetBrains Mono (`app/src/main/assets/JetBrainsMono-Regular.ttf`) | editor font | SIL Open Font License 1.1 |
| PRoot, talloc, libandroid-shmem (prebuilt, from Termux) | running the Kali userspace | GPL-2.0+, LGPL-3.0, BSD |

sora-editor is linked as an unmodified library from Maven Central, so it can be replaced with another build of the same
library (LGPL-2.1 section 6). If you ever modify sora-editor itself, publish those changes under the same license.
Kali Linux is a trademark of OffSec; NullShell is not affiliated with or endorsed by it.

## IceWM themes (downloaded on request)

The optional theme download in Settings > VNC server fetches the community themes of
[ice-wm/icewm-extra](https://github.com/ice-wm/icewm-extra) (GPL-3.0; each theme has its own authors) at the user's request.
They are not bundled in the APK.

## Bundled IceWM themes

`app/src/main/assets/icewm-themes/` contains three IceWM themes, each licensed under the GNU GPL (the license text is in each
theme's `COPYING` file) and copied into the user's Kali home folder at runtime as plain data files:

- **Nord** (originally "Icewm_Nord_style") by IamJony - GPL
- **Nitrogen** by jcsl and Marcus Moeller - GPL
- **Ice7** by Lioh Moeller, based on work by bluedxca93 and I-Jurij - GPL

They are separate works and are not linked into the app's code.
