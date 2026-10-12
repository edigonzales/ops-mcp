# Entwicklerhandbuch – AGI Ops-Agent V1

Technische Dokumentation der Implementierung. Die verbindlichen Anforderungen stehen in
[`docs/spec-v1/`](spec-v1/README.md); dieses Dokument beschreibt, **wie** die Anforderungen
umgesetzt sind, welche Entscheidungen getroffen wurden und welche Grenzen bleiben.

---

## 1. Architektur

```text
Codex oder OpenCode (Diagnosemodus)
                |
                | stdio-MCP (JSON-RPC)
                v
      McpStdioServer / OpsTools          ch.so.agi.ops.mcp
                |
        InvestigationService             ch.so.agi.ops.application
         /        |         \
        v         v          v
ClusterReader  BuildReader  ConfigurationReader   ch.so.agi.ops.application.port
     |             |               |
OcClusterReader JenkinsHttpReader LocalGitConfigurationReader
     |             |               |              ch.so.agi.ops.adapter.*
 festes oc      Jenkins GET     lokales Git
      \            |              /
       --- Redaktion + Evidence ---              ch.so.agi.ops.security / .evidence
                    |
            immutable Snapshot
                    |
     LLM liest Belege ueber MCP (ops_read_evidence)
                    |
       validiertes DiagnosisDocument              ch.so.agi.ops.report
                    |
               lokaler Bericht
```

Der Java-Prozess enthält **keinen** LLM-Aufruf und **keine** providerabhängige Agentenschleife.
CLI und MCP benutzen **dieselben** Application Services: `CliRunner` ruft `OpsTools`, und
`OpsTools` ist die einzige fachliche Fassade, die die MCP-Handler aufrufen.

### 1.1 Package-Struktur

```text
ch.so.agi.ops.domain           Domaenenmodelle, Fehlercodes, CollectionBudget
ch.so.agi.ops.domain.k8s       normalisierte Kubernetes-/OpenShift-Objekte
ch.so.agi.ops.domain.jenkins   normalisierte Jenkins-Objekte
ch.so.agi.ops.domain.git       Konfigurationswerte und Revisionsherkunft
ch.so.agi.ops.config           Konfigurationsmodell, strenger Loader, YamlSupport
ch.so.agi.ops.security         Redactor, PathSafety, ScopeViolationException
ch.so.agi.ops.util             Hashes, Dateirechte, ProcessRunner, VersionInfo
ch.so.agi.ops.adapter          FixtureReaders-Interface, ServiceFactory
ch.so.agi.ops.adapter.openshift OcClusterReader, OcCommandBuilder, OcJsonParser
ch.so.agi.ops.adapter.jenkins  JenkinsHttpReader, JenkinsUrlBuilder, JenkinsJsonParser
ch.so.agi.ops.adapter.git      LocalGitConfigurationReader, TemplateParameterResolver
ch.so.agi.ops.adapter.fixture  Fixtureadapter (dieselben Parser)
ch.so.agi.ops.application      InvestigationService, TargetBuilder, TargetRegistry, DoctorService
ch.so.agi.ops.application.port ClusterReader, BuildReader, ConfigurationReader, SnapshotStore
ch.so.agi.ops.evidence         FileSnapshotStore, JsonLineRenderer
ch.so.agi.ops.report           DiagnosisDocument, Parser, Validator, Markdown, FileReportStore
ch.so.agi.ops.cli              OpsAgentMain, CliRunner, CliArguments, ExitCodes
ch.so.agi.ops.mcp              McpStdioServer, OpsTools, ToolSchemas, EvidenceReadBudget
```

### 1.2 Verdrahtung im Modus

`ch.so.agi.ops.adapter.ServiceFactory` wählt die Adapter **nach dem Modus** der Konfiguration:
`fixture` liest ausschliesslich das synthetische Szenario, `live` ausschliesslich die echten
Adapter. Es gibt **keine automatische Umschaltung** und **keinen stillen Fixture-Fallback**:
Ein fehlender Live-Adapter führt zu einem erklärten Fehler.

---

## 2. Technische Basis und gepinnte Versionen

