package ch.so.agi.ops.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimaler Argumentparser. Es werden nur die dokumentierten Befehle und Optionen akzeptiert.
 * Es gibt ausdruecklich keine Befehle {@code deploy}, {@code fix}, {@code apply},
 * {@code rollback} oder {@code trigger}.
 */
public final class CliArguments {

    public static final String USAGE = """
            Verwendung:
              ops-agent --config <datei> [--json] doctor
              ops-agent --config <datei> [--json] applications [--application <id>]
              ops-agent --config <datei> [--json] resolve --application <id> --environment <name> \
            (--build <nummer> | --last-failed)
              ops-agent --config <datei> [--json] collect --application <id> --environment <name> \
            (--build <nummer> | --last-failed) [--scenario <id>]
              ops-agent --config <datei> [--json] inspect --snapshot <id>
              ops-agent --config <datei> [--json] report --snapshot <id> --input <datei.json>
              ops-agent --config <datei> serve

            Optionen:
              --config <datei>        explizit gewaehlte YAML-Konfiguration (Pflicht)
              --json                  maschinenlesbare Ausgabe (nicht bei serve)
              --application <id>      Anwendungskennung
              --environment <name>    Umgebungsname
              --build <nummer>        konkrete Buildnummer
              --last-failed           letzten fehlgeschlagenen Build aufloesen
              --scenario <id>         Fixture-Szenario (nur im Modus fixture)
              --snapshot <id>         vorhandene Snapshot-ID
              --input <datei>         lokale JSON-Eingabe fuer report
              --help                  diese Hilfe

            Es gibt keine Befehle zum Aendern, Reparieren, Ausrollen oder Ausloesen.
            """;

    private final Map<String, String> options;
    private final List<String> flags;
    private final List<String> positional;

    private CliArguments(Map<String, String> options, List<String> flags, List<String> positional) {
        this.options = options;
        this.flags = flags;
        this.positional = positional;
    }

    public static CliArguments parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        List<String> flags = new ArrayList<>();
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if (argument == null || argument.isBlank()) {
                continue;
            }
            if (argument.startsWith("--")) {
                String name = argument.substring(2);
                String inlineValue = null;
                int equals = name.indexOf('=');
                if (equals >= 0) {
                    inlineValue = name.substring(equals + 1);
                    name = name.substring(0, equals);
                }
                if (isFlag(name)) {
                    flags.add(name);
                    continue;
                }
                if (!isKnownOption(name)) {
                    throw new CliUsageException("Unbekannte Option --" + name + ".");
                }
                String value = inlineValue;
                if (value == null) {
                    if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                        throw new CliUsageException("Option --" + name + " braucht einen Wert.");
                    }
                    value = args[++i];
                }
                if (options.containsKey(name)) {
                    throw new CliUsageException("Option --" + name + " wurde mehrfach angegeben.");
                }
                options.put(name, value);
            } else {
                positional.add(argument);
            }
        }
        return new CliArguments(options, flags, positional);
    }

    private static boolean isFlag(String name) {
        return switch (name) {
            case "json", "last-failed", "help" -> true;
            default -> false;
        };
    }

    /**
     * Nur die dokumentierten Optionen sind zulaessig. Eine unbekannte Option wird abgewiesen,
     * statt still ignoriert zu werden: Ein Tippfehler darf nicht zu einer stillen
     * Fehlkonfiguration fuehren.
     */
    private static boolean isKnownOption(String name) {
        return switch (name) {
            case "config", "application", "environment", "build", "scenario", "snapshot", "input" -> true;
            default -> false;
        };
    }

    public String option(String name) {
        return options.get(name);
    }

    public String requireOption(String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new CliUsageException("Option --" + name + " ist erforderlich.");
        }
        return value;
    }

    public boolean flag(String name) {
        return flags.contains(name);
    }

    public List<String> positional() {
        return List.copyOf(positional);
    }

    public String command() {
        if (positional.isEmpty()) {
            throw new CliUsageException("Es wurde kein Befehl angegeben.");
        }
        return positional.get(0);
    }

    public Map<String, String> options() {
        return Map.copyOf(options);
    }
}
