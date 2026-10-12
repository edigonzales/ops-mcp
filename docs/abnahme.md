# Abnahme – AGI Ops-Agent V1

Diese Datei führt den Abnahmestand nach [`docs/spec-v1/ABNAHME.md`](spec-v1/ABNAHME.md). Die drei
Ebenen werden **getrennt** geführt. Ein fehlender Zugang ist kein bestandener Nachweis.

```text
Implementierungsversion / Git-Commit: 1.0.0 / 0ab2ad5 (Branch main, gepusht nach origin)
Java / Gradle / MCP-SDK:              Java 25.0.2 (Temurin) / Gradle 9.8.1 / io.modelcontextprotocol.sdk:mcp 2.0.1
Betriebssysteme:                      getestet: macOS 27.0.1 (aarch64); vorgesehen: Linux/Ubuntu unter WSL2
Ausgeführte Testbefehle:              siehe Abschnitt 2
Ebene A – Implementierung:            PASSED
Ebene B – Agentenverhalten:           NOT_RUN
Ebene C – Liveintegration:            NOT_RUN
Getestete Codex-/OpenCode-Version:    keine (kein Client installiert)
Verwendetes Modell, soweit verfügbar: keines
Fixture-Szenarien und Ergebnisse:     20 Szenarien, 115 Tests, alle grün (Abschnitt 4)
Offene Nachweise:                     Abschnitt 6
Bekannte Einschränkungen:             Abschnitt 7
```

**Kein Gesamteintrag „alles bestanden": Ebene B und C sind offen.**

---

## 1. Gegenstand und Umfang dieser Abnahme

Abgenommen wird der in diesem Repository liegende Stand der technischen V1:

- Java-Anwendung mit CLI und stdio-MCP, gemeinsamer Fachlogik in
  `ch.so.agi.ops.application` und getrennten Adaptern.
- Sechs MCP-Werkzeuge mit Eingabe-/Ausgabeschemas, echte stdio-Protokollinitialisierung.
- Read-only-Adapter für OpenShift (`oc`), Jenkins (HTTP) und einen lokalen Git-Checkout.
- Synthetischer Fixturebetrieb mit 20 Szenarien über dieselben Parser und Normalisierer.
- Sicherheitsnachweise S01–S12 und Gegenfälle.

**Nicht** Gegenstand und **nicht** abgenommen: Deployment des Agenten nach OpenShift,
GRETL-/Schema-Jobdiagnose, Logit.io, OpenProject, PR-Vorschläge, Webfrontend, Betrieb als
Hintergrunddienst.

Der abgenommene Stand ist Commit **`0ab2ad5`** auf `main` (gepusht nach `origin`). Alle
Testbefehle in Abschnitt 2 wurden auf diesem Stand ausgeführt; die Ergebnisdateien liegen in
`build/test-results/test/`. Nach weiteren Änderungen ist der Commit-Hash hier zu erneuern –
die Abnahme gilt jeweils nur für den genannten Stand.

---

## 2. Tatsächlich ausgeführte Testbefehle

Alle Befehle wurden auf **macOS 27.0.1 (aarch64)** mit **Java 25.0.2 (Temurin)** und dem
eingecheckten Wrapper (**Gradle 9.8.1**) ausgeführt.

| Befehl | Ergebnis |
|---|---|
| `./gradlew test installDist` | **BUILD SUCCESSFUL** |
| `./gradlew check` | **BUILD SUCCESSFUL** |
| `./gradlew test --console=plain` | **523 Tests, 0 Failures, 0 Errors, 0 Skipped** |
| `./gradlew dependencyReport` | `build/dependency-report.txt` erzeugt (Java 25.0.2 / Gradle 9.8.1) |
| `./bin/ops-agent --config config/fixture.example.yaml doctor` | Exitcode **0**, 20 Szenarien gefunden |
| `./bin/ops-agent --config config/fixture.example.yaml applications` | Exitcode **0** |
| `./bin/ops-agent --config config/fixture.example.yaml collect --application modelfinder --environment integration --build 42` | Exitcode **0**, `collectionStatus: complete`, 14 Evidence, Links `exact`/`exact` |
| `./bin/ops-agent --config config/fixture.example.yaml inspect --snapshot <ID>` | Exitcode **0** |
| stdio-MCP mit echtem SDK-Client (`McpStdioProtocolTest`) | **5 Tests grün**, verhandelte Protokollversion **2025-11-25** |

