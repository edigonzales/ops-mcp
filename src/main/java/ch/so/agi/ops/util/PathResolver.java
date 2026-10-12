package ch.so.agi.ops.util;

import ch.so.agi.ops.config.ConfigException;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aufloesung lokaler Pfadangaben.
 *
 * <p>Unterstuetzt werden ausschliesslich:</p>
 * <ul>
 *   <li>{@code ~} und {@code ~/...} fuer das Benutzerverzeichnis,</li>
 *   <li>{@code $HOME} und {@code ${HOME}},</li>
 *   <li>{@code ${NAME}} fuer eine <em>gesetzte</em> Umgebungsvariable mit einem Namen aus
 *       Grossbuchstaben, Ziffern und Unterstrich.</li>
 * </ul>
 *
 * <p>Es findet <em>keine</em> beliebige Variablensubstitution und <em>keine</em>
 * Shellauswertung statt: Eine nicht gesetzte oder unzulaessig benannte Variable wird
 * abgewiesen. Backticks, {@code $(...)}, Klammerausdruecke und Kommandos sind unzulaessig.</p>
 *
 * <p>Relative Pfade werden relativ zum Verzeichnis der Konfigurationsdatei aufgeloest.</p>
 */
public final class PathResolver {

    /** Zulaessige Form einer Variablenreferenz: ${NAME} mit Grossbuchstaben. */
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]{0,63})\\}");

    private PathResolver() {
    }

    public static Path resolve(Path baseDir, String rawValue, String fieldName) {
        if (rawValue == null) {
            return null;
        }
        String value = rawValue.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.contains("\u0000")) {
            throw ConfigException.invalid(fieldName + ": Pfadangabe enthaelt ein Null-Zeichen.");
        }
        checkForbiddenConstructs(value, fieldName);

        String home = System.getProperty("user.home");
        String expanded = value;
        if (expanded.startsWith("~/")) {
            expanded = home + expanded.substring(1);
        } else if (expanded.equals("~")) {
            expanded = home;
        }
        expanded = expandVariables(expanded, fieldName);
        if (expanded.contains("$")) {
            throw ConfigException.invalid(fieldName
                    + ": nur ~, $HOME und ${NAME} mit einer gesetzten Umgebungsvariable sind als "
                    + "Pfadabkuerzung zulaessig; keine beliebige Variablensubstitution.");
        }
        Path path = Path.of(expanded);
        if (!path.isAbsolute()) {
            if (baseDir == null) {
                throw ConfigException.invalid(fieldName + ": relativer Pfad ohne Basisverzeichnis.");
            }
            path = baseDir.resolve(path);
        }
        return path.normalize();
    }

    private static String expandVariables(String value, String fieldName) {
        if (!value.contains("${")) {
            return value;
        }
        if (value.contains("$HOME")) {
            value = value.replace("$HOME", System.getProperty("user.home"));
        }
        Matcher matcher = VARIABLE.matcher(value);
        StringBuilder result = new StringBuilder();
        Set<String> used = new LinkedHashSet<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = System.getenv(name);
            if (replacement == null || replacement.isBlank()) {
                throw ConfigException.invalid(fieldName + ": die Umgebungsvariable " + name
                        + " ist nicht gesetzt. Gesetzte Variablen: "
                        + String.join(", ", sysenvNames()) + ".");
            }
            used.add(name);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static void checkForbiddenConstructs(String value, String fieldName) {
        if (value.contains("$(") || value.contains("`")) {
            throw ConfigException.invalid(fieldName
                    + ": Kommandosubstitution ist in Pfadangaben nicht zulaessig.");
        }
        if (value.contains("${") && value.contains(":")) {
            // Faengt ${NAME:-default} und aehnliche Shell-Erweiterungen ab, ohne regulaere
            // Windows- oder URL-Pfade zu stoeren.
            Matcher matcher = Pattern.compile("\\$\\{[A-Za-z0-9_]+:").matcher(value);
            if (matcher.find()) {
                throw ConfigException.invalid(fieldName
                        + ": Shell-Erweiterungen wie ${NAME:-default} sind nicht zulaessig.");
            }
        }
    }

    private static Set<String> sysenvNames() {
        Set<String> names = new java.util.TreeSet<>(System.getenv().keySet());
        return names;
    }

    /** Nur fuer Testzwecke: prueft, ob eine Zeichenfolge eine unzulaessige Variablenreferenz enthaelt. */
    public static boolean containsUnsupportedVariable(String value) {
        if (value == null) {
            return false;
        }
        return value.matches(".*\\$\\{(?![A-Z][A-Z0-9_]{0,63}\\}).*");
    }
}
