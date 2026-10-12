# AGI Ops-Agent

Read-only Betriebsdiagnose für OpenShift-Deployments des **Amts für Geoinformation des
Kantons Solothurn**. Eine Java-Anwendung mit CLI und stdio-MCP, gemeinsamer Fachlogik und
getrennten Adaptern.

Der Agent sammelt für einen konkreten fehlgeschlagenen Deploymentlauf begrenzte, bereinigte
Belege aus Jenkins, OpenShift und einem lokalen Checkout von `sogis/openshift-templates`.
Ein MCP-fähiger Client (Codex, OpenCode) liest diese Belege und formuliert daraus einen
belegten, begrenzten Diagnosebericht mit **nicht ausgeführten** Korrekturvorschlägen.

> **Read-only bezieht sich auf die untersuchten Systeme.** Lokale, bereinigte Snapshots und
> Berichte werden geschrieben. Es gibt **keine** Änderung im Cluster, in Jenkins oder in den
> untersuchten Repositories.

**Status:** technische V1 implementiert; die Implementierungs- und MCP-Vertragstests laufen
lokal. Die **Agentenabnahme (Ebene B)** und die **Live-Abnahme in WSL (Ebene C)** sind offen.
Der verbindliche Stand steht in [`docs/abnahme.md`](docs/abnahme.md). Es wird **nicht**
behauptet, die Software sei produktionsbereit.

---

## Schnellstart (synthetisch, offline)

Voraussetzungen: **Java 25**. Kein Netzwerk, kein `oc`, kein Jenkins, kein LLM-Zugang nötig.

```bash
./gradlew test installDist

./bin/ops-agent --config config/fixture.example.yaml doctor
./bin/ops-agent --config config/fixture.example.yaml applications
./bin/ops-agent --config config/fixture.example.yaml collect \
    --application modelfinder --environment integration --build 42
./bin/ops-agent --config config/fixture.example.yaml serve
```

`bin/ops-agent` startet das **gebaute** Programm; pro MCP-Aufruf läuft **kein** Gradle-Build.

---

## Dokumentation

### Auftrag und Spezifikation

| Datei | Zweck |
|---|---|
| [`docs/spec-v1/CODEX_AUFTRAG.md`](docs/spec-v1/CODEX_AUFTRAG.md) | Arbeitsauftrag und Lieferobjekte |
| [`docs/spec-v1/SPEZIFIKATION_V1.md`](docs/spec-v1/SPEZIFIKATION_V1.md) | verbindlicher Funktionsumfang (MUST/SHOULD/OUT) |
| [`docs/spec-v1/SCHNITTSTELLEN.md`](docs/spec-v1/SCHNITTSTELLEN.md) | Konfiguration, CLI, MCP-Werkzeuge, Datenverträge |
| [`docs/spec-v1/ABNAHME.md`](docs/spec-v1/ABNAHME.md) | Szenarien, Sicherheitsprüfungen, Abnahme |
| [`docs/spec-v1/QUELLEN.md`](docs/spec-v1/QUELLEN.md) | technische Primärquellen |

### Betrieb und Entwicklung

| Datei | Zweck |
|---|---|
| [`docs/anwenderhandbuch.md`](docs/anwenderhandbuch.md) | Quickstart, CLI, `doctor`, Snapshot, **WSL-Live-Anleitung** |
| [`docs/entwicklerhandbuch.md`](docs/entwicklerhandbuch.md) | Architektur, Versionen, Adapter, Sicherheitstests |
| [`docs/abnahme.md`](docs/abnahme.md) | Abnahmestand, getrennt nach Ebenen A/B/C |
| [`agents/ops-diagnose.md`](agents/ops-diagnose.md) | **Laufzeit-Anweisung** für den Diagnose-Agenten |
| [`runbooks/deployment-startup.md`](runbooks/deployment-startup.md) | fachliches Runbook zu Probe-/Startfehlern |
| [`AGENTS.md`](AGENTS.md) | **Entwicklungsregeln** – keine Laufzeit-Anweisung |
| [`examples/codex/`](examples/codex/README.md) | geprüfte Codex-Diagnosekonfiguration |
| [`examples/opencode/`](examples/opencode/README.md) | geprüfte OpenCode-Diagnosekonfiguration |
| [`schemas/`](schemas/) | `config-v1`, `snapshot-v1`, `diagnosis-v1` |
| [`config/fixture.example.yaml`](config/fixture.example.yaml) | sofort lauffähige synthetische Konfiguration |
| [`config/live.example.yaml`](config/live.example.yaml) | Live-Vorlage **ohne** Credentials, mit offenen Zuordnungen |

---

## Die sechs MCP-Werkzeuge

| Werkzeug | Zweck |
|---|---|
| `ops_list_applications` | Anwendungen/Umgebungen mit `configurationStatus` |
| `ops_resolve_target` | Ziel und Buildnummer auflösen, kurzlebiges `targetId` |
| `ops_collect_snapshot` | begrenzten, bereinigten Snapshot lokal schreiben |
| `ops_get_snapshot` | Zusammenfassung, Links, Quellenstatus, Evidence-Index |
| `ops_read_evidence` | redigierter, zeilennummerierter Ausschnitt |
| `ops_store_report` | validierten Bericht lokal speichern |

Es gibt **kein** freies Shell-, HTTP-, SQL-, Git- oder Dateiwerkzeug und **keine** Befehle
`deploy`, `fix`, `apply`, `rollback` oder `trigger`. Details:
[`docs/entwicklerhandbuch.md`](docs/entwicklerhandbuch.md).

---

## Technische Basis

Java 25 · Gradle 9.8.1 (Groovy DSL, eingecheckter Wrapper) · offizielles MCP-Java-SDK **2.0.1**
(stdio) · Jackson 3.1.4 · keine Spring-Boot-Abhängigkeit, kein eigener LLM-Aufruf, kein
Webserver. Alle Versionen sind fest gepinnt.

```bash
./gradlew check               # verpflichtende deterministische Tests
./gradlew test installDist    # bauen und testen
./gradlew dependencyReport    # aufgeloeste Versionen nach build/dependency-report.txt
```

Tests mit echtem LLM oder Firmennetz sind **nicht** Teil des Builds.

---

## Nicht Teil von V1

Kein Deployment nach OpenShift, kein Hintergrunddienst, keine automatische Reparatur, kein
OpenProject-Schreibzugriff, keine GRETL-/Logit.io-Integration, kein Plugin-Framework, keine
Datenbank, kein Webfrontend. Die Architektur hält diese Erweiterungen offen, implementiert sie
aber nicht vorsorglich.

## Rechtliches und Betrieb

Keine produktiven Ressourcen anlegen, ändern oder löschen. Keine PRs in den untersuchten
Konfigurationsrepositories erstellen. Keine Zugangsdaten anfordern, einchecken oder ausgeben.
Live-Betriebsdaten dürfen nur an einen dafür freigegebenen Modellanbieter gesendet werden.
Regex-Redaktion garantiert **keine** vollständige Anonymisierung.

Lizenz: siehe [`LICENSE`](LICENSE).