### 2.1 Testverteilung (aus `build/test-results/test/*.xml`)

| Paket | Tests |
|---|---|
| `ch.so.agi.ops.adapter.fixture` | 115 |
| `ch.so.agi.ops.security` | 85 |
| `ch.so.agi.ops.adapter.jenkins` | 75 |
| `ch.so.agi.ops.adapter.openshift` | 74 |
| `ch.so.agi.ops.report` | 60 |
| `ch.so.agi.ops.adapter.git` | 46 |
| `ch.so.agi.ops.config` | 28 |
| `ch.so.agi.ops.cli` | 18 |
| `ch.so.agi.ops.schema` | 17 |
| `ch.so.agi.ops.mcp` | 5 |
| **Summe** | **523** |

Reproduzierbar:

```bash
./gradlew check                 # alle verpflichtenden deterministischen Tests
./gradlew test installDist      # bauen und testen
./gradlew dependencyReport      # aufgeloeste Versionen
```

Kein Test benötigt Internet, Firmennetz, Docker, `oc`, Jenkins, ein Git-Remote oder einen
LLM-Schlüssel. `Test.environment` setzt `NO_PROXY=*`, damit kein Test eine Firmenadresse
auflöst.

---

## 3. Ebene A – Implementierung: PASSED

### 3.1 Getestete Verträge

| Nachweis | Wo belegt |
|---|---|
| Konfiguration: unbekannte Schlüssel, Typen, Hardlimits, Pfadabkürzungen, Modustrennung, Scope-Grenzen | `ConfigLoaderTest` (28) |
| CLI: Befehle, Optionen, Exitcodes, keine ändernden Befehle, `--json` nicht bei `serve` | `CliRunnerTest` (18) |
| Schemas: `config-v1`, `snapshot-v1`, `diagnosis-v1` gegen gültige und ungültige Beispiele, Hardlimits, `executed: true`, `confidence`, Pfad-Traversal, `x-`-Erweiterung | `SchemaContractTest` (17) |
| Bericht: Parser, Validator, Markdown, Persistenz, keine Überschreibung, Rechte 0700/0600 | `ch.so.agi.ops.report` (60) |
| MCP: echter Serverprozess über stdio, SDK-Client, `tools/list`, `tools/call`, alle sechs Werkzeuge, `structuredContent`, `isError` | `McpStdioProtocolTest` (5) |
| OpenShift-Adapter: exakte Argumentlisten, Parser, Fehlercodes, kubeconfig-Ablehnung, Owner-Ketten, Events, Logs | `ch.so.agi.ops.adapter.openshift` (74) |
| Jenkins-Adapter: nur GET/HEAD, Folder-Encoding, 401/403/407/404, Login-HTML, Redirect ohne Auth an fremde Origins, Console-Log-Budget, `lastFailed` einmalig | `ch.so.agi.ops.adapter.jenkins` (75) |
| Git-Adapter: nur lesende Aufrufe, gehärtete Kindumgebung, keine Hooks/externen Diffs, `REVISION_*`-Grenzen, Symlinkfall, Maskierung von `Secret`/`env` | `ch.so.agi.ops.adapter.git` (46) |
| Fixturebetrieb: 20 Szenarien, Oracle, Owner-Ketten, Events, Logs, Revision, REFERENCE_ONLY | `ch.so.agi.ops.adapter.fixture` (115) |
| Sicherheit S01–S12, Budgets, Persistenz | `ch.so.agi.ops.security` (85) |

