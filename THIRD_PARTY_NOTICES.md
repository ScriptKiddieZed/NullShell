# Third-party software in NullShell

| Component | Used for | License |
|---|---|---|
| [sora-editor](https://github.com/Rosemoe/sora-editor) (`editor`, `language-textmate`, `oniguruma-native`, `editor-lsp`) | code editor, syntax highlighting, language-server client | LGPL-2.1 |
| [Eclipse LSP4J](https://github.com/eclipse-lsp4j/lsp4j) | language-server protocol types | EPL-2.0 |
| Apache Commons Compress, XZ for Java | reading / writing rootfs archives | Apache-2.0, public domain |
| TextMate grammars and themes in `app/src/main/assets/textmate` (Java, Kotlin, Python, XML, HTML, JavaScript, Markdown, Lua; Darcula, Quiet Light, Solarized Dark, Ayu Dark) | syntax highlighting | copied from the sora-editor demo app; each grammar keeps the license of its upstream project (mostly MIT) |
| `nullshell.json` theme and the Shell, JSON, YAML, C and CSS grammars | syntax highlighting | written for NullShell |
| JetBrains Mono (`app/src/main/assets/JetBrainsMono-Regular.ttf`) | editor font | SIL Open Font License 1.1 |

sora-editor is linked as an unmodified library from Maven Central, so it can be replaced with another build of the same
library (LGPL-2.1 section 6). If you ever modify sora-editor itself, publish those changes under the same license.
Kali Linux is a trademark of OffSec; NullShell is not affiliated with or endorsed by it.

## Native runtime (prebuilt, bundled in `app/src/main/jniLibs/arm64-v8a/`)

Unmodified builds from the Termux packages, used to run the Kali userspace. They are separate works under their own
licenses, not covered by NullShell's MIT license.

| File | Component | License | Source |
|---|---|---|---|
| `libproot.so` | [PRoot](https://github.com/proot-me/proot) | GPL-2.0-or-later | <https://github.com/termux/proot> (Termux build), upstream <https://github.com/proot-me/proot> |
| `libproot-loader.so`, `libproot-loader32.so` | PRoot loader (built from the PRoot source) | GPL-2.0-or-later | same as PRoot |
| `libtalloc.so` | [talloc](https://talloc.samba.org/) | LGPL-3.0-or-later | <https://www.samba.org/ftp/talloc/>; Termux recipe: <https://github.com/termux/termux-packages/tree/master/packages/libtalloc> |
| `libandroid-shmem.so` | [libandroid-shmem](https://github.com/termux/libandroid-shmem) | BSD-3-Clause | <https://github.com/termux/libandroid-shmem> |

Versions shipped: proot 5.1.107.96, libtalloc 2.4.3, libandroid-shmem 0.7 (see `pkg list-installed` in Termux).
Termux packaging recipes: <https://github.com/termux/termux-packages> (`packages/proot`, `packages/libtalloc`,
`packages/libandroid-shmem`).

**Source availability (GPL/LGPL).** The corresponding source for these exact versions is available at the links above.
If those become unavailable, open an issue in this repository and the source will be provided for at least three years
from distribution of the binaries.

**Modifications.** `scripts/prepare-proot-from-termux.sh` only patches `libproot.so`'s `DT_NEEDED` entry from
`libtalloc.so.2` to `libtalloc.so` so it loads from Android's flat `nativeLibraryDir`. There are no source changes.

**Replacing the libraries (LGPL).** `libtalloc.so` is a separate shared library loaded dynamically. To use a modified
version, replace the file in `app/src/main/jniLibs/arm64-v8a/` and rebuild with `./gradlew assembleDebug`.

License texts are in [`licenses/`](licenses/): `GPL-2.0.txt` (PRoot), `LGPL-3.0.txt` and `GPL-3.0.txt` (talloc),
and the BSD-3-Clause text with copyright line for libandroid-shmem.

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
