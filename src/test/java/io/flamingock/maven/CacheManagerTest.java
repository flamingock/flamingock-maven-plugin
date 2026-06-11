package io.flamingock.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheManagerTest {

    @TempDir
    Path tempDir;

    private final CacheManager cacheManager = new CacheManager();

    @Test
    void isNotUpToDateWhenStampFileMissing() throws IOException {
        Path stampDir = tempDir.resolve("target");
        assertFalse(cacheManager.isUpToDate(stampDir, "some-hash"));
    }

    @Test
    void isUpToDateWhenStampMatches() throws IOException {
        Path stampDir = tempDir.resolve("target");
        cacheManager.saveStamp(stampDir, "the-hash");
        assertTrue(cacheManager.isUpToDate(stampDir, "the-hash"));
    }

    @Test
    void isNotUpToDateWhenStampDiffers() throws IOException {
        Path stampDir = tempDir.resolve("target");
        cacheManager.saveStamp(stampDir, "stored-hash");
        assertFalse(cacheManager.isUpToDate(stampDir, "different-hash"));
    }

    @Test
    void emptyHashWhenNoFiles() throws IOException {
        String hash = cacheManager.computeCombinedHash(Collections.<Path>emptyList());
        assertEquals("", hash);
    }

    @Test
    void hashIsDeterministic() throws IOException {
        Path file = createFile(tempDir, "test.yaml", "content: hello");
        String first = cacheManager.computeCombinedHash(Arrays.asList(file));
        String second = cacheManager.computeCombinedHash(Arrays.asList(file));
        assertEquals(first, second);
    }

    @Test
    void hashChangesWhenContentChanges() throws IOException {
        Path file = createFile(tempDir, "test.yaml", "content: hello");
        String before = cacheManager.computeCombinedHash(Arrays.asList(file));

        Files.write(file, Arrays.asList("content: changed"), StandardCharsets.UTF_8);
        String after = cacheManager.computeCombinedHash(Arrays.asList(file));

        assertFalse(before.equals(after));
    }

    @Test
    void stampSurvivesSaveAndReload() throws IOException {
        Path stampDir = tempDir.resolve("target");
        cacheManager.saveStamp(stampDir, "persistent-hash");
        assertTrue(cacheManager.isUpToDate(stampDir, "persistent-hash"));

        // Verify it's actually on disk
        Path stampFile = stampDir.resolve(CacheManager.STAMP_FILE);
        assertTrue(Files.exists(stampFile));
        String content = new String(Files.readAllBytes(stampFile), StandardCharsets.UTF_8).trim();
        assertEquals("persistent-hash", content);
    }

    private Path createFile(Path dir, String relativePath, String content) throws IOException {
        Path file = dir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.write(file, Arrays.asList(content), StandardCharsets.UTF_8);
        return file;
    }
}
