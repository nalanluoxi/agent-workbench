package com.agentdesk.workbench.process;

import com.agentdesk.workbench.config.WorkbenchProperties;
import com.agentdesk.workbench.core.ApiException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/** Runs user-selected local code without network, host writes, child processes or platform credentials. */
@Component
public class RestrictedProcess {
    private static final long MAX_BYTES = 50L * 1024 * 1024;
    private static final int MAX_FILES = 2500;
    private final WorkbenchProperties properties;

    public RestrictedProcess(WorkbenchProperties properties) { this.properties = properties; }

    public Plan mcp(String command, List<String> arguments) {
        if (command == null || arguments == null || arguments.isEmpty()) throw ApiException.badRequest("STDIO 需要 Python 或 Node 和脚本路径");
        String runtime = Path.of(command).getFileName().toString();
        if (!command.equals(runtime) || !(runtime.equals("node") || runtime.equals("python3"))) throw ApiException.badRequest("STDIO 当前只支持命令 node 或 python3 直接运行脚本，不支持 npx、uvx 或 shell 命令");
        int index = 0;
        if (runtime.equals("python3") && arguments.get(0).equals("-u")) index++;
        if (index >= arguments.size() || arguments.get(index).startsWith("-")) throw ApiException.badRequest("请提供本地 MCP 脚本路径，不支持动态代码参数");
        Path script = safeFile(Path.of(arguments.get(index)));
        String extension = extension(script);
        if ((runtime.equals("node") && !List.of("js", "mjs", "cjs").contains(extension)) || (runtime.equals("python3") && !extension.equals("py"))) throw ApiException.badRequest("脚本类型与运行时不匹配");
        List<String> parameters = List.copyOf(arguments.subList(index + 1, arguments.size()));
        return plan(runtime, script.getParent(), script, parameters);
    }

    public Plan skill(Path root, String relativeScript, List<String> arguments) {
        if (relativeScript == null || relativeScript.isBlank()) throw ApiException.badRequest("请选择技能脚本");
        Path relative = Path.of(relativeScript);
        if (relative.isAbsolute()) throw ApiException.badRequest("脚本路径必须相对于技能目录");
        try {
            Path base = root.toRealPath();
            Path target = base.resolve(relative).normalize();
            if (!target.startsWith(base)) throw ApiException.badRequest("脚本路径越界");
            Path script = safeFile(target);
            if (!script.startsWith(base)) throw ApiException.badRequest("脚本路径越界");
            String runtime = switch (extension(script)) {
                case "py" -> "python3";
                case "js", "mjs", "cjs" -> "node";
                default -> throw ApiException.badRequest("只支持 Python 或 Node 脚本");
            };
            return plan(runtime, base, script, arguments == null ? List.of() : arguments);
        } catch (IOException e) { throw ApiException.badRequest("技能目录不存在或不可访问"); }
    }

    public ProcessBuilder process(Plan plan) {
        requireSandbox();
        try {
            String current = fingerprint(plan.root());
            if (!MessageDigest.isEqual(current.getBytes(StandardCharsets.US_ASCII), plan.fingerprint().getBytes(StandardCharsets.US_ASCII))) throw ApiException.conflict("脚本或依赖文件已修改，请重新发起操作");
            List<String> command = new ArrayList<>();
            command.add("/usr/bin/sandbox-exec"); command.add("-p"); command.add(profile(plan)); command.add(plan.runtime().toString());
            if (plan.runtimeName().equals("python3")) command.add("-u");
            command.add(plan.script().toString()); command.addAll(plan.arguments());
            ProcessBuilder builder = new ProcessBuilder(command).directory(plan.root().toFile());
            builder.environment().clear();
            builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
            builder.environment().put("PYTHONNOUSERSITE", "1");
            builder.environment().put("LANG", "en_US.UTF-8");
            return builder;
        } catch (IOException e) { throw new IllegalStateException("无法准备受限进程", e); }
    }

