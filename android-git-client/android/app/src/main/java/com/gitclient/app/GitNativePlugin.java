package com.gitclient.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand.ListMode;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Native git backend for the app: every repo is a real JGit repository
// under the public Documents/GitClient/repos/<name> folder, so other apps
// (Files, editors) can see the working tree directly. Replaces the old
// isomorphic-git + virtual-filesystem approach.
@CapacitorPlugin(name = "GitNative")
public class GitNativePlugin extends Plugin {

    private static final int STORAGE_PERMISSION_REQUEST = 9001;

    // ---------- storage access ----------

    @PluginMethod
    public void checkStorageAccess(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", hasStorageAccess());
        call.resolve(ret);
    }

    @PluginMethod
    public void requestStorageAccess(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getContext().getPackageName()));
                getActivity().startActivity(intent);
            } catch (Exception e) {
                getActivity().startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            ActivityCompat.requestPermissions(
                getActivity(),
                new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE },
                STORAGE_PERMISSION_REQUEST
            );
        }
        call.resolve();
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED;
    }

    // ---------- path helpers ----------

    private File rootDir() {
        File docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        return new File(docs, "GitClient");
    }

    private File reposDir() {
        return new File(rootDir(), "repos");
    }

    private File repoDir(String name) throws IOException {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("..")) {
            throw new IOException("invalid repo name");
        }
        return new File(reposDir(), name);
    }

    // Resolves a working-tree-relative path, rejecting anything that would
    // escape the repo directory.
    private File resolveInRepo(File repo, String filepath) throws IOException {
        File target = new File(repo, filepath);
        String repoCanon = repo.getCanonicalPath();
        String targetCanon = target.getCanonicalPath();
        if (!targetCanon.equals(repoCanon) && !targetCanon.startsWith(repoCanon + File.separator)) {
            throw new IOException("invalid path");
        }
        return target;
    }

    private UsernamePasswordCredentialsProvider credsFrom(PluginCall call) {
        String username = call.getString("username");
        String password = call.getString("password");
        if (username == null || username.isEmpty()) return null;
        return new UsernamePasswordCredentialsProvider(username, password == null ? "" : password);
    }

    private void applyIdentity(Repository repo, String name, String email) throws IOException {
        StoredConfig config = repo.getConfig();
        if (name != null && !name.isEmpty()) config.setString("user", null, "name", name);
        if (email != null && !email.isEmpty()) config.setString("user", null, "email", email);
        config.save();
    }

    // ---------- repo list / lifecycle ----------

    @PluginMethod
    public void listRepos(PluginCall call) {
        try {
            File dir = reposDir();
            JSArray names = new JSArray();
            File[] entries = dir.listFiles();
            if (entries != null) {
                List<String> sorted = new ArrayList<>();
                for (File f : entries) if (f.isDirectory()) sorted.add(f.getName());
                sorted.sort(String::compareTo);
                for (String n : sorted) names.put(n);
            }
            JSObject ret = new JSObject();
            ret.put("names", names);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void repoExists(PluginCall call) {
        try {
            File dir = repoDir(call.getString("name"));
            JSObject ret = new JSObject();
            ret.put("exists", dir.isDirectory());
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void removeRepo(PluginCall call) {
        try {
            deleteRecursive(repoDir(call.getString("name")));
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    @PluginMethod
    public void initRepo(PluginCall call) {
        try {
            File dir = repoDir(call.getString("name"));
            dir.mkdirs();
            String defaultBranch = call.getString("defaultBranch", "main");
            try (Git git = Git.init().setDirectory(dir).setInitialBranch(defaultBranch).call()) {
                applyIdentity(git.getRepository(), call.getString("authorName"), call.getString("authorEmail"));
            }
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void cloneRepo(PluginCall call) {
        File dir = null;
        try {
            dir = repoDir(call.getString("name"));
            String url = call.getString("url");
            Integer depth = call.getInt("depth");
            String ref = call.getString("ref");

            org.eclipse.jgit.api.CloneCommand cmd = Git.cloneRepository()
                .setURI(url)
                .setDirectory(dir)
                .setCloneAllBranches(ref == null || ref.isEmpty());
            if (depth != null && depth > 0) cmd.setDepth(depth);
            if (ref != null && !ref.isEmpty()) cmd.setBranch(ref);
            UsernamePasswordCredentialsProvider creds = credsFrom(call);
            if (creds != null) cmd.setCredentialsProvider(creds);

            try (Git git = cmd.call()) {
                applyIdentity(git.getRepository(), call.getString("authorName"), call.getString("authorEmail"));
            }
            call.resolve();
        } catch (Exception e) {
            if (dir != null) deleteRecursive(dir);
            call.reject(e.getMessage(), e);
        }
    }

    // ---------- remote config ----------

    @PluginMethod
    public void getRemoteUrl(PluginCall call) {
        try (Git git = Git.open(repoDir(call.getString("name")))) {
            String remote = call.getString("remote", "origin");
            String url = git.getRepository().getConfig().getString("remote", remote, "url");
            JSObject ret = new JSObject();
            ret.put("url", url == null ? "" : url);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void setRemoteUrl(PluginCall call) {
        try (Git git = Git.open(repoDir(call.getString("name")))) {
            String remote = call.getString("remote", "origin");
            String url = call.getString("url");
            try {
                git.remoteRemove().setRemoteName(remote).call();
            } catch (Exception ignored) {
                // remote didn't exist yet
            }
            git.remoteAdd().setName(remote).setUri(new URIish(url)).call();
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    // ---------- status / staging ----------

    @PluginMethod
    public void getStatus(PluginCall call) {
        try (Git git = Git.open(repoDir(call.getString("name")))) {
            Status status = git.status().call();
            Set<String> allPaths = new LinkedHashSet<>();
            allPaths.addAll(status.getAdded());
            allPaths.addAll(status.getChanged());
            allPaths.addAll(status.getRemoved());
            allPaths.addAll(status.getMissing());
            allPaths.addAll(status.getModified());
            allPaths.addAll(status.getUntracked());
            allPaths.addAll(status.getConflicting());

            JSArray entries = new JSArray();
            for (String path : allPaths) {
                String label;
                boolean staged;
                if (status.getConflicting().contains(path)) {
                    label = "conflict";
                    staged = false;
                } else if (status.getAdded().contains(path)) {
                    label = "added (staged)";
                    staged = true;
                } else if (status.getChanged().contains(path)) {
                    label = "modified (staged)";
                    staged = true;
                } else if (status.getRemoved().contains(path)) {
                    label = "deleted (staged)";
                    staged = true;
                } else if (status.getMissing().contains(path)) {
                    label = "deleted";
                    staged = false;
                } else if (status.getModified().contains(path)) {
                    label = "modified";
                    staged = false;
                } else {
                    label = "untracked";
                    staged = false;
                }
                JSObject entry = new JSObject();
                entry.put("filepath", path);
                entry.put("label", label);
                entry.put("staged", staged);
                entries.put(entry);
            }
            JSObject ret = new JSObject();
            ret.put("entries", entries);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void stageFile(PluginCall call) {
        try (Git git = Git.open(repoDir(call.getString("name")))) {
            String path = call.getString("filepath");
            Status status = git.status().call();
            if (status.getMissing().contains(path)) {
                git.rm().addFilepattern(path).call();
            } else {
                git.add().addFilepattern(path).call();
            }
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void unstageFile(PluginCall call) {
        try (Git git = Git.open(repoDir(call.getString("name")))) {
            git.reset().addPath(call.getString("filepath")).call();
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void stageAll(PluginCall call) {
        try (Git git = Git.open(repoDir(call.getString("name")))) {
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void unstageAll(PluginCall call) {
        try (Git git = Git.open(repoDir(call.getString("name")))) {
            git.reset().call();
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    // ---------- commit / log ----------

    @PluginMethod
    public void commit(PluginCall call) {
        try (Git git = Git.open(repoDir(call.getString("name")))) {
            String authorName = call.getString("authorName");
            String authorEmail = call.getString("authorEmail");
            PersonIdent author = new PersonIdent(authorName, authorEmail);
            git.commit()
                .setMessage(call.getString("message"))
                .setAuthor(author)
                .setCommitter(author)
                .call();
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void getLog(PluginCall call) {
        try (Git git = Git.open(repoDir(call.getString("name")))) {
            org.eclipse.jgit.api.LogCommand cmd = git.log();
            Integer depth = call.getInt("depth");
            if (depth != null && depth > 0) cmd.setMaxCount(depth);
            String ref = call.getString("ref");
            if (ref != null && !ref.isEmpty()) {
                cmd.add(git.getRepository().resolve(ref));
            }
            JSArray entries = new JSArray();
            for (RevCommit commit : cmd.call()) {
                PersonIdent author = commit.getAuthorIdent();
                JSObject entry = new JSObject();
                entry.put("oid", commit.getName());
                entry.put("message", commit.getFullMessage());
                entry.put("authorName", author.getName());
                entry.put("authorTimestamp", author.getWhen().getTime() / 1000);
                entries.put(entry);
            }
            JSObject ret = new JSObject();
            ret.put("entries", entries);
            call.resolve(ret);
        } catch (Exception e) {
            // No commits yet / unresolvable ref: same "empty history" contract as before.
            JSObject ret = new JSObject();
            ret.put("entries", new JSArray());
            call.resolve(ret);
        }
    }

    // ---------- branches ----------

    @PluginMethod
    public void currentBranch(PluginCall call) {
        try (Git git = Git.open(repoDir(call.getString("name")))) {
            Repository repo = git.getRepository();
            String full = repo.getFullBranch();
            JSObject ret = new JSObject();
            ret.put("branch", full != null && full.startsWith("refs/heads/") ? repo.getBranch() : JSObject.NULL);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void listBranches(PluginCall call) {
        try (Git git = Git.open(repoDir(call.getString("name")))) {
            JSArray branches = new JSArray();
            for (Ref ref : git.branchList().call()) {
                branches.put(shortenRef(ref.getName(), "refs/heads/"));
            }
            JSObject ret = new JSObject();
            ret.put("branches", branches);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void listRemoteBranches(PluginCall call) {
        try (Git git = Git.open(repoDir(call.getString("name")))) {
            String remote = call.getString("remote", "origin");
            String prefix = "refs/remotes/" + remote + "/";
            JSArray branches = new JSArray();
            for (Ref ref : git.branchList().setListMode(ListMode.REMOTE).call()) {
                if (ref.getName().startsWith(prefix) && !ref.getName().endsWith("/HEAD")) {
                    branches.put(ref.getName().substring(prefix.length()));
                }
            }
            JSObject ret = new JSObject();
            ret.put("branches", branches);
            call.resolve(ret);
        } catch (Exception e) {
            JSObject ret = new JSObject();
            ret.put("branches", new JSArray());
            call.resolve(ret);
        }
    }

    private String shortenRef(String name, String prefix) {
        return name.startsWith(prefix) ? name.substring(prefix.length()) : name;
    }

    @PluginMethod
    public void createBranch(PluginCall call) {
        try (Git git = Git.open(repoDir(call.getString("name")))) {
            String branch = call.getString("branch");
            git.branchCreate().setName(branch).call();
            if (Boolean.TRUE.equals(call.getBoolean("checkout", true))) {
                git.checkout().setName(branch).call();
            }
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void checkoutBranch(PluginCall call) {
        try (Git git = Git.open(repoDir(call.getString("name")))) {
            String ref = call.getString("ref");
            try {
                git.checkout().setName(ref).call();
            } catch (RefNotFoundException notFound) {
                // Not a local branch — treat ref as "<remote>/<branch>" (as
                // produced by the remote-branch dropdown). If a local branch
                // by that short name already exists (e.g. picked before),
                // just switch to it; otherwise create one tracking the
                // remote branch, same as `git checkout <remote-branch>`.
                int slash = ref.indexOf('/');
                String localName = slash >= 0 ? ref.substring(slash + 1) : ref;
                boolean localExists = false;
                for (Ref r : git.branchList().call()) {
                    if (shortenRef(r.getName(), "refs/heads/").equals(localName)) {
                        localExists = true;
                        break;
                    }
                }
                if (localExists) {
                    git.checkout().setName(localName).call();
                } else {
                    git.checkout()
                        .setCreateBranch(true)
                        .setName(localName)
                        .setStartPoint(ref)
                        .setUpstreamMode(SetupUpstreamMode.TRACK)
                        .call();
                }
            }
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    // ---------- remote operations ----------

    @PluginMethod
    public void fetchRepo(PluginCall call) {
        try (Git git = Git.open(repoDir(call.getString("name")))) {
            org.eclipse.jgit.api.FetchCommand cmd = git.fetch();
            UsernamePasswordCredentialsProvider creds = credsFrom(call);
            if (creds != null) cmd.setCredentialsProvider(creds);
            cmd.call();
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void pullRepo(PluginCall call) {
        try (Git git = Git.open(repoDir(call.getString("name")))) {
            applyIdentity(git.getRepository(), call.getString("authorName"), call.getString("authorEmail"));
            org.eclipse.jgit.api.PullCommand cmd = git.pull();
            UsernamePasswordCredentialsProvider creds = credsFrom(call);
            if (creds != null) cmd.setCredentialsProvider(creds);
            cmd.call();
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void pushRepo(PluginCall call) {
        try (Git git = Git.open(repoDir(call.getString("name")))) {
            org.eclipse.jgit.api.PushCommand cmd = git.push();
            UsernamePasswordCredentialsProvider creds = credsFrom(call);
            if (creds != null) cmd.setCredentialsProvider(creds);
            cmd.setForce(Boolean.TRUE.equals(call.getBoolean("force", false)));
            String ref = call.getString("ref");
            String remoteRef = call.getString("remoteRef");
            if (ref != null && !ref.isEmpty()) {
                String dest = (remoteRef != null && !remoteRef.isEmpty()) ? remoteRef : ref;
                cmd.setRefSpecs(new RefSpec(ref + ":" + dest));
            }
            cmd.call();
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    // ---------- working tree files ----------

    @PluginMethod
    public void readFile(PluginCall call) {
        try {
            File repo = repoDir(call.getString("name"));
            File file = resolveInRepo(repo, call.getString("filepath"));
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSObject ret = new JSObject();
            ret.put("content", content);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void writeFile(PluginCall call) {
        try {
            File repo = repoDir(call.getString("name"));
            File file = resolveInRepo(repo, call.getString("filepath"));
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            Files.write(file.toPath(), call.getString("content", "").getBytes(StandardCharsets.UTF_8));
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void deleteFile(PluginCall call) {
        try {
            File repo = repoDir(call.getString("name"));
            File file = resolveInRepo(repo, call.getString("filepath"));
            file.delete();
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void listWorkingFiles(PluginCall call) {
        try {
            File repo = repoDir(call.getString("name"));
            List<String> files = new ArrayList<>();
            walk(repo, repo, files);
            JSArray arr = new JSArray();
            for (String f : files) arr.put(f);
            JSObject ret = new JSObject();
            ret.put("files", arr);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    private void walk(File root, File dir, List<String> out) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File f : entries) {
            if (dir.equals(root) && f.getName().equals(".git")) continue;
            if (f.isDirectory()) {
                walk(root, f, out);
            } else {
                String rel = root.toURI().relativize(f.toURI()).getPath();
                out.add(rel);
            }
        }
    }
}
