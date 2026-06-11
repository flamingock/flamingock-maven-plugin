package io.flamingock.maven;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Flamingock Maven Mojo that generates a Java trigger source from YAML
 * configuration files and wires annotation processor arguments.
 *
 * <p>In {@code generate-sources}, the Mojo:
 * <ol>
 *   <li>Scans configured source roots for YAML files</li>
 *   <li>Computes a combined SHA-256 stamp to avoid unnecessary regeneration</li>
 *   <li>Generates {@code FlamingockGenerated.java} under
 *       {@code target/generated-sources/flamingock/}</li>
 *   <li>Registers the generated-sources directory as a compile source root</li>
 *   <li>Injects {@code -Aflamingock.sources} and {@code -Aflamingock.resources}
 *       into the maven-compiler-plugin configuration</li>
 * </ol>
 *
 * <p>Gradle parity: the injected compiler args match the Gradle plugin contract
 * so the same annotation processor works for both build systems.
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class GenerateMojo extends AbstractMojo {

    private static final List<String> DEFAULT_INCLUDES = Arrays.asList("**/*.yaml", "**/*.yml");

    private static final String GENERATED_PACKAGE = "io.flamingock";

    private static final String GENERATED_CLASS = "FlamingockGenerated";

    private static final String GENERATED_SOURCES_SUBDIR = "generated-sources/flamingock";

    @Parameter
    private List<File> yamlSourceRoots;

    @Parameter
    private List<String> yamlIncludes;

    @Parameter
    private List<String> yamlExcludes;

    @Parameter(defaultValue = "${project.build.directory}", required = true)
    private File outputDirectory;

    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private File basedir;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    private final YamlResolver yamlResolver;

    private final CacheManager cacheManager;

    public GenerateMojo() {
        this(new YamlResolver(), new CacheManager());
    }

    GenerateMojo(YamlResolver yamlResolver, CacheManager cacheManager) {
        this.yamlResolver = yamlResolver;
        this.cacheManager = cacheManager;
    }

    @Override
    public void execute() throws MojoExecutionException {
        try {
            Path projectDir = basedir.toPath().toAbsolutePath().normalize();
            Path buildDir = outputDirectory.toPath().toAbsolutePath().normalize();
            Path generatedSourcesDir = buildDir.resolve(GENERATED_SOURCES_SUBDIR);

            List<Path> trackedRoots = resolveTrackedRoots(projectDir);

            List<Path> trackedFiles = yamlResolver.resolve(
                    trackedRoots, resolveIncludes(), resolveExcludes());

            if (trackedFiles.isEmpty()) {
                getLog().info("[flamingock] No Flamingock YAML files found — skipping");
                return;
            }

            // Check cache stamp — skip generation if YAML content hasn't changed
            String combinedHash = cacheManager.computeCombinedHash(trackedFiles);
            if (cacheManager.isUpToDate(buildDir, combinedHash)) {
                getLog().info("[flamingock] No changes in YAML files — skipping regeneration");
                return;
            }

            // Build compiler-arg values
            String sourcesArg = buildSourcesArg(projectDir, trackedFiles, trackedRoots);
            String resourcesArg = buildResourcesArg(projectDir);

            // Generate Java trigger source
            generateSource(generatedSourcesDir, sourcesArg, resourcesArg, combinedHash);

            // Register generated sources directory for compilation
            project.addCompileSourceRoot(generatedSourcesDir.toAbsolutePath().toString());

            // Inject -Aflamingock.* compiler args
            injectFlamingockCompilerArgs(sourcesArg, resourcesArg);

            // Persist cache stamp
            cacheManager.saveStamp(buildDir, combinedHash);

            getLog().info("[flamingock] Registered " + trackedFiles.size()
                    + " YAML file(s) as compile inputs");

        } catch (IOException exception) {
            throw new MojoExecutionException(
                    "[flamingock] Failed to execute Flamingock generate mojo", exception);
        }
    }

    // ---------------------------------------------------------------
    // Package-private setters for testing
    // ---------------------------------------------------------------

    void setYamlSourceRoots(List<File> yamlSourceRoots) {
        this.yamlSourceRoots = yamlSourceRoots;
    }

    void setYamlIncludes(List<String> yamlIncludes) {
        this.yamlIncludes = yamlIncludes;
    }

    void setYamlExcludes(List<String> yamlExcludes) {
        this.yamlExcludes = yamlExcludes;
    }

    void setOutputDirectory(File outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    void setBasedir(File basedir) {
        this.basedir = basedir;
    }

    void setProject(MavenProject project) {
        this.project = project;
    }

    // ---------------------------------------------------------------
    // Root resolution
    // ---------------------------------------------------------------

    private List<Path> resolveTrackedRoots(Path projectDir) {
        Set<Path> rootSet = new LinkedHashSet<Path>();

        // 1. Default roots from Maven build model — compile source roots
        if (project != null && project.getCompileSourceRoots() != null) {
            for (String root : project.getCompileSourceRoots()) {
                if (root == null || root.isEmpty()) {
                    continue;
                }
                Path path = new File(root).toPath().toAbsolutePath().normalize();
                if (path.startsWith(projectDir)) {
                    rootSet.add(path);
                }
            }
        }

        // 2. Default roots from Maven build model — resource directories
        if (project != null && project.getResources() != null) {
            for (Resource res : project.getResources()) {
                String dir = res.getDirectory();
                if (dir == null || dir.isEmpty()) {
                    continue;
                }
                Path path = new File(dir).toPath();
                if (!path.isAbsolute()) {
                    path = projectDir.resolve(path);
                }
                path = path.toAbsolutePath().normalize();
                if (path.startsWith(projectDir)) {
                    rootSet.add(path);
                }
            }
        }

        // 3. Explicit yamlSourceRoots — additive, never replaces defaults
        if (yamlSourceRoots != null) {
            for (File root : yamlSourceRoots) {
                if (root == null) {
                    continue;
                }
                Path path = root.toPath();
                if (!path.isAbsolute()) {
                    path = projectDir.resolve(path);
                }
                path = path.toAbsolutePath().normalize();
                if (path.startsWith(projectDir)) {
                    rootSet.add(path);
                }
            }
        }

        return new ArrayList<Path>(rootSet);
    }

    private List<String> resolveIncludes() {
        return yamlIncludes == null || yamlIncludes.isEmpty()
                ? DEFAULT_INCLUDES
                : yamlIncludes;
    }

    private List<String> resolveExcludes() {
        return yamlExcludes == null
                ? new ArrayList<String>()
                : yamlExcludes;
    }

    // ---------------------------------------------------------------
    // Java source generation
    // ---------------------------------------------------------------

    private void generateSource(
            Path generatedSourcesDir,
            String sourcesArg,
            String resourcesArg,
            String combinedHash) throws IOException {

        Files.createDirectories(generatedSourcesDir);

        String source = "package " + GENERATED_PACKAGE + ";\n"
                + "\n"
                + "/** THIS FILE IS AUTO-GENERATED — DO NOT EDIT. */\n"
                + "final class " + GENERATED_CLASS + " {\n"
                + "    static final String YAML_STAMP = \"" + combinedHash + "\";\n"
                + "    static final String SOURCES = \"" + escape(sourcesArg) + "\";\n"
                + "    static final String RESOURCES = \"" + escape(resourcesArg) + "\";\n"
                + "}\n";

        Files.write(
                generatedSourcesDir.resolve(GENERATED_CLASS + ".java"),
                source.getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---------------------------------------------------------------
    // Compiler-arg value builders
    // ---------------------------------------------------------------

    /**
     * Builds the {@code -Aflamingock.sources} value: project-relative paths
     * of the YAML source roots (directories that actually contained YAML
     * files), comma-separated.
     */
    private String buildSourcesArg(
            Path projectDir, List<Path> trackedFiles, List<Path> trackedRoots) {
        // Collect unique parent roots of tracked files
        List<String> roots = new ArrayList<String>();
        for (Path root : trackedRoots) {
            // Only include roots that actually contain files
            boolean hasFiles = false;
            for (Path file : trackedFiles) {
                if (file.startsWith(root)) {
                    hasFiles = true;
                    break;
                }
            }
            if (hasFiles) {
                roots.add(projectDir.relativize(root).toString()
                        .replace('\\', '/'));
            }
        }
        return join(",", roots);
    }

    /**
     * Builds the {@code -Aflamingock.resources} value: project-relative paths
     * of the project's resource directories, comma-separated.
     */
    private String buildResourcesArg(Path projectDir) {
        List<String> list = new ArrayList<String>();
        if (project != null && project.getResources() != null) {
            for (Resource res : project.getResources()) {
                String dir = res.getDirectory();
                if (dir != null) {
                    Path dirPath = new File(dir).toPath();
                    if (!dirPath.isAbsolute()) {
                        dirPath = projectDir.resolve(dirPath);
                    }
                    list.add(projectDir.relativize(dirPath).toString()
                            .replace('\\', '/'));
                }
            }
        }
        if (list.isEmpty()) {
            // Fallback: use default resource directory
            list.add("src/main/resources");
        }
        return join(",", list);
    }

    // ---------------------------------------------------------------
    // Xpp3Dom compiler-arg injection
    // ---------------------------------------------------------------

    private void injectFlamingockCompilerArgs(String sources, String resources) {
        Plugin compilerPlugin = findOrCreateCompilerPlugin();
        if (compilerPlugin == null) {
            getLog().warn(
                    "[flamingock] Could not find or create maven-compiler-plugin"
                            + " — annotation processor args not injected");
            return;
        }

        Xpp3Dom config = (Xpp3Dom) compilerPlugin.getConfiguration();
        if (config == null) {
            config = new Xpp3Dom("configuration");
            compilerPlugin.setConfiguration(config);
        }

        // Build fresh compilerArgs with existing non-flamingock args preserved
        Xpp3Dom compilerArgs = config.getChild("compilerArgs");
        if (compilerArgs == null) {
            compilerArgs = new Xpp3Dom("compilerArgs");
            config.addChild(compilerArgs);
        } else {
            // Remove any previously injected flamingock args
            removeFlamingockArgs(compilerArgs);
        }

        addArg(compilerArgs, "-Aflamingock.sources=" + sources);
        addArg(compilerArgs, "-Aflamingock.resources=" + resources);
    }

    private Plugin findOrCreateCompilerPlugin() {
        if (project == null) {
            return null;
        }
        Build build = project.getBuild();
        if (build == null) {
            return null;
        }

        // Look in explicit plugin declarations
        for (Plugin p : build.getPlugins()) {
            if ("maven-compiler-plugin".equals(p.getArtifactId())
                    && "org.apache.maven.plugins".equals(p.getGroupId())) {
                return p;
            }
        }

        // Look in pluginManagement
        if (build.getPluginManagement() != null) {
            for (Plugin p : build.getPluginManagement().getPlugins()) {
                if ("maven-compiler-plugin".equals(p.getArtifactId())
                        && "org.apache.maven.plugins".equals(p.getGroupId())) {
                    return p;
                }
            }
        }

        // Not found anywhere — create a new plugin entry
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-compiler-plugin");
        build.addPlugin(plugin);
        return plugin;
    }

    private void removeFlamingockArgs(Xpp3Dom compilerArgs) {
        Xpp3Dom[] args = compilerArgs.getChildren("arg");
        if (args == null) {
            return;
        }
        for (Xpp3Dom arg : args) {
            String value = arg.getValue();
            if (value != null && value.startsWith("-Aflamingock.")) {
                compilerArgs.removeChild(indexOf(compilerArgs, arg));
            }
        }
    }

    private void addArg(Xpp3Dom parent, String value) {
        Xpp3Dom arg = new Xpp3Dom("arg");
        arg.setValue(value);
        parent.addChild(arg);
    }

    private static int indexOf(Xpp3Dom parent, Xpp3Dom child) {
        Xpp3Dom[] children = parent.getChildren();
        for (int i = 0; i < children.length; i++) {
            if (children[i] == child) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------

    private static String join(String delimiter, List<String> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(delimiter);
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
