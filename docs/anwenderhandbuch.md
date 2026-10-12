# Anwenderhandbuch – AGI Ops-Agent V1

Der AGI Ops-Agent sammelt für einen **konkreten fehlgeschlagenen Deploymentlauf** begrenzte,
bereinigte Belege aus Jenkins, OpenShift und einem lokalen Checkout von
`sogis/openshift-templates`. Ein MCP-fähiger Client (Codex, OpenCode) liest diese Belege und
formuliert daraus einen belegten, begrenzten Diagnosebericht.

**Read-only bezieht sich auf die untersuchten Systeme.** Der Agent schreibt ausschliesslich
lokale, bereinigte Snapshots und Berichte in den konfigurierten Ausgabebereich. Es gibt keine
Änderung im Cluster, in Jenkins oder in den untersuchten Repositories.

---

## 1. Quickstart (lokal, synthetisch, offline)

Voraussetzungen: **Java 25**, dieses Repository. Kein Netzwerk, kein `oc`, kein Jenkins,
kein LLM-Zugang nötig.

```bash
# 1. Bauen und deterministische Tests ausfuehren
./gradlew test installDist

# 2. Synthetische Umgebung pruefen (kein Livezugriff)
./bin/ops-agent --config config/fixture.example.yaml doctor

# 3. Konfigurierte Anwendungen und Umgebungen anzeigen
./bin/ops-agent --config config/fixture.example.yaml applications

# 4. Einen synthetischen Build untersuchen (42 ist ausschliesslich die Fixture-Buildnummer)
./bin/ops-agent --config config/fixture.example.yaml collect \
  --application modelfinder --environment integration --build 42

# 5. Vorhandenen Snapshot ansehen (ohne neue Systemabfragen)
./bin/ops-agent --config config/fixture.example.yaml inspect --snapshot SNAPSHOT_ID

# 6. Einen Diagnoseentwurf validieren und speichern
./bin/ops-agent --config config/fixture.example.yaml report \
  --snapshot SNAPSHOT_ID --input diagnosis.json

# 7. stdio-MCP starten (fuer Codex/OpenCode; ohne eigene LLM-Verbindung)
./bin/ops-agent --config config/fixture.example.yaml serve
```

`SNAPSHOT_ID` ist ein zu ersetzender Wert. Der Launcher `bin/ops-agent` startet das **gebaute**
Programm; pro MCP-Aufruf läuft **kein** Gradle-Build. Nach einer Codeänderung einmalig
`./gradlew installDist` erneut ausführen.

**Maschinenlesbare Ausgabe:** `--json` ist für alle Befehle ausser `serve` zulässig. Ausgaben
enthalten Status und lokale Artefaktpfade, aber keine Zugangsdaten.

---

## 2. Die drei Nachweisebenen

| Ebene | Was wird nachgewiesen? | Voraussetzung |
|---|---|---|
| **A – Implementierung** | Parser, Adapter, Sicherheit, Snapshot, CLI und echtes MCP-Protokoll | lokal, synthetisch, ohne Firmenzugang und ohne LLM |
| **B – Agentenverhalten** | ein tatsächlicher Codex-/OpenCode-Lauf liest Belege über MCP und formuliert eine passende Diagnose | freigegebener Client/Modellzugang, **ausschliesslich synthetische Daten** |
| **C – Liveintegration** | die echten AGI-Quellen sind aus WSL mit den konkreten Zuordnungen lesbar | autorisierter Mensch, Firmennetz, lokale Credentials |

Die Ebenen werden getrennt geführt. Ein fehlender Zugang ist **kein** bestandener Negativtest.
Den aktuellen Stand führt [`abnahme.md`](abnahme.md).

---

## 3. CLI-Referenz

