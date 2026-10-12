package ch.so.agi.ops.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Restriktive Dateirechte fuer lokale Artefakte. Unter Linux/macOS werden Verzeichnisse mit
 * 0700 und Dateien mit 0600 angelegt. Auf Dateisystemen ohne POSIX-Unterstuetzung wird das
 * stillschweigend uebersprungen; das ist kein Sicherheitsversprechen.
 */
public final class FilePermissions {

    public static final Set<PosixFilePermission> DIRECTORY_MODE = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    public static final Set<PosixFilePermission> FILE_MODE = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private FilePermissions() {
    }

    public static boolean posixSupported(Path path) {
        try {
            return path.getFileSystem().supportedFileAttributeViews().contains("posix");
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Legt ein Verzeichnis an und setzt - soweit moeglich - 0700. */
    public static Path createDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        applyDirectory(directory);
        return directory;
    }

    public static void applyDirectory(Path directory) {
        if (!posixSupported(directory)) {
            return;
        }
        try {
            Files.setPosixFilePermissions(directory, DIRECTORY_MODE);
        } catch (IOException | UnsupportedOperationException ex) {
            // Kein Sicherheitsversprechen: Rechte konnten nicht gesetzt werden.
            silence();
        }
    }

    public static void applyFile(Path file) {
        if (!posixSupported(file)) {
            return;
        }
        try {
            Files.setPosixFilePermissions(file, FILE_MODE);
        } catch (IOException | UnsupportedOperationException ex) {
            silence();
        }
    }

    /** Schreibt Bytes und setzt 0600. */
    public static void writeFile(Path file, byte[] content) throws IOException {
        Files.write(file, content);
        applyFile(file);
    }

    private static void silence() {
        // Bewusst leer: fehlende POSIX-Unterstuetzung ist kein Fehlerfall.
    }
}
