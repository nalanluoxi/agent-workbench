package com.agentdesk.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentdesk.workbench.core.ApiException;
import com.agentdesk.workbench.skills.SkillService;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class SkillTest {
    private static final Path TEMP = temp();
    @Autowired JdbcTemplate db;
    @Autowired SkillService skills;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEMP.resolve("data").toString());
        registry.add("workbench.key-file", () -> TEMP.resolve("key").resolve("master.key").toString());
    }
    @BeforeEach void setup() {
        db.update("INSERT OR IGNORE INTO users(id,email,password_hash,created_at) VALUES('first','first@skills.test','unused',0)");
        db.update("INSERT OR IGNORE INTO users(id,email,password_hash,created_at) VALUES('second','second@skills.test','unused',0)");
    }

    @Test void localSkillCanBeInstalledEditedDisabledAndRemovedWithUserIsolation() throws Exception {
        Path source = Files.createTempDirectory(TEMP, "source-");
        Files.writeString(source.resolve("SKILL.md"), "---\nname: demo-skill\ndescription: 示例技能\n---\n原始说明", StandardCharsets.UTF_8);
        Files.createDirectories(source.resolve("references"));
        Files.writeString(source.resolve("references/参考.md"), "参考资料");
        var installed = skills.installLocal("first", source.toString());
        String id = installed.view().id();
        assertThat(installed.view().name()).isEqualTo("demo-skill");
        assertThat(skills.list("second")).noneMatch(item -> item.id().equals(id));
        assertThatThrownBy(() -> skills.detail("second", id)).isInstanceOf(ApiException.class);
        var edited = skills.update("first", id, new SkillService.UpdateInput("新名称", "新说明", "更新后的技能内容"));
        assertThat(edited.view().version()).isEqualTo(2);
        assertThat(edited.content()).isEqualTo("更新后的技能内容");
        assertThat(Files.readString(source.resolve("SKILL.md"))).contains("原始说明");
        skills.setEnabled("first", id, false);
        assertThat(skills.snapshot("first")).noneMatch(item -> item.id().equals(id));
        skills.delete("first", id);
        assertThat(skills.list("first")).noneMatch(item -> item.id().equals(id));
    }

    @Test void zipImportRejectsPathsOutsideItsTemporaryDirectory() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("../outside.txt")); zip.write("bad".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        var file = new MockMultipartFile("file", "skill.zip", "application/zip", bytes.toByteArray());
        assertThatThrownBy(() -> skills.installZip("first", file, "")).isInstanceOf(ApiException.class).hasMessageContaining("越界");
        assertThat(TEMP.resolve("outside.txt")).doesNotExist();
    }
    private static Path temp() { try { return Files.createTempDirectory("workbench-skills-test-"); } catch (Exception e) { throw new IllegalStateException(e); } }
}