```text
ops-agent --config <datei> [--json] doctor
ops-agent --config <datei> [--json] applications [--application <id>]
ops-agent --config <datei> [--json] resolve --application <id> --environment <name> \
    (--build <nummer> | --last-failed)
ops-agent --config <datei> [--json] collect --application <id> --environment <name> \
    (--build <nummer> | --last-failed) [--scenario <id>]
ops-agent --config <datei> [--json] inspect --snapshot <id>
ops-agent --config <datei> [--json] report --snapshot <id> --input <datei.json>
ops-agent --config <datei> serve
```

- `--build` und `--last-failed` schliessen sich gegenseitig aus. `lastFailed` wird **einmal**
  zu einer konkreten Nummer aufgelöst und danach nicht weiterverwendet.
- `--build-url` (im MCP) muss zu einer vorhandenen freigegebenen Zuordnung passen und eröffnet
  **keine** eigenen Netzwerkziele.
- Es gibt **keine** Befehle `deploy`, `fix`, `apply`, `rollback` oder `trigger`.
  `doctor` repariert keine Einstellungen.

### Exitcodes

| Code | Bedeutung |
|---|---|
| `0` | Befehl technisch erfolgreich; Daten-/Diagnosestatus zusätzlich im Ergebnis beachten |
| `2` | Ungültige Eingabe oder unvollständige/ungültige Konfiguration |
| `3` | Quellzugriff fehlgeschlagen; keine verwendbare Untersuchung möglich |
| `4` | Partieller Snapshot erzeugt; mindestens eine geplante Quelle/Budgetprüfung unvollständig |
| `5` | Berichtsschema, Snapshotbindung oder Belegreferenzen ungültig |
| `6` | Sicherheits-/Scope-Verletzung abgewehrt |

Ein korrekt gespeicherter Bericht mit `assessment: insufficient_evidence` kann Exitcode `0`
haben. Das ist **keine** Behauptung, die Ursache sei gefunden.

---

## 4. `doctor` lesen

`doctor` prüft dieselben Transport-, TLS-, Credential- und Proxyklassen wie die Diagnose.
Im Fixturebetrieb findet **kein** Livezugriff statt; die Live-Fähigkeiten bleiben dann bewusst
offen.

| Status | Bedeutung |
|---|---|
| `OK` | lokal bzw. über einen erlaubten Leseaufruf bestätigt |
| `WARN` | eingeschränkt; manuelle Prüfung nötig |
| `FAIL` | nicht nutzbar; so ist keine Diagnose möglich |
| `NOT_CONFIGURED` | in dieser Konfiguration nicht vorgesehen |

Geprüft werden: Konfiguration und Dateien, `oc`-Version, Context-/Serverübereinstimmung,
Zugriff auf den konfigurierten Workload, Jenkins-Buildmetadaten, lokaler Git-Checkout und die
erlaubten Pfade.

> **Ein erfolgreicher GET beweist nur Lesbarkeit, nicht das Fehlen von Schreibrechten.** Die
> Rechtebeschränkung der technischen Identität wird deshalb **nicht** als bestätigt ausgegeben.
> `identityMode` ist eine Betreiberdeklaration. Bei `personal-prototype` mit
> `allowPersonalCredentials: true` meldet `doctor` zusätzlich
> `PERSONAL_CREDENTIALS_UNVERIFIED_PRIVILEGES`.

Es werden keine vollständigen `kubeconfig`-Inhalte und keine globalen Namespace-Listen
ausgegeben. `doctor` bietet **keine** automatische Zertifikats- oder Proxyreparatur an.

---

## 5. Snapshot und Bericht

### 5.1 Ablage

```text
<storageRoot>/
├── snapshots/<snapshotId>/
│   ├── manifest.json
│   ├── normalized.json
│   └── evidence/EV-0001.txt …
└── reports/<snapshotId>/<reportId>/
    ├── diagnosis.json
    └── diagnosis.md
```

- Verzeichnisse werden mit `0700`, Dateien mit `0600` angelegt (soweit das Dateisystem POSIX
  unterstützt).
- Ein Snapshot wird **atomar** fertiggestellt. Eine laufende Erfassung ist nicht als finaler
  Snapshot sichtbar.