| Bestandteil | Version | Begründung |
|---|---|---|
| Java | **25** | Auftrag; Sprachversion `release = 25` |
| Gradle | **9.8.1** (Wrapper, `bin`-Distribution) | stabil; Gradle unterstützt Java 25 ab 9.1.0 |
| MCP Java SDK (offiziell) | **2.0.1** | Protokoll und stdio-Transport; **keine** eigene JSON-RPC-Implementierung |
| Jackson (JSON/YAML) | **3.1.4** (`tools.jackson.*`) | vom MCP-SDK 2.0.1 transitiv gefordert; YAML über `jackson-dataformat-yaml` |
| SnakeYAML Engine | 2.9 (transitiv) | strikter YAML-Parser mit `LoadSettings`-Grenzen |
| networknt json-schema-validator | **3.0.6** | Schemaprüfung der Schemas und der Tool-Eingaben |
| JUnit Jupiter | **5.14.1** | Tests |
| SLF4J | **2.0.17** | Logging des SDK, Ausgabe nach stderr |

Alle Versionen sind fest gepinnt; es gibt **keine** dynamischen Versionsangaben und kein
`latest`. `resolutionStrategy.failOnVersionConflict()` verhindert eine stille Auflösung;
kollidierende Module werden bewusst auf eine gemeinsame Version gezogen (derzeit
`org.slf4j:slf4j-api`/`slf4j-simple` auf 2.0.17, weil `json-schema-validator` 2.0.16 anfordert).

Die aufgelösten Versionen lassen sich reproduzierbar ausgeben:

```bash
./gradlew dependencyReport && cat build/dependency-report.txt
```

---

## 3. Konfiguration

- **Eine explizit gewählte YAML-Datei pro Start** (`--config`). Keine automatische Suche nach
  produktiven Credentials, keine versteckte Umschaltung auf Livebetrieb.
- `schemaVersion: 1`, **unbekannte Schlüssel werden abgelehnt**, Typen validiert. Namespacete
  Erweiterungen sind über `x-`-Präfixe möglich.
- Relative Pfade werden **relativ zur Konfigurationsdatei** aufgelöst. Unterstützt werden nur
  `~`, `$HOME` und `${HOME}`. Jede andere Variable wird abgewiesen – keine Shellauswertung.
- Secrets stehen **nie** im YAML: `token.file` (lokale Datei) oder `token.environmentVar`.
- `limits` werden gegen dokumentierte Hardlimits geprüft (180 s, 16 Pods, 1000 Events,
  120 Quellaufrufe, 64 MiB Eingang, 64 KiB Evidence-Antwort, 256 KiB Berichtsentwurf).
- Ungelöste `null`-Pflichtfelder in einem Live-Anwendungseintrag führen für **diesen** Eintrag
  zu `CONFIG_INCOMPLETE`; es gibt **keinen** Defaultnamespace.

`StrictNode` sammelt alle Probleme, damit `doctor` und die CLI sie gemeinsam ausgeben können.

### 3.1 Erweiterung gegenüber der Spezifikation

Der Abschnitt `fixture` (`root`, `scenario`, `clock`, `fixedNow`) ist eine bewusste,
dokumentierte Ergänzung: Die Spezifikation fordert eine sofort lauffähige Fixturekonfiguration
und eine injizierte `Clock`, legt aber kein Konfigurationsfeld dafür fest. `fixture` ist nur
im Modus `fixture` zulässig und wird im Modus `live` abgewiesen.

---

## 4. Adapter

### 4.1 OpenShift (`OcClusterReader`)

- **Feste, lesende `oc`-Aufrufe** als Argumentlisten über `ProcessBuilder`. Es gibt keine
  Shell, keine Modellargumente und keine Zeichenkettenverkettung zu einem Kommando.
- Vor **jeder** Abfrage wird geprüft, dass der API-Server der kubeconfig zur erlaubten
  Clusteradresse passt. Bei Abweichung: Abbruch, kein weiterer Aufruf.
- `oc project`, `oc config use-context` und `oc login` werden **nie** ausgeführt.
- Die kubeconfig muss tokenbasiert und statisch sein. `exec`-Credential-Plugins,
  `insecure-skip-tls-verify` und fremde `proxy-url`-Einträge werden **abgelehnt**.
- `directHosts` werden in `NO_PROXY`/`no_proxy` der Kindumgebung zusammengeführt; andere
  Proxyvariablen werden weder überschrieben noch geloggt.
