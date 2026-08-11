import git from 'isomorphic-git';
import http from 'isomorphic-git/http/web';
import { fs, pfs, repoDir, ensureReposRoot, listFilesRecursive, rmrf } from './fs.js';

// isomorphic-git runs entirely in the browser/webview here — there is no
// server component. Browsers refuse cross-origin git-smart-http requests
// unless the remote sends CORS headers (most git hosts, incl. GitHub,
// don't), so a CORS-unwrapping proxy is required for clone/fetch/push
// against real remotes. Defaults to the public isomorphic-git demo proxy;
// users can point this at their own (see README) for reliability/privacy.
const DEFAULT_CORS_PROXY = 'https://cors.isomorphic-git.org';

export function getCorsProxy() {
  return localStorage.getItem('git-client:corsProxy') || DEFAULT_CORS_PROXY;
}

export function setCorsProxy(value) {
  if (value) localStorage.setItem('git-client:corsProxy', value);
  else localStorage.removeItem('git-client:corsProxy');
}

export function getIdentity() {
  return {
    name: localStorage.getItem('git-client:userName') || '',
    email: localStorage.getItem('git-client:userEmail') || '',
  };
}

export function setIdentity({ name, email }) {
  localStorage.setItem('git-client:userName', name || '');
  localStorage.setItem('git-client:userEmail', email || '');
}

// Credentials are kept per-repo in localStorage only when the user opts in
// via "remember" so a PAT isn't silently persisted. Stored client-side —
// same trust boundary as browser saved passwords.
function credKey(name) {
  return `git-client:cred:${name}`;
}

export function getSavedCredentials(name) {
  const raw = localStorage.getItem(credKey(name));
  return raw ? JSON.parse(raw) : null;
}

export function saveCredentials(name, { username, password }) {
  localStorage.setItem(credKey(name), JSON.stringify({ username, password }));
}

export function clearCredentials(name) {
  localStorage.removeItem(credKey(name));
}

function onAuthFor(creds) {
  if (!creds || !creds.username) return undefined;
  return () => ({ username: creds.username, password: creds.password });
}

export async function initRepo(name, { defaultBranch = 'main' } = {}) {
  await ensureReposRoot();
  const dir = repoDir(name);
  await pfs.mkdir(dir);
  await git.init({ fs, dir, defaultBranch });
  await applyIdentity(dir);
}

export async function cloneRepo(name, url, { creds, corsProxy, depth, ref } = {}) {
  await ensureReposRoot();
  const dir = repoDir(name);
  await pfs.mkdir(dir);
  try {
    await git.clone({
      fs,
      http,
      dir,
      url,
      corsProxy: corsProxy ?? getCorsProxy(),
      depth: depth ?? 50,
      singleBranch: false,
      ref: ref || undefined,
      onAuth: onAuthFor(creds),
    });
  } catch (err) {
    // Clean up the partial directory so a failed clone doesn't leave a
    // broken "repo" behind in the sidebar.
    await rmrf(dir).catch(() => {});
    throw err;
  }
  await applyIdentity(dir);
}

async function applyIdentity(dir) {
  const { name, email } = getIdentity();
  if (name) await git.setConfig({ fs, dir, path: 'user.name', value: name });
  if (email) await git.setConfig({ fs, dir, path: 'user.email', value: email });
}

export async function getRemoteUrl(dir, remote = 'origin') {
  try {
    const remotes = await git.listRemotes({ fs, dir });
    const found = remotes.find((r) => r.remote === remote);
    return found ? found.url : '';
  } catch {
    return '';
  }
}

export async function setRemoteUrl(dir, url, remote = 'origin') {
  const remotes = await git.listRemotes({ fs, dir });
  if (remotes.find((r) => r.remote === remote)) {
    await git.deleteRemote({ fs, dir, remote });
  }
  await git.addRemote({ fs, dir, remote, url });
}

// See https://isomorphic-git.org/docs/en/statusMatrix — HEAD/WORKDIR/STAGE
// are each 0-3 codes, not booleans, so "staged" and "has further unstaged
// changes" have to be derived rather than read off directly.
function isStaged(head, stage) {
  return head === 1 ? stage !== 1 : stage !== 0;
}

function hasUnstagedChange(workdir, stage) {
  if (stage === 0) return workdir === 2;
  if (stage === 1) return workdir !== 1;
  if (stage === 2) return false;
  return true; // stage === 3
}