- Hashes beziehen sich auf die gespeicherten **bereinigten** Inhalte. Sie sind kein Beweis für
  das Original und kein Kausalitätsbeweis.
- Snapshots sind portabel und später ohne Zugang zu den Live-Systemen lesbar. Manifest- und
  Evidence-Dateien gelten beim erneuten Lesen als **nicht vertrauenswürdig**; Pfadgrenzen,
  Grössen und Hashes werden geprüft.

### 5.2 `collectionStatus`

- `complete`: die geplante begrenzte Erfassung wurde ohne technische Lücke abgeschlossen.
  Das bedeutet **nicht**, dass die Historie vollständig ist oder die Ursache bewiesen wäre.
- `partial`: mindestens eine geplante Quelle oder Budgetprüfung ist unvollständig.
  **Das ist ein reguläres Ergebnis**, kein Bedienfehler.
- `failed`: es konnte keine verwertbare Untersuchung aufgebaut werden.

Ein `NOT_APPLICABLE`-Eintrag, etwa keine vorherigen Logs bei `restartCount: 0`, ist **keine**
technische Lücke. Fehlende erwartete historische Daten, Zugriffssperren und Budgetabbrüche
führen dagegen mindestens zu einer Warnung und in der Regel zu `partial`.

### 5.3 Quellenstatus

`COLLECTED`, `NOT_PRESENT`, `NOT_APPLICABLE`, `NOT_ALLOWED`, `TRUNCATED`, `UNREACHABLE`,
`NOT_ATTEMPTED` – pro Quelle explizit. Eine leere Liste ist nie ein allgemeiner Ersatz für
einen Fehler.

---

## 6. Diagnosemodus im Client

Der Diagnosemodus hat **keine freie Shell**, **keine allgemeinen Schreibwerkzeuge**, **keine
Websuche** und **keine anderen MCP-Server**. Er darf nur die sechs `ops_*`-Werkzeuge verwenden.

- Fertige Konfigurationen: [`examples/codex/`](../examples/codex/README.md) und
  [`examples/opencode/`](../examples/opencode/README.md).
- Die Diagnoseanweisung: [`agents/ops-diagnose.md`](../agents/ops-diagnose.md).
- Wichtig: `AGENTS.md` im Repositorywurzelverzeichnis sind **Entwicklungsregeln**, keine
  Laufzeitanweisung für den Diagnose-Agenten.

Der Diagnose-Client-Timeout muss **über** dem konfigurierten Collect-Timeout liegen, mit
Reserve: bei 90 Sekunden Collect-Budget mindestens 120 Sekunden.

---

## 7. WSL-Live-Betrieb (Anleitung für den autorisierten Menschen)

**Dieser Abschnitt ist kein Auftrag an einen Coding-Agenten, die echten Systeme zu kontaktieren.**

### 7.1 Vorzubereiten

| Angabe | Wo eintragen | Stand |
|---|---|---|
| OpenShift-API und Proxy-Ausnahme | `clusters.ocp.server`, `directHosts` | bekannt, lokal bestätigen |
| `oc`-Pfad | `clusters.ocp.ocExecutable` | zu bestätigen |
| kubeconfig (tokenbasiert, statisch) | `clusters.ocp.kubeconfig` | vom Betreiber bereitzustellen |
| Context | `clusters.ocp.context` | **offen** – ohne Wert `CONFIG_INCOMPLETE` |
| Zielnamespace | `applications.<id>.environments.<env>.namespace` | **offen** |
| Workload-Typ/-Name, Container | `workload.kind`, `workload.name`, `workload.containerNames` | **offen** |
| Jenkins-Jobpfad | `deployment.jobPath` | **offen** |
| Jenkins-Leseidentität und Token | `jenkins.apps-deploy.username`, `token.file` | lokal zu prüfen |
| CA des Jenkins-Endpunkts | `jenkins.apps-deploy.tls.additionalCaFile` | lokal zu prüfen |
| Lokaler `openshift-templates`-Checkout | `repositories.deployment-config.localPath` | vom Betreiber vorzubereiten |
| Template-/Parameterdateien | `configuration.templateFiles`, `parameterFiles` | **offen** |
| Freigabe des Modellanbieters für Betriebsdaten | organisatorisch | vor LLM-Live-Diagnose nötig |