- Owner-Ketten: `Deployment → ReplicaSet → Pod` und
  `DeploymentConfig → ReplicationController → Pod`, ausschliesslich über **UID und Kind**.
  Label-Selektoren dienen nur der Kandidatenauswahl.
- Events aus `core/v1` und `events.k8s.io/v1` werden über einen gemeinsamen Normalisierer
  behandelt und über `dedupeKey()` entdoppelt; ein Dublett wird **nicht** als unabhängiger
  zweiter Beleg gezählt.
- Logs: aktuelles Log immer, `--previous` nur bei `restartCount > 0`. Kein `--follow`, kein
  `exec`. `restartCount == 0` ergibt `NOT_APPLICABLE` mit Begründung, **nicht**
  `NO_PREVIOUS_LOG`.
- Ein Transportfehler ist **kein** leeres, erfolgreich gelesenes Log.

### 4.2 Jenkins (`JenkinsHttpReader`)

- Nur feste **GET**-Endpunkte, begrenzte `tree`-Abfragen, optional sichere HEAD-Abfragen.
  Keine `config.xml`, keine Script Console, keine Pipelineausführung, kein Build-Start/-Stopp.
- Folder-Jobs werden als **Liste von Pfadsegmenten** behandelt und **je Segment** URL-kodiert.
- Eigener HTTP-Client mit eigenständiger Proxy- und CA-Konfiguration. Java wertet **nicht**
  dieselben Proxy-Umgebungsvariablen wie `curl` oder `oc` aus; `proxy.mode: direct` bedeutet
  deshalb ausdrücklich „kein Proxy“ und nicht „Systemproxy“.
- Eine zusätzliche lokale CA wird **nur** in diesem Client ergänzt. Es gibt keine Option zum
  Abschalten der TLS-Prüfung.
- Bei Redirects wird der `Authorization`-Header **nicht** an fremde Origins weitergegeben.
  Login-HTML mit HTTP 200 ist **keine** gültige JSON-Antwort.
- `lastFailed` ≠ `lastUnsuccessful`. Ein laufender oder abgebrochener Build ist nicht
  automatisch ein Deploymentfehler.
- Console-Log: kleines Log vollständig; grosses Log budgetbegrenzt mit Kopf-/Endausschnitt und
  ehrlicher Truncation-Kennzeichnung. Ein Console-Log ohne Zeitstempel bekommt
  Zeilenreferenzen, keine erfundenen Uhrzeiten.

### 4.3 Lokale Konfiguration (`LocalGitConfigurationReader`)

- Liest ausschliesslich aus einem **vom Menschen vorbereiteten lokalen Checkout**. Kein
  Clone, Fetch, Pull, Commit, Push oder Checkout.
- Die Kindumgebung erzwingt `GIT_CONFIG_NOSYSTEM`, eine leere globale Konfiguration,
  `core.hooksPath` auf ein leeres Verzeichnis, kein externes Diff-/Textconv-Programm und
  keinen Pager. Hooks und externe Programme werden nicht ausgeführt.
- Objektkennungen werden streng validiert; **kein** `HEAD`, `main`, `origin/...` oder
  Ref-Ausdruck aus einer Modelleingabe.
- Auflösungstiefe bewusst klein: einfache `NAME=wert`-Zuweisungen und einfache
  `${NAME}`-Referenzen auf erlaubte Parameter. Keine Shell-Ausdrücke, keine Substitutionen,
  keine Default-/Command-Expansion, kein Helm/Groovy/Jenkinsfile. `.env` wird **nie**
  ausgeführt oder gesourct.
- Nicht auflösbare oder widersprüchliche Werte bleiben offen (`UNRESOLVED`, `CONFLICTING`).
- Kubernetes-`Secret`-Dokumente werden **nicht** ausgewertet und nicht ausgeliefert.
- Fehlt der Commit lokal: `REVISION_UNAVAILABLE`, **kein** Ersatz durch einen anderen Commit.
  Fehlt die Zuordnung: `REVISION_UNKNOWN`/`REVISION_UNVERIFIED`. Eine zusätzlich gelesene
  Datei des heutigen Stands heisst ausdrücklich `current_reference_only`.

---

## 5. Snapshot, Evidence und Redaktion

- `FileSnapshotStore` schreibt zuerst in ein temporäres Verzeichnis und benennt es erst danach
  um. Nach einem Prozessabbruch ist **kein** halbfertiger Snapshot als final sichtbar.
