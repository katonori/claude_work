import LightningFS from '@isomorphic-git/lightning-fs';

// A single IndexedDB-backed filesystem shared by every repo. Repos live
// side by side under /repos/<name> so the whole app works fully offline
// and persists across reloads without any native/server storage.
const fs = new LightningFS('git-client-fs');

export const pfs = fs.promises;
export { fs };

export const REPOS_ROOT = '/repos';

export async function ensureReposRoot() {
  try {
    await pfs.mkdir(REPOS_ROOT);
  } catch (err) {
    if (err.code !== 'EEXIST') throw err;
  }
}

export function repoDir(name) {
  return `${REPOS_ROOT}/${name}`;
}

export async function listRepos() {
  await ensureReposRoot();
  const entries = await pfs.readdir(REPOS_ROOT);
  return entries.sort();
}

export async function repoExists(name) {
  try {
    await pfs.stat(repoDir(name));
    return true;
  } catch {
    return false;
  }
}

// Recursively remove a repo directory. LightningFS has no rm -rf, so we
// walk the tree ourselves.
export async function removeRepo(name) {
  await rmrf(repoDir(name));
}

export async function rmrf(path) {
  let stat;
  try {
    stat = await pfs.stat(path);
  } catch {
    return;
  }
  if (stat.isDirectory()) {
    const entries = await pfs.readdir(path);
    for (const entry of entries) {
      await rmrf(`${path}/${entry}`);
    }
    await pfs.rmdir(path);
  } else {
    await pfs.unlink(path);
  }
}

// List files recursively under a repo dir (excluding .git), used by the
// Files tab and the "stage all" helper.
export async function listFilesRecursive(dir, base = '') {
  const full = base ? `${dir}/${base}` : dir;
  const entries = await pfs.readdir(full);
  let results = [];
  for (const entry of entries) {
    if (base === '' && entry === '.git') continue;
    const relPath = base ? `${base}/${entry}` : entry;
    const stat = await pfs.stat(`${dir}/${relPath}`);
    if (stat.isDirectory()) {
      results = results.concat(await listFilesRecursive(dir, relPath));
    } else {
      results.push(relPath);
    }
  }
  return results;
}
