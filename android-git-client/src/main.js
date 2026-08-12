import './style.css';

import { listRepos, repoDir, repoExists, removeRepo } from './git.js';
import * as Git from './git.js';

const app = document.getElementById('app');

const state = {
  view: 'home', // 'home' | 'repo'
  repos: [],
  repoBranches: {}, // name -> current branch label (best effort, home list only)
  currentRepo: null,
  tab: 'changes', // 'changes' | 'history' | 'branches' | 'files' | 'remote'
  status: [],
  branch: null,
  branches: [],
  remoteBranches: [],
  log: [],
  files: [],
  editingFile: null,
  editingIsNew: false,
  modal: null,
  busy: false,
  busyLabel: '',
  toast: null,
};

function esc(str) {
  return String(str ?? '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;',
  }[c]));
}

function shortOid(oid) {
  return oid ? oid.slice(0, 7) : '';
}

function formatDate(author) {
  if (!author) return '';
  const d = new Date(author.timestamp * 1000);
  return d.toLocaleString();
}

function statusKind(entry) {
  if (entry.label.startsWith('untracked')) return 'untracked';
  if (entry.label.startsWith('added')) return 'added';
  if (entry.label.startsWith('deleted')) return 'deleted';
  return 'modified';
}

function showToast(message, isError = false) {
  state.toast = { message, isError };
  render();
  clearTimeout(showToast._t);
  showToast._t = setTimeout(() => {
    state.toast = null;
    render();
  }, 3500);
}

async function withBusy(label, fn) {
  state.busy = true;
  state.busyLabel = label;
  render();
  try {
    return await fn();
  } catch (err) {
    console.error(err);
    showToast(err && err.message ? err.message : String(err), true);
    throw err;
  } finally {
    state.busy = false;
    state.busyLabel = '';
    render();
  }
}

function currentDir() {
  return repoDir(state.currentRepo);
}

async function goHome() {
  state.view = 'home';
  state.currentRepo = null;
  await refreshRepoList();
  render();
}

async function refreshRepoList() {
  state.repos = await listRepos();
  for (const name of state.repos) {
    try {
      state.repoBranches[name] = await Git.currentBranch(repoDir(name));
    } catch {
      state.repoBranches[name] = null;
    }
  }
}

async function openRepo(name) {
  state.currentRepo = name;
  state.view = 'repo';
  state.tab = 'changes';
  state.commitMessageValue = '';
  await refreshRepoData();
  render();
}

async function refreshRepoData() {
  const dir = currentDir();
  const [status, branch, branches, remoteBranches, log, files] = await Promise.all([
    Git.getStatus(dir).catch(() => []),
    Git.currentBranch(dir).catch(() => null),
    Git.listBranches(dir).catch(() => []),
    Git.listRemoteBranches(dir).catch(() => []),
    Git.getLog(dir, { depth: 50 }).catch(() => []),
    Git.listWorkingFiles(dir).catch(() => []),
  ]);
  state.status = status;
  state.branch = branch;
  state.branches = branches;
  state.remoteBranches = remoteBranches;
  state.log = log;
  state.files = files.sort();
}

function openModal(type, extra = {}) {
  state.modal = { type, ...extra };
  render();
}

function closeModal() {
  state.modal = null;
  render();
}

// ---------- Rendering ----------

// Every render() does a full innerHTML rebuild, which would otherwise wipe
// out whatever the user is mid-typing (e.g. a commit message) if an
// unrelated async op (stage/unstage/fetch…) completes and re-renders while
// they're typing. Inputs listed here are restored from `state` right after
// the rebuild, and kept in sync via an 'input' listener in attachHandlers().
const PRESERVED_INPUTS = [
  ['editor-textarea', 'editingContentValue'],
  ['commit-msg', 'commitMessageValue'],
];

function render() {
  app.innerHTML =
    state.view === 'permission' ? renderPermissionGate() : state.view === 'home' ? renderHome() : renderRepoView();
  if (state.modal) app.insertAdjacentHTML('beforeend', renderModal());
  if (state.busy) app.insertAdjacentHTML('beforeend', renderBusy());
  if (state.toast) app.insertAdjacentHTML('beforeend', renderToast());
  attachHandlers();
  for (const [id, key] of PRESERVED_INPUTS) {
    const el = document.getElementById(id);
    if (el && state[key] !== undefined) el.value = state[key];
  }
}