- Evidence wird als **zeilennummerierte, redigierte Textdarstellung** gespeichert. Auch
  strukturierte Projektionen werden über `JsonLineRenderer` deterministisch gerendert
  (Objektschlüssel alphabetisch, zwei Leerzeichen Einrückung). Damit haben Zeilenverweise im
  Bericht stabilen Charakter.
- **Keine** persistente Rohdatenkopie vor der Redaktion, **keine** Redaktionsmap mit
  Originalwerten.
- Hashes (`sha256`) beziehen sich auf die gespeicherten bereinigten Inhalte. Bei jedem Lesen
  werden Pfadgrenzen, Grössen und Hashes geprüft; eine Abweichung ergibt
  `ARTIFACT_INTEGRITY`.
- Redaktionsregeln und Einschränkungen stehen im Manifest (`redaction.ruleVersion`,
  Zähler, `limitations`). **Regex-Redaktion ist keine vollständige Anonymisierung.**

### 5.1 Hinweis zur Redaktion und ein behobener Fehler

Die Regeln `authorization-header` und `proxy-authorization-header` waren zunächst mit
`Matcher::group` verdrahtet. Das ist `group(0)`, also der **gesamte Treffer**, und hat den
Wert damit **nicht** maskiert. Der Fehler wurde behoben (`m -> m.group(1) + MASK`), die
Authorization-Regel deckt jetzt das Zeilenende ab (auch die Digest-Form mit Parametern), und
eine Regel `url-userinfo` maskiert `scheme://user:pass@host`. `RedactorTest` prüft die
dokumentierten Muster mit einem einheitlichen Canary-Wert. 40-stellige Git-Commit-Hashes
bleiben bewusst unverändert, längere Hex-Werte werden als verdächtiges Secret maskiert.

---

## 6. MCP-Vertrag

Sechs Werkzeuge, feste Namen und Argumente:

| Werkzeug | `readOnlyHint` | `destructiveHint` | `idempotentHint` |
|---|---|---|---|
| `ops_list_applications` | true | false | true |
| `ops_resolve_target` | true | false | false |
| `ops_collect_snapshot` | **false** | false | **false** |
| `ops_get_snapshot` | true | false | true |
| `ops_read_evidence` | true | false | true |
| `ops_store_report` | **false** | false | false |

`ops_collect_snapshot` wird **ehrlich** als nicht seiteneffektfrei annotiert, weil es lokale
Dateien schreibt – obwohl die Quellsysteme nur gelesen werden. `ops_store_report` schreibt
lokale Berichtsdateien. Annotationen sind Hinweise, **keine** Zugriffskontrolle.

- Erfolgsantworten werden als `structuredContent` **plus** kompatible Textrepräsentation
  geliefert (von SDK 2.0.1 unterstützt).
- Fachliche Fehler ergeben `isError: true` mit strukturiertem Fehler
  (`code`, `sourceId`, `message`, `retryable`, `suggestedAction`).
- Ein **partieller, nutzbarer Snapshot** ist dagegen ein reguläres Ergebnis mit
  `collectionStatus: partial` und **kein** Toolfehler.
- Eingaben werden vom SDK gegen die mitgelieferten Schemas validiert
  (`additionalProperties: false`).
- **stdout bleibt JSON-RPC.** Logs gehen nach stderr und werden redigiert.

### 6.1 Evidence-Lesebudget

Zwei Grenzen greifen gleichzeitig:

1. **je Antwort**: maximal 200 Zeilen und `limits.maxEvidenceResponseBytes`.
2. **je Snapshot (auftragsweit)**: `EvidenceReadBudget`. Ein Agent darf durch wiederholte
   Aufrufe **keinen** unbeschränkten Zugriff auf weitere Quellen eröffnen.

### 6.2 Protokollversion

Die verhandelte Protokollversion wird **nicht** behauptet, sondern zur Laufzeit ermittelt:
Der stdio-Integrationstest liest `InitializeResult.protocolVersion()` der gepinnten
SDK-/Clientkombination und dokumentiert sie in [`abnahme.md`](abnahme.md). Es wird keine
Unterstützung zukünftiger Protokollfeatures behauptet.

---

## 7. Bericht und Validierung

`DiagnosisDocument` (Paket `report`) ist der Vertrag; `diagnosis-v1.schema.json` ist die
formale Fassung. Die Validierung prüft:

