# OpenCode als Diagnoseclient (AGI Ops-Agent)

Diese Konfiguration ist die **Diagnosekonfiguration**. Sie ist bewusst von einer
Coding-Konfiguration getrennt und schränkt den Client auf die sechs `ops_*`-Werkzeuge ein.

## Geprüfter Dokumentationsstand

Die Schlüssel stammen aus der offiziellen OpenCode-Dokumentation
([MCP servers](https://opencode.ai/docs/mcp-servers/), [Permissions](https://opencode.ai/docs/permissions/),
[Tools](https://opencode.ai/docs/tools/), abgerufen am 12. Oktober 2026):

| Schlüssel | Bedeutung laut Dokumentation |
|---|---|
| `mcp.<name>.type` | `"local"` für einen lokalen Prozess |
| `mcp.<name>.command` | Kommando **und** Argumente als Array |
| `mcp.<name>.environment` | Umgebungsvariablen des Serverprozesses |
| `mcp.<name>.enabled` | Server ein-/ausschalten |
| `mcp.<name>.timeout` | Timeout in ms für das Laden der Werkzeugliste (Standard 5000) |
| `permission.<tool>` | `"allow"`, `"ask"` oder `"deny"` |
| `permission.<tool>.<pattern>` | granulare Regeln; die **letzte passende Regel gewinnt** |
| `permission` mit MCP-Wildcard | z. B. `"mymcp_*": "ask"` |
| `tools` (Legacy) | seit v1.1.1 zugunsten von `permission` veraltet, aber noch unterstützt |

Die MCP-Werkzeuge erscheinen als `<servername>_<toolname>`. `permission` gilt je Werkzeugname,
die globalen Sicherheitswächter heissen `edit` (deckt `edit`, `write`, `apply_patch`), `bash`,
`read`, `glob`, `grep`, `webfetch`, `websearch`, `external_directory`.

## Zeitlimit

`mcp.<name>.timeout` betrifft **nur** das Laden der Werkzeugliste, nicht die Laufzeit eines
Aufrufs. Für die Laufzeit gilt die Einstellung des Modellaufrufs im Client. Der Server begrenzt
seine Arbeit serverseitig über `limits.collectTimeoutSeconds`; das Client-Timeout muss darüber
liegen, mit Reserve (90 s ⇒ mindestens 120 s).

## Dateien

| Datei | Zweck |
|---|---|
| [`opencode.json`](opencode.json) | MCP- und Berechtigungskonfiguration für den Diagnosemodus |
| [`diagnose-prompt.md`](diagnose-prompt.md) | Auftragstext für einen Diagnoselauf |

## Verwendung (Fixturebetrieb)

```bash
# 1. Bauen (einmalig)
./gradlew installDist

# 2. opencode.json anpassen: absolute Pfade eintragen
#    (Platzhalter /ABSOLUTER/PFAD/ZUM/ops-mcp ersetzen)

# 3. Server und Werkzeuge pruefen:  /mcp  in der TUI

# 4. Diagnoselauf mit dem Prompt aus diagnose-prompt.md
```

## Sicherheitsgrenze – bitte beachten

1. Dieses Profil ist **kein** Coding-Profil. Für Entwicklungsarbeit am Repository gilt
   `AGENTS.md`.
2. `edit: "deny"`, `bash: "deny"`, `webfetch: "deny"`, `websearch: "deny"` und die
   `ops-agent_*`-Regeln sind **Client-Einstellungen**. Sie sind Hinweise und keine technische
   Zugriffskontrolle; die technische Grenze liegt im MCP-Server und in den Rechten der
   Identitäten.
3. Die lokale Write-Sandbox des Clients ist **keine** Sicherheitsgrenze für den extern
   gestarteten MCP-Prozess oder für entfernte Systeme. Die erlaubte lokale Berichtsablage
   kontrolliert **ausschliesslich** der MCP-Server.
4. `--auto` (bzw. „Enable auto-approve permissions“) darf im Live-Diagnosemodus **nicht**
   verwendet werden; explizite `deny`-Regeln bleiben dort zwar wirksam, aber das Profil
   verliert seine Nachvollziehbarkeit.
5. Sind freie Shell, allgemeine Edits oder weitere Schreib-MCPs aktiv, ist das Diagnoseprofil
   **nicht** freigegeben (S12).

## Nachweisstatus in dieser Auslieferung

In der Entwicklungsumgebung war **kein** OpenCode-Client installiert. Die Konfiguration stammt
aus der oben genannten offiziellen Dokumentation; die clientseitige Wirksamkeit (Toolliste,
Negativversuch mit einem nicht erlaubten Werkzeug, tatsächlicher Diagnoselauf) wurde **nicht**
verifiziert. Dieser Nachweis ist in [`docs/abnahme.md`](../../docs/abnahme.md) als **offen**
geführt.
