# Flamingock Maven Plugin

A Maven plugin for [Flamingock](https://flamingock.io) that registers YAML
configuration files as incremental compilation inputs and wires annotation
processor arguments — bringing Gradle parity to Maven builds.

---

## Table of Contents

- [Usage](#usage)
- [Configuration Reference](#configuration-reference)
- [How It Works](#how-it-works)
- [Build System Parity (Gradle)](#build-system-parity-gradle)
- [Troubleshooting](#troubleshooting)
- [Developer Guide](#developer-guide)
- [License](#license)

---

## Usage

Add the plugin to your POM:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>io.flamingock</groupId>
            <artifactId>flamingock-maven-plugin</artifactId>
            <version>0.1.0-SNAPSHOT</version>
            <executions>
                <execution>
                    <goals>
                        <goal>generate</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

Place your Flamingock YAML configuration files under any Maven source root
or resource directory:

```
src/
├── main/
│   ├── java/                    # compile source root (scanned by default)
│   │   └── flamingock/
│   │       └── change-v1.yaml
│   └── resources/               # resource directory (scanned by default)
│       └── flamingock/
│           └── change-v2.yaml
└── main/custom/                 # additional yamlSourceRoot (configured)
    └── flamingock/
        └── change-v3.yaml
```

Default roots are derived from the Maven build model:
`compileSourceRoots` + resource directories. Configured `yamlSourceRoots`
**add** to — never replace — these defaults.

Run the build:

```bash
mvn compile
```

The plugin:

1. Scans the configured roots for `*.yaml` / `*.yml` files.
2. Generates a Java trigger source under
   `target/generated-sources/flamingock/FlamingockGenerated.java`.
3. Registers the generated source with the compiler so that YAML changes
   invalidate the incremental build cache.
4. Injects `-Aflamingock.sources` and `-Aflamingock.resources` into the
   compiler plugin automatically — no additional POM configuration required.

### What happens when nothing changes?

Subsequent `mvn compile` invocations without YAML changes skip generation
entirely.  A lightweight content stamp (`.flamingock-stamp`) prevents
unnecessary recompilation.

### What happens with no YAML files?

A single `INFO` log line is emitted.  No warnings, no errors.  Because no
generated source is produced, the compiler plugin does not receive the
`-Aflamingock.sources` or `-Aflamingock.resources` arguments — there is
nothing for the annotation processor to process.

---

## Configuration Reference

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `yamlSourceRoots` | `List<File>` | Maven build model (compile source roots + resource dirs) | Additional directories to scan for YAML files — **appended** to defaults, never replacing them |
| `yamlIncludes` | `List<String>` | `**/*.yaml`, `**/*.yml` | Glob patterns for files to include |
| `yamlExcludes` | `List<String>` | none | Glob patterns for files to exclude |

### Example with custom roots

```xml
<plugin>
    <groupId>io.flamingock</groupId>
    <artifactId>flamingock-maven-plugin</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <configuration>
        <yamlSourceRoots>
            <yamlSourceRoot>src/main/custom-yaml</yamlSourceRoot>
        </yamlSourceRoots>
    </configuration>
    <executions>
        <execution>
            <goals>
                <goal>generate</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

The configured `yamlSourceRoots` **adds** to the Maven-derived defaults
(compile source roots + resource directories).  Duplicate and nonexistent
paths are handled silently.

---

## How It Works

The plugin participates in the standard Maven lifecycle during the
`generate-sources` phase:

```
mvn compile
   │
   ▼
GENERATE_SOURCES phase
    │
    ├── Compute default roots from Maven build model
    │   (compile source roots + resource directories)
    ├── Append explicit yamlSourceRoots (additive — never replaces)
    ├── Scan for YAML files via YamlResolver
    ├── Compare content stamp (.flamingock-stamp)
   │    ├── MATCH → skip (log INFO)
   │    └── MISMATCH → regenerate:
   │                    ├── Write FlamingockGenerated.java (with YAML
   │                    │   content hash as a constant)
   │                    ├── project.addCompileSourceRoot(...)
   │                    ├── Inject -Aflamingock.sources / .resources
   │                    │   into maven-compiler-plugin via Xpp3Dom
   │                    └── Save new stamp
   │
   ▼
COMPILE phase
   │
   ├── Compiler detects new/modified generated source
   ├── Recompiles if stamp (and therefore YAML content) changed
   └── Annotation processor receives -Aflamingock.* args
```

### Generated source

```java
package io.flamingock;

/** THIS FILE IS AUTO-GENERATED — DO NOT EDIT. */
final class FlamingockGenerated {
    static final String YAML_STAMP = "a1b2c3d4e5f6...";
    static final String SOURCES = "src/main/resources";
    static final String RESOURCES = "src/main/resources";
}
```

The `YAML_STAMP` constant is a SHA-256 digest of all tracked YAML file
contents.  When any YAML file changes, the stamp (and therefore the generated
source) changes, which triggers recompilation.

---

## Build System Parity (Gradle)

The annotation processor arguments match the Gradle plugin contract:

| Maven | Gradle equivalent |
|-------|-------------------|
| `-Aflamingock.sources` | `flamingock.sources` property |
| `-Aflamingock.resources` | `flamingock.resources` property |

The same annotation processor in `flamingock-java` reads these arguments
regardless of the build system.

---

## Troubleshooting

### Build runs but YAML changes don't trigger recompilation

- Confirm the plugin is bound to the `generate-sources` phase (it is by
  default).
- Check that your YAML files are under a configured `yamlSourceRoots`.
- Run `mvn clean compile` to force a full rebuild and regenerate the stamp.

### Log messages missing the `[flamingock]` prefix

All plugin log lines are prefixed with `[flamingock]`.  If you see log output
without the prefix, it comes from a different component.  Use grep to isolate:

```bash
mvn compile | grep "\[flamingock\]"
```

### No YAML files warning

If you don't use Flamingock YAML, the plugin logs a single `INFO` line and
does nothing else.  This is expected — no action needed.

---

## Developer Guide

### Prerequisites

- Java 8+
- Apache Maven 3.9.x
- Git

### Build

```bash
mvn clean verify
```

Unit tests (`*Test.java`) run via `maven-surefire-plugin`.
Integration tests (`*IT.java`) run via `maven-failsafe-plugin`.
Fixture-based ITs run via `maven-invoker-plugin`.

### Project structure

```
src/
├── main/java/io/flamingock/maven/
│   ├── GenerateMojo.java      # Maven Mojo — entry point
│   ├── YamlResolver.java      # Glob-based YAML file scanner
│   └── CacheManager.java      # Content stamp cache (.flamingock-stamp)
├── test/java/io/flamingock/maven/
│   ├── GenerateMojoTest.java  # Unit tests for GenerateMojo
│   ├── CacheManagerTest.java  # Unit tests for CacheManager
│   ├── YamlResolverTest.java  # Unit tests for YamlResolver
│   └── GenerateMojoIT.java    # Integration tests (real Maven invocation)
└── it/
    ├── simple-yaml/           # Fixture: YAML present → verify generated source
    └── no-yaml/               # Fixture: no YAML → verify INFO-only
```

### Running individual tests

```bash
# Unit tests only
mvn test

# Integration tests only (requires installed plugin)
mvn install -DskipTests
mvn failsafe:integration-test failsafe:verify
```

### Common tasks

| Task | Command |
|------|---------|
| Full build with ITs | `mvn clean verify` |
| Unit tests only | `mvn test` |
| Single unit test | `mvn test -Dtest=CacheManagerTest` |
| Check test coverage | `mvn jacoco:report` (if jacoco is configured) |
| Install locally | `mvn clean install -DskipTests` |

### Adding a new IT fixture

1. Create a directory under `src/it/<scenario-name>`.
2. Add a `pom.xml` that uses the plugin with your scenario configuration.
3. Add source files as needed.
4. Add a `verify.groovy` script for post-build assertions.
5. Add a test method in `GenerateMojoIT.java` if you need programmatic
   verification beyond the Groovy script.

---

## License

Flamingock is open source under the [Apache License 2.0](LICENSE.md).
