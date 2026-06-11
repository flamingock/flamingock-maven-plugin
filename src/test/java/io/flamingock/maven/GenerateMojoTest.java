package io.flamingock.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.maven.model.Build;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GenerateMojoTest {

    @TempDir
    Path tempDir;

    @Mock
    private MavenProject project;

    @Mock
    private Build build;

    private Path basedir;
    private Path targetDir;
    private GenerateMojo mojo;

    @BeforeEach
    void setUp() throws Exception {
        basedir = tempDir.resolve("project");
        targetDir = basedir.resolve("target");

        // Default stubs
        when(project.getBuild()).thenReturn(build);
        when(build.getPlugins()).thenReturn(Collections.<org.apache.maven.model.Plugin>emptyList());
        when(project.getCompileSourceRoots())
                .thenReturn(Arrays.asList(basedir.resolve("src/main/java").toString()));
        Resource resource = new Resource();
        resource.setDirectory(basedir.resolve("src/main/resources").toString());
        when(project.getResources()).thenReturn(Arrays.asList(resource));
        doNothing().when(project).addCompileSourceRoot(any());

        mojo = new GenerateMojo(new YamlResolver(), new CacheManager());
        mojo.setProject(project);
        mojo.setBasedir(basedir.toFile());
        mojo.setOutputDirectory(targetDir.toFile());
    }

    @Test
    void generatesSourceWhenYamlPresent() throws Exception {
        Path resources = Files.createDirectories(basedir.resolve("src/main/resources"));
        Files.write(resources.resolve("app.yaml"),
                Arrays.asList("service: flamingock"), StandardCharsets.UTF_8);

        CollectingLog log = new CollectingLog();
        mojo.setLog(log);

        mojo.execute();

        Path generated = targetDir.resolve("generated-sources/flamingock/FlamingockGenerated.java");
        assertTrue(Files.exists(generated), "Generated source should exist");

        String content = new String(Files.readAllBytes(generated), StandardCharsets.UTF_8);
        assertTrue(content.contains("YAML_STAMP"), "Should contain YAML_STAMP constant");
        assertTrue(content.contains("SOURCES"), "Should contain SOURCES constant");

        // Should have [flamingock] prefix
        assertTrue(log.infoMessages.stream()
                        .anyMatch(msg -> msg.contains("[flamingock]")),
                "All log messages should have [flamingock] prefix");
    }

    @Test
    void skipsWhenTrackedYamlDidNotChange() throws Exception {
        Path resources = Files.createDirectories(basedir.resolve("src/main/resources"));
        Path yaml = resources.resolve("app.yaml");
        Files.write(yaml, Arrays.asList("service: flamingock"), StandardCharsets.UTF_8);

        // First execution
        CollectingLog firstLog = new CollectingLog();
        mojo.setLog(firstLog);
        mojo.execute();

        // Second execution — no changes
        CollectingLog secondLog = new CollectingLog();
        mojo.setLog(secondLog);
        mojo.execute();

        // Should have skip message
        assertTrue(secondLog.infoMessages.stream()
                        .anyMatch(msg -> msg.contains("No changes in YAML files")),
                "Should log skip message when YAML hasn't changed");
    }

    @Test
    void rerunsWhenYamlContentChanges() throws Exception {
        Path resources = Files.createDirectories(basedir.resolve("src/main/resources"));
        Path yaml = resources.resolve("app.yaml");
        Files.write(yaml, Arrays.asList("service: flamingock"), StandardCharsets.UTF_8);

        // First execution
        mojo.setLog(new CollectingLog());
        mojo.execute();

        // Modify YAML content
        Files.write(yaml, Arrays.asList("service: updated"), StandardCharsets.UTF_8);

        // Second execution with modified content
        CollectingLog secondLog = new CollectingLog();
        mojo.setLog(secondLog);
        mojo.execute();

        // Should NOT have skip message
        assertFalse(secondLog.infoMessages.stream()
                        .anyMatch(msg -> msg.contains("No changes in YAML files")),
                "Should regenerate when YAML content changes");

        Path generated = targetDir.resolve("generated-sources/flamingock/FlamingockGenerated.java");
        assertTrue(Files.exists(generated), "Generated source should exist after change");
    }

    @Test
    void rerunsWhenYamlFileDeleted() throws Exception {
        Path resources = Files.createDirectories(basedir.resolve("src/main/resources"));
        Path yaml1 = resources.resolve("change-v1.yaml");
        Path yaml2 = resources.resolve("change-v2.yaml");
        Files.write(yaml1, Arrays.asList("key: value1"), StandardCharsets.UTF_8);
        Files.write(yaml2, Arrays.asList("key: value2"), StandardCharsets.UTF_8);

        // First execution — both files present
        mojo.setLog(new CollectingLog());
        mojo.execute();

        // Delete one YAML file
        Files.delete(yaml2);

        // Second execution — one file gone, stamp changed
        CollectingLog secondLog = new CollectingLog();
        mojo.setLog(secondLog);
        mojo.execute();

        // Should NOT have skip message — stamp changed because tracked files set changed
        assertFalse(secondLog.infoMessages.stream()
                        .anyMatch(msg -> msg.contains("No changes in YAML files")),
                "Should regenerate when a YAML file is deleted");

        Path generated = targetDir.resolve("generated-sources/flamingock/FlamingockGenerated.java");
        assertTrue(Files.exists(generated), "Generated source should exist after YAML deletion");
    }

    @Test
    void logsInfoWhenNoYamlFiles() throws Exception {
        // Create project with no YAML files
        Files.createDirectories(basedir.resolve("src/main/resources"));

        CollectingLog log = new CollectingLog();
        mojo.setLog(log);

        mojo.execute();

        // Should have INFO about no YAML
        assertTrue(log.infoMessages.stream()
                        .anyMatch(msg -> msg.contains("No Flamingock YAML files found")),
                "Should log INFO when no YAML files found");

        // Should NOT have any WARN messages about YAML
        assertTrue(log.warnMessages.isEmpty() || log.warnMessages.stream()
                        .noneMatch(msg -> msg.toLowerCase().contains("yaml")),
                "Should not WARN about missing YAML");
    }

    @Test
    void skipsNonexistentExplicitRootsSilently() throws Exception {
        // Configure explicit non-existent path — should not fail
        File badRoot = basedir.resolve("src/main/non-existent").toFile();
        mojo.setYamlSourceRoots(Arrays.asList(badRoot));

        CollectingLog log = new CollectingLog();
        mojo.setLog(log);

        // Should NOT throw — nonexistent roots are silently skipped
        mojo.execute();

        // Since there are no YAML files anywhere, it should log "no YAML files found"
        assertTrue(log.infoMessages.stream()
                        .anyMatch(msg -> msg.contains("No Flamingock YAML files found")),
                "Should log INFO about no YAML files (nonexistent root silently skipped)");
    }

    @Test
    void usesCompileAndResourceRootsWhenNoExplicitYamlRoots() throws Exception {
        // setUp already mocks getCompileSourceRoots=[src/main/java] and
        // getResources=[src/main/resources]. With no explicit yamlSourceRoots
        // the result should include the resource dir (src/main/resources).
        Path resources = Files.createDirectories(basedir.resolve("src/main/resources"));
        Files.write(resources.resolve("app.yaml"),
                Arrays.asList("service: flamingock"), StandardCharsets.UTF_8);

        CollectingLog log = new CollectingLog();
        mojo.setLog(log);
        mojo.execute();

        Path generated = targetDir.resolve("generated-sources/flamingock/FlamingockGenerated.java");
        assertTrue(Files.exists(generated),
                "Generated source should exist — YAML found in default resource root");

        String content = new String(Files.readAllBytes(generated), StandardCharsets.UTF_8);
        assertTrue(content.contains("src/main/resources"),
                "Default resource root should appear in generated SOURCES");
    }

    @Test
    void addsExplicitYamlRootsToDefaults() throws Exception {
        // Add a custom root alongside the default resource root
        Path custom = Files.createDirectories(basedir.resolve("src/main/custom-yaml"));
        Files.write(custom.resolve("change.yaml"),
                Arrays.asList("key: value"), StandardCharsets.UTF_8);
        mojo.setYamlSourceRoots(Arrays.asList(basedir.resolve("src/main/custom-yaml").toFile()));

        CollectingLog log = new CollectingLog();
        mojo.setLog(log);
        mojo.execute();

        Path generated = targetDir.resolve("generated-sources/flamingock/FlamingockGenerated.java");
        assertTrue(Files.exists(generated),
                "Generated source should exist — YAML found in both default and explicit roots");

        String content = new String(Files.readAllBytes(generated), StandardCharsets.UTF_8);
        assertTrue(content.contains("src/main/resources"),
                "Default resource root should appear in SOURCES");
        assertTrue(content.contains("src/main/custom-yaml"),
                "Explicit custom root should appear in SOURCES");
    }

    @Test
    void deduplicatesWhenExplicitRootMatchesDefault() throws Exception {
        // Explicit root that matches one of the default roots should not duplicate
        Path resources = Files.createDirectories(basedir.resolve("src/main/resources"));
        Files.write(resources.resolve("app.yaml"),
                Arrays.asList("service: flamingock"), StandardCharsets.UTF_8);
        // Add the same path as explicit — should be deduped
        mojo.setYamlSourceRoots(Arrays.asList(basedir.resolve("src/main/resources").toFile()));

        CollectingLog log = new CollectingLog();
        mojo.setLog(log);
        mojo.execute();

        Path generated = targetDir.resolve("generated-sources/flamingock/FlamingockGenerated.java");
        assertTrue(Files.exists(generated));

        String content = new String(Files.readAllBytes(generated), StandardCharsets.UTF_8);
        // Extract the SOURCES line — count occurrences of src/main/resources there
        int sourcesIdx = content.indexOf("SOURCES");
        String sourcesLine = content.substring(sourcesIdx,
                content.indexOf('\n', sourcesIdx));
        int count = sourcesLine.split("src/main/resources", -1).length - 1;
        assertEquals(1, count,
                "src/main/resources should appear only once in SOURCES (deduped)");
    }

    @Test
    void allLogMessagesHaveFlamingockPrefix() throws Exception {
        Path resources = Files.createDirectories(basedir.resolve("src/main/resources"));
        Files.write(resources.resolve("app.yaml"),
                Arrays.asList("service: flamingock"), StandardCharsets.UTF_8);

        CollectingLog log = new CollectingLog();
        mojo.setLog(log);

        mojo.execute();

        // All info messages should start with [flamingock]
        for (String msg : log.infoMessages) {
            assertTrue(msg.startsWith("[flamingock]"),
                    "Info message should start with [flamingock]: " + msg);
        }
    }

    // ---------------------------------------------------------------
    // CollectingLog — test log sink
    // ---------------------------------------------------------------

    static final class CollectingLog implements Log {

        final List<String> debugMessages = new java.util.ArrayList<String>();
        final List<String> infoMessages = new java.util.ArrayList<String>();
        final List<String> warnMessages = new java.util.ArrayList<String>();
        final List<String> errorMessages = new java.util.ArrayList<String>();

        @Override
        public boolean isDebugEnabled() {
            return true;
        }

        @Override
        public void debug(CharSequence content) {
            debugMessages.add(String.valueOf(content));
        }

        @Override
        public void debug(CharSequence content, Throwable error) {
            debug(content);
        }

        @Override
        public void debug(Throwable error) {
            debug(error.getMessage());
        }

        @Override
        public boolean isInfoEnabled() {
            return true;
        }

        @Override
        public void info(CharSequence content) {
            infoMessages.add(String.valueOf(content));
        }

        @Override
        public void info(CharSequence content, Throwable error) {
            info(content);
        }

        @Override
        public void info(Throwable error) {
            info(error.getMessage());
        }

        @Override
        public boolean isWarnEnabled() {
            return true;
        }

        @Override
        public void warn(CharSequence content) {
            warnMessages.add(String.valueOf(content));
        }

        @Override
        public void warn(CharSequence content, Throwable error) {
            warn(content);
        }

        @Override
        public void warn(Throwable error) {
            warn(error.getMessage());
        }

        @Override
        public boolean isErrorEnabled() {
            return true;
        }

        @Override
        public void error(CharSequence content) {
            errorMessages.add(String.valueOf(content));
        }

        @Override
        public void error(CharSequence content, Throwable error) {
            error(content);
        }

        @Override
        public void error(Throwable error) {
            error(error.getMessage());
        }
    }
}
