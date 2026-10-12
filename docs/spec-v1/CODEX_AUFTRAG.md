# Implementierungsauftrag an Codex: AGI Ops-Agent V1

## 1. Auftrag

Implementiere in diesem Repository die erste nutzbare Version eines **read-only Betriebsdiagnose-Agenten** für das Amt für Geoinformation des Kantons Solothurn.

Die vollständigen Anforderungen stehen in:

1. [SPEZIFIKATION_V1.md](SPEZIFIKATION_V1.md)
2. [SCHNITTSTELLEN.md](SCHNITTSTELLEN.md)
3. [ABNAHME.md](ABNAHME.md)

[QUELLEN.md](QUELLEN.md) enthält die technischen Primärquellen. Die Quellen sind Referenzen, keine Erlaubnis, Live-Systeme zu untersuchen.

**Liefere eine Implementierung, nicht nur einen Architekturvorschlag.** Halte die Lösung klein: eine Java-Anwendung mit CLI und stdio-MCP, einer gemeinsamen Fachlogik und getrennten Adaptern. Kein eigener LLM-Client, kein Webfrontend und keine neue Betriebsplattform.

## 2. Woran die Version gemessen wird

Ein Diagnose-Agent in Codex oder OpenCode kann einen konkreten fehlgeschlagenen Modelfinder-Deploymentlauf untersuchen. Er verbindet Jenkins, den passenden OpenShift-Workload, dessen Containerzustände/Events/Logs und die zugehörige Konfiguration. Er beschreibt einen nachgewiesenen oder wahrscheinlichen Fehler mit nachvollziehbaren Belegen. Er unterscheidet Fakten, Hypothesen und offene Punkte.

Die Anwendung sammelt und normalisiert die Belege **deterministisch**. Das LLM übernimmt die fallbezogene Interpretation. Eine hart codierte Modelfinder-Antwort, ein Schlüsselwort-Matcher als angeblich vollständiger Agent oder ein Textbericht ohne echte Tool-Aufrufe erfüllen den Auftrag nicht.

## 3. Arbeitsbedingungen

- Java 25, Gradle mit Groovy DSL und eingechecktem Wrapper. Eine mit Java 25 kompatible stabile Wrapper-Version fest pinnen, keine dynamischen Versionsangaben. Offizielles Java-MCP-SDK verwenden; die konkret verwendete Version dokumentieren.
- Zuerst die vorhandene Repository-Struktur prüfen. Bestehende fremde Dateien und Konfigurationen erhalten. Nicht voraussetzen, dass das Repository leer ist.
- Die Quellstruktur bevorzugt in einem Gradle-Projekt mit klaren Java-Packages organisieren. Keine unnötige Aufteilung in mehrere Dienste oder fünf MCP-Server.
- Das Muster des bestehenden Datenportal-Themenintegrators darf als Vorbild dienen. Keine Docker-, GRETL-, Modellierungs- oder Publikationsabhängigkeiten daraus übernehmen.
- Während dieser Implementierung ausschliesslich synthetische Daten, lokale Testserver und Fake-Prozesse verwenden. Ein vorhandenes `oc`, eine `kubeconfig` oder ein Firmen-Proxy sind **keine Erlaubnis für Live-Aufrufe**.
- Keine Zugangsdaten anfordern, ins Repository schreiben oder in Testausgaben ausgeben. Für den späteren manuellen WSL-Test nur die benötigten lokalen Konfigurationsfelder dokumentieren.
- Versionsabhängige Codex-/OpenCode-Konfiguration anhand offizieller Dokumentation und der tatsächlich getesteten Client-Version prüfen. Keine erfundenen Konfigurationsschlüssel liefern. Ohne installierten Client den betreffenden Nachweis als offen markieren.
- Automatische Download-/Buildschritte dürfen für öffentliche Buildabhängigkeiten notwendig sein. Der eigentliche Fixturebetrieb und die deterministischen Tests dürfen nicht von Internet, Firmennetz, Docker, `oc`, Jenkins oder einem LLM-Zugang abhängen.

## 4. Implementierung in sechs Arbeitspaketen

### P1 – Grundgerüst und Verträge

Implementiere Launcher, CLI-Grundstruktur, Konfigurationsvalidierung, Domänenmodelle, Fehlercodes und Schemas. Unbekannte Konfigurationsschlüssel und ungelöste Live-Pflichtangaben werden erkannt. Trenne den Konfigurationsmodus `fixture` von `live`; keine automatische Umschaltung bei Fehlern.

Ergebnis: `./gradlew test` und `./gradlew installDist` funktionieren. Der Launcher startet das gebaute Programm, nicht bei jedem MCP-Aufruf einen Gradle-Build.

### P2 – Durchgehender Fixturefall ohne LLM

Implementiere ein synthetisches Fehlerszenario mit Jenkins-Lauf, Deployment/ReplicaSet/Pods, Events, aktuellen/vorherigen Container-Logs und einem temporären Git-Repository. Verwende dieselben Parser und Normalisierer wie für die Live-Adapter. Implementiere Snapshot, Evidence-IDs, Herkunft, Redaktion und einen deterministischen Evidence-Überblick.

Ergebnis: `collect` erzeugt einen lesbaren, bereinigten Snapshot. Er heisst noch nicht „LLM-Diagnose“.

### P3 – Echte read-only Adapter

Implementiere `OcClusterReader`, `JenkinsHttpReader` und `LocalGitConfigurationReader` gegen die definierten Interfaces. Teste sie mit einem Fake-`oc`, lokalen HTTP-Testservern und temporären Git-Repositories. Berücksichtige insbesondere Proxy/TLS, Folder-Jobs in Jenkins, unterschiedliche Workload-Typen, Owner-UIDs, Container-Neustarts und die korrekte Git-Revision.