### 3.2 Verhandelte Protokollversion

Der `McpStdioProtocolTest` initialisiert einen **echten** SDK-Client gegen den über
`installDist` gebauten Launcher als Kindprozess. Ausgabe des Laufs:

```text
MCP-NACHWEIS verhandelteProtokollversion=2025-11-25 server=agi-ops-agent/1.0.0
  sdk=mcp-java-sdk/2.0.1 transport=stdio
```

stdout des Kindprozesses enthält ausschließlich JSON-RPC; Diagnose geht nach stderr
(`[ops-agent] … bereit (stdout bleibt JSON-RPC)`). Es wird **keine** Unterstützung zukünftiger
Protokollfeatures behauptet.

### 3.3 Fake-`oc`, HTTP-Testserver, lokales Git

- **Fake-`oc`:** kontrolliertes Python3-Test-Executable, das die **exakten Argumentlisten**
  prüft und alle Aufrufe protokolliert. Eine Whitelist-Prüfung in `@AfterEach` lässt den Test
  scheitern, sobald ein verbotener Aufruf vorkommt. Es wird **nie** ein echtes `oc` aus dem
  `PATH` ausgeführt. Fehlt Python3, greift `Assumptions.assumeTrue` (sauberer Skip).
- **HTTP-Testserver:** `com.sun.net.httpserver` auf `127.0.0.1:0`, zeichnet Methode, Pfad,
  Query, `Authorization` und `Range` auf. Der Redirect-Test verwendet **zwei** lokale Server
  und belegt, dass der fremde Server **0 Requests** erhält.
- **Lokales Git:** temporäre Repositories mit zwei Commits, erlaubten/unerlaubten Dateien,
  Template + Parameterdatei, Symlink, Hooks und externem Textconv-Programm. Nach dem Lesen von
  Commit A sind `HEAD` (B) und alle Worktree-Hashes unverändert; die Markerdateien der Hooks
  und des externen Diff-Programms bleiben leer.

### 3.4 Sicherheitsabnahme (S01–S12)

| ID | Erwartung | Ergebnis |
|---|---|---|
| S01 | Namespace/Workload außerhalb der Allowlist: `SCOPE_DENIED`, kein Quellaufruf | **PASS** (inkl. Gegenprobe: erlaubter Fall erreicht den Reader) |
| S02 | Fremde Build-URL, Userinfo, manipulierte Jobsegmente: abgewiesen, kein Auth-Header an fremde Hosts | **PASS** (fremder Server erhält 0 Requests) |
| S03 | `../`, absoluter Pfad, Symlink-Flucht: kein Zugriff außerhalb der Roots | **PASS** (inkl. manipulierter Evidence-ID und Indexpfad) |
| S04 | Shellmetazeichen und Optionsinjektion: keine Shellausführung | **PASS** (Metazeichen bleiben **ein** Argument) |
| S05 | Logtext fordert `oc delete`/Tokenausgabe: als Daten behandelt, keine zusätzliche Fähigkeit | **PASS** (Werkzeugliste unverändert, Dateibaum außerhalb unverändert) |
| S06 | Canary-Tokens: kein Canary in Snapshot, Evidence, `normalized.json`, Bericht, Toolausgabe | **PASS** |
| S07 | Kubernetes-`Secret` zwischen YAML-Templates: nicht ausgeliefert | **PASS** |
| S08 | Falscher Context oder deaktivierte TLS-Prüfung: Abbruch vor Erfassung | **PASS** (0 Prozessaufrufe) |
| S09 | Credential-`exec`-Plugin: abgelehnt, keine Programmausführung | **PASS** (Ziel der `exec`-Zeile existiert nicht) |
| S10 | Bericht mit `executed: true`, fremder Evidence-ID oder Outputpfad | **PASS** (Ausgabepfad ist nicht setzbar; Nachweis über Reflexion) |
| S11 | Lang laufender Prozess: Timeout, Prozess beendet, partieller Status | **PASS** (`/bin/sleep 30`, Timeout 300 ms, kein lebender Kindprozess) |
| S12 | Diagnoseclient mit freier Shell oder Schreib-MCPs | **PASS** als **statischer** Nachweis der Beispielkonfigurationen (siehe unten) |

