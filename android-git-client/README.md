# Git Client (isomorphic-git, standalone Web/PWA)

A git client that runs **entirely client-side in the browser** — no backend
server of any kind. It uses [isomorphic-git](https://isomorphic-git.org/) for
all git operations and [`@isomorphic-git/lightning-fs`](https://github.com/isomorphic-git/lightning-fs)
(an IndexedDB-backed filesystem) for storage, so every repo you clone, edit,
and commit lives entirely on the device — nothing is ever uploaded except
the git traffic you explicitly push to a remote.

Install it to the home screen on Android Chrome ("Add to Home screen") and it
runs full-screen like a native app (PWA `display: standalone`), works
offline for everything except talking to remotes, and needs no App Store /
Play Store distribution.

## Why "standalone" and not a native APK

The original ask was an Android git client built on isomorphic-git. Two
architectures were possible:

1. **Native app shell (Capacitor) wrapping this same web code.** More
   "app-like," but adds an Android/Gradle build users have to run, and this
   sandbox has no Android SDK to build/verify the APK.
2. **Pure web app / installable PWA.** Same isomorphic-git core, zero native
   build step, works today in any browser (Android, desktop, iOS), fully
   testable in this environment. Chosen approach — see the interruption in
   the task history where this was explicitly requested.

Nothing about the git logic (`src/git.js`, `src/fs.js`) is web-only; if a
native shell is wanted later, that same core can be dropped into a Capacitor
project with only the shell/build layer added.

## Architecture

```
index.html        entry point, loads src/main.js
src/main.js        all UI: render(state) -> innerHTML, single delegated
                    click handler, small amount of state kept in memory
src/git.js          thin wrapper around isomorphic-git: init/clone/status/
                    add/commit/log/branches/fetch/pull/push, identity and
                    credential storage (localStorage)
src/fs.js           LightningFS setup; every repo lives under /repos/<name>
                    in one shared IndexedDB-backed filesystem
public/manifest.webmanifest, public/sw.js
                    PWA install metadata + an app-shell cache so the UI
                    itself loads with zero network (repos already cloned
                    keep working fully offline too, since they live in
                    IndexedDB, not on a server)
```

There is no server component. `npm run build` produces static files
(`dist/`) you can host anywhere (GitHub Pages, Netlify, `npx serve`, or even
open in Chrome as `file://` for local-only use — service worker install
won't work over `file://`, but IndexedDB and isomorphic-git still do).

## Running locally

```sh
npm install
npm run dev       # http://localhost:5173, hot reload
# or
npm run build && npm run preview   # production build, http://localhost:4173
```

## Installing on Android as a standalone app

1. Host `dist/` somewhere reachable over HTTPS (GitHub Pages, Netlify, Vercel,
   Cloudflare Pages, or your own server — a PWA service worker requires a
   secure origin, `localhost` also works for testing).
2. Open the URL in Chrome for Android.
3. Menu (⋮) → **Add to Home screen** / **Install app**.
4. Launch it from the home screen — it opens full-screen with no browser
   chrome (`display: standalone` in the manifest) and keeps working offline
   for anything already cloned.

## Talking to real git remotes: the CORS proxy requirement

This is the one real limitation of a pure-browser git client, and it's
worth understanding rather than hiding:

Git's smart-HTTP protocol (what `git clone`/`fetch`/`push` use over HTTPS)
was not designed with CORS in mind, and essentially no git host (GitHub,
GitLab, Bitbucket, self-hosted...) sends the `Access-Control-Allow-Origin`
headers a browser requires for cross-origin requests. isomorphic-git's
browser HTTP client therefore needs a small CORS-unwrapping proxy in front
of the remote.

- **Default**: the app points at the public isomorphic-git demo proxy
  (`https://cors.isomorphic-git.org`). Fine for trying things out; it is
  rate-limited and not something to rely on for real work (or for anything
  private — it's a third party in the path).
- **Recommended for real use**: run your own. isomorphic-git publishes a
  [reference proxy implementation](https://github.com/isomorphic-git/cors-proxy)
  that's a few lines of Node/Express (also deployable to Cloudflare
  Workers, etc.). Point the app at it from **Settings → CORS プロキシ** on any
  repo's Remote tab, or a repo's own Remote tab (per-repo override).
- This proxy only relays HTTP requests/responses byte-for-byte and adds
  CORS headers — it never sees your git objects any differently than the
  real remote would, and credentials go straight through to the origin
  server (Basic Auth over HTTPS), not to the proxy's application logic.
- This requirement is specific to running inside a **browser tab**. If this
  code is later wrapped in a native shell (e.g. Capacitor with
  `CapacitorHttp` enabled), native HTTP requests bypass the browser's CORS
  enforcement entirely and the proxy is no longer necessary — worth
  revisiting if a native build becomes a priority.

## Authentication

Git hosts today require a Personal Access Token (PAT), not a password, for
HTTPS git operations (GitHub, GitLab, Bitbucket all deprecated plain
passwords for this). On a repo's **Remote** tab, enter your username and a
PAT as the "password". Check "この端末に保存する" to keep it in
`localStorage` for that repo (skip it to be asked every time). This is the
same trust model as a browser's saved-password manager — anything with
script access to the page can read it, so don't use this for anything more
sensitive than you'd put in a browser-saved password.

## Features

- Multiple local repos, each an isolated directory in IndexedDB
- `git init` and `git clone` (with optional auth)
- Working-tree file browser: create, edit (plain-text editor), delete files
- Status view with per-file stage/unstage, "stage all" / "unstage all"
- Commit (author identity set once in Settings, reused for every repo)
- Commit history (`git log`)
- Local branches: list, create, checkout
- Remote: configure URL, fetch, pull, push, per-repo CORS proxy override
- Installable as a standalone PWA (offline app shell, home-screen icon)

## Known limitations

- No merge-conflict UI — a `pull` that would conflict fails with an error
  toast rather than offering a resolution flow.
- No diff view yet (status shows changed files, not line-level diffs).
- Large repos: `clone` defaults to `depth: 50` (shallow) to keep IndexedDB
  usage and clone time reasonable on a phone; adjust in `src/git.js` if you
  need full history.
- Relies on a CORS proxy for any real remote, as explained above.