function renderToast() {
  return `<div class="toast ${state.toast.isError ? 'error' : ''}">${esc(state.toast.message)}</div>`;
}

function renderBusy() {
  return `<div class="toast"><span class="spinner"></span>${esc(state.busyLabel || 'Working…')}</div>`;
}

function renderPermissionGate() {
  return `
    <main style="display:flex;flex-direction:column;justify-content:center;align-items:center;min-height:100vh;padding:24px;text-align:center;gap:16px;">
      <h1>Git Client</h1>
      <p style="color:var(--text-dim);max-width:320px;">
        リポジトリを端末の Documents フォルダに保存し、他のアプリからも見えるようにするには、
        「すべてのファイルへのアクセス」を許可してください。
      </p>
      <button class="btn" data-action="request-storage-access">アクセスを許可</button>
      <button class="small-btn" data-action="recheck-storage-access">許可したので確認する</button>
    </main>
  `;
}

function renderHome() {
  const list = state.repos.length
    ? `<ul class="repo-list">${state.repos
        .map(
          (name) => `
        <li class="repo-card">
          <button data-action="open-repo" data-name="${esc(name)}">
            <div class="name">${esc(name)}</div>
            <div class="meta">${esc(state.repoBranches[name] || 'no commits yet')}</div>
          </button>
          <button class="small-btn" data-action="delete-repo" data-name="${esc(name)}">Delete</button>
        </li>`
        )
        .join('')}</ul>`
    : `<div class="empty-state">まだリポジトリがありません。<br/>「Clone」で既存リポジトリを取得するか、「New」でローカルリポジトリを作成してください。<br/><br/>すべて端末の Documents/GitClient フォルダに保存され、サーバーには送信されません。</div>`;

  return `
    <header class="topbar">
      <h1>Git Client</h1>
      <button class="icon-btn" data-action="open-settings">⚙️ 設定</button>
    </header>
    <main>
      <div class="btn-row" style="margin-bottom:16px;">
        <button class="btn" data-action="open-clone">＋ Clone</button>
        <button class="btn secondary" data-action="open-init">＋ New</button>
      </div>
      ${list}
    </main>
  `;
}

const TABS = [
  ['changes', '変更'],
  ['history', '履歴'],
  ['branches', 'ブランチ'],
  ['files', 'ファイル'],
  ['remote', 'リモート'],
];

function renderRepoView() {
  return `
    <header class="topbar">
      <button class="icon-btn" data-action="go-home">←</button>
      <h1>${esc(state.currentRepo)}</h1>
      <span class="branch-pill">${esc(state.branch || 'detached')}</span>
    </header>
    <main>${renderTabContent()}</main>
    <nav class="tabbar">
      ${TABS.map(
        ([id, label]) => `
        <button data-action="set-tab" data-tab="${id}" class="${state.tab === id ? 'active' : ''} ${
          id === 'changes' && state.status.length > 0 ? 'has-badge' : ''
        }">
          <span class="dot"></span>${label}
        </button>`
      ).join('')}
    </nav>
  `;
}

function renderTabContent() {
  if (state.editingFile !== null) return renderFileEditor();
  switch (state.tab) {
    case 'changes':
      return renderChanges();
    case 'history':
      return renderHistory();
    case 'branches':
      return renderBranches();
    case 'files':
      return renderFiles();
    case 'remote':
      return renderRemote();
    default:
      return '';
  }
}

function renderChanges() {
  if (!state.status.length) {
    return `<div class="empty-state">変更はありません。作業ツリーはクリーンです。</div>`;
  }
  const rows = state.status
    .map((s) => {
      const kind = statusKind(s);
      return `
      <div class="file-row">
        <span class="status-dot ${kind}"></span>
        <button class="path" style="background:none;border:none;color:inherit;text-align:left;padding:0;" data-action="edit-file" data-path="${esc(
          s.filepath
        )}">${esc(s.filepath)}<div class="status-label">${esc(s.label)}</div></button>
        <button class="small-btn" data-action="${s.staged ? 'unstage' : 'stage'}" data-path="${esc(s.filepath)}">${
        s.staged ? 'Unstage' : 'Stage'
      }</button>
      </div>`;
    })
    .join('');

  return `
    <div class="btn-row" style="margin-bottom:10px;">
      <button class="small-btn" data-action="stage-all">全てStage</button>
      <button class="small-btn" data-action="unstage-all">全てUnstage</button>
    </div>
    ${rows}
    <div class="section-title">コミット</div>
    <textarea id="commit-msg" class="commit-msg" placeholder="コミットメッセージ"></textarea>
    <div class="btn-row" style="margin-top:10px;">
      <button class="btn" data-action="commit">コミット</button>
    </div>
  `;
}