    public String fingerprint(Path root) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        List<Path> paths;
        try (var stream = Files.walk(root)) { paths = stream.limit(MAX_FILES + 1L).toList(); }
        if (paths.size() > MAX_FILES) throw ApiException.badRequest("程序目录文件过多，请使用独立目录");
        paths = paths.stream().sorted(Comparator.comparing(path -> root.relativize(path).toString())).toList();
        long total = 0;
        for (Path path : paths) {
            if (Files.isSymbolicLink(path)) throw ApiException.badRequest("程序目录不能包含符号链接");
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw ApiException.badRequest("程序目录包含不支持的文件类型");
            Object links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            if (links instanceof Number count && count.longValue() > 1) throw ApiException.badRequest("程序目录不能包含硬链接");
            long size = Files.size(path); total += size;
            if (total > MAX_BYTES) throw ApiException.badRequest("程序目录超过 50 MB");
            byte[] name = root.relativize(path).toString().getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).putInt(name.length).array()); digest.update(name);
            digest.update(ByteBuffer.allocate(8).putLong(size).array());
            try (var in = Files.newInputStream(path)) {
                byte[] buf = new byte[8192]; int count; long read = 0;
                while ((count = in.read(buf)) != -1) { read += count; if (read > size) throw ApiException.conflict("程序文件在校验时发生变化"); digest.update(buf, 0, count); }
                if (read != size) throw ApiException.conflict("程序文件在校验时发生变化");
            }
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
    }

    private Plan plan(String runtimeName, Path root, Path script, List<String> arguments) {
        if (arguments.size() > 100 || arguments.stream().anyMatch(value -> value == null || value.length() > 4096 || value.indexOf('\0') >= 0)) throw ApiException.badRequest("脚本参数无效");
        try {
            Path directory = root.toRealPath(), executable = resolveRuntime(runtimeName);
            rejectSensitiveRoot(directory);
            return new Plan(runtimeName, executable, directory, script, List.copyOf(arguments), fingerprint(directory));
        } catch (IOException e) { throw new IllegalStateException("无法检查程序目录或运行时", e); }
    }

    private void rejectSensitiveRoot(Path root) throws IOException {
        Path home = Path.of(System.getProperty("user.home")).toRealPath();
        Path data = realOrAbsolute(Path.of(properties.dataDir()));
        Path key = realOrAbsolute(Path.of(properties.keyFile()));
        Path token = home.resolve(".agent-workbench");
        if (root.getParent() == null || root.equals(home) || home.startsWith(root) || root.equals(data) || data.startsWith(root)
                || key.startsWith(root) || token.startsWith(root)) throw ApiException.badRequest("程序目录范围过大或包含工作台敏感数据");
        String custom = System.getenv("WORKBENCH_CLI_HOME");
        if (custom != null && !custom.isBlank() && realOrAbsolute(Path.of(custom)).startsWith(root)) throw ApiException.badRequest("程序目录包含 CLI 登录凭据");
    }

    private Path resolveRuntime(String name) throws IOException {
        String path = System.getenv("PATH");
        if (path != null) for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) continue;
            Path candidate = Path.of(entry, name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                Path resolved = candidate.toRealPath();
                if (name.equals("python3") && resolved.getParent() != null && resolved.getParent().getParent() != null) {
                    Path framework = resolved.getParent().getParent().resolve("Resources/Python.app/Contents/MacOS/Python");
                    if (Files.isRegularFile(framework) && Files.isExecutable(framework)) return framework.toRealPath();
                }
                return resolved;
            }
        }
        throw ApiException.badRequest("未找到本机 " + name + " 运行时");
    }
    private Path safeFile(Path path) {
        try {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw ApiException.badRequest("脚本不存在或是符号链接");
            return path.toRealPath();
        } catch (IOException e) { throw ApiException.badRequest("脚本不存在或不可访问"); }
    }
    private String extension(Path path) { String name = path.getFileName().toString(); int dot = name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT); }
    private Path realOrAbsolute(Path path) throws IOException { Path absolute = path.toAbsolutePath().normalize(); return Files.exists(absolute) ? absolute.toRealPath() : absolute; }
    private void requireSandbox() { if (!"Mac OS X".equals(System.getProperty("os.name")) || !Files.isExecutable(Path.of("/usr/bin/sandbox-exec"))) throw new IllegalStateException("当前系统缺少已验证的受限进程环境"); }

    private String profile(Plan plan) throws IOException {
        List<Path> blocked = new ArrayList<>(List.of(Path.of("/Users"), Path.of("/Volumes"), Path.of("/private/tmp"), Path.of("/private/var/folders"), Path.of(System.getProperty("user.home")), realOrAbsolute(Path.of(properties.dataDir())), realOrAbsolute(Path.of(properties.keyFile()))));
        String cliHome = System.getenv("WORKBENCH_CLI_HOME");
        if (cliHome != null && !cliHome.isBlank()) blocked.add(realOrAbsolute(Path.of(cliHome)));
        StringBuilder value = new StringBuilder("(version 1)(allow default)(deny file-read-data");
        for (Path path : blocked) value.append(" (subpath ").append(quote(path)).append(")");
        value.append(")(allow file-read-data (subpath ").append(quote(plan.root())).append(") (literal ").append(quote(plan.runtime())).append("))");
        value.append("(deny file-write*)(deny network-inbound)(deny network-outbound)(deny process-fork)(deny process-exec)(allow process-exec (literal ").append(quote(plan.runtime())).append("))");
        return value.toString();
    }
    private String quote(Path path) { String text = path.toString(); if (text.chars().anyMatch(Character::isISOControl)) throw ApiException.badRequest("路径包含不支持的字符"); return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    public record Plan(String runtimeName, Path runtime, Path root, Path script, List<String> arguments, String fingerprint) {}
}
