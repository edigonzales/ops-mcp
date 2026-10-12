package ch.so.agi.ops.security;

import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.OpsError;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pfadgrenzen. Es wird nur innerhalb der vorgesehenen Wurzel-Verzeichnisse gelesen und
 * geschrieben. {@code ..}, absolute Pfade, Symlink-Fluchten und Steuerzeichen werden
 * abgewiesen, unabhaengig davon, ob die Anfrage von einem Modell oder von Hand kommt.
 */
public final class PathSafety {

    private PathSafety() {
    }

    /** Prueft einen relativen, portablen Pfad ohne Traversal. */
    public static boolean isSafeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        if (relativePath.startsWith("/") || relativePath.startsWith("\\")) {
            return false;
        }
        if (relativePath.length() > 512) {
            return false;
        }
        if (relativePath.contains("\u0000") || relativePath.contains("\\")) {
            return false;
        }
        for (String segment : relativePath.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** Loest einen relativen Pfad gegen eine Wurzel auf und prueft, dass er die Wurzel nicht verlaesst. */
    public static Path resolveWithin(Path root, String relativePath) {
        if (!isSafeRelativePath(relativePath)) {
            throw new ScopeViolationException(ErrorCode.SCOPE_DENIED,
                    "Unzulaessiger relativer Pfad: " + describe(relativePath));
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new ScopeViolationException(ErrorCode.SCOPE_DENIED,
                    "Pfad verlaesst den erlaubten Bereich: " + describe(relativePath));
        }
        return resolved;
    }

    /**
     * Prueft zusaetzlich, dass keine Symlink-Flucht vorliegt. Fehlende Endpunkte gelten nicht
     * als Flucht; das spaetere Lesen scheitert dann als "nicht vorhanden".
     */
    public static Path resolveWithinNoSymlinkEscape(Path root, String relativePath) throws IOException {
        Path resolved = resolveWithin(root, relativePath);
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path realRoot = Files.exists(normalizedRoot) ? normalizedRoot.toRealPath() : normalizedRoot;
        Path probe = resolved;
        List<Path> visited = new ArrayList<>();
        while (probe != null && !probe.equals(normalizedRoot)) {
            visited.add(probe);
            if (Files.exists(probe, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(probe)) {
                Path target = probe.toRealPath();
                if (!target.startsWith(realRoot)) {
                    throw new ScopeViolationException(ErrorCode.SCOPE_DENIED,
                            "Symlink verlaesst den erlaubten Bereich: " + describe(relativePath));
                }
            }
            probe = probe.getParent();
        }
        return resolved;
    }

    /** Fehlermeldung ohne vollstaendigen Pfad. */
    public static String describe(String value) {
        if (value == null) {
            return "<leer>";
        }
        String cleaned = value.replace('\n', ' ').replace('\r', ' ');
        if (cleaned.length() > 200) {
            cleaned = cleaned.substring(0, 200) + "...";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            sb.append(c < 0x20 ? '?' : c);
        }
        return sb.toString();
    }

    public static OpsError scopeDenied(String sourceId, String message) {
        return new OpsError(ErrorCode.SCOPE_DENIED, sourceId, message, false,
                "Nur konfigurierte und freigegebene Ziele verwenden.");
    }
}
