import { registerPlugin } from '@capacitor/core';

// Native git backend (JGit, running in the Android process) — see
// android/app/src/main/java/com/gitclient/app/GitNativePlugin.java. Every
// repo is a real directory under the public Documents/GitClient/repos/<name>
// folder, so other apps (Files, editors) can see it directly.
const GitNative = registerPlugin('GitNative');

export async function checkStorageAccess() {
  const { granted } = await GitNative.checkStorageAccess();
  return granted;
}

export async function requestStorageAccess() {
  await GitNative.requestStorageAccess();
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

function identityFields() {
  const { name, email } = getIdentity();
  return { authorName: name, authorEmail: email };
}

export const REPOS_ROOT = '/repos';

export function repoDir(name) {
  return `${REPOS_ROOT}/${name}`;
}

// `dir` is always `/repos/<name>` (see repoDir above) — this strips that
// convention back down to the bare repo name the native plugin expects.
function nameFromDir(dir) {
  return dir.startsWith(`${REPOS_ROOT}/`) ? dir.slice(REPOS_ROOT.length + 1) : dir;
}

export async function listRepos() {
  const { names } = await GitNative.listRepos();
  return names;
}

export async function repoExists(name) {
  const { exists } = await GitNative.repoExists({ name });
  return exists;
}

export async function removeRepo(name) {
  await GitNative.removeRepo({ name });
}

export async function initRepo(name, { defaultBranch = 'main' } = {}) {
  await GitNative.initRepo({ name, defaultBranch, ...identityFields() });
}

export async function cloneRepo(name, url, { creds, depth, ref } = {}) {
  await GitNative.cloneRepo({
    name,
    url,
    depth: depth ?? 50,
    ref: ref || undefined,
    username: creds?.username,
    password: creds?.password,
    ...identityFields(),
  });
}

export async function getRemoteUrl(dir, remote = 'origin') {
  const { url } = await GitNative.getRemoteUrl({ name: nameFromDir(dir), remote });
  return url;
}

export async function setRemoteUrl(dir, url, remote = 'origin') {
  await GitNative.setRemoteUrl({ name: nameFromDir(dir), url, remote });
}

export async function getStatus(dir) {
  const { entries } = await GitNative.getStatus({ name: nameFromDir(dir) });
  return entries;
}

export async function stageFile(dir, filepath) {
  await GitNative.stageFile({ name: nameFromDir(dir), filepath });
}

export async function unstageFile(dir, filepath) {
  await GitNative.unstageFile({ name: nameFromDir(dir), filepath });
}

export async function stageAll(dir) {
  await GitNative.stageAll({ name: nameFromDir(dir) });
}

export async function unstageAll(dir) {
  await GitNative.unstageAll({ name: nameFromDir(dir) });
}

export async function commit(dir, message) {
  const { name, email } = getIdentity();
  if (!name || !email) {
    throw new Error('Set your name and email in Settings before committing.');
  }
  await GitNative.commit({ name: nameFromDir(dir), message, ...identityFields() });
}

export async function getLog(dir, { depth = 50, ref } = {}) {
  const { entries } = await GitNative.getLog({ name: nameFromDir(dir), depth, ref });
  return entries.map((e) => ({
    oid: e.oid,
    commit: {
      message: e.message,
      author: { name: e.authorName, timestamp: e.authorTimestamp },
    },
  }));
}

export async function currentBranch(dir) {
  const { branch } = await GitNative.currentBranch({ name: nameFromDir(dir) });
  return branch || null;
}

export async function listBranches(dir) {
  const { branches } = await GitNative.listBranches({ name: nameFromDir(dir) });
  return branches;
}

export async function listRemoteBranches(dir, remote = 'origin') {
  const { branches } = await GitNative.listRemoteBranches({ name: nameFromDir(dir), remote });
  return branches;
}

export async function createBranch(dir, name, { checkout = true } = {}) {
  await GitNative.createBranch({ name: nameFromDir(dir), branch: name, checkout });
}

export async function checkoutBranch(dir, ref) {
  await GitNative.checkoutBranch({ name: nameFromDir(dir), ref });
}

export async function fetchRepo(dir, { creds } = {}) {
  await GitNative.fetchRepo({ name: nameFromDir(dir), username: creds?.username, password: creds?.password });
}

export async function pullRepo(dir, { creds } = {}) {
  await GitNative.pullRepo({
    name: nameFromDir(dir),
    username: creds?.username,
    password: creds?.password,
    ...identityFields(),
  });
}

export async function pushRepo(dir, { creds, ref, remoteRef, force = false } = {}) {
  await GitNative.pushRepo({
    name: nameFromDir(dir),
    username: creds?.username,
    password: creds?.password,
    ref,
    remoteRef,
    force,
  });
}

export async function readFile(dir, filepath) {
  const { content } = await GitNative.readFile({ name: nameFromDir(dir), filepath });
  return content;
}

export async function writeFile(dir, filepath, content) {
  await GitNative.writeFile({ name: nameFromDir(dir), filepath, content });
}

export async function deleteFile(dir, filepath) {
  await GitNative.deleteFile({ name: nameFromDir(dir), filepath });
}

export async function listWorkingFiles(dir) {
  const { files } = await GitNative.listWorkingFiles({ name: nameFromDir(dir) });
  return files;
}
