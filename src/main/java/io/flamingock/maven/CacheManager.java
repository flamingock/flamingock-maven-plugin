package io.flamingock.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

/**
 * Lightweight cache manager that uses a single combined SHA-256 stamp file
 * to determine whether YAML files have changed since the last build.
 *
 * <p>If the stamp matches, the Mojo skips regeneration entirely, avoiding
 * unnecessary recompilation. The stamp is a hex-encoded SHA-256 digest of
 * all tracked YAML file contents concatenated in sorted path order.
 */
final class CacheManager {

    static final String STAMP_FILE = ".flamingock-stamp";

    /**
     * Returns {@code true} if the stored stamp matches the given combined hash,
     * meaning no tracked files have changed.
     */
    boolean isUpToDate(Path stampDir, String combinedHash) throws IOException {
        Path stampFile = stampDir.resolve(STAMP_FILE);
        if (!Files.exists(stampFile)) {
            return false;
        }
        String stored = new String(Files.readAllBytes(stampFile), StandardCharsets.UTF_8).trim();
        return stored.equals(combinedHash);
    }

    /**
     * Persists the combined hash to the stamp file.
     */
    void saveStamp(Path stampDir, String combinedHash) throws IOException {
        Files.createDirectories(stampDir);
        Files.write(stampDir.resolve(STAMP_FILE),
                Arrays.asList(combinedHash),
                StandardCharsets.UTF_8);
    }

    /**
     * Computes a single SHA-256 digest across all tracked YAML files.
     * Files are processed in sorted path order for deterministic output.
     */
    String computeCombinedHash(List<Path> trackedFiles) throws IOException {
        if (trackedFiles == null || trackedFiles.isEmpty()) {
            return "";
        }
        MessageDigest digest = getSha256();
        for (Path file : trackedFiles) {
            byte[] content = Files.readAllBytes(file);
            digest.update(content);
        }
        return bytesToHex(digest.digest());
    }

    private MessageDigest getSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is not available", exception);
        }
    }

    private String bytesToHex(byte[] hash) {
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte part : hash) {
            builder.append(String.format("%02x", part));
        }
        return builder.toString();
    }
}