function renderHistory() {
  if (!state.log.length) {
    return `<div class="empty-state">コミット履歴がありません。</div>`;
  }
  return state.log
    .map(
      (entry) => `
      <div class="commit-item">
        <div class="msg">${esc((entry.commit.message || '').split('\n')[0])}</div>
        <div class="meta"><span class="oid">${shortOid(entry.oid)}</span> · ${esc(
        entry.commit.author.name
      )} · ${esc(formatDate(entry.commit.author))}</div>
      </div>`
    )
    .join('');
}

function renderBranches() {
  const localItems = state.branches
    .map(
      (b) => `
      <div class="branch-item">
        <span>${esc(b)}</span>
        ${
          b === state.branch
            ? '<span class="current">current</span>'
            : `<button class="small-btn" data-action="checkout-branch" data-branch="${esc(b)}">Checkout</button>`
        }
      </div>`
    )
    .join('');

  const remoteItems = state.remoteBranches
    .map((b) => `<div class="branch-item"><span>origin/${esc(b)}</span></div>`)
    .join('');

  const options = [
    ...state.branches.map(
      (b) => `<option value="${esc(b)}" ${b === state.branch ? 'selected' : ''}>${esc(b)}</option>`
    ),
    ...state.remoteBranches.map((b) => `<option value="origin/${esc(b)}">origin/${esc(b)}</option>`),
  ].join('');

  return `
    <div class="section-title">Checkout</div>
    <div class="btn-row" style="margin-bottom:18px;">
      <select id="branch-select" class="branch-select">${options}</select>
      <button class="small-btn" data-action="checkout-selected-branch">Checkout</button>
    </div>

    <div class="btn-row" style="margin-bottom:14px;">
      <button class="btn secondary" data-action="open-new-branch">＋ 新規ブランチ</button>
    </div>

    <div class="section-title">ローカルブランチ</div>
    ${localItems || '<div class="empty-state">ブランチがありません。</div>'}

    <div class="section-title">リモートブランチ (origin)</div>
    ${remoteItems || '<div class="empty-state">リモートブランチがありません。Fetchすると表示されます。</div>'}
  `;
}

function renderFiles() {
  const rows = state.files
    .map(
      (f) => `
      <div class="file-row">
        <button class="path" style="background:none;border:none;color:inherit;text-align:left;padding:0;" data-action="edit-file" data-path="${esc(
          f
        )}">${esc(f)}</button>
        <button class="small-btn" data-action="delete-file" data-path="${esc(f)}">削除</button>
      </div>`
    )
    .join('');
  return `
    <div class="btn-row" style="margin-bottom:10px;">
      <button class="btn secondary" data-action="open-new-file">＋ 新規ファイル</button>
    </div>
    ${rows || '<div class="empty-state">ファイルがありません。</div>'}
  `;
}

function renderRemote() {
  const cred = Git.getSavedCredentials(state.currentRepo) || {};
  return `
    <div class="section-title">リモートURL</div>
    <div class="field">
      <input id="remote-url" type="text" placeholder="https://github.com/user/repo.git" value="${esc(
        state.remoteUrlValue ?? ''
      )}" />
    </div>
    <div class="btn-row" style="margin-bottom:18px;">
      <button class="small-btn" data-action="save-remote-url">保存</button>
      <button class="small-btn" data-action="load-remote-url">読込</button>
    </div>

    <div class="section-title">認証 (Personal Access Token 推奨)</div>
    <div class="field">
      <label>ユーザー名</label>
      <input id="cred-username" type="text" value="${esc(cred.username || '')}" />
    </div>
    <div class="field">
      <label>パスワード / トークン</label>
      <input id="cred-password" type="password" value="${esc(cred.password || '')}" />
    </div>
    <label class="checkbox-row">
      <input type="checkbox" id="cred-remember" ${Git.getSavedCredentials(state.currentRepo) ? 'checked' : ''}/>
      この端末に保存する (localStorage)
    </label>

    <div class="section-title">操作</div>
    <div class="btn-row">
      <button class="btn secondary" data-action="git-fetch">Fetch</button>
      <button class="btn secondary" data-action="git-pull">Pull</button>
      <button class="btn" data-action="git-push">Push</button>
    </div>
  `;
}

