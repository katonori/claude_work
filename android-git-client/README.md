# Git Client (native Android, JGit)

A native Android git client. All git operations run natively via
[JGit](https://www.eclipse.org/jgit/) inside a thin
[Capacitor](https://capacitorjs.com/) shell — there is no backend server of
any kind, and no browser-based git engine either. Every repo you clone,
edit, and commit is a real JGit repository under the device's public
`Documents/GitClient/repos/<name>` folder, so it's visible to other apps
(Files, text editors, etc.), and nothing is ever uploaded except the git
traffic you explicitly push to a remote.

## Architecture

```
index.html          entry point, loads src/main.js
src/main.js          all UI: render(state) -> innerHTML, single delegated
                      click handler, small amount of state kept in memory
src/git.js            thin JS bridge over the native GitNative plugin —
                      identity/credential storage (localStorage) plus
                      call-shape translation, no git logic itself
android/app/.../GitNativePlugin.java
                      all actual git work: init/clone/status/add/commit/
                      log/branches/fetch/pull/push via JGit, operating
                      directly on Documents/GitClient/repos/<name>
android/              generated Capacitor/Gradle native project
```

## Building the APK

Requires Node.js, a JDK (21, per `android/app/capacitor.build.gradle`), and
the Android SDK.

```sh
npm install
npm run build          # vite build -> dist/
npx cap sync android    # copy dist/ into android/
cd android
./gradlew assembleDebug
```

The debug APK lands at
`android/app/build/outputs/apk/debug/app-debug.apk`. Install it on a device
via `adb install` (over USB with debugging enabled) or by copying the APK
to the device and opening it (allow "install unknown apps" for whichever
app you use to open it).

There's an `npm run sync` shortcut for the first two steps
(`vite build && npx cap sync android`) when iterating on the web UI.

## Storage permission

Because repos are plain files under the public Documents folder (so other
apps can see them) rather than app-private storage, Android 11+ requires
the app to hold **All files access** (`MANAGE_EXTERNAL_STORAGE`) — the old
`READ/WRITE_EXTERNAL_STORAGE` runtime-permission dialog doesn't cover public
directories anymore. There's no in-app prompt for this permission (Android
doesn't allow one); on first launch the app shows a gate screen with a
button that opens the system settings page to grant it, and re-checks
automatically when you return to the app.

## Talking to real git remotes

No CORS proxy is needed. That was only ever a browser/WebView-`fetch`
limitation (isomorphic-git's old constraint); JGit's HTTP transport is
plain Java networking and isn't subject to CORS at all, so it talks to
GitHub/GitLab/etc. directly.

## Authentication

Git hosts today require a Personal Access Token (PAT), not a password, for
HTTPS git operations (GitHub, GitLab, Bitbucket all deprecated plain
passwords for this). On a repo's **Remote** tab, enter your username and a
PAT as the "password". Check "この端末に保存する" to keep it in
`localStorage` for that repo (skip it to be asked every time). Anything
with script access to the WebView can read it, so don't use this for
anything more sensitive than you'd put in a browser-saved password.

## Features

- Multiple local repos, each a real directory under
  `Documents/GitClient/repos/<name>`
- `git init` and `git clone` (with optional auth)
- Working-tree file browser: create, edit (plain-text editor), delete files
- Status view with per-file stage/unstage, "stage all" / "unstage all"
- Commit (author identity set once in Settings, reused for every repo)
- Commit history (`git log`)
- Local branches: list, create, checkout
- Remote: configure URL, fetch, pull, push

## Known limitations

- No merge-conflict UI — a `pull` that would conflict fails with an error
  toast rather than offering a resolution flow.
- No diff view yet (status shows changed files, not line-level diffs).
- Large repos: `clone` defaults to `depth: 50` (shallow) to keep clone time
  reasonable on a phone; adjust in `src/git.js` if you need full history.
- No symlink support in the working tree.
- Requires "All files access" (see Storage permission above).
