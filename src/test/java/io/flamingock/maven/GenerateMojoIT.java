package io.flamingock.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.util.Arrays;
import java.util.Properties;

import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration tests that invoke real Maven builds on fixture projects
 * under {@code src/it/}.
 *
 * <p>These tests require the plugin to be installed in the local Maven
 * repository first — handled by {@code maven-invoker-plugin:install}
 * running in {@code pre-integration-test}.  The project version is
 * forwarded via the system property {@code flamingock.plugin.version}
 * (set in the pom.xml failsafe config).
 */
class GenerateMojoIT {

    private static Invoker invoker;
    private static Properties testProperties;

    @BeforeAll
    static void setUpInvoker() {
        invoker = new DefaultInvoker();

        // Allow override via system property (set by failsafe plugin)
        String mavenHome = System.getProperty("maven.home");
        if (mavenHome != null && !mavenHome.isEmpty()) {
            invoker.setMavenHome(new File(mavenHome));
        }

        // Fallback: M2_HOME environment variable
        if (invoker.getMavenHome() == null) {
            String m2Home = System.getenv("M2_HOME");
            if (m2Home != null && !m2Home.isEmpty()) {
                invoker.setMavenHome(new File(m2Home));
            }
        }

        // Forward the plugin version so fixture POMs resolve it
        testProperties = new Properties();
        String pluginVersion = System.getProperty("flamingock.plugin.version");
        if (pluginVersion != null && !pluginVersion.isEmpty()) {
            testProperties.setProperty("flamingock.plugin.version", pluginVersion);
        }

        System.out.println("[GenerateMojoIT] Maven home: " + invoker.getMavenHome());
        System.out.println("[GenerateMojoIT] Plugin version: "
                + testProperties.getProperty("flamingock.plugin.version", "UNSET"));
    }

    @Test
    void simpleYamlCompileSucceeds() throws MavenInvocationException {
        InvocationResult result = invoke("src/it/simple-yaml/pom.xml",
                Arrays.asList("compile"));

        assertEquals(0, result.getExitCode(),
                "simple-yaml should compile successfully with YAML present");
    }

    @Test
    void noYamlCompileSucceeds() throws MavenInvocationException {
        InvocationResult result = invoke("src/it/no-yaml/pom.xml",
                Arrays.asList("compile"));

        assertEquals(0, result.getExitCode(),
                "no-yaml should compile successfully without YAML files");
    }

    private static InvocationResult invoke(String pomPath, java.util.List<String> goals)
            throws MavenInvocationException {
        InvocationRequest request = new DefaultInvocationRequest()
                .setPomFile(new File(pomPath))
                .setGoals(goals)
                .setProperties(testProperties)
                .setOutputHandler(
                        line -> System.out.println("[mvn] " + line));

        return invoker.execute(request);
    }
}