function renderFileEditor() {
  if (state.editingContentValue === undefined) state.editingContentValue = '';
  return `
    <div class="breadcrumb">${state.editingIsNew ? '新規ファイル' : '編集'}: ${esc(state.editingFile || '')}</div>
    ${
      state.editingIsNew
        ? `<div class="field"><input id="new-file-path" type="text" placeholder="path/to/file.txt" /></div>`
        : ''
    }
    <div class="file-editor">
      <textarea id="editor-textarea" spellcheck="false"></textarea>
    </div>
    <div class="btn-row" style="margin-top:10px;">
      <button class="btn" data-action="save-file">保存</button>
      <button class="btn secondary" data-action="cancel-edit">キャンセル</button>
    </div>
  `;
}

function renderModal() {
  const m = state.modal;
  let body = '';
  if (m.type === 'clone') {
    body = `
      <h2>リポジトリを Clone</h2>
      <div class="field"><label>URL</label><input id="m-url" type="text" placeholder="https://github.com/user/repo.git" /></div>
      <div class="field"><label>保存名</label><input id="m-name" type="text" placeholder="repo" /></div>
      <div class="field"><label>ユーザー名 (プライベートリポジトリのみ)</label><input id="m-username" type="text" /></div>
      <div class="field"><label>パスワード / トークン</label><input id="m-password" type="password" /></div>
      <div class="btn-row"><button class="btn" data-action="do-clone">Clone</button><button class="btn secondary" data-action="close-modal">キャンセル</button></div>
    `;
  } else if (m.type === 'init') {
    body = `
      <h2>新規ローカルリポジトリ</h2>
      <div class="field"><label>名前</label><input id="m-name" type="text" placeholder="my-repo" /></div>
      <div class="btn-row"><button class="btn" data-action="do-init">作成</button><button class="btn secondary" data-action="close-modal">キャンセル</button></div>
    `;
  } else if (m.type === 'settings') {
    const id = Git.getIdentity();
    body = `
      <h2>設定 (コミット用ID)</h2>
      <div class="field"><label>名前</label><input id="m-username" type="text" value="${esc(id.name)}" /></div>
      <div class="field"><label>メールアドレス</label><input id="m-email" type="text" value="${esc(id.email)}" /></div>
      <div class="btn-row"><button class="btn" data-action="save-settings">保存</button><button class="btn secondary" data-action="close-modal">閉じる</button></div>
    `;
  } else if (m.type === 'new-branch') {
    body = `
      <h2>新規ブランチ</h2>
      <div class="field"><label>ブランチ名</label><input id="m-name" type="text" /></div>
      <label class="checkbox-row"><input type="checkbox" id="m-checkout" checked/> 作成後に切り替える</label>
      <div class="btn-row" style="margin-top:12px;"><button class="btn" data-action="do-new-branch">作成</button><button class="btn secondary" data-action="close-modal">キャンセル</button></div>
    `;
  }
  return `<div class="modal-backdrop" data-action="backdrop"><div class="modal">${body}</div></div>`;
}

// ---------- Event handling ----------

function attachHandlers() {
  app.querySelectorAll('[data-action]').forEach((el) => {
    el.addEventListener('click', onAction);
  });
  const backdrop = app.querySelector('.modal-backdrop');
  if (backdrop) {
    backdrop.addEventListener('click', (e) => {
      if (e.target === backdrop) closeModal();
    });
  }
  for (const [id, key] of PRESERVED_INPUTS) {
    const el = document.getElementById(id);
    if (el) el.addEventListener('input', (e) => { state[key] = e.target.value; });
  }
}

