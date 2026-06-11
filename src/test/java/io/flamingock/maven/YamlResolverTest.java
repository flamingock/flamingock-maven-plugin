package io.flamingock.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesYamlAndYmlWithinSourceStyleRoots() throws IOException {
        Path javaRoot = Files.createDirectories(tempDir.resolve("src/main/java"));
        Path resourcesRoot = Files.createDirectories(tempDir.resolve("src/main/resources"));
        Files.createDirectories(javaRoot.resolve("nested"));
        Files.createDirectories(resourcesRoot.resolve("config"));
        Files.write(javaRoot.resolve("nested/schema.yaml"), Arrays.asList("name: java"));
        Files.write(resourcesRoot.resolve("config/app.yml"), Arrays.asList("name: resources"));
        Files.write(resourcesRoot.resolve("config/ignored.txt"), Arrays.asList("ignore"));

        YamlResolver resolver = new YamlResolver();

        List<Path> resolved = resolver.resolve(
                Arrays.asList(javaRoot, resourcesRoot),
                Arrays.asList("**/*.yaml", "**/*.yml"),
                Arrays.asList());

        assertEquals(
                Arrays.asList(
                        javaRoot.resolve("nested/schema.yaml").toAbsolutePath().normalize(),
                        resourcesRoot.resolve("config/app.yml").toAbsolutePath().normalize()),
                resolved);
    }

    @Test
    void excludesConfiguredSubsetFromTrackedFiles() throws IOException {
        Path resourcesRoot = Files.createDirectories(tempDir.resolve("src/main/resources"));
        Files.createDirectories(resourcesRoot.resolve("config/private"));
        Path included = resourcesRoot.resolve("config/public.yaml");
        Path excluded = resourcesRoot.resolve("config/private/secret.yaml");
        Files.write(included, Arrays.asList("public: true"));
        Files.write(excluded, Arrays.asList("secret: true"));

        YamlResolver resolver = new YamlResolver();

        List<Path> resolved = resolver.resolve(
                Arrays.asList(resourcesRoot),
                Arrays.asList("**/*.yaml"),
                Arrays.asList("**/private/**"));

        assertEquals(Arrays.asList(included.toAbsolutePath().normalize()), resolved);
    }

    @Test
    void honorsExplicitRootsWithoutScanningOutsideThem() throws IOException {
        Path explicitRoot = Files.createDirectories(tempDir.resolve("tracked"));
        Path outsideRoot = Files.createDirectories(tempDir.resolve("other-module/src/main/resources"));
        Path tracked = explicitRoot.resolve("feature.yaml");
        Path untracked = outsideRoot.resolve("ignored.yaml");
        Files.write(tracked, Arrays.asList("tracked: true"));
        Files.write(untracked, Arrays.asList("tracked: false"));

        YamlResolver resolver = new YamlResolver();

        List<Path> resolved = resolver.resolve(
                Arrays.asList(explicitRoot),
                Arrays.asList("**/*.yaml"),
                Arrays.asList());

        assertEquals(Arrays.asList(tracked.toAbsolutePath().normalize()), resolved);
        assertTrue(resolved.stream().noneMatch(path -> path.equals(untracked.toAbsolutePath().normalize())));
    }

    @Test
    void returnsEmptyListWhenNoYamlMatches() throws IOException {
        Path resourcesRoot = Files.createDirectories(tempDir.resolve("src/main/resources"));
        Files.write(resourcesRoot.resolve("readme.txt"), Arrays.asList("hello"));

        YamlResolver resolver = new YamlResolver();

        List<Path> resolved = resolver.resolve(
                Arrays.asList(resourcesRoot),
                Arrays.asList("**/*.yaml", "**/*.yml"),
                Arrays.asList());

        assertTrue(resolved.isEmpty());
    }

    @Test
    void matchesYamlInRootDirectoryWithDefaultGlobPatterns() throws IOException {
        Path resourcesRoot = Files.createDirectories(tempDir.resolve("src/main/resources"));
        Path yaml = resourcesRoot.resolve("app.yaml");
        Files.write(yaml, Arrays.asList("root: true"));

        YamlResolver resolver = new YamlResolver();

        List<Path> resolved = resolver.resolve(
                Arrays.asList(resourcesRoot),
                Arrays.asList("**/*.yaml", "**/*.yml"),
                Arrays.asList());

        assertEquals(Arrays.asList(yaml.toAbsolutePath().normalize()), resolved);
    }

    @Test
    void ignoresPathsOutsideCurrentRootEvenWhenSiblingModuleContainsYaml() throws IOException {
        Path currentModuleRoot = Files.createDirectories(tempDir.resolve("module-a/src/main/resources"));
        Path siblingModuleRoot = Files.createDirectories(tempDir.resolve("module-b/src/main/resources"));
        Path currentYaml = currentModuleRoot.resolve("current.yaml");
        Path siblingYaml = siblingModuleRoot.resolve("sibling.yaml");
        Files.write(currentYaml, Arrays.asList("module: a"));
        Files.write(siblingYaml, Arrays.asList("module: b"));

        YamlResolver resolver = new YamlResolver();

        List<Path> resolved = resolver.resolve(
                Arrays.asList(currentModuleRoot),
                Arrays.asList("**/*.yaml"),
                Arrays.asList());

        assertEquals(Arrays.asList(currentYaml.toAbsolutePath().normalize()), resolved);
        assertTrue(resolved.stream().noneMatch(path -> path.equals(siblingYaml.toAbsolutePath().normalize())));
    }
}
