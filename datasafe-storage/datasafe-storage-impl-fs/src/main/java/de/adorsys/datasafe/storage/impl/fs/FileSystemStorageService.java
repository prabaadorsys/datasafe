package de.adorsys.datasafe.storage.impl.fs;

import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import de.adorsys.datasafe.storage.api.StorageService;
import de.adorsys.datasafe.types.api.resource.*;
import de.adorsys.datasafe.types.api.utils.Obfuscate;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.stream.Stream;

/**
 * Filesystem ({@link java.nio.file}) compatible storage service default implementation.
 */
@Slf4j
@RequiredArgsConstructor
public class FileSystemStorageService implements StorageService {

    private final Uri dir;

    public FileSystemStorageService(Path dir) {
        this.dir = new Uri(dir.toUri());
    }

    /**
     * Lists resources and returns their location without access credentials.
     */
    @SneakyThrows
    @Override
    public Stream<AbsoluteLocation<ResolvedResource>> list(AbsoluteLocation path) {
        log.debug("List file request: {}", Obfuscate.secure(path));
        Path filePath = resolve(path.location().asURI(), false);
        log.debug("List file: {}", Obfuscate.secure(filePath));

        // FS should be compatible with s3 behavior:
        if (!filePath.toFile().exists()) {
            return Stream.empty();
        }

        return Files.walk(filePath)
                .filter(it -> !it.startsWith("."))
                .filter(it -> !it.toFile().isDirectory())
                .map(it -> new AbsoluteLocation<>(
                        new BaseResolvedResource(
                                new BasePrivateResource(new Uri(it.toUri())),
                                Instant.ofEpochMilli(it.toFile().lastModified()))
                        )
                );
    }

    @SneakyThrows
    @Override
    public InputStream read(AbsoluteLocation path) {
        log.debug("Read file request: {}", Obfuscate.secure(path));
        Path filePath = resolve(path.location().asURI(), false);
        log.debug("Read file: {}", Obfuscate.secure(filePath));
        return MoreFiles.asByteSource(filePath, StandardOpenOption.READ).openStream();
    }

    @SneakyThrows
    @Override
    public OutputStream write(AbsoluteLocation path) {
        log.debug("Write file request: {}", Obfuscate.secure(path.location()));
        Path filePath = resolve(path.location().asURI(), true);
        log.debug("Write file: {}", Obfuscate.secure(filePath));
        return MoreFiles.asByteSink(filePath, StandardOpenOption.CREATE).openStream();
    }

    @SneakyThrows
    @Override
    public void remove(AbsoluteLocation location) {
        if (!objectExists(location)) {
            log.debug("nothing to delete {}", Obfuscate.secure(location));
            return;
        }

        Path path = resolve(location.location().asURI(), false);
        boolean isFile = !path.toFile().isDirectory();
        MoreFiles.deleteRecursively(path, RecursiveDeleteOption.ALLOW_INSECURE);
        log.debug("deleted {} at: {}", isFile ? "file" : "directory", Obfuscate.secure(location));
    }

    @Override
    public boolean objectExists(AbsoluteLocation location) {
        boolean exists = Files.exists(resolve(location.location().asURI(), false));
        log.debug("exists {} directory at: {}", exists, Obfuscate.secure(location));
        return exists;
    }

    protected Path resolve(URI uri, boolean mkDirs) {
        Path path = Paths.get(dir.resolve(uri).asURI());
        if (!path.getParent().toFile().exists() && mkDirs) {
            log.debug("Creating directories for: {}", Obfuscate.secure(path));
            path.getParent().toFile().mkdirs();
        }

        return Paths.get(dir.resolve(uri).asURI());
    }
}