async function onAction(e) {
  const el = e.currentTarget;
  const action = el.dataset.action;
  if (action === 'backdrop') return;

  try {
    switch (action) {
      case 'request-storage-access':
        await Git.requestStorageAccess();
        break;
      case 'recheck-storage-access':
        await enterAppIfReady();
        break;
      case 'open-clone':
        openModal('clone');
        break;
      case 'open-init':
        openModal('init');
        break;
      case 'open-settings':
        openModal('settings');
        break;
      case 'close-modal':
        closeModal();
        break;
      case 'open-repo':
        await withBusy('読み込み中…', () => openRepo(el.dataset.name));
        break;
      case 'go-home':
        await withBusy('読み込み中…', goHome);
        break;
      case 'set-tab':
        state.tab = el.dataset.tab;
        state.editingFile = null;
        if (state.tab === 'remote') {
          state.remoteUrlValue = await Git.getRemoteUrl(currentDir());
        }
        render();
        break;
      case 'delete-repo':
        if (confirm(`"${el.dataset.name}" を削除しますか？この操作は取り消せません。`)) {
          await withBusy('削除中…', async () => {
            await removeRepo(el.dataset.name);
            Git.clearCredentials(el.dataset.name);
            await refreshRepoList();
          });
        }
        break;
      case 'do-clone':
        await handleClone();
        break;
      case 'do-init':
        await handleInit();
        break;
      case 'save-settings': {
        const name = document.getElementById('m-username').value.trim();
        const email = document.getElementById('m-email').value.trim();
        Git.setIdentity({ name, email });
        showToast('設定を保存しました');
        closeModal();
        break;
      }
      case 'stage-all':
        await withBusy('Staging…', async () => {
          await Git.stageAll(currentDir());
          await refreshRepoData();
        });
        break;
      case 'unstage-all':
        await withBusy('Unstaging…', async () => {
          await Git.unstageAll(currentDir());
          await refreshRepoData();
        });
        break;
      case 'stage':
        await withBusy('Staging…', async () => {
          await Git.stageFile(currentDir(), el.dataset.path);
          await refreshRepoData();
        });
        break;
      case 'unstage':
        await withBusy('Unstaging…', async () => {
          await Git.unstageFile(currentDir(), el.dataset.path);
          await refreshRepoData();
        });
        break;
      case 'commit': {
        const msg = (state.commitMessageValue || '').trim();
        if (!msg) {
          showToast('コミットメッセージを入力してください', true);
          break;
        }
        await withBusy('コミット中…', async () => {
          await Git.commit(currentDir(), msg);
          await refreshRepoData();
        });
        state.commitMessageValue = '';
        showToast('コミットしました');
        render();
        break;
      }
      case 'checkout-branch':
        await withBusy('切り替え中…', async () => {
          await Git.checkoutBranch(currentDir(), el.dataset.branch);
          await refreshRepoData();
        });
        break;
      case 'checkout-selected-branch': {
        const branch = document.getElementById('branch-select')?.value;
        if (!branch) break;
        await withBusy('切り替え中…', async () => {
          await Git.checkoutBranch(currentDir(), branch);
          await refreshRepoData();
        });
        break;
      }
      case 'open-new-branch':
        openModal('new-branch');
        break;
      case 'do-new-branch': {
        const name = document.getElementById('m-name').value.trim();
        const checkout = document.getElementById('m-checkout').checked;
        if (!name) break;
        closeModal();
        await withBusy('ブランチ作成中…', async () => {
          await Git.createBranch(currentDir(), name, { checkout });
          await refreshRepoData();
        });
        break;
      }
      case 'edit-file': {
        const path = el.dataset.path;
        const content = await withBusy('読み込み中…', () => Git.readFile(currentDir(), path).catch(() => ''));
        state.editingFile = path;
        state.editingIsNew = false;
        state.editingContentValue = content;
        render();
        break;
      }
      case 'open-new-file':
        state.editingFile = '';
        state.editingIsNew = true;
        state.editingContentValue = '';
        render();
        break;
      case 'save-file': {
        let path = state.editingFile;
        if (state.editingIsNew) {
          path = document.getElementById('new-file-path').value.trim();
          if (!path) {
            showToast('ファイルパスを入力してください', true);
            break;
          }
        }
        const content = document.getElementById('editor-textarea').value;
        await withBusy('保存中…', async () => {
          await Git.writeFile(currentDir(), path, content);
          await refreshRepoData();
        });
        state.editingFile = null;
        showToast('保存しました');
        render();
        break;
      }
      case 'cancel-edit':
        state.editingFile = null;
        state.editingContentValue = undefined;
        render();
        break;
      case 'delete-file':
        if (confirm(`"${el.dataset.path}" を削除しますか？`)) {
          await withBusy('削除中…', async () => {
            await Git.deleteFile(currentDir(), el.dataset.path);
            await refreshRepoData();
          });
        }
        break;
      case 'save-remote-url': {
        const url = document.getElementById('remote-url').value.trim();
        await withBusy('保存中…', () => Git.setRemoteUrl(currentDir(), url));
        showToast('リモートURLを保存しました');
        break;
      }
      case 'load-remote-url':
        state.remoteUrlValue = await Git.getRemoteUrl(currentDir());
        render();
        break;
      case 'git-fetch':
        await runRemoteOp('fetch');
        break;
      case 'git-pull':
        await runRemoteOp('pull');
        break;
      case 'git-push':
        await runRemoteOp('push');
        break;
    }
  } catch {
    /* errors already surfaced via toast in withBusy */
  }
}

