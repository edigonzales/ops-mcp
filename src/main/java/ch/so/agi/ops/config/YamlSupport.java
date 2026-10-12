package ch.so.agi.ops.config;

import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.LoadSettingsBuilder;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Zentrale Parser-Konfiguration fuer Konfiguration und Fixture-/Quell-YAML.
 *
 * <p>Alle Parser sind begrenzt: Tiefe, Zeichenkettenlaenge, Dokumentgroesse und Aliaszahl.
 * Es findet <em>keine</em> Objektinstanziierung aus YAML statt; globale Tags (insbesondere
 * {@code !!java...}) werden vor dem Parsen abgewiesen.</p>
 */
public final class YamlSupport {

    public static final int MAX_DEPTH = 40;
    public static final int MAX_STRING_LENGTH = 1_000_000;
    public static final long MAX_DOCUMENT_LENGTH = 64L * 1024 * 1024;
    public static final int MAX_ALIASES_FOR_COLLECTIONS = 20;
    public static final int MAX_CODE_POINTS = 64 * 1024 * 1024;

    private YamlSupport() {
    }

    /** Strikter YAML-Mapper: doppelte Schluessel und rekursive Aliasse ausgeschlossen. */
    public static YAMLMapper strictMapper() {
        LoadSettingsBuilder settings = LoadSettings.builder()
                .setAllowDuplicateKeys(false)
                .setAllowRecursiveKeys(false)
                .setMaxAliasesForCollections(MAX_ALIASES_FOR_COLLECTIONS)
                .setCodePointLimit(MAX_CODE_POINTS)
                .setLabel("ops-agent");
        YAMLFactory factory = YAMLFactory.builder()
                .loadSettings(settings.build())
                .streamReadConstraints(readConstraints())
                .build();
        return YAMLMapper.builder(factory).build();
    }

    /** Begrenzter JSON-Mapper fuer lokale Artefakte, Quellantworten und Toolausgaben. */
    public static JsonMapper jsonMapper() {
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(readConstraints())
                .build();
        return JsonMapper.builder(factory).build();
    }

    private static StreamReadConstraints readConstraints() {
        return StreamReadConstraints.builder()
                .maxNestingDepth(MAX_DEPTH)
                .maxStringLength(MAX_STRING_LENGTH)
                .maxDocumentLength(MAX_DOCUMENT_LENGTH)
                .build();
    }

    /**
     * Erkennt YAML-Tags, die eine Objektinstanziierung ausloesen koennten. Solche Dateien
     * werden abgewiesen, nicht "best effort" geparst.
     */
    public static boolean containsUnsafeTags(String text) {
        if (text == null) {
            return false;
        }
        return text.contains("!!java") || text.contains("!!javax") || text.contains("!!jdk")
                || text.contains("!!python") || text.contains("!!ruby") || text.contains("!!com.")
                || text.contains("!!org.") || text.contains("!!net.");
    }

    /** Kurze, redigierte Beschreibung eines Parserfehlers ohne Dateiinhalt. */
    public static String describeParseError(Throwable error) {
        String message = error.getMessage();
        if (message == null) {
            return error.getClass().getSimpleName();
        }
        String singleLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        return singleLine.length() > 300 ? singleLine.substring(0, 300) + "..." : singleLine;
    }
}
