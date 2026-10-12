package ch.so.agi.ops.domain.git;

import java.util.Objects;

/**
 * Ergebnis des Lesens einer einzelnen erlaubten Datei.
 *
 * @param relativePath   Pfad relativ zum Repositorywurzelverzeichnis
 * @param role           TEMPLATE, PARAMETERS, REFERENCE_ONLY oder OTHER
 * @param status         Ergebnisstatus
 * @param lines          bereinigte Zeilen, soweit gelesen
 * @param byteSize       Groesse der gelesenen Datei
 * @param sha256         Hash des bereinigten Inhalts
 * @param commit         Commit, aus dem der Inhalt gelesen wurde
 * @param note           Klartextnotiz, insbesondere bei Fehlern
 */
public record FileReadResult(
        String relativePath,
        FileRole role,
        FileStatus status,
        java.util.List<String> lines,
        Long byteSize,
        String sha256,
        String commit,
        String note) {

    public enum FileRole {
        TEMPLATE("template"),
        PARAMETERS("parameters"),
        /** Zusaetzlich gelesene Datei des heutigen Stands; ausdruecklich kein historischer Sollzustand. */
        REFERENCE_ONLY("current_reference_only"),
        OTHER("other");

        private final String wireName;

        FileRole(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public enum FileStatus {
        READ,
        NOT_PRESENT,
        NOT_ALLOWED,
        AMBIGUOUS,
        PARSE_ERROR,
        UNSUPPORTED_TYPE;

        public boolean isGap() {
            return this != READ;
        }
    }

    public FileReadResult {
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(status, "status");
        lines = lines == null ? java.util.List.of() : java.util.List.copyOf(lines);
        note = note == null ? "" : note;
    }

    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("path", relativePath);
        map.put("role", role.wireName());
        map.put("status", status.name());
        map.put("lineCount", lines.size());
        map.put("byteSize", byteSize);
        map.put("sha256", sha256);
        map.put("commit", commit);
        if (!note.isBlank()) {
            map.put("note", note);
        }
        return map;
    }
}