function collectCreds() {
  const username = document.getElementById('cred-username')?.value.trim();
  const password = document.getElementById('cred-password')?.value;
  const remember = document.getElementById('cred-remember')?.checked;
  if (remember && username) {
    Git.saveCredentials(state.currentRepo, { username, password });
  } else if (!remember) {
    Git.clearCredentials(state.currentRepo);
  }
  return username ? { username, password } : null;
}

async function runRemoteOp(kind) {
  const creds = collectCreds();
  const dir = currentDir();
  const labels = { fetch: 'Fetch中…', pull: 'Pull中…', push: 'Push中…' };
  await withBusy(labels[kind], async () => {
    if (kind === 'fetch') await Git.fetchRepo(dir, { creds });
    if (kind === 'pull') await Git.pullRepo(dir, { creds });
    if (kind === 'push') await Git.pushRepo(dir, { creds });
    await refreshRepoData();
  });
  showToast(`${kind} 完了`);
}

async function handleClone() {
  const url = document.getElementById('m-url').value.trim();
  const nameInput = document.getElementById('m-name').value.trim();
  const username = document.getElementById('m-username').value.trim();
  const password = document.getElementById('m-password').value;
  if (!url) {
    showToast('URLを入力してください', true);
    return;
  }
  const name = nameInput || url.replace(/\/$/, '').split('/').pop().replace(/\.git$/, '');
  if (!name || /[^\w.-]/.test(name)) {
    showToast('保存名が不正です', true);
    return;
  }
  if (await repoExists(name)) {
    showToast('同名のリポジトリが既に存在します', true);
    return;
  }
  closeModal();
  await withBusy('Clone中… (サイズによって時間がかかります)', async () => {
    const creds = username ? { username, password } : null;
    if (creds) Git.saveCredentials(name, creds);
    await Git.cloneRepo(name, url, { creds });
  });
  showToast('Cloneしました');
  await openRepo(name);
}

async function handleInit() {
  const name = document.getElementById('m-name').value.trim();
  if (!name || /[^\w.-]/.test(name)) {
    showToast('リポジトリ名が不正です', true);
    return;
  }
  if (await repoExists(name)) {
    showToast('同名のリポジトリが既に存在します', true);
    return;
  }
  closeModal();
  await withBusy('作成中…', () => Git.initRepo(name));
  showToast('作成しました');
  await openRepo(name);
}

// ---------- boot ----------

async function enterAppIfReady() {
  if (await Git.checkStorageAccess()) {
    state.view = 'home';
    await refreshRepoList();
  } else {
    state.view = 'permission';
  }
  render();
}

async function boot() {
  await enterAppIfReady();
  // Re-check automatically when returning from the Android storage-access
  // settings screen, so the user doesn't have to tap "確認" manually.
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && state.view === 'permission') {
      enterAppIfReady();
    }
  });
}

boot();