function describe(head, workdir, stage) {
  if (head === 0 && stage === 0) return 'untracked';
  if (workdir === 0) return isStaged(head, stage) ? 'deleted (staged)' : 'deleted';
  if (head === 0) return isStaged(head, stage) ? 'added (staged)' : 'added';
  return isStaged(head, stage) ? 'modified (staged)' : 'modified';
}

// Wraps git.statusMatrix into a friendlier shape for the UI.
export async function getStatus(dir) {
  const matrix = await git.statusMatrix({ fs, dir });
  return matrix
    .filter(([, head, workdir, stage]) => !(head === 1 && workdir === 1 && stage === 1))
    .map(([filepath, head, workdir, stage]) => ({
      filepath,
      head,
      workdir,
      stage,
      label: describe(head, workdir, stage),
      staged: isStaged(head, stage),
      hasUnstagedChange: hasUnstagedChange(workdir, stage),
    }));
}

export async function stageFile(dir, filepath) {
  await git.add({ fs, dir, filepath });
}

export async function unstageFile(dir, filepath) {
  await git.resetIndex({ fs, dir, filepath });
}

export async function stageAll(dir) {
  const status = await getStatus(dir);
  for (const entry of status) {
    if (entry.workdir === 0) {
      await git.remove({ fs, dir, filepath: entry.filepath });
    } else {
      await git.add({ fs, dir, filepath: entry.filepath });
    }
  }
}

export async function unstageAll(dir) {
  const status = await getStatus(dir);
  for (const entry of status) {
    await git.resetIndex({ fs, dir, filepath: entry.filepath });
  }
}

export async function commit(dir, message) {
  const { name, email } = getIdentity();
  if (!name || !email) {
    throw new Error('Set your name and email in Settings before committing.');
  }
  return git.commit({ fs, dir, message, author: { name, email } });
}

export async function getLog(dir, { depth = 50, ref } = {}) {
  try {
    return await git.log({ fs, dir, depth, ref });
  } catch {
    return [];
  }
}

export async function currentBranch(dir) {
  return (await git.currentBranch({ fs, dir, fullname: false })) || null;
}

export async function listBranches(dir) {
  return git.listBranches({ fs, dir });
}

export async function listRemoteBranches(dir, remote = 'origin') {
  try {
    return await git.listBranches({ fs, dir, remote });
  } catch {
    return [];
  }
}

export async function createBranch(dir, name, { checkout = true } = {}) {
  await git.branch({ fs, dir, ref: name, checkout });
}

export async function checkoutBranch(dir, ref) {
  await git.checkout({ fs, dir, ref });
}

export async function fetchRepo(dir, { creds, corsProxy } = {}) {
  return git.fetch({
    fs,
    http,
    dir,
    corsProxy: corsProxy ?? getCorsProxy(),
    onAuth: onAuthFor(creds),
  });
}

export async function pullRepo(dir, { creds, corsProxy, ref } = {}) {
  const { name, email } = getIdentity();
  return git.pull({
    fs,
    http,
    dir,
    ref,
    corsProxy: corsProxy ?? getCorsProxy(),
    onAuth: onAuthFor(creds),
    author: name && email ? { name, email } : undefined,
  });
}

export async function pushRepo(dir, { creds, corsProxy, ref, remoteRef, force = false } = {}) {
  return git.push({
    fs,
    http,
    dir,
    ref,
    remoteRef,
    force,
    corsProxy: corsProxy ?? getCorsProxy(),
    onAuth: onAuthFor(creds),
  });
}

export async function readFile(dir, filepath) {
  return pfs.readFile(`${dir}/${filepath}`, { encoding: 'utf8' });
}

export async function writeFile(dir, filepath, content) {
  const parts = filepath.split('/');
  let current = dir;
  for (let i = 0; i < parts.length - 1; i++) {
    current += `/${parts[i]}`;
    try {
      await pfs.mkdir(current);
    } catch (err) {
      if (err.code !== 'EEXIST') throw err;
    }
  }
  await pfs.writeFile(`${dir}/${filepath}`, content, 'utf8');
}

export async function deleteFile(dir, filepath) {
  await pfs.unlink(`${dir}/${filepath}`);
}

export async function listWorkingFiles(dir) {
  return listFilesRecursive(dir);
}