**Zu S12:** Geprüft wird, dass die gelieferten Client-Profile genau einen MCP-Server und genau
die sechs `ops_*`-Werkzeuge aktivieren und in OpenCode `bash`, `edit`, `webfetch` und
`websearch` auf `deny` stehen. Das ist ein **Konfigurationsnachweis**, kein Laufzeitnachweis:
Ohne installierten Client ist die **wirksame** Toolliste nicht verifiziert. Dieser Teil wird in
Ebene B nachgeholt.

**Zu S05:** Ebene A belegt die technischen Sperren. Der geforderte echte
Prompt-Injection-Gegenfall mit einem Agenten gehört zu Ebene B und ist dort offen.

### 3.5 Budgets und Persistenz

| Nachweis | Ergebnis |
|---|---|
| Großes Jenkins-Log innerhalb des Budgets, Fehler nahe dem Ende | **PASS** (Fehler im gelieferten Ausschnitt, zusätzlich über `readEvidence` belegt) |
| Größeres, vor dem Ende abgebrochener Stream | **PASS** (`truncated=true`, Grund genannt, „Buildende NICHT untersucht") |
| `readEvidence` maximal 200 Zeilen, `hasMore`/`nextStartLine` korrekt | **PASS** |
| Auftragsweites Lesebudget, wiederholte Aufrufe | **PASS** (zweiter Aufruf: `BUDGET_EXCEEDED`) |
| Snapshot atomar, kein `.incomplete-*` sichtbar | **PASS** |
| Evidence unverändert, Hash/Zeilenreferenzen stabil | **PASS** (Änderung bei gleicher Größe wird erkannt) |
| Korrupter Snapshot erkannt | **PASS** (fehlendes Manifest, ungültige ID) |
| Bericht kann fremden Snapshot nicht referenzieren oder überschreiben | **PASS** |

### 3.6 Während der Abnahme gefundene und behobene Fehler

Diese Fehler wurden durch die Tests und die Integration sichtbar und sind behoben. Sie sind
hier aufgeführt, weil sie die Aussagekraft der Nachweise belegen.

1. **Redaktion wirkungslos (kritisch).** `authorization-header` und
   `proxy-authorization-header` waren mit `Matcher::group` verdrahtet – das ist `group(0)`,
   also der gesamte Treffer. `Authorization: token abc…` blieb im Klartext. Behoben; die Regeln
   decken jetzt das Zeilenende ab (auch die Digest-Form), zusätzlich wurde `url-userinfo`
   (`scheme://user:pass@host`) ergänzt. Präfixfreie Zählung durch `RedactorTest` belegt.
2. **Deutsche Passwort-Schlüssel nicht redigiert.** `Passwort: …`, `Kennwort=…`,
   `--passwort=…` blieben im Klartext. Behoben (Regel additiv erweitert, Tests ergänzt).
3. **`StrictNode` lehnte jede Zuordnung ab.** `child()` prüfte dynamische Bezeichner gegen
   einen leeren Schlüsselsatz; dadurch lud **keine** Konfiguration mit Einträgen. Behoben
   (Prüfung nur bei bekanntem Schlüsselsatz; Zuordnungen werden über `children()` geprüft).
4. **`OwnershipGraph.revisionsOf()` fand nie eine Revision.** Der Filter verglich
   `owner.kind()` mit `revision.kind()` (immer `Deployment` vs. `ReplicaSet`). Behoben; die
   Owner-Kette wird jetzt ausschließlich über die UID und das Workload-Kind nachgewiesen.
5. **`events.k8s.io/v1`-Meldung fehlte.** Der Parser las nur `message`, nicht `note`; dadurch
   wurde dasselbe Event aus zwei Formaten nicht als Dublette erkannt. Behoben.
6. **Probe-Vergleich meldete Falschunterschiede.** Fehlende Felder im YAML (Kubernetes-Defaults
   für `periodSeconds`, `failureThreshold`, `scheme`, …) wurden als Abweichung gemeldet.
   Behoben: Defaults werden auf beiden Seiten normalisiert.
7. **Image-Tag-Vergleich war fehlerhaft.** `endsWith(":" + tagOf("2026.10.1"))` ergab bei einem
   reinen Tag `":"`; der Build-zu-Workload-Link blieb dadurch `probable`. Behoben; bei
   passendem Tag ist der Link jetzt `exact`.
8. **MCP-Ausgabeschemas stimmten nicht mit den Antworten überein.** Die SDK-seitige
   Ausgabevalidierung (`outputSchema`) wies Felder ab (`dataMode`, `evidenceId`, `buildRef`,
   `target`, `findings`, `readBudget`, `byteBudgetHit`, `createdAt`, …). Behoben: Die Schemas
   und die erzeugten Strukturen sind angeglichen; collect und get liefern dieselbe Form.

---

## 4. Fixture-Szenarien und tatsächliche Ergebnisse

Alle Szenarien sind synthetisch (`synthetic: true`), enthalten keine echten Hosts, Tokens oder
Logkopien und liegen unter `fixtures/`. Die **erwartete Diagnose steht ausschließlich im
Test-Oracle** und wird nicht als Evidence an ein Modell gegeben.

| Szenario | Erwartung | Ergebnis des Oracle-Tests |
|---|---|---|
| `t01-liveness-startup` | Liveness 10/10/3, keine Startup-Probe, Restartcount 1 mit `lastState` Terminated, Killing-Event, Config am Commit `c0563524…` aufgelöst, Link `exact` | **PASS** |
| `t02-readiness-only` | NotReady, `restartCount 0`, **kein** Killing, Readiness-Fehler | **PASS** |
| `t03-wrong-probe-path` | falscher Pfad, 404-artiger Misserfolg, richtiger Pfad aus dem Log | **PASS** |
| `t04-application-crash` | fataler Startfehler, Exitcode ≠ 0, keine Probe-Ursache, keine Secretwerte | **PASS** |
| `t05-history-lost` | Events/vorherige Logs/alte UIDs fehlen, Pod gesund, Lücken benannt | **PASS** |
| `t06a/t06b/t06c` | Commit vorhanden / fehlt (`REVISION_UNAVAILABLE`, kein Ersatz) / fremder SHA wird nicht zugeordnet | **PASS** |
| `t07-probe-timeout` | Antwortzeit-Timeout, kein Neustart | **PASS** |
| `t08-jenkins-before-deploy` | Fehler vor dem Rollout, alte Version gesund | **PASS** |
| `t09-sidecar-oom` / `t09b-main-oom` / `t09c-exit137` | betroffener Container korrekt; Exitcode 137 allein kein OOM | **PASS** |
| `t10-second-application` | zweite Anwendung ohne Codeänderung | **PASS** |
| `t10b-deploymentconfig` | DeploymentConfig → ReplicationController → Pod | **PASS** |
| `t11-ambiguous-revision` | zwei plausible Revisionen, gleichnamige Pods mit anderer UID, Link `ambiguous` | **PASS** |
| `t12a–t12d` | Jenkins 403, fehlendes vorheriges Log, ungültiges JSON, Events verboten | **PASS** |

End-to-End-Lauf mit der ausgelieferten Fixturekonfiguration:

```text
snapshotId        20261001T101000Z-…
collectionStatus  complete
evidence          14  (Buildmetadaten, Console-Log, Workload, Revision, Pod, 6 Events,
                       2 Container-Logs, Git-Konfiguration)
links             build-to-workload=exact, build-to-configuration=exact
errors            keine
```

Die Fixturenamen sind ausdrücklich **keine** echten Betriebsdaten.

---

## 5. Ebene B – Agentenverhalten: NOT_RUN

**Begründung:** In der Entwicklungsumgebung ist **kein** Codex- und **kein** OpenCode-Client
installiert. Ein Agentenlauf mit einem freigegebenen Modellzugang war nicht möglich. Damit ist
die Ebene weder bestanden noch fehlgeschlagen: **`NOT_RUN`**.

Nicht ausgeführt wurden daher insbesondere:

- die tatsächlichen Agentenläufe zu T01, T02, T03, T05 und S05 (mindestens zwei unabhängige
  Läufe je Fall),
- der Nachweis, dass die relevanten Belege über `ops_read_evidence` gelesen werden, statt die
  Snapshotübersicht zu paraphrasieren,
- die Prüfrubrik aus Abschnitt 5 der Abnahme (Probe-Art und Container korrekt, Readiness nicht
  als Neustartursache erfunden, Fakten und Hypothesen getrennt, Belegreferenzen passend,
  Vorschlag nur als Vorschlag),
- die Wirksamkeit der Client-Einschränkungen (Toolliste, Negativversuch mit einem nicht
  erlaubten Werkzeug) und der echte Prompt-Injection-Gegenfall zu S05.

### 5.1 Exakte Befehle für Ebene B

```bash
# 1. Bauen
./gradlew installDist

# 2. Absolute Pfade in den Clientprofilen eintragen
#    examples/codex/config.toml      -> /ABSOLUTER/PFAD/ZUM/ops-mcp
#    examples/opencode/opencode.json -> /ABSOLUTER/PFAD/ZUM/ops-mcp

# 3. Codex: Server und Werkzeuge pruefen
codex mcp list

# 4. Diagnoselauf (Fixturebetrieb, ausschliesslich synthetische Daten)
#    Prompt: examples/codex/diagnose-prompt.md
#    Erwartete Werkzeugfolge:
#      ops_resolve_target -> ops_collect_snapshot -> ops_get_snapshot
#      -> ops_read_evidence (mehrfach, gezielt) -> ops_store_report

# 5. OpenCode
#    /mcp in der TUI pruefen, dann Prompt: examples/opencode/diagnose-prompt.md

# 6. Negativversuch: ein nicht erlaubtes Werkzeug (z. B. Shell) muss im
#    Diagnoseprofil abgewiesen werden.
```

**Zu erfassen sind:** Client-Version, Modellkennung (soweit verfügbar), Datum, Fixture-Commit,
die sichtbaren Werkzeugaufrufe und der Ergebnisstatus – sowie eine Bestehensquote, ohne
Fehlversuche zu verbergen.

---

## 6. Ebene C – Liveintegration: NOT_RUN

**Begründung:** Es wurde kein autorisierter Live-Test in WSL ausgeführt. Es wurden **keine**
produktiven Ressourcen gelesen oder verändert, **keine** Credentials angefordert und **keine**
Netzwerkbrücke ins Firmennetz eingerichtet. Damit ist die Ebene **`NOT_RUN`**.

Nicht bestätigt sind daher insbesondere:

- die Erreichbarkeit der OpenShift-API und des Jenkins aus WSL mit den lokalen Credentials,
- die Context-/Serverübereinstimmung der tatsächlichen `kubeconfig`,
- die tatsächliche Lesbarkeit des konfigurierten Workloads, der Events und Logs,
- die Auflösung der realen Konfigurationsrevision aus den Jenkins-SCM-Daten,
- die Identität und die realen RBAC-Rechte der verwendeten technischen Identität.

### 6.1 Für den ersten WSL-Live-Test noch fehlende lokale Angaben

| Angabe | Konfigurationsfeld | Stand |
|---|---|---|
| Pfad des `oc`-Programms | `clusters.ocp.ocExecutable` | **offen** |
| kubeconfig (tokenbasiert, statisch) | `clusters.ocp.kubeconfig` | vom Betreiber bereitzustellen |
| Expliziter Context | `clusters.ocp.context` | **offen** (ohne Wert `CONFIG_INCOMPLETE`) |
| Freigegebene Namespaces | `clusters.ocp.allowedNamespaces` | **offen** |
| Jenkins-Benutzername und Token-Datei | `jenkins.apps-deploy.username`, `token.file` | **offen** |
| Ggf. zusätzliche CA des Jenkins-Endpunkts | `jenkins.apps-deploy.tls.additionalCaFile` | **offen** |
| Proxy-Modus des Jenkins-Adapters | `jenkins.apps-deploy.proxy` | **offen** |
| Modelfinder-Jobpfad | `applications.modelfinder.environments.integration.deployment.jobPath` | **offen** (kein Sodata-Beispiel übernehmen) |
| Zielnamespace | `…namespace` | **offen** |
| Workload-Typ, Name, Container | `…workload.kind/name/containerNames` | **offen** |
| Lokaler `openshift-templates`-Checkout | `repositories.deployment-config.localPath` | vom Betreiber vorzubereiten |
| Template-/Parameterdateien | `…configuration.templateFiles/parameterFiles` | **offen** |
| Erlaubte Parameter | `…configuration.allowedParameterNames` | **offen** |
| Freigabe des Modellanbieters für Betriebsdaten | organisatorisch | vor LLM-Live-Diagnose erforderlich |

### 6.2 Ablauf (wie in `docs/anwenderhandbuch.md` beschrieben)

1. `cp config/live.example.yaml config/live.local.yaml`, offene Angaben ergänzen, **nicht
   einchecken**.
2. Credential lokal unter `0600` ablegen; keine Credentials im YAML.
3. `./bin/ops-agent --config config/live.local.yaml doctor`. `FAIL`-Einträge **manuell**
   beheben; V1 bietet keine automatische Zertifikats- oder Proxyreparatur.
4. `collect` mit einer konkreten Buildnummer – **ohne** LLM-Zugang. Namespace, Build, Zeitpunkt,
   UID-Verbindungen und Configrevision manuell kontrollieren.
5. Bereinigte Belege auf sensible Inhalte prüfen; für die Modellübermittlung nur die
   freigegebene Betriebsweise verwenden.
6. Denselben Snapshot mit dem eingeschränkten Diagnoseclient untersuchen.
7. Bericht mit Jenkins-Console und OpenShift-Events/Logs vergleichen. **Keine Änderung
   ausführen.**
8. Nachweis intern ablegen; hier nur erlaubte Metadaten und ein redigiertes Resultat
   dokumentieren.

Historische Events oder Pods können fehlen. Dann ist ein korrekt begrenzter Bericht eine
erfolgreiche Behandlung unvollständiger Daten – **keine** erfolgreiche Rekonstruktion der
damaligen Ursache. Für den historischen Modelfinder-Vorfall ist ein begründetes
`insufficient_evidence` ein korrektes Ergebnis.

---

## 7. Offene Nachweise

| Nr. | Offener Nachweis | Ebene |
|---|---|---|
| 1 | Tatsächlicher Agentenlauf mit Codex oder OpenCode (mindestens T01, T02, T03, T05, S05; je zwei Läufe) | B |
| 2 | Wirksame Toolliste und wirksame Client-Einschränkungen im Diagnoseprofil (inkl. Negativversuch) | B |
| 3 | Echter Prompt-Injection-Gegenfall mit einem Agenten | B |
| 4 | Autorisierter Live-Test in WSL: `doctor` und `collect` mit echten Zuordnungen | C |
| 5 | Manuelle Kontrolle der Live-Belege gegen Jenkins-Console und OpenShift-Events/Logs | C |
| 6 | Bestätigung der tatsächlichen RBAC-Rechte der verwendeten Identität (nicht durch `doctor` ersetzbar) | C |
| 7 | Freigabe des Modellanbieters für Betriebsdaten | organisatorisch |
| 8 | Erneuerung des Abnahme-Commit-Hashs nach jeder weiteren Änderung | – |

---

## 8. Bekannte Einschränkungen

1. **Keine Produktionsreife.** Die technische V1 ist implementiert und lokal getestet. Die
   Integration mit den echten AGI-Systemen ist **nicht** nachgewiesen.
2. **Regex-Redaktion ist keine vollständige Anonymisierung.** Nicht erkannte Secretformate
   können im Text verbleiben. Die Grenze ist im Manifest als
   `redaction.limitations` ausgewiesen. Live-Logs dürfen nur an einen dafür freigegebenen
   Modellanbieter gesendet werden; der Standort des MCP in WSL verhindert das nicht.
3. **Die Report-Validierung bestätigt nur Struktur, Snapshotbindung und Referenzexistenz** –
   nicht die Wahrheit oder Kausalität der Aussagen. Die inhaltliche Prüfung bleibt beim
   Menschen.
4. **OpenShift `kind: Template`** (die `objects:`-Liste) wird in V1 **nicht** entpackt. Falls
   die realen `openshift-templates` so aufgebaut sind, ist das eine bekannte Lücke.
5. **`ContainerSnapshot` führt keine Ports, Ressourcen oder `env`-Werte.** Diese Felder werden
   aus dem Pod nicht übernommen. Ports und ausgewählte Ressourcen liegen über die aufgelöste
   Konfiguration vor.
6. **`workload.templateProbes` ist eine Map** und führt deshalb nur eine Probe je Container
   (Priorität Liveness > Readiness > Startup). Vollständig stehen die Probes in
   `RevisionSnapshot.probes` und `PodSnapshot.probesFromPod`.
7. **Keine Prozent-Konfidenz.** Die Belegstärke ist qualitativ; `cause_identified` verlangt
   `exact`-Links und eine `supported`-Hypothese.
8. **Kein Nachweis über einen echten Proxyserver.** Der explizite Proxy-Modus ist
   konfiguriert und getestet, aber nicht gegen einen realen Proxy mit Authentifizierung. Für
   HTTPS-Tunneling kann die JDK-Eigenschaft `jdk.http.auth.tunneling.disabledSchemes` ein
   zusätzliches JVM-Flag erfordern.
9. **`DNS_FAILURE` ist implementiert, aber nicht automatisiert getestet**, weil alle
   Testserver auf `127.0.0.1` laufen und kein Test eine Namensauflösung auslösen darf.
10. **S11 und S03 sind plattformabhängig** (`/bin/sleep`, `/bin/cat`, Symlink-Unterstützung).
    Auf anderen Plattformen greift `Assumptions.assumeTrue` und der Test wird übersprungen –
    ein Skip ist kein bestandener Nachweis.
11. **Der Live-`doctor`-Pfad ist implementiert, aber nicht gegen echte Systeme ausgeführt.**
    Der Fixturebetrieb führt bewusst keinen Livezugriff aus.
12. **Kein automatischer Ausweichweg.** Fehlen historische Belege, ist `insufficient_evidence`
    das Ergebnis; V1 weicht nicht auf Logit.io, einen privilegierten Account oder den heutigen
    Git-Stand aus.

---

## 9. Aussage zum Gesamtstand

> Implementierung und MCP-Verträge getestet; Agentenabnahme (Ebene B) und Live-Abnahme
> (Ebene C) offen.

Die erfolgreiche Erzeugung eines Berichts im Fixturebetrieb oder eine gespeicherte
LLM-Musterantwort ist **kein** Ersatz für Ebene B. Ein diff-freier Clusterzustand ist **kein**
Beweis, dass keine Schreibaufrufe erfolgt sind; deshalb prüfen die Adaptertests die
aufgezeichneten Argumentlisten und Methoden.
