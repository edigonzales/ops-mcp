# AGENTS.md – Regeln für die Entwicklung dieses Repositories

Diese Datei richtet sich an **Entwicklungsagenten und Menschen, die am Code arbeiten**.
Sie ist **keine** Laufzeit-Anweisung für den Diagnose-Agenten. Die Regeln für den
Diagnose-Betrieb stehen in [`agents/ops-diagnose.md`](agents/ops-diagnose.md).

## 1. Was dieses Repository ist

Ein read-only Betriebsdiagnose-Agent für OpenShift-Deployments des Amts für Geoinformation
des Kantons Solothurn. Eine Java-Anwendung mit CLI und stdio-MCP, gemeinsamer Fachlogik und
getrennten Adaptern. Die verbindlichen Anforderungen stehen in
[`docs/spec-v1/`](docs/spec-v1/README.md):

| Datei | Rolle |
|---|---|
| [`SPEZIFIKATION_V1.md`](docs/spec-v1/SPEZIFIKATION_V1.md) | Was gebaut wird (MUST/SHOULD/OUT) |
| [`SCHNITTSTELLEN.md`](docs/spec-v1/SCHNITTSTELLEN.md) | Verträge an den Grenzen |
| [`ABNAHME.md`](docs/spec-v1/ABNAHME.md) | Nachweise, Szenarien, Sicherheitstests |
| [`CODEX_AUFTRAG.md`](docs/spec-v1/CODEX_AUFTRAG.md) | Arbeitsauftrag und Lieferobjekte |

## 2. Harte Regeln

1. **Read-only gegenueber den untersuchten Systemen.** Kein `apply`, `patch`, `delete`,
   `scale`, `rollout restart`, `exec`, `attach`, Port-Forwarding, Secrets lesen,
   Jenkins starten/stoppen/konfigurieren, Git schreiben.
2. **Keine Live-Zugriffe in Entwicklung und Test.** Synthetische Daten, lokale Testserver
   und Fake-Prozesse. Ein vorhandenes `oc`, eine `kubeconfig` oder ein Firmen-Proxy sind
   **keine** Erlaubnis. Tests duerfen keine Firmen-DNS-Aufloesung versuchen.
3. **Keine Zugangsdaten** anfordern, ins Repository schreiben, loggen oder in Testausgaben
   ausgeben. Keine echten Logkopien, Benutzerkennungen oder Tokens in Fixtures.
4. **Keine erfundenen Betriebsangaben.** Unbekannte Jobpfade, Namespaces, Workloads,
   Container, Dateipfade und Commit-Hashes gehoeren in die lokale Konfiguration oder in
   Fixtures, nie als Annahme in den Produktivcode.
5. **Kein Modelfinder-Sonderfall.** Kein `if (applicationId.equals("modelfinder"))` in der
   Fachlogik. Modelfinder ist Konfiguration und Fixture.
6. **Kein eigener LLM-Aufruf im Java-Prozess.** Codex/OpenCode ist die Agentenlaufzeit.
7. **Nur die sechs definierten MCP-Werkzeuge** und keine freien Shell-, HTTP-, SQL-, Git-
   oder Dateiwerkzeuge.
8. **stdout ist ausschliesslich MCP-JSON-RPC.** Logs gehen nach stderr und werden redigiert.
9. **Aussagen belegen oder als offen markieren.** Keine Prozent-Konfidenzen, keine
   erfundenen Zeitstempel, keine Kausalaussage ohne Beleg.
10. **Keine Dokumentation erfinden.** Nicht getestete Funktionen werden als offen
    ausgewiesen, nicht als geprüft.

## 3. Technische Festlegungen

- **Java 25**, **Gradle 9.8.1** (Groovy DSL, eingecheckter Wrapper), feste
  Dependency-Versionen ohne dynamische Angaben. Versionen stehen in
  [`gradle/libs.versions.toml`](gradle/libs.versions.toml).
- **Offizielles MCP-Java-SDK** für Protokoll und stdio-Transport. Keine eigene
  JSON-RPC-Implementierung.
- **Keine Spring-Boot-Abhaengigkeit.** Eine kleine CLI genuegt.
- **Parser begrenzen:** Tiefe, Zeichenkettenlaenge, Dokumentgroesse, Aliaszahl. Keine
  Objektinstanziierung aus YAML.
- **Fachlogik liegt in `application`**, nicht in MCP-Handlern oder CLI-Klassen. CLI und MCP
  benutzen dieselben Application Services.
- Neue fachliche Faehigkeiten zuerst als kleines Interface in
  `ch.so.agi.ops.application.port`, dann als Adapter.

## 4. Package-Struktur

```text
ch.so.agi.ops.domain            Domaenenmodelle, Fehlercodes, Budget
ch.so.agi.ops.domain.k8s        normalisierte Kubernetes-/OpenShift-Objekte
ch.so.agi.ops.domain.jenkins    normalisierte Jenkins-Objekte
ch.so.agi.ops.domain.git        normalisierte Konfigurationswerte und Revisionen
ch.so.agi.ops.config            Konfigurationsmodell, strenger Loader, Validierung
ch.so.agi.ops.security          Redaktion, Pfad-/Scope-Grenzen, kubeconfig-Pruefung
ch.so.agi.ops.util              kleine Hilfsmittel (Zeit, Versionen, Prozesse, Hashes)
ch.so.agi.ops.adapter.openshift OcClusterReader und Parser
ch.so.agi.ops.adapter.jenkins   JenkinsHttpReader und Parser
ch.so.agi.ops.adapter.git       LocalGitConfigurationReader
ch.so.agi.ops.adapter.fixture   Fixtureadapter mit denselben Parsern
ch.so.agi.ops.application       Application Services, Target Registry, Collector
ch.so.agi.ops.application.port  Interfaces zu den Datenquellen und zur Persistenz
ch.so.agi.ops.evidence          Snapshot-/Evidence-Persistenz
ch.so.agi.ops.report            Diagnosisvertrag, Validierung, Markdown
ch.so.agi.ops.cli               CLI, Exitcodes, Launcher-Einstieg
ch.so.agi.ops.mcp               stdio-MCP-Server und die sechs Werkzeuge
```

## 5. Arbeitsweise

1. Vor Aenderungen die betroffenen Verträge in `docs/spec-v1/` lesen.
2. Erst Vertrag/Interface, dann Implementierung, dann Test.
3. `./gradlew check` muss gruen sein, bevor eine Aufgabe als fertig gilt.
4. Neue Tests ohne Netz, ohne Firmenzugang, ohne LLM-Schluessel.
5. Bei einem echten Widerspruch in der Spezifikation: gezielte Entscheidung im
   Entwicklerhandbuch dokumentieren. Sicherheitsanforderungen nie stillschweigend lockern.
6. Fixtures nur synthetisch und als Rohantworten, nicht als gewuenschte Enddiagnose.

## 6. Befehle

```bash
./gradlew test installDist          # bauen und deterministische Tests
./bin/ops-agent --config config/fixture.example.yaml doctor
./bin/ops-agent --config config/fixture.example.yaml applications
./bin/ops-agent --config config/fixture.example.yaml collect \
  --application modelfinder --environment integration --build 42
./bin/ops-agent --config config/fixture.example.yaml serve
```

Der Launcher startet das gebaute Programm. Pro MCP-Aufruf darf **kein** Gradle-Build laufen.
