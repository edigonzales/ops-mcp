# AGI Ops-Agent – Spezifikation und Codex-Auftrag für V1

**Stand:** 12. Oktober 2026  
**Status:** Implementierungsauftrag; noch keine implementierte oder im Firmennetz abgenommene Software.  
**Vorgeschlagener Repository-Name:** `sogis/ops-agent` – zunächst privat.

## Das Ergebnis dieser ersten Version

Ein Benutzer fragt in Codex oder OpenCode:

> Untersuche das fehlgeschlagene Modelfinder-Deployment in der angegebenen Umgebung und den angegebenen Jenkins-Lauf. Erkläre mit Belegen, warum es gescheitert ist. Verändere nichts an unseren Systemen.

Ein lokaler Java-MCP-Server sammelt begrenzte, bereinigte Informationen aus OpenShift, dem Deployment-Jenkins und einem lokalen Checkout von `sogis/openshift-templates`. Der Agent verbindet diese Informationen und erstellt einen deutschsprachigen Diagnosebericht mit Quellen, Unsicherheiten und einem **nicht ausgeführten** Korrekturvorschlag.

V1 muss insbesondere erkennen können, dass eine Anwendung während ihrer Startphase durch eine zu knapp konfigurierte Liveness-Prüfung neu gestartet wird. Sie muss diesen Fall aber auch von reinen Readiness-Problemen, einem falschen Probe-Endpunkt, einem echten Anwendungsabsturz und fehlenden Belegen unterscheiden. Die Diagnose darf nicht vorprogrammiert auf „Probe zu kurz“ hinauslaufen.

**Read-only bezieht sich auf die untersuchten Systeme.** Lokale, bereinigte Snapshots und Berichte dürfen geschrieben werden. Es gibt keine Änderungen im Cluster, in Jenkins oder in den untersuchten Repositories.

## Dateien und Lesereihenfolge

| Datei | Zweck |
|---|---|
| [CODEX_AUFTRAG.md](CODEX_AUFTRAG.md) | Direkter Arbeitsauftrag, Implementierungsreihenfolge und Fertigstellungskriterien für einen Coding-Agenten. |
| [SPEZIFIKATION_V1.md](SPEZIFIKATION_V1.md) | Verbindlicher Funktionsumfang, Architektur, Diagnoseverhalten, Sicherheit und Erweiterungsgrenzen. |
| [SCHNITTSTELLEN.md](SCHNITTSTELLEN.md) | Konfiguration, CLI, MCP-Werkzeuge, Datenverträge, Fehler- und Berichtsschema. |
| [ABNAHME.md](ABNAHME.md) | Synthetische Szenarien, Sicherheitsprüfungen, echte Agentenabnahme und manueller Live-Nachweis in WSL. |
| [QUELLEN.md](QUELLEN.md) | Offizielle technische Referenzen und Abgrenzung gegenüber nicht überprüften Betriebsdetails. |

Die Spezifikation definiert das **Was**, die Schnittstellen das **Wie an den Grenzen**, die Abnahme den **Nachweis**. Der Codex-Auftrag legt die Arbeitsreihenfolge fest. Bei einem echten Widerspruch eine gezielte Entscheidung dokumentieren; Sicherheitsanforderungen nicht stillschweigend lockern.

## Startprompt für Codex

Die Dateien beispielsweise unter `docs/spec-v1/` in den neuen oder vorhandenen Checkout kopieren. Dann diesen Auftrag geben:

```text
Lies docs/spec-v1/CODEX_AUFTRAG.md und die dort verlinkten Spezifikationen.
Implementiere die erste Version des AGI Ops-Agenten vollständig gemäss diesem
Auftrag. Beginne mit der synthetischen, offline testbaren vertikalen Strecke.
Implementiere anschliessend die echten read-only Adapter für oc, Jenkins
und einen lokalen Git-Checkout sowie den stdio-MCP-Server.

Verwende keine echten Zugangsdaten und greife während der Implementierung
nicht auf unsere produktiven Systeme zu. Erfinde keine Modelfinder-Jobpfade,
Namespaces, Konfigurationsdateien oder Prüfergebnisse. Fehlende Live-Angaben
gehören in eine lokale Konfiguration, nicht als Annahmen in den Code.

Liefere lauffähigen Code, Tests, Beispielkonfigurationen, Agent-Anweisungen,
Anwenderdokumentation und einen ehrlichen Abnahmestand. Eine nur geplante
oder gemockte Funktion darf nicht als real getestet bezeichnet werden.
```

## Bewusste Beschränkung

V1 läuft lokal: Entwicklung auf dem Mac mit synthetischen Daten, Live-Diagnose auf dem Firmen-Laptop in WSL2. Noch kein Deployment des Agenten nach OpenShift, kein Hintergrunddienst, keine automatische Reparatur, kein OpenProject-Schreibzugriff und keine GRETL-/Logit.io-Integration. Die Architektur hält diese Erweiterungen offen, implementiert sie aber nicht vorsorglich.

Die tatsächlichen Modelfinder-Job-, Namespace- und Template-Zuordnungen fehlen noch. Das blockiert die Entwicklung mit Fixtures **nicht**, aber einen als erfolgreich ausgewiesenen Live-Test.