- `schemaVersion`, Sprache, **Snapshotbindung** (`snapshotId` muss zum Snapshot passen)
- `cause_identified` nur mit einer `supported`-Hypothese **und** ausschliesslich `exact`-Links
- Belegreferenzen existieren und Zeilenbereiche liegen im Umfang der Evidence
- `supports`/`basedOnHypotheses` verweisen auf existierende Fact-/Hypothesen-IDs
- `actionsTaken` ist leer; jede Empfehlung hat `executed: false`
- Längenbegrenzungen; keine ausführbaren HTML-/Scriptteile
- `insufficient_evidence` und `no_failure_evidenced` sind **gültige** Bewertungen
- Der Bericht ist ein **geschlossener Vertrag**: unbekannte Felder werden abgewiesen, auch
  namespacete `x-`-Felder. Das ist bewusst strenger als `diagnosis-v1.schema.json` und in
  `DiagnosisParserTest.namespacedExtensionFieldsAreRejected` festgehalten. Die
  Erweiterbarkeit der Spezifikation betrifft das Domänenmodell (generische Evidence-Typen,
  `useCase`) und die Konfiguration, nicht den Berichtsentwurf

> **Wichtig:** Die Report-Validierung bestätigt **nur Struktur, Snapshotbindung und
> Referenzexistenz**. Sie bestätigt **nicht** automatisch die Wahrheit oder Kausalität der
> LLM-Aussage. Ob ein Beleg den Text inhaltlich trägt, bleibt Gegenstand der Agentenabnahme
> und der menschlichen Prüfung.

Der Markdown-Bericht wird deterministisch erzeugt, escaped HTML und lädt keine externen
Bilder.

---

## 8. Sicherheitsmodell und Tests

Read-only wird auf drei Ebenen kombiniert: feste Operationen im MCP, eingeschränkte
Diagnosewerkzeuge im Client und möglichst echte Leserechte der technischen Identität.
**Ein `readOnlyHint` oder ein Prompt ist keine technische Zugriffskontrolle.**

| ID | Angriff/Test | Erwartung |
|---|---|---|
| S01 | Namespace/Workload ausserhalb der Allowlist | `SCOPE_DENIED`, kein Quellaufruf |
| S02 | Fremde Build-URL, Userinfo, manipulierte Jobsegmente | Abgewiesen, kein Auth-Header an fremden Host |
| S03 | `../`, absoluter Evidencepfad, Symlink-Flucht | Kein Zugriff ausserhalb der Roots |
| S04 | Shellmetazeichen oder Optionsinjektion in Namen | Keine Shellausführung, Eingabe abgewiesen |
| S05 | Logtext fordert `oc delete`, Jenkins-Start oder Tokenausgabe | Als Daten behandelt, keine Aktion |
| S06 | Canary-Tokens in Logs, env, HTTP-Fehlern, Probe-Headern, Bericht | Kein Canary im Snapshot, stderr, Toolausgabe oder Bericht |
| S07 | Kubernetes-`Secret`-Dokument zwischen YAML-Templates | Nicht an Modell oder Snapshot ausgeliefert |
| S08 | Falscher Context oder deaktivierte TLS-Prüfung | Abbruch vor Live-Datenerfassung |
| S09 | Credential-`exec`-Plugin in kubeconfig | Abgelehnt, keine Programmausführung |
| S10 | Bericht setzt `executed: true`, manipuliert Evidence-ID oder Pfad | Validierungsfehler, keine freie Dateiausgabe |
| S11 | Lang laufender/nie endender Quellprozess | Timeout, Prozess beendet, partieller Status |
| S12 | Diagnoseclient mit freier Shell oder Schreib-MCPs | Diagnoseprofil nicht freigeben, Konfiguration korrigieren |

In Fake-Prozess- und HTTP-Aufzeichnungen wird nachgewiesen, dass der Erfassungsweg **nur**
erlaubte Leseoperationen verwendet. Ein diff-freier Clusterzustand vor/nach dem Test ist
**kein** Beweis, dass keine Schreibaufrufe erfolgt sind.

### 8.1 Teststrategie

- **Kein** Firmennetz, **kein** echtes `oc`, **kein** echtes Jenkins, **kein** LLM-Schlüssel.
  `Test.environment` setzt `NO_PROXY=*`, damit kein Test eine Firmenadresse auflöst.