Ergebnis: Die produktiven Codepfade existieren und werden durch Transport-/Parser-Tests ausgeführt. Es sind keine Adapter, die im Live-Modus unbemerkt Fixtures zurückgeben.

### P4 – MCP und Agent-Anweisungen

Implementiere die sechs definierten MCP-Werkzeuge, deren Eingabe-/Ausgabeschemas und einen echten stdio-Integrationstest über einen MCP-Client. Liefere eine Diagnoseanweisung für Codex und OpenCode sowie validierbare Beispielkonfigurationen.

Der Diagnosemodus hat keine freie Shell, keine allgemeinen Schreibwerkzeuge, keine Websuche und keine anderen MCPs. Er darf nur die vorgesehenen Betriebswerkzeuge verwenden. Die Sicherheitsgrenze im MCP und die Systemrechte bleiben unabhängig von den Client-Einstellungen notwendig.

Ergebnis: Ein Client kann Anwendungen auflösen, einen Snapshot sammeln, Belege lesen und einen validierten Bericht ausschliesslich im vorgesehenen lokalen Ausgabebereich speichern.

### P5 – Gegenfälle und Sicherheitsnachweise

Implementiere die Pflichtszenarien und Negativtests aus der Abnahme. Insbesondere darf „Readiness failed“ nicht als bewiesene Neustartursache ausgegeben werden. Fehlende historische Daten dürfen nicht durch den heutigen Zustand oder den aktuellen `main`-Branch ersetzt werden.

Ergebnis: Positive Diagnosebelege und begründetes Nichtwissen sind beide testbar. Tokens, Secrets, Pfadmanipulation, fremde URLs und Befehlsinjektion sind in den definierten Angriffstests gesperrt.

### P6 – Übergabe

Liefere einen verständlichen lokalen Quickstart, WSL-Live-Anleitung, Konfigurationsreferenz, Schema-/Toolreferenz und `docs/abnahme.md`. Dokumentiere getestete Versionen, genaue Testbefehle und verbleibende offene Nachweise.

Eine optionale echte LLM-Abnahme benötigt einen ausdrücklich bereitgestellten, freigegebenen Client/Modellzugang. Führe sie nur mit synthetischen Daten aus. Fehlt dieser Zugang, markiere sie als `NOT_RUN`, nicht als bestanden.

## 5. Erwartete Lieferobjekte im implementierten Repository

Die Implementierung darf die Dateien sinnvoll gliedern; folgende Inhalte müssen vorhanden sein:

```text
AGENTS.md                              # Regeln für die Entwicklung dieses Repos
build.gradle / settings.gradle
gradlew / gradlew.bat / gradle/wrapper/
bin/ops-agent
config/fixture.example.yaml
config/live.example.yaml               # ohne Credentials, mit offenen Zuordnungen
agents/ops-diagnose.md                  # harness-unabhängiger Diagnoseablauf
examples/codex/                         # geprüfte MCP-/Diagnosekonfiguration
examples/opencode/                      # geprüfte MCP-/Diagnosekonfiguration
runbooks/deployment-startup.md
schemas/config-v1.schema.json
schemas/snapshot-v1.schema.json
schemas/diagnosis-v1.schema.json
src/main/java/ch/so/agi/ops/...
src/test/...
docs/anwenderhandbuch.md
docs/entwicklerhandbuch.md
docs/abnahme.md
```

Die Implementierungsregeln in `AGENTS.md` dürfen nicht versehentlich als Laufzeit-Anweisung für den Diagnose-Agenten dienen. Der Diagnose-Workspace erhält die gesonderten Regeln aus `agents/ops-diagnose.md` und keine Aufforderung, Code oder Clusterkonfiguration zu ändern.

## 6. Definition of Done

Die technische V1 ist erst fertig, wenn die verpflichtenden deterministischen Tests und ein echter MCP-Protokolltest grün sind, die Live-Adapter implementiert sind und die Dokumentation ihre Verwendung erklärt. Zusätzlich sind zwei Nachweise gesondert auszuweisen:

- **Agentenabnahme:** Ein tatsächlicher Codex-/OpenCode-Lauf hat synthetische Belege über MCP untersucht und einen korrekten Bericht erzeugt.
- **Live-Abnahme:** Ein autorisierter Mensch hat in WSL einen echten konfigurierten Deploymentlauf ausschliesslich lesend untersucht.

Ohne diese Nachweise lautet der Status beispielsweise „Implementierung und MCP-Verträge getestet; Agentenabnahme/Live-Abnahme offen“. Die erfolgreiche Erzeugung eines Mock-Berichts ist kein Ersatz.

## 7. Abschlussmeldung des Coding-Agenten

Beschreibe am Ende kurz:

1. Was implementiert wurde und wie man es lokal startet.
2. Welche Testbefehle tatsächlich ausgeführt wurden und ihr Ergebnis.
3. Ob ein echter Agentenlauf stattgefunden hat, mit welcher Client-Version und welchem Datenmodus.
4. Welche lokalen Angaben für den ersten WSL-Live-Test noch fehlen.
5. Welche Einschränkungen verbleiben; keine unbelegten Aussagen wie „produktionsbereit“.

Keine produktiven Ressourcen anlegen, ändern oder löschen. Keine PRs in den untersuchten Konfigurationsrepositories erstellen. Keine Netzwerkbrücke vom privaten Mac ins Firmennetz einrichten. V1 endet bei einem nachvollziehbaren Diagnosebericht.
