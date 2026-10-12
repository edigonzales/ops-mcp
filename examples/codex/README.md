# Codex als Diagnoseclient (AGI Ops-Agent)

Diese Konfiguration ist die **Diagnosekonfiguration**. Sie ist bewusst von einer
Coding-Konfiguration getrennt und schränkt den Client auf die sechs `ops_*`-Werkzeuge ein.

## Geprüfter Dokumentationsstand

Die Schlüssel stammen aus der offiziellen Codex-Dokumentation
([MCP](https://learn.chatgpt.com/docs/extend/mcp), abgerufen am 12. Oktober 2026):

| Schlüssel | Bedeutung laut Dokumentation |
|---|---|
| `[mcp_servers.<name>]` | Tabelle für einen MCP-Server in `~/.codex/config.toml` oder (nur für vertrauenswürdige Projekte) `.codex/config.toml` |
| `command` | Startbefehl des stdio-Servers (Pflicht) |
| `args` | Argumente (optional) |
| `env` / `env_vars` | zu setzende bzw. durchzureichende Umgebungsvariablen |
| `cwd` | Arbeitsverzeichnis |
| `startup_timeout_sec` | Startzeitlimit; Standard 10 s |
| `tool_timeout_sec` | Zeitlimit eines Werkzeugaufrufs; Standard 60 s |
| `enabled_tools` | Allowlist der Werkzeuge |
| `default_tools_approval_mode` | `auto`, `prompt`, `writes`, `approve` |
| `tools.<tool>.approval_mode` | Freigabeverhalten je Werkzeug |

Zusätzlich liest Codex das MCP-`instructions`-Feld aus der Initialisierung. Der Server liefert
es (siehe `McpStdioServer.instructions()`).

## Zeitlimit

`tool_timeout_sec` muss **über** dem konfigurierten Collect-Timeout liegen, mit Reserve:
`limits.collectTimeoutSeconds: 90` ⇒ `tool_timeout_sec: 120`.

## Dateien

| Datei | Zweck |
|---|---|
| [`config.toml`](config.toml) | MCP- und Werkzeugkonfiguration für den Diagnosemodus |
| [`diagnose-prompt.md`](diagnose-prompt.md) | Auftragstext für einen Diagnoselauf |

## Verwendung (Fixturebetrieb)

```bash
# 1. Bauen (einmalig)
./gradlew installDist

# 2. Konfiguration anpassen: den absoluten Pfad des Checkouts eintragen
#    (Platzhalter /ABSOLUTER/PFAD/ZUM/ops-mcp ersetzen)

# 3. In Codex pruefen, welche Server und Werkzeuge aktiv sind
codex mcp list

# 4. Diagnoselauf mit dem Prompt aus diagnose-prompt.md
```

## Sicherheitsgrenze – bitte beachten

1. Dieses Profil ist **kein** Coding-Profil. Für Entwicklungsarbeit am Repository gilt
   `AGENTS.md`; dieses Profil ist ausschliesslich für den Diagnosebetrieb gedacht.
2. Die Datei- und Shell-Sandbox des Clients ist **keine** Sicherheitsgrenze für den extern
   gestarteten MCP-Prozess oder für entfernte Systeme. Die erlaubte lokale Berichtsablage
   kontrolliert **ausschliesslich** der MCP-Server.
3. `enabled_tools` und `approval_mode` sind Hinweise an den Client. Die technische Grenze
   liegt in den festen Operationen des MCP-Servers und in den Rechten der technischen
   Identitäten.
4. Ein nicht eingeschränktes Coding-Profil darf **nicht** als sicherer Live-Diagnosemodus
   ausgegeben werden. Wenn freie Shell, allgemeine Edits oder weitere Schreib-MCPs aktiv sind,
   ist das Diagnoseprofil nicht freigegeben (S12).

## Nachweisstatus in dieser Auslieferung

In der Entwicklungsumgebung war **kein** Codex-Client installiert. Die Konfiguration stammt
aus der oben genannten offiziellen Dokumentation; die clientseitige Wirksamkeit
(Toolliste, Negativversuch mit einem nicht erlaubten Werkzeug, tatsächlicher Diagnoselauf)
wurde **nicht** verifiziert. Dieser Nachweis ist in [`docs/abnahme.md`](../../docs/abnahme.md)
als **offen** geführt.