- Fake-`oc` als kontrolliertes Test-Executable: prüft die **exakten Argumente**, liefert
  Roh-JSON/Logs und protokolliert jeden Aufruf. Ein verbotener Aufruf lässt den Test scheitern.
- HTTP-Testserver auf `127.0.0.1` mit Aufzeichnung von Methode, Pfad und `Authorization`.
- Temporäre Git-Repositories mit mehreren Commits, erlaubten/unerlaubten Dateien, Symlinkfall,
  Hooks und einem externen Textconv-Programm, das eine Markerdatei anlegen würde.
- MCP: **echter** Serverprozess über stdio mit SDK-Client (`McpStdioProtocolTest`).
  Ein direkter Handler-Unit-Test genügt nicht.

```bash
./gradlew check                       # alle verpflichtenden deterministischen Tests
./gradlew test installDist            # bauen und testen
./gradlew dependencyReport            # aufgeloeste Versionen
```

Tests mit echtem LLM oder Firmennetz sind **nicht** Teil des Builds und liegen ausserhalb
dieses Repositories (siehe `docs/abnahme.md`).

---

## 9. Erweiterbarkeit

Snapshot und Report führen `schemaVersion`, `useCase`, Source-IDs und generische
Evidence-Typen. In V1 ist nur `deployment-failure` implementiert. Namespacete
Erweiterungsfelder (`x-…`) sind in der Konfiguration vorgesehen.

| Spätere Erweiterung | Bereits passende Grenze |
|---|---|
| GRETL-/Schema-Jobdiagnose | wiederverwendbarer `JenkinsHttpReader` + anderes Runbook + Jobkonfiguration |
| Logit.io | zusätzlicher Evidence-Collector, **kein** Ersatz des Domänenmodells |
| OpenProject | neuer, gesondert berechtigter Ausgabe-/Triggeradapter |
| PR-Vorschläge | getrennte schreibende Fähigkeit mit eigenem Berechtigungs- und Freigabemodell |
| Betrieb in OpenShift | gleiche Fachlogik, anderer Credential-/Clusteradapter |
| Snapshot direkt nach Jenkins-Fehler | headless aufrufbare CLI ohne LLM in der Erfassung |

**Nicht** implementiert und **nicht** abgenommen: Plugin-Framework, Eventbus, Datenbank,
Vektorsuche, RAG-Index, Web-UI, Kubernetes-Operator, eigener Agenten-Orchestrator, dauerhaft
laufende HTTP-Dienste, Deployment des Agenten nach OpenShift, GRETL-Diagnosen, Logit.io.

---

## 10. Offene technische Punkte

1. Der stdio-MCP-Betrieb ist mit SDK 2.0.1 getestet. Ein **echter** Codex-/OpenCode-Clientlauf
   (Ebene B) wurde in dieser Umgebung nicht ausgeführt; siehe `docs/abnahme.md`.
2. Die verhandelte Protokollversion ist gegen den echten stdio-Serverprozess belegt
   (**2025-11-25**, SDK 2.0.1, siehe `docs/abnahme.md` Abschnitt 3.2). Die **wirksame
   Tool-Allowlist im Client** ist dagegen ohne installierten Client nicht verifiziert; sie ist
   nur als Konfigurationsnachweis geprüft (S12).
3. Die Live-Pfade (`oc`, Jenkins, lokaler Git-Checkout) sind implementiert und durch
   Transport-/Parsertests ausgeführt, aber **nicht** gegen die echten AGI-Systeme getestet.
4. Die tatsächlichen Modelfinder-Zuordnungen (Jobpfad, Namespace, Workload, Container,
   Konfigurationsdateien, Commit) sind unbekannt und gehören in die lokale Konfiguration.
5. Die Redaktionsregeln decken die dokumentierten Muster ab (inklusive deutscher
   Passwort-Schlüssel und URL-Userinfo). Ein Secretwert **ohne** verdächtigen Schlüssel wird
   bewusst nicht maskiert; diese Grenze steht im Manifest als `redaction.limitations`.
6. `OpenShift doctor` (`OpenShiftDoctorReader`) ist implementiert und nutzt dieselben
   Transport-, kubeconfig- und Proxyprüfungen wie die Diagnose, wurde aber nicht gegen echte
   Systeme ausgeführt.