Es ist **kein** Modelfinder-Jobpfad, Namespace oder Dateipfad bekannt. Diese Werte dürfen nicht
geraten und nicht aus dem Sodata-Beispiel übernommen werden.

### 7.2 Ablauf

1. `cp config/live.example.yaml config/live.local.yaml` und die offenen Angaben
   vervollständigen. Die Datei ist über `.gitignore` ausgeschlossen; **nicht einchecken**.
2. Credential lokal ablegen: `${HOME}/.config/ops-agent/credentials/jenkins-apps.token`
   mit `0600`, Inhalt nur der API-Token. Keine Credentials im YAML.
3. `./bin/ops-agent --config config/live.local.yaml doctor` ausführen.
   Vorgeschlagene automatische Zertifikats-/Proxyreparaturen **nicht** erwarten – V1 bietet
   keine an. `FAIL`-Einträge manuell beheben.
4. Einen konkreten Jenkins-Lauf mit `collect` untersuchen. Dieser Schritt benötigt **keinen**
   LLM-Zugang. Namespace, Build, Zeitpunkt, UID-Verbindungen und Configrevision manuell
   kontrollieren.
5. Erzeugte bereinigte Belege auf sensible Inhalte prüfen. Für die Modellübermittlung nur die
   freigegebene Betriebsweise verwenden.
6. Mit dem eingeschränkten Diagnoseclient **denselben** Snapshot untersuchen lassen; keine
   parallele Neuaufnahme ohne Kennzeichnung.
7. Bericht mit Jenkins-Console und OpenShift-Events/Logs vergleichen.
   **Keine Änderung aus dem Vorschlag ausführen.**
8. Nachweis intern ablegen; in der Projektabnahme nur erlaubte Metadaten und ein redigiertes
   Resultat dokumentieren.

Historische Events oder Pods können bereits fehlen. Dann ist ein korrekt begrenzter Bericht
eine **erfolgreiche Behandlung unvollständiger Daten**, aber keine erfolgreiche Rekonstruktion
der damaligen Ursache. Es wird **nicht** eigens eine Störung erzeugt, um eine grüne Abnahme zu
erzwingen.

---

## 8. Was V1 nicht tut

- keine Reparatur, kein `oc apply/patch/delete/scale/rollout restart/exec/attach`
- kein Jenkins-Build-Start/-Stopp, keine Pipelineausführung, keine Script Console
- kein Commit, Push oder PR in den untersuchten Repositories
- kein automatischer Clone/Fetch/Pull, kein GitHub-Schreibzugang
- kein eigener LLM-Aufruf im Java-Prozess, kein Webserver, keine Datenbank
- keine Anbindung an Logit.io, OpenProject oder GRETL (spätere Erweiterungen)
- keine Prozent-Konfidenz und keine automatische Kausalaussage

## 9. Datenschutz und Grenzen

- Regex-Redaktion garantiert **keine** vollständige Anonymisierung. Nicht erkannte
  Secretformate können im Text verbleiben.
- Live-Logs dürfen nur an einen für diese Betriebsdaten **freigegebenen** Modellanbieter
  gesendet werden. Dass der MCP in WSL läuft, verhindert diese Übermittlung nicht.
- Weitergabe vom Firmen-Laptop auf ein privates Gerät nur mit synthetischen oder gesondert
  freigegebenen Daten.
- Ein Bericht mit Belegreferenzen ist kein Wahrheits- oder Kausalitätsbeweis. Die inhaltliche
  Prüfung bleibt beim Menschen.
