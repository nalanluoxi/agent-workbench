package com.agentdesk.workbench.agent;

import com.agentdesk.workbench.config.WorkbenchProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Restricts the built-in database CLI; it is not a sandbox for arbitrary third-party scripts. */
@Component
public class AgentToolSandbox {
    private final WorkbenchProperties properties;

    public AgentToolSandbox(WorkbenchProperties properties) { this.properties = properties; }

    public ProcessBuilder process(Path script, int port) throws IOException {
        if (!"Mac OS X".equals(System.getProperty("os.name")) || !Files.isExecutable(Path.of("/usr/bin/sandbox-exec"))) {
            throw new IllegalStateException("当前系统缺少已验证的工具进程隔离，无法运行数据库 CLI");
        }
        if (port < 1 || port > 65535) throw new IllegalStateException("本机服务端口无效");
        Path executable = resolveNode();
        Path cli = script.toRealPath().getParent();
        List<Path> protectedPaths = protectedPaths();
        for (Path path : protectedPaths) {
            if (cli.startsWith(path) || path.startsWith(cli) || executable.startsWith(path)) {
                throw new IllegalStateException("工具目录与敏感数据目录重叠，无法安全启动");
            }
        }
        String profile = buildProfile(cli, executable, protectedPaths, port);
        return new ProcessBuilder("/usr/bin/sandbox-exec", "-p", profile, executable.toString(), script.toRealPath().toString(), "--delegated")
                .directory(cli.toFile());
    }

    private Path resolveNode() throws IOException {
        String value = System.getenv("PATH");
        if (value != null) {
            for (String entry : value.split(java.io.File.pathSeparator)) {
                if (entry.isBlank()) continue;
                Path candidate = Path.of(entry, "node");
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toRealPath();
            }
        }
        throw new IllegalStateException("未找到 Node.js，无法运行数据库 CLI");
    }

    private List<Path> protectedPaths() throws IOException {
        List<Path> paths = new ArrayList<>();
        paths.add(realOrAbsolute(Path.of(properties.dataDir())));
        paths.add(realOrAbsolute(Path.of(properties.keyFile())));
        String cliHome = System.getenv("WORKBENCH_CLI_HOME");
        if (cliHome != null && !cliHome.isBlank()) paths.add(realOrAbsolute(Path.of(cliHome)));
        return paths;
    }

    private Path realOrAbsolute(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        return Files.exists(absolute) ? absolute.toRealPath() : absolute;
    }

    private String buildProfile(Path cli, Path node, List<Path> protectedPaths, int port) {
        StringBuilder rules = new StringBuilder("(version 1)(allow default)");
        rules.append("(deny file-read-data (subpath \"/Users\") (subpath \"/Volumes\") (subpath \"/private/tmp\") (subpath \"/private/var/folders\")");
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) rules.append(" (subpath ").append(quote(Path.of(home).toAbsolutePath().normalize())).append(")");
        for (Path path : protectedPaths) rules.append(" (subpath ").append(quote(path)).append(")");
        rules.append(")");
        rules.append("(allow file-read-data (subpath ").append(quote(cli)).append(") (literal ").append(quote(node)).append("))");
        rules.append("(deny file-write*)(deny network-inbound)(deny network-outbound)");
        rules.append("(allow network-outbound (remote tcp \"localhost:").append(port).append("\"))");
        rules.append("(deny process-fork)(deny process-exec)(allow process-exec (literal ").append(quote(node)).append("))");
        return rules.toString();
    }

    private String quote(Path path) {
        String value = path.toString();
        if (value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("工具路径包含不支持的字符");
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
