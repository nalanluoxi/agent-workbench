package com.agentdesk.workbench.skills;

import com.agentdesk.workbench.config.WorkbenchProperties;
import com.agentdesk.workbench.core.ApiException;
import com.agentdesk.workbench.core.SecretVault;
import com.agentdesk.workbench.process.RestrictedProcess;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class SkillService {
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final int MAX_FILES = 500;
    private final JdbcTemplate db;
    private final WorkbenchProperties properties;
    private final SecretVault vault;
    private final ObjectMapper json;
    private final RestrictedProcess sandbox;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    public SkillService(JdbcTemplate db, WorkbenchProperties properties, SecretVault vault, ObjectMapper json, RestrictedProcess sandbox) {
        this.db = db; this.properties = properties; this.vault = vault; this.json = json; this.sandbox = sandbox;
    }

    public List<SkillView> list(String userId) {
        List<SkillView> result = new ArrayList<>();
        result.add(builtin(userId, "builtin-mysql", "mysql-operations", "MySQL 结构化操作"));
        result.add(builtin(userId, "builtin-redis", "redis-operations", "Redis 结构化操作"));
        result.addAll(db.query("SELECT id,name,description,source_type,enabled,version FROM skills WHERE user_id=? ORDER BY name",
                (rs, n) -> new SkillView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getInt(6), false), userId));
        return result;
    }

    public SkillDetail detail(String userId, String id) {
        SkillView view = list(userId).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow(() -> ApiException.notFound("技能不存在"));
        if (view.builtin()) return new SkillDetail(view, builtinContent(id), "");
        return db.query("SELECT content,source_ref FROM skills WHERE user_id=? AND id=?",
                rs -> { if (!rs.next()) throw ApiException.notFound("技能不存在"); return new SkillDetail(view, rs.getString(1), rs.getString(2)); }, userId, id);
    }

    public List<SkillSnapshot> snapshot(String userId) {
        return list(userId).stream().filter(SkillView::enabled).map(view -> {
            List<String> paths = scripts(userId, view.id());
            String hash = null;
            if (!paths.isEmpty()) try { hash = sandbox.fingerprint(packageDir(userId, view.id())); }
            catch (IOException e) { throw new IllegalStateException("无法检查技能脚本", e); }
            return new SkillSnapshot(view.id(), view.name(), view.description(), view.version(), detail(userId, view.id()).content(), paths, hash);
        }).toList();
    }

    public SkillDetail installLocal(String userId, String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) throw ApiException.badRequest("请输入本地技能目录");
        Path source = Path.of(sourcePath).toAbsolutePath().normalize();
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) throw ApiException.badRequest("本地技能目录不存在");
        return install(userId, source, "LOCAL", source.toString());
    }

    public SkillDetail installZip(String userId, MultipartFile file, String subpath) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) throw ApiException.badRequest("ZIP 文件为空或超过 20 MB");
        Path temp = temporary();
        try (InputStream input = file.getInputStream()) { extract(input, temp); return install(userId, findSkill(temp, subpath), "ZIP", file.getOriginalFilename() == null ? "本地 ZIP" : file.getOriginalFilename()); }
        catch (IOException e) { throw new IllegalStateException("无法读取技能压缩包", e); }
        finally { removeTree(temp); }
    }

    public SkillDetail installGithub(String userId, GithubInput input) {
        GithubSource source = githubSource(input);
        Path temp = temporary();
        try {
            downloadGithub(source, temp);
            return install(userId, findSkill(temp, source.subpath()), "GITHUB", json.writeValueAsString(source));
        } catch (IOException e) { throw new IllegalStateException("无法读取 GitHub 技能", e); }
        finally { removeTree(temp); }
    }

    public String githubDownloadUrl(GithubInput input) {
        GithubSource source = githubSource(input);
        return githubDownloadUri(source).toString();
    }

    public SkillDetail refreshGithub(String userId, String id) {
        SkillDetail current = detail(userId, id);
        if (!current.view().source().equals("GITHUB")) throw ApiException.badRequest("只有 GitHub 来源支持重新拉取");
        Path temp = temporary();
        try {
            GithubSource source = json.readValue(current.sourceRef(), GithubSource.class);
            downloadGithub(source, temp);
            Path sourceDir = findSkill(temp, source.subpath());
            return replace(userId, id, sourceDir, readContent(sourceDir), null, null);
        } catch (IOException e) { throw new IllegalStateException("无法更新 GitHub 技能", e); }
        finally { removeTree(temp); }
    }

    public SkillDetail update(String userId, String id, UpdateInput input) {
        SkillDetail current = detail(userId, id);
        if (current.view().builtin()) throw ApiException.badRequest("内置技能由系统维护，可启用或停用");
        requireNoRunningScript(userId, id, false);
        if (input == null || input.content() == null || input.content().isBlank() || input.content().getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) throw ApiException.badRequest("技能内容不能为空且不能超过 1 MB");
        Path source = packageDir(userId, id);
        return replace(userId, id, source, input.content(), input.name(), input.description());
    }

    @Transactional
    public SkillDetail setEnabled(String userId, String id, boolean enabled) {
        SkillDetail current = detail(userId, id);
        if (!current.view().builtin()) requireNoRunningScript(userId, id, false);
        if (current.view().builtin()) db.update("INSERT INTO builtin_skill_settings(user_id,skill_id,enabled) VALUES(?,?,?) ON CONFLICT(user_id,skill_id) DO UPDATE SET enabled=excluded.enabled", userId, id, enabled ? 1 : 0);
        else db.update("UPDATE skills SET enabled=?,version=version+1,updated_at=? WHERE id=? AND user_id=?", enabled ? 1 : 0, System.currentTimeMillis(), id, userId);
        return detail(userId, id);
    }

    @Transactional
    public void delete(String userId, String id) {
        SkillDetail current = detail(userId, id);
        if (current.view().builtin()) throw ApiException.badRequest("内置技能不可卸载，可停用");
        requireNoRunningScript(userId, id, true);
        db.update("DELETE FROM skills WHERE id=? AND user_id=?", id, userId);
        removeTree(ownerRoot(userId).resolve(id));
    }

    public List<String> scripts(String userId, String id) {
        SkillDetail skill = detail(userId, id);
        if (skill.view().builtin()) return List.of();
        Path root = packageDir(userId, id);
        try (var walk = Files.walk(root)) {
            return walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
                    .map(root::relativize).map(Path::toString)
                    .filter(path -> path.endsWith(".py") || path.endsWith(".js") || path.endsWith(".mjs") || path.endsWith(".cjs"))
                    .sorted().limit(501).toList();
        } catch (IOException e) { throw new IllegalStateException("无法读取技能脚本", e); }
    }

    public Path executablePackage(String userId, String id) {
        SkillDetail skill = detail(userId, id);
        if (skill.view().builtin() || !skill.view().enabled()) throw ApiException.badRequest("技能不可执行或已停用");
        return packageDir(userId, id);
    }

    private void requireNoRunningScript(String userId, String id, boolean includePending) {
        String states = includePending ? "'PENDING','APPROVED','EXECUTING'" : "'APPROVED','EXECUTING'";
        int active = db.queryForObject("SELECT COUNT(*) FROM skill_script_actions WHERE user_id=? AND skill_id=? AND status IN (" + states + ")", Integer.class, userId, id);
        if (active > 0) throw ApiException.conflict(includePending ? "技能仍有待确认或执行中的脚本" : "技能脚本正在执行，暂不能修改");
    }

    private SkillDetail install(String userId, Path source, String sourceType, String sourceRef) {
        String content = readContent(source), id = UUID.randomUUID().toString();
        String name = metadata(content, "name", source.getFileName().toString());
        String description = metadata(content, "description", "用户安装的技能");
        validateMetadata(name, description);
        Path destination = ownerRoot(userId).resolve(id).resolve("v1");
        try {
            copyTree(source, destination);
            long now = System.currentTimeMillis();
            db.update("INSERT INTO skills(id,user_id,name,description,content,source_type,source_ref,package_dir,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    id, userId, name, description, content, sourceType, sourceRef, destination.toString(), now, now);
        } catch (RuntimeException e) { removeTree(destination.getParent()); throw e; }
        return detail(userId, id);
    }

    private SkillDetail replace(String userId, String id, Path source, String content, String name, String description) {
        SkillDetail current = detail(userId, id);
        requireNoRunningScript(userId, id, false);
        Path previous = packageDir(userId, id);
        String newName = name == null ? current.view().name() : name.trim();
        String newDescription = description == null ? current.view().description() : description.trim();
        validateMetadata(newName, newDescription);
        Path destination = ownerRoot(userId).resolve(id).resolve("v" + (current.view().version() + 1) + "-" + UUID.randomUUID());
        try {
            copyTree(source, destination);
            Files.writeString(destination.resolve("SKILL.md"), content, StandardCharsets.UTF_8);
        } catch (IOException e) { removeTree(destination); throw new IllegalStateException("无法保存技能内容", e); }
        catch (RuntimeException e) { removeTree(destination); throw e; }
        int changed;
        try {
            changed = db.update("UPDATE skills SET name=?,description=?,content=?,package_dir=?,version=version+1,updated_at=? WHERE user_id=? AND id=? AND version=?",
                    newName, newDescription, content, destination.toString(), System.currentTimeMillis(), userId, id, current.view().version());
        } catch (RuntimeException e) {
            removeTree(destination);
            throw e;
        }
        if (changed != 1) {
            removeTree(destination);
            throw ApiException.conflict("技能已被修改，请刷新后重试");
        }
        SkillDetail result = detail(userId, id);
        removeTreeQuietly(previous);
        return result;
    }

    private Path packageDir(String userId, String id) {
        String path = db.query("SELECT package_dir FROM skills WHERE user_id=? AND id=?", rs -> rs.next() ? rs.getString(1) : null, userId, id);
        if (path == null) throw ApiException.notFound("技能不存在");
        Path value = Path.of(path).toAbsolutePath().normalize();
        if (!value.startsWith(ownerRoot(userId).resolve(id))) throw new IllegalStateException("技能目录不合法");
        return value;
    }

    private SkillView builtin(String userId, String id, String name, String description) {
        Integer enabled = db.query("SELECT enabled FROM builtin_skill_settings WHERE user_id=? AND skill_id=?", rs -> rs.next() ? rs.getInt(1) : null, userId, id);
        return new SkillView(id, name, description, "BUILTIN", enabled == null || enabled == 1, 1, true);
    }
    private String builtinContent(String id) {
        String resource = id.equals("builtin-mysql") ? "skills/mysql-operations/技能说明.md" : "skills/redis-operations/技能说明.md";
        try (InputStream input = new ClassPathResource(resource).getInputStream()) { return new String(input.readAllBytes(), StandardCharsets.UTF_8); }
        catch (IOException e) { throw new IllegalStateException("内置技能不可用", e); }
    }
    private Path ownerRoot(String userId) { return Path.of(properties.dataDir()).toAbsolutePath().normalize().resolve("skills").resolve(vault.digest("skill-owner", userId)); }
    private Path temporary() {
        try { Path root = Path.of(properties.dataDir()).toAbsolutePath().normalize().resolve("skill-imports"); Files.createDirectories(root); return Files.createTempDirectory(root, "import-"); }
        catch (IOException e) { throw new IllegalStateException("无法准备技能安装目录", e); }
    }

    private String readContent(Path dir) {
        Path file = dir.resolve("SKILL.md");
        try {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1024 * 1024) throw ApiException.badRequest("技能目录必须包含不超过 1 MB 的 SKILL.md");
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.isBlank()) throw ApiException.badRequest("SKILL.md 内容为空");
            return content;
        } catch (IOException e) { throw new IllegalStateException("无法读取 SKILL.md", e); }
    }
    private String metadata(String content, String field, String fallback) {
        if (!content.startsWith("---")) return fallback;
        int end = content.indexOf("\n---", 3);
        if (end < 0) return fallback;
        Matcher match = Pattern.compile("(?m)^" + field + ":\\s*([^\\r\\n|>]+)$").matcher(content.substring(3, end));
        return match.find() ? match.group(1).trim().replaceAll("^[\"']|[\"']$", "") : fallback;
    }
    private void validateMetadata(String name, String description) {
        if (name == null || name.isBlank() || name.length() > 128 || description == null || description.length() > 500) throw ApiException.badRequest("技能名称或描述无效");
    }

    private void copyTree(Path source, Path destination) {
        try (var paths = Files.walk(source)) {
            long total = 0; int count = 0;
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (++count > MAX_FILES) throw ApiException.badRequest("技能包文件过多");
                if (Files.isSymbolicLink(path)) throw ApiException.badRequest("技能包不能包含符号链接");
                Path target = destination.resolve(source.relativize(path).toString()).normalize();
                if (!target.startsWith(destination)) throw ApiException.badRequest("技能包路径越界");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(target);
                else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    total += Files.size(path);
                    if (total > MAX_BYTES) throw ApiException.badRequest("技能包超过 20 MB");
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                } else throw ApiException.badRequest("技能包包含不支持的文件类型");
            }
        } catch (IOException e) { throw new IllegalStateException("无法复制技能包", e); }
    }
    private void extract(InputStream input, Path target) throws IOException {
        long total = 0; int count = 0;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                Path dest = target.resolve(name).normalize();
                if (name.startsWith("/") || !dest.startsWith(target) || name.contains("\0")) throw ApiException.badRequest("压缩包包含越界路径");
                if (++count > MAX_FILES) throw ApiException.badRequest("压缩包文件过多");
                if (entry.isDirectory()) { Files.createDirectories(dest); continue; }
                Files.createDirectories(dest.getParent());
                try (var output = Files.newOutputStream(dest, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                    int n; while ((n = zip.read(buffer)) != -1) { total += n; if (total > MAX_BYTES) throw ApiException.badRequest("解压后内容超过 20 MB"); output.write(buffer, 0, n); }
                }
            }
        }
    }
    private Path findSkill(Path root, String subpath) {
        try {
            Path candidate = root;
            List<Path> top; try (var items = Files.list(root)) { top = items.toList(); }
            if (top.size() == 1 && Files.isDirectory(top.get(0))) candidate = top.get(0);
            if (subpath != null && !subpath.isBlank()) {
                Path relative = relativeSubpath(subpath);
                Path resolved = candidate.resolve(relative).normalize();
                if (!resolved.startsWith(candidate)) throw ApiException.badRequest("技能子目录越界");
                candidate = resolved;
            }
            if (Files.isRegularFile(candidate.resolve("SKILL.md"), LinkOption.NOFOLLOW_LINKS)) return candidate;
            try (var walk = Files.walk(candidate, 8)) {
                List<Path> matches = walk.filter(path -> path.getFileName().toString().equals("SKILL.md") && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList();
                if (matches.size() != 1) throw ApiException.badRequest("请提供包含唯一 SKILL.md 的技能目录，或指定子目录");
                return matches.get(0).getParent();
            }
        } catch (IOException e) { throw new IllegalStateException("无法定位技能目录", e); }
    }
    private GithubSource githubSource(GithubInput input) {
        if (input == null || input.url() == null) throw ApiException.badRequest("请输入 GitHub 仓库地址");
        Matcher match = Pattern.compile("^https://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/?$").matcher(input.url().trim());
        if (!match.matches()) throw ApiException.badRequest("请使用 https://github.com/所有者/仓库 格式");
        String owner = match.group(1), repo = match.group(2).replaceFirst("\\.git$", ""), branch = input.branch() == null || input.branch().isBlank() ? "main" : input.branch().trim();
        if (owner.length() > 100 || repo.isBlank() || repo.length() > 100 || !validBranch(branch)) throw ApiException.badRequest("仓库或分支名称无效");
        String subpath = input.subpath() == null ? "" : input.subpath().trim();
        if (!subpath.isBlank()) relativeSubpath(subpath);
        return new GithubSource(owner, repo, branch, subpath);
    }
    private void downloadGithub(GithubSource source, Path target) {
        try {
            URI uri = githubDownloadUri(source);
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(40)).header("User-Agent", "agent-workbench").GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) throw ApiException.badRequest("GitHub 下载失败，请检查仓库和分支（HTTP " + response.statusCode() + "）");
                long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (declared > MAX_BYTES) throw ApiException.badRequest("GitHub 压缩包超过 20 MB");
                extract(body, target);
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("GitHub 下载已中断", e); }
        catch (IOException e) { throw new IllegalStateException("无法连接 GitHub", e); }
    }
    private void removeTree(Path path) {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(path)) { for (Path item : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(item); }
        catch (IOException e) { throw new IllegalStateException("无法清理技能文件", e); }
    }
    private void removeTreeQuietly(Path path) {
        try { removeTree(path); }
        catch (RuntimeException ignored) { /* 旧版本目录仅作为清理对象，不能让已完成的版本切换变成失败。 */ }
    }
    private Path relativeSubpath(String value) {
        try {
            Path relative = Path.of(value).normalize();
            if (relative.isAbsolute() || relative.toString().isBlank() || relative.startsWith("..")) throw ApiException.badRequest("技能子目录无效");
            return relative;
        } catch (java.nio.file.InvalidPathException e) { throw ApiException.badRequest("技能子目录无效"); }
    }
    private boolean validBranch(String branch) {
        if (branch.length() > 200 || !branch.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*") || branch.contains("..") || branch.endsWith(".")) return false;
        for (String part : branch.split("/")) if (part.equals(".") || part.equals("..") || part.endsWith(".lock")) return false;
        return true;
    }
    private URI githubDownloadUri(GithubSource source) {
        return URI.create("https://codeload.github.com/" + source.owner() + "/" + source.repo() + "/zip/refs/heads/" + source.branch());
    }

    public record SkillView(String id, String name, String description, String source, boolean enabled, int version, boolean builtin) {}
    public record SkillDetail(SkillView view, String content, String sourceRef) {}
    public record SkillSnapshot(String id, String name, String description, int version, String content, List<String> scripts, String packageHash) {}
    public record UpdateInput(String name, String description, String content) {}
    public record GithubInput(String url, String branch, String subpath) {}
    public record GithubSource(String owner, String repo, String branch, String subpath) {}
}
