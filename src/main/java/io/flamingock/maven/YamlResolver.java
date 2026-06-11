package io.flamingock.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

final class YamlResolver {

    List<Path> resolve(List<Path> roots, List<String> includes, List<String> excludes) throws IOException {
        if (roots == null || roots.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Path> resolved = new LinkedHashSet<Path>();
        List<String> includePatterns = toPatterns(includes, new String[] {"**/*.yaml", "**/*.yml"});
        List<String> excludePatterns = toPatterns(excludes, new String[0]);

        for (Path root : roots) {
            if (root == null || !root.toFile().exists() || !root.toFile().isDirectory()) {
                continue;
            }

            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .forEach(path -> addIfTracked(root, path, includePatterns, excludePatterns, resolved));
            }
        }

        List<Path> ordered = new ArrayList<Path>(resolved);
        Collections.sort(ordered);
        return ordered;
    }

    private void addIfTracked(
            Path root,
            Path candidate,
            List<String> includePatterns,
            List<String> excludePatterns,
            Set<Path> resolved) {
        String relativePath = normalize(root.relativize(candidate));
        if (!matchesAny(relativePath, includePatterns)) {
            return;
        }
        if (matchesAny(relativePath, excludePatterns)) {
            return;
        }
        resolved.add(candidate.toAbsolutePath().normalize());
    }

    private boolean matchesAny(String relativePath, List<String> patterns) {
        for (String pattern : patterns) {
            if (matches(relativePath, pattern)) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(String relativePath, String pattern) {
        return matchesSegments(split(normalize(relativePath)), 0, split(normalize(pattern)), 0);
    }

    private boolean matchesSegments(List<String> pathSegments, int pathIndex, List<String> patternSegments, int patternIndex) {
        if (patternIndex == patternSegments.size()) {
            return pathIndex == pathSegments.size();
        }

        String patternSegment = patternSegments.get(patternIndex);
        if ("**".equals(patternSegment)) {
            if (patternIndex == patternSegments.size() - 1) {
                return true;
            }
            for (int nextPathIndex = pathIndex; nextPathIndex <= pathSegments.size(); nextPathIndex++) {
                if (matchesSegments(pathSegments, nextPathIndex, patternSegments, patternIndex + 1)) {
                    return true;
                }
            }
            return false;
        }

        if (pathIndex >= pathSegments.size()) {
            return false;
        }

        return matchesSegment(pathSegments.get(pathIndex), patternSegment)
                && matchesSegments(pathSegments, pathIndex + 1, patternSegments, patternIndex + 1);
    }

    private boolean matchesSegment(String pathSegment, String patternSegment) {
        StringBuilder expression = new StringBuilder();
        expression.append('^');
        for (int index = 0; index < patternSegment.length(); index++) {
            char character = patternSegment.charAt(index);
            if (character == '*') {
                expression.append(".*");
            } else if (character == '?') {
                expression.append('.');
            } else {
                if ("\\.[]{}()+-^$|".indexOf(character) >= 0) {
                    expression.append('\\');
                }
                expression.append(character);
            }
        }
        expression.append('$');
        return pathSegment.matches(expression.toString());
    }

    private List<String> split(String value) {
        if (value.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> segments = new ArrayList<String>();
        for (String segment : value.split("/")) {
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }
        return segments;
    }

    private String normalize(Path value) {
        return normalize(value.toString());
    }

    private String normalize(String value) {
        return value.replace('\\', '/');
    }

    private List<String> toPatterns(List<String> values, String[] defaultValue) {
        if (values == null || values.isEmpty()) {
            values = Arrays.asList(defaultValue);
        }
        List<String> patterns = new ArrayList<String>();
        for (String value : values) {
            if (value != null) {
                String normalized = normalize(value.trim());
                if (normalized.startsWith("./")) {
                    normalized = normalized.substring(2);
                }
                if (normalized.startsWith("/")) {
                    normalized = normalized.substring(1);
                }
                if (!normalized.isEmpty()) {
                    patterns.add(normalized);
                }
            }
        }
        return patterns;
    }
}
