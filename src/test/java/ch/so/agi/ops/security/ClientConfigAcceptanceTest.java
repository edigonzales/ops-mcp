package ch.so.agi.ops.security;

import ch.so.agi.ops.config.YamlSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S12 – Diagnoseclient mit freier Shell oder zusaetzlichen Schreib-MCPs.
 *
 * <p>Statischer Nachweis der Client-Beispielkonfigurationen: genau ein MCP-Server, genau die
 * sechs {@code ops_*}-Werkzeuge erlaubt, freie Shell- und Netzfaehigkeiten abgelehnt und keine
 * Zugangsdaten in den Beispieldateien. Es wird ausschliesslich Text gelesen; die Dateien sind
 * ausdruecklich keine Laufzeitumgebung dieses Tests.</p>
 */
class ClientConfigAcceptanceTest {

    private static final List<String> WERKZEUGE = List.of("ops_list_applications", "ops_resolve_target",
            "ops_collect_snapshot", "ops_get_snapshot", "ops_read_evidence", "ops_store_report");

    private static Path projektVerzeichnis() {
        String eigenschaft = System.getProperty("opsagent.projectDir");
        if (eigenschaft != null && !eigenschaft.isBlank()) {
            return Path.of(eigenschaft).toAbsolutePath().normalize();
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    private static String leseBeispieldatei(String relativ) {
        Path datei = projektVerzeichnis().resolve(relativ);
        if (!Files.isRegularFile(datei)) {
            Assumptions.abort("Beispieldatei fehlt: " + relativ);
        }
        try {
            return Files.readString(datei, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Assumptions.abort("Beispieldatei nicht lesbar: " + relativ + " (" + ex.getMessage() + ")");
            return "";
        }
    }

    /** Entfernt Kommentarzeilen; die Beispieldateien dokumentieren Verbote in Kommentaren. */
    private static List<String> aktiveZeilen(String text) {
        List<String> zeilen = new ArrayList<>();
        for (String zeile : text.split("\n", -1)) {
            String getrimmt = zeile.trim();
            if (getrimmt.isEmpty() || getrimmt.startsWith("#") || getrimmt.startsWith("//")
                    || getrimmt.startsWith("\"//")) {
                continue;
            }
            zeilen.add(zeile);
        }
        return zeilen;
    }

    private static void assertKeineZugangsdaten(String text, String datei) {
        Pattern zuweisung = Pattern.compile(
                "(?i)[A-Za-z0-9_.\\-]*(?:token|passwor|secret|credential|api[_-]?key)[A-Za-z0-9_.\\-]*\\s*[:=]\\s*(.+)");
        for (String zeile : aktiveZeilen(text)) {
            String ohneKommentar = zeile.replaceAll("\\s+#.*$", "");
            Matcher matcher = zuweisung.matcher(ohneKommentar);
            if (matcher.find()) {
                String wert = matcher.group(1).trim();
                assertTrue(wert.isEmpty() || "[]".equals(wert) || "null".equals(wert),
                        datei + ": Zugangsdaten mit Wert sind nicht zulaessig: " + zeile);
            }
        }
        for (String muster : List.of("Bearer ", "ghp_", "xox", "-----BEGIN", "AKIA", "AIza")) {
            assertFalse(text.contains(muster), datei + ": verdaechtiges Credentialmuster gefunden: " + muster);
        }
    }

    private static Set<String> werkzeugnamenAusToml(String text) {
        Matcher block = Pattern.compile("(?s)enabled_tools\\s*=\\s*\\[(.*?)\\]").matcher(text);
        assertTrue(block.find(), "enabled_tools fehlt in der Codex-Konfiguration.");
        Set<String> namen = new TreeSet<>();
        Matcher eintrag = Pattern.compile("\"([^\"]+)\"").matcher(block.group(1));
        while (eintrag.find()) {
            namen.add(eintrag.group(1));
        }
        return namen;
    }

    private static Set<String> mcpServerAusToml(String text) {
        Set<String> server = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("(?m)^\\[mcp_servers\\.([A-Za-z0-9_-]+)\\]").matcher(text);
        while (matcher.find()) {
            server.add(matcher.group(1));
        }
        return server;
    }

    /** Wertet die OpenCode-Berechtigungen in Dokumentreihenfolge aus (die letzte Regel gewinnt). */
    private static String permission(JsonNode permission, String key) {
        String entscheidung = null;
        for (Map.Entry<String, JsonNode> eintrag : permission.properties()) {
            String muster = eintrag.getKey();
            JsonNode wert = eintrag.getValue();
            if (muster.startsWith("//") || !wert.isString()) {
                continue;
            }
            boolean passt = "*".equals(muster) || muster.equals(key)
                    || (muster.endsWith("*") && key.startsWith(muster.substring(0, muster.length() - 1)));
            if (passt) {
                entscheidung = wert.asString();
            }
        }
        return entscheidung;
    }

    @Test
    @DisplayName("S12: Codex-Profil erlaubt genau einen MCP-Server und genau die sechs Werkzeuge")
    void codexProfilErlaubtGenauEinenServerUndSechsWerkzeuge() {
        String text = leseBeispieldatei("examples/codex/config.toml");

        assertEquals(Set.of("ops-agent"), mcpServerAusToml(text),
                "Es darf genau ein MCP-Server konfiguriert sein.");
        assertEquals(new TreeSet<>(WERKZEUGE), werkzeugnamenAusToml(text),
                "enabled_tools muss genau die sechs ops_*-Werkzeuge listen.");
        assertTrue(text.contains("default_tools_approval_mode"), "Die Freigabevorgabe muss dokumentiert sein.");
        assertKeineZugangsdaten(text, "examples/codex/config.toml");
    }

    @Test
    @DisplayName("S12: OpenCode-Profil verbietet Shell und Netz und erlaubt nur die sechs Werkzeuge")
    void openCodeProfilVerbietetShellUndNetz() {
        String text = leseBeispieldatei("examples/opencode/opencode.json");
        JsonNode root = YamlSupport.jsonMapper().readTree(text);

        JsonNode mcp = root.get("mcp");
        assertNotNull(mcp, "Der mcp-Abschnitt fehlt.");
        Set<String> server = new TreeSet<>();
        mcp.properties().forEach(eintrag -> server.add(eintrag.getKey()));
        assertEquals(Set.of("ops-agent"), server, "Es darf genau ein MCP-Server konfiguriert sein.");

        JsonNode permission = root.get("permission");
        assertNotNull(permission, "Der permission-Abschnitt fehlt.");
        assertEquals("deny", permission(permission, "*"), "Die Voreinstellung muss deny sein.");
        for (String verboten : List.of("bash", "edit", "webfetch", "websearch")) {
            assertEquals("deny", permission(permission, verboten),
                    "Die Faehigkeit '" + verboten + "' muss ausdruecklich verboten sein.");
        }
        for (String werkzeug : WERKZEUGE) {
            assertEquals("allow", permission(permission, "ops-agent_" + werkzeug),
                    "Das Werkzeug '" + werkzeug + "' muss erlaubt sein.");
        }
        assertEquals("deny", permission(permission, "write"), "Nicht genannte Faehigkeiten bleiben verboten.");
        assertEquals("deny", permission(permission, "task"));
        assertKeineZugangsdaten(text, "examples/opencode/opencode.json");
    }

    @Test
    @DisplayName("S12: keine Beispieldatei enthaelt Zugangsdaten oder freie Shellbefehle")
    void keineBeispieldateiEnthaeltZugangsdaten() {
        for (String datei : List.of("examples/codex/config.toml", "examples/opencode/opencode.json")) {
            String text = leseBeispieldatei(datei);
            assertKeineZugangsdaten(text, datei);
            String klein = text.toLowerCase(Locale.ROOT);
            for (String muster : List.of("oc delete", "oc apply", "kubectl apply", "rollout restart", "sudo ")) {
                assertFalse(klein.contains(muster), datei + ": verbotener Befehl im Beispiel: " + muster);
            }
        }
    }
}
