# AGI Ops-Agent V1 – Schnittstellen und Datenverträge

Diese Datei präzisiert [SPEZIFIKATION_V1.md](SPEZIFIKATION_V1.md). Alle Befehle und Werkzeuge sind **zu implementierende** Schnittstellen. Sie existieren nicht bereits durch diese Spezifikation.

## 1. Lokale Konfiguration

### 1.1 Prinzipien

MUST: Eine explizit gewählte YAML-Datei pro Start. Keine automatische Suche nach produktiven Credentials und keine versteckte Umschaltung auf Livebetrieb. Ein Modus ist entweder `fixture` oder `live`.

MUST: Relative Pfade relativ zur Konfigurationsdatei auflösen. `~` und `${HOME}` als dokumentierte lokale Pfadabkürzungen unterstützen; keine beliebige Variablensubstitution und keine Shellauswertung. Secret-Referenzen werden gesondert behandelt.

MUST: `schemaVersion: 1`, unbekannte Schlüssel ablehnen, Typen validieren. Alle IDs sind nichtleere begrenzte Zeichenfolgen. Ungelöste `null`-Pflichtfelder in einem Live-Anwendungseintrag führen für diesen Eintrag zu `CONFIG_INCOMPLETE`; kein Defaultnamespace.

MUST: Beispielkonfigurationen frei von Credentials und echten Logdaten. Die bekannten Serveradressen dürfen im privaten Live-Beispiel vorkommen; eine zusätzlich veröffentlichbare generische Variante verwendet `example.invalid`.

### 1.2 Live-Beispiel

Das Beispiel zeigt die Struktur. Die `null`-Angaben sind absichtlich nicht einsatzbereit. Insbesondere ist kein tatsächlicher Modelfinder-Jobpfad bekannt.

```yaml
schemaVersion: 1
mode: live

storage:
  root: "${HOME}/.local/state/ops-agent"

security:
  allowPersonalCredentials: false
  modelDataPolicy: internal-approved-only

limits:
  collectTimeoutSeconds: 90
  requestTimeoutSeconds: 15
  connectTimeoutSeconds: 5
  maxParallelRequests: 3
  maxRequests: 60
  maxPods: 8
  maxEvents: 500
  maxGitFiles: 12
  maxBuildLogBytes: 8388608
  maxContainerLogBytes: 1048576
  maxTotalInputBytes: 33554432
  maxEvidenceResponseBytes: 32768

clusters:
  ocp:
    server: "https://api.ocp.so.ch:6443"
    ocExecutable: "/usr/local/bin/oc"
    kubeconfig: "${HOME}/.config/ops-agent/credentials/ocp.kubeconfig"
    context: null
    identityMode: personal-prototype
    directHosts:
      - api.ocp.so.ch
    allowedNamespaces: []

jenkins:
  apps-deploy:
    baseUrl: "https://jenkins-agi-apps-production.apps.ocp.so.ch"
    username: null
    token:
      file: "${HOME}/.config/ops-agent/credentials/jenkins-apps.token"
    tls:
      additionalCaFile: null
    proxy:
      mode: direct
    allowedJobPaths: []
    allowedParameterNames: []

repositories:
  deployment-config:
    repositoryId: sogis/openshift-templates
    localPath: null
    allowedFiles: []

applications:
  modelfinder:
    displayName: Modelfinder
    environments:
      integration:
        cluster: ocp
        namespace: null
        workload:
          kind: null
          name: null
          containerNames: []
        deployment:
          jenkins: apps-deploy
          jobPath: null
        configuration:
          repository: deployment-config
          templateFiles: []
          parameterFiles: []
          allowedParameterNames: []
          revision:
            source: jenkins-scm
            repositoryId: sogis/openshift-templates
```

`integration` ist hier nur der zu vervollständigende Umgebungseintrag, keine Behauptung zum historischen Modelfinder-Fall. Weitere Umgebungen müssen separat und explizit angelegt werden.

Die tatsächlichen Werte für Context, Namespace, Workload-Typ/-Name, Container, Jobpfad und Dateien müssen durch den Betreiber bestätigt werden. `identityMode` ist eine Betreiberdeklaration; der gezeigte persönliche Prototypzugang ist mit `allowPersonalCredentials: false` absichtlich noch gesperrt. Beispiel für das **Format** eines Folder-Jobpfads: `['folder', 'application']`. Keine Strings mit ungeprüften `/job/`-URL-Teilen verwenden.

`containerNames` ist eine explizite Auswahl innerhalb des ermittelten Workloads. Eine leere Auswahl erlaubt die Erfassung der Containermetadaten, aber nicht automatisch beliebige Logs; `doctor` fordert eine Auswahl oder eine ausdrücklich dokumentierte lokale `allWorkloadContainers`-Policy.

Die Allowed-Listen des Systems sind die obere Berechtigungsgrenze. Anwendungseinträge dürfen sie nur weiter einschränken. Beispielsweise wird ein Namespace nicht allein dadurch erlaubt, dass er in einer Anwendungskonfiguration steht.

### 1.3 Herkunft der Konfigurationsrevision

MUST: Zwei deterministische Konfigurationswege unterstützen:

- `revision.source: jenkins-scm`: Aus den tatsächlich verfügbaren strukturierten SCM-Daten den vollständigen Commit des konfigurierten `repositoryId` zuordnen. Kein Treffer oder mehrere widersprüchliche Treffer ergeben eine explizite Lücke. Ein nachweislich erkannter Repo-Checkout im Console-Log darf zusätzlich ausgewertet werden, muss aber mit Zeilenbeleg und eigener Herkunft markiert sein.
- `revision.source: operator-supplied`: Lokale Felder `commit` und `forBuild` sind erforderlich. `forBuild` muss exakt der untersuchten Buildnummer entsprechen. So kann der Betreiber einen bekannten Commit angeben, ohne dass V1 jeden Jenkins-SCM-Pluginaufbau verstehen muss. Die Revision bleibt `operator_supplied_unverified`, bis unabhängige Buildbelege die Zuordnung bestätigen.

Der Collector darf einen vorgegebenen Commit nicht allein deshalb als tatsächlich deployed bezeichnen, weil dessen Datei lesbar ist. Kein Agent-Tool darf seine eigene Herkunftsstufe als `verified` festlegen.

### 1.4 Proxy und Zertifikate

MUST: Für Jenkins zunächst `proxy.mode: direct` und `proxy.mode: explicit` mit einem expliziten Proxyhost/-port unterstützen. Credentials für den Proxy nur über Secret-Referenzen; keine NTLM-/Kerberos-Automatisierung als V1-Pflicht. Ein HTTP-407 wird erklärt und nicht durch TLS-Abschaltung umgangen.

MUST: JVM-Standardvertrauen beibehalten und eine lokal konfigurierte zusätzliche CA nur im betreffenden HTTP-Client ergänzen. CA-Dateien nicht global installieren. Das Jenkins-Zertifikat kann einen anderen Aussteller als die OpenShift-API haben.

MUST: Für `oc` die vorhandene CA-Konfiguration der freigegebenen `kubeconfig` verwenden und TLS-Abschaltung ausschliessen. `directHosts` in der Child-Umgebung in `NO_PROXY` und `no_proxy` zusammenführen. Andere erforderliche Proxy-Einstellungen nicht überschreiben oder loggen.

### 1.5 Fixturekonfiguration

MUST: `config/fixture.example.yaml` sofort lauffähig mitliefern. Sie referenziert ein mitgeliefertes synthetisches Szenario und enthält keine realen Systemadressen, Credentials oder Benutzeridentitäten. Namespaces und Jobpfade dürfen hier frei erfunden sein, müssen aber als synthetisch markiert sein.

MUST: Die Fixtureadapter dieselben Domänenmodelle und Parser verwenden. Zeit über eine injizierte `Clock` kontrollieren. Das Szenario enthält Rohantworten/Fake-Transporte, nicht nur die gewünschte Enddiagnose.

## 2. CLI

### 2.1 Vorgesehene Befehle

```bash
# Projekt bauen; danach kein Gradle-Build pro Toolaufruf.
./gradlew test installDist

# Synthetische Umgebung prüfen.
./bin/ops-agent --config config/fixture.example.yaml doctor

# Konfigurierte Anwendungen/Umgebungen anzeigen.
./bin/ops-agent --config config/fixture.example.yaml applications

# Ein synthetischer Build; 42 ist ausschliesslich die Fixture-Buildnummer.
./bin/ops-agent --config config/fixture.example.yaml collect \
  --application modelfinder --environment integration --build 42

# Vorhandenen Snapshot zusammenfassen, ohne neue Systemabfragen.
./bin/ops-agent --config config/fixture.example.yaml inspect \
  --snapshot SNAPSHOT_ID

# Einen vom Agenten erstellten Diagnoseentwurf validieren und speichern.
./bin/ops-agent --config config/fixture.example.yaml report \
  --snapshot SNAPSHOT_ID --input diagnosis.json

# stdio-MCP, ohne eigene LLM-Verbindung.
./bin/ops-agent --config config/fixture.example.yaml serve
```

`SNAPSHOT_ID` ist ein zu ersetzender Wert, keine feste Kennung. `collect --last-failed` ist als Alternative zu `--build` zulässig; beides zusammen ist ein Eingabefehler. Ein optionales `--build-url` darf nur zu einer vorhandenen freigegebenen Zuordnung passen, nicht eigene Netzwerkziele eröffnen.

MUST: Maschinenlesbare Ausgabe über `--json` für alle Befehle ausser `serve`; menschenlesbare Standardausgabe. Ausgaben enthalten Status und gegebenenfalls lokale Artefaktpfade, aber keine Credentials.

MUST: `report` liest nur eine lokale, vom Benutzer explizit gewählte JSON-Eingabe. Im MCP gibt es dagegen keine frei wählbare Input-/Outputdatei: Der strukturierte Bericht ist der Toolparameter.

MUST: Keine Befehle `deploy`, `fix`, `apply`, `rollback` oder `trigger` anbieten. `doctor` repariert keine Einstellungen.

### 2.2 Exitcodes

| Code | Bedeutung |
|---|---|
| `0` | Befehl technisch erfolgreich; Daten-/Diagnosestatus zusätzlich im Ergebnis beachten. |
| `2` | Ungültige Eingabe oder unvollständige/ungültige Konfiguration. |
| `3` | Quellzugriff fehlgeschlagen; keine verwendbare Untersuchung möglich. |
| `4` | Partieller Snapshot erzeugt; mindestens eine geplante Quelle/Budgetprüfung unvollständig. |
| `5` | Berichtsschema, Snapshotbindung oder Belegreferenzen ungültig. |
| `6` | Sicherheits-/Scope-Verletzung abgewehrt. |

Ein korrekt gespeicherter Bericht mit `assessment: insufficient_evidence` kann Exitcode 0 haben. Das ist keine Behauptung, die Ursache sei gefunden.

### 2.3 Doctor

MUST: Die gleichen Transport-, TLS-, Credential- und Proxyklassen wie die Diagnose verwenden. Bei Fixtures keinerlei Livezugriff. Im Livemodus nur erlaubte kleine GET-Abfragen und lokale Leseprüfungen, keine Mutation und keine automatische Anmeldung.

Ergebnis enthält pro Fähigkeit `OK`, `WARN`, `FAIL` oder `NOT_CONFIGURED`, eine redigierte Erklärung und einen konkreten manuellen nächsten Schritt. Prüfen: Config/Dateien, `oc`-Version, Context/Serverübereinstimmung, Zugriff auf den konfigurierten Workload, Jenkins-Buildmetadaten, lokaler Git-Checkout und erlaubte Pfade. Keine komplette `kubeconfig` oder globalen Namespace-Listen ausgeben.

Ein erfolgreicher GET beweist nur Lesbarkeit, nicht das Fehlen von Schreibrechten. Die Rechtebeschränkung der Identität darf deshalb nicht allein durch `doctor` als bestätigt ausgegeben werden.

## 3. Interne Java-Schnittstellen

Die Namen sind empfohlen; die Verantwortungsgrenzen sind verbindlich. Kleine Records und sealed/enumerierte Fehler-/Statusmodelle verwenden, keine unstrukturierten `Map<String,Object>` durch die gesamte Anwendung reichen.

```java
interface ClusterReader {
    ClusterEvidence collect(ResolvedTarget target, CollectionBudget budget);
}

interface BuildReader {
    BuildSelection resolveBuild(TargetDefinition target, BuildSelector selector);
    BuildEvidence collect(ResolvedTarget target, CollectionBudget budget);
}

interface ConfigurationReader {
    ConfigurationEvidence collect(
        ResolvedTarget target,
        ConfigurationRevision revision,
        CollectionBudget budget
    );
}

interface SnapshotStore {
    SnapshotId save(SnapshotDraft draft);
    SnapshotSummary describe(SnapshotId id);
    EvidencePage read(SnapshotId id, EvidenceId evidenceId, EvidenceRange range);
}
```

MUST: Credential-/Proxydetails bleiben in den Adaptern, nicht im Domainmodell oder Tooloutput. `CollectionBudget` gilt auftragsweit; die Einzeladapter dürfen es nicht jeweils erneut vollständig verbrauchen.

MUST: Der Application Service koordiniert eine zeitnahe OpenShift-Erfassung und Jenkins-Abfrage. Bei explizitem Build dürfen Events/Logs früh gesichert werden; Repositorylektüre wartet bei Bedarf auf eine belastbare Revision. Ein Jenkins-Ausfall darf vorhandene Clusterbelege nicht löschen. Fehlende Links bleiben markiert.

MUST: Pro Source eine strukturierte `SourceResult<T>` mit Daten, Status, Erfassungszeit, Hinweisen und redigierten Fehlern liefern. Keine leere Liste als allgemeiner Ersatz für Exceptions.

## 4. MCP-Werkzeuge

V1 liefert genau diese sechs fachlichen Werkzeuge. Zusätzliche interne Methoden benötigen keine eigenen MCP-Tools. Die Namen und wesentlichen Argumente bilden den stabilen V1-Vertrag.

### 4.1 `ops_list_applications`

**Input:** `{}` oder `{"applicationId":"modelfinder"}`.

**Output:** Erlaubte Anwendungen/Umgebungen mit `configurationStatus`. Keine Secrets, lokalen Credentialpfade oder vollständige Systemkonfiguration.

**Seiteneffekt:** keiner. `readOnlyHint: true`.

### 4.2 `ops_resolve_target`

**Input:**

```json
{
  "applicationId": "modelfinder",
  "environment": "integration",
  "build": { "kind": "number", "number": 42 }
}
```

Alternativ `"build": {"kind":"lastFailed"}`. `number` und `lastFailed` sind gegenseitig ausschliessend. In V1 sind Target-Handles für 30 Minuten innerhalb des Serverprozesses gültig. Die daraus erzeugten Snapshots sind unabhängig davon dauerhaft über ihre ID lesbar.

**Output:** `targetId`, konkrete Buildnummer, System-/Anwendungskennungen, erlaubter Namespace/Workload, Auflösungszeit und Warnungen. `targetId` ist eine serverseitig erzeugte opaque Kennung, kein vom Modell editierbares URL-/Scopeobjekt.

MUST: Eindeutige Konfigurationszuordnung lokal auflösen. Bei `lastFailed` den Job gezielt lesen. Fehlgeschlagenes Lesen des explizit nummerierten Builds kann später zu einem partiellen Snapshot führen; Namespace/Jobbindung muss trotzdem eindeutig konfiguriert sein.

**Seiteneffekt:** Nur kurzlebiger lokaler Sessionzustand; keine Betriebsänderung. `readOnlyHint: true` ist für diesen Auflösungs-/Lesevorgang vertretbar. Keine persistente Credentialablage.

### 4.3 `ops_collect_snapshot`

**Input:** `{"targetId":"TARGET_ID"}`.

**Output:** `snapshotId`, `collectionStatus`, kompakte Source-Übersicht, Erfassungszeitfenster, Evidence-Index und Warnungen. Nicht alle Logzeilen in eine Toolantwort packen.

MUST: Target serverseitig nachschlagen; Scope/Build nicht aus nachträglich übergebenen Werten übernehmen. Ein unbekannter oder abgelaufener Target-Handle muss neu aufgelöst werden. Jede neue Erfassung erzeugt eine neue Snapshot-ID; keine verschleierte Wiederverwendung alter Live-Daten.

**Seiteneffekt:** Schreibt ausschliesslich einen lokalen bereinigten Snapshot. Deshalb `readOnlyHint: false`, obwohl die Quellsysteme nur gelesen werden. Beschreibung nennt diese Unterscheidung. `destructiveHint: false`; nicht als idempotente Liveaufnahme deklarieren.

### 4.4 `ops_get_snapshot`

**Input:** `{"snapshotId":"SNAPSHOT_ID"}`.

**Output:** Zusammenfassung, Referenzen, Linkstärke, Quellenstatus, normalisierte Probes/Containerzustände und Evidence-Index. Rein lokales Lesen; keine neuen Systemabfragen.

**Seiteneffekt:** keiner. `readOnlyHint: true`.

### 4.5 `ops_read_evidence`

**Input:**

```json
{
  "snapshotId": "SNAPSHOT_ID",
  "evidenceId": "EV-0004",
  "startLine": 1,
  "maxLines": 100
}
```

**Output:** Redigierter Textausschnitt mit Zeilennummern, Source-Metadaten, `hasMore`, `nextStartLine` und gegebenenfalls Erfassungslücken. Evidence-Texte sind unveränderlich und snapshotgebunden. Kein frei wählbarer Pfad oder URL.

MUST: Auch JSON-Projektionen in einer stabilen zeilennummerierten Darstellung liefern; strukturierte Daten zusätzlich über `ops_get_snapshot`. Maximal 200 Zeilen und das konfigurierte Bytebudget pro Antwort, auch wenn der Agent mehr anfordert. Eine überlange Einzelzeile nicht unbegrenzt ausgeben; Abbruch/Teilzeile kennzeichnen. Neben dem Antwortbudget das auftragsweite Lesebudget beachten; ein Agent darf durch wiederholte Aufrufe keinen unbeschränkten Zugriff auf weitere Quellen eröffnen.

**Seiteneffekt:** keiner. `readOnlyHint: true`.

### 4.6 `ops_store_report`

**Input:** `{"snapshotId":"SNAPSHOT_ID","diagnosis":{...}}` nach Diagnosis-Schema.

**Output:** Report-ID, lokaler relativer Markdown-/JSON-Pfad, Validierungsstatus und Warnungen.

MUST: Referenzen und Zeilenbereiche gegen den Snapshot prüfen; unbekannte Belege, leere Begründung für starke Aussagen und unzulässige Berichtsfelder abweisen. Bericht erneut redigieren. Texte sicher als Markdown rendern, keine ausführbaren HTML-/Scriptteile und keine automatisch geladenen externen Bilder.

MUST: Nur unter `reports/<snapshotId>/<reportId>/` speichern. Kein beliebiger Dateipfad, keine Überschreibung von Evidence und keine JSON-Felder für auszuführende Kommandos. Handlungsvorschläge sind Text mit `executed: false`.

**Seiteneffekt:** lokale Berichtsdateien. `readOnlyHint: false`, `destructiveHint: false`. Keine Betriebsänderung.

### 4.7 Gemeinsame Protokollregeln

MUST: Offizielle SDK-Protokollinitialisierung, `tools/list`, `tools/call`, Eingabe-/Ausgabeschemas und kontrollierte Fehler. Mit der verwendeten SDK-/Clientkombination verhandelte Protokollversion dokumentieren; keine nur vermeintliche Unterstützung zukünftiger Features behaupten.

MUST: Erfolgsantworten möglichst als `structuredContent` plus kurzer kompatibler Textrepräsentation liefern, soweit von der gepinnten SDK-Version unterstützt. Fachlicher Toolfehler: `isError: true` mit strukturiertem Fehler. Ein partieller, nutzbarer Snapshot ist dagegen ein reguläres Ergebnis mit `collectionStatus: partial`.

MUST: Stdio-stdout bleibt JSON-RPC. Keine Startupbanner oder Gradleausgaben hineinmischen. Diagnoseclient-Tooltimeout mindestens über dem konfigurierten Collect-Timeout, mit Reserve. Defaultbeispiel: 120 Sekunden bei 90 Sekunden Collect-Budget.

## 5. Snapshotvertrag

### 5.1 Ablage

```text
<storageRoot>/
├── snapshots/<snapshotId>/
│   ├── manifest.json
│   ├── normalized.json
│   ├── evidence/EV-0001.txt
│   ├── evidence/EV-0002.txt
│   └── ...
└── reports/<snapshotId>/<reportId>/
    ├── diagnosis.json
    └── diagnosis.md
```

MUST: Atomar fertigstellen, für unvollständige laufende Erfassung ein nicht als final sichtbares temporäres Verzeichnis verwenden. Nach Prozessabbruch keine halbfertige Datei als kompletten Snapshot anbieten. Keine persistente Rohdatenkopie vor der Redaktion.

MUST: Versionierte portable relative Pfade. Ein Snapshot kann später ohne Zugang zu den Live-Systemen untersucht werden. Seine Manifest-/Evidence-Dateien gelten auch beim erneuten Lesen als untrusted; Pfadgrenzen, Grössen und Hashes prüfen.

MUST: Hashes beziehen sich auf gespeicherte **bereinigte** Inhalte, nicht als Beweis auf das unbekannte Original. Hashintegrität ist kein Vertrauens- oder Kausalitätsbeweis.

### 5.2 Pflichtfelder des Manifests

| Feld | Bedeutung |
|---|---|
| `schemaVersion` | `1`. |
| `snapshotId` | Zufällig erzeugte, pfadsichere ID. |
| `useCase` | In V1 `deployment-failure`. |
| `dataMode` | `fixture` oder `live`; nie weglassen. |
| `applicationId`, `environment` | Bestätigte Zuordnung. |
| `buildRef` | Jenkins-ID, Jobsegmente, konkrete Nummer; Metadatenverfügbarkeit gesondert. |
| `collectionStartedAt`, `collectionFinishedAt` | Erfassungszeit. |
| `investigationWindow` | Fachliches Zeitfenster und seine Herkunft. |
| `collectionStatus` | `complete`, `partial`, `failed`. |
| `sourceResults` | Status je geplanten Quellzugriff, mit Warnungen/Lücken. |
| `links` | Beziehungen Build ↔ Workload/Revision ↔ Config mit Linkstärke und Belegen. |
| `evidenceIndex` | Evidence-ID, Art, Herkunft, redigierter relativer Pfad, Hash, Zeilenanzahl. |
| `redaction` | Regelversion, Zähler und bekannte Einschränkungen; niemals entfernte Secretwerte. |
| `toolVersions` | Java, Anwendung, `oc`/Git soweit verwendet; keine kompletten Systemdumps. |

`complete` bedeutet: Die geplante begrenzte Erfassung wurde ohne technische Lücke abgeschlossen. Es bedeutet **nicht**: Historie vollständig vorhanden oder Ursache bewiesen. Ein legitimer `NOT_APPLICABLE`-Eintrag, etwa keine vorherigen Logs bei Restartcount 0, ist keine technische Lücke. Fehlende erwartete historische Daten, Zugriffssperren und Budgetabbrüche führen mindestens zu einer Warnung und in der Regel `partial`.

### 5.3 Zeitfenster und Standardbudgets

MUST: Bei einem abgeschlossenen Build als Standardfenster Start minus zwei Minuten bis Ende plus zwei Minuten verwenden und die Herleitung speichern. Bei laufendem Build bildet die Erfassungszeit die vorläufige obere Grenze. Ohne Buildzeit kein historisches Fenster erfinden: `timeBasis: unknown`, aktuelle Erfassung explizit kennzeichnen.

MUST: Zeitfensterfilter begrenzen Log-/Eventdaten, verwandeln aktuelle API-Antworten aber nicht in historische Snapshots. Ein Event, das über mehrere Zeitpunkte aggregiert wurde, darf als zeitlich überlappender Beleg aufgenommen werden; seine Zählung nicht vollständig dem gewählten Fenster zuschreiben.

MUST: Die Standardbudgets aus der Beispielkonfiguration implementieren. Lokale Änderungen dürfen nur innerhalb dokumentierter Hardlimits gelten: maximal 180 Sekunden Collection, 16 Pods, 1000 Events, 120 Quellaufrufe, 64 MiB gesamter Eingang und 64 KiB einzelne MCP-Evidenceantwort. Ein einzelner Berichtsentwurf darf höchstens 256 KiB gross sein. Diese Grenzen sind Entwurfswerte für V1, keine gemessenen AGI-Anforderungen.

### 5.4 Evidence-Metadaten

MUST pro Evidence: ID, Typ, Source-ID, Erfassungszeit, fachlicher Zeitbezug soweit bekannt, redigierte Provenienz und Locator. Beispielsweise:

```json
{
  "id": "EV-0004",
  "type": "kubernetes-event",
  "sourceId": "ocp",
  "observedAt": "2026-10-01T10:02:00Z",
  "object": {
    "kind": "Pod",
    "namespace": "fixture-integration",
    "name": "fixture-modelfinder-a1",
    "uid": "fixture-pod-001"
  },
  "locator": {
    "path": "evidence/EV-0004.txt",
    "lineCount": 12
  },
  "dataMode": "fixture"
}
```

Dieses Beispiel ist ein Ausschnitt; Hash und weitere Schemafelder werden von der Implementierung ergänzt. Die Fixture-Namen sind ausdrücklich keine echten Betriebsdaten.

MUST: Git-Evidence trägt Repo-ID, vollständigen Commit, Dateipfad und Quellpointer/Zeilenabbildung. Jenkins-Evidence trägt Job/Build und ursprüngliche Zeilen-/Byteposition, soweit verfügbar. Eventaggregation und fehlende Zeiten werden explizit dargestellt.

## 6. Diagnosisvertrag

### 6.1 Struktur

Die Implementierung liefert ein vollständiges `diagnosis-v1.schema.json`. Das folgende Beispiel ist synthetisch und beschreibt die gewünschte Form, keine reale Modelfinder-Diagnose.

```json
{
  "schemaVersion": 1,
  "snapshotId": "SNAPSHOT_ID",
  "language": "de-CH",
  "assessment": "probable_cause",
  "title": "Liveness-Prüfung unterbricht die Startphase",
  "summary": "Der Container wird während der Initialisierung nach Liveness-Fehlern neu gestartet. Ein zu kleines Startbudget ist wahrscheinlich.",
  "facts": [
    {
      "id": "F-1",
      "statement": "Das erfasste Event nennt einen Neustart nach fehlgeschlagener Liveness-Prüfung.",
      "evidence": [
        {"evidenceId": "EV-0004", "startLine": 3, "endLine": 6}
      ]
    }
  ],
  "hypotheses": [
    {
      "id": "H-1",
      "category": "startup-budget",
      "statement": "Die konfigurierte Startabsicherung ist für diese Initialisierung zu knapp.",
      "strength": "supported",
      "supports": ["F-1"],
      "counterEvidence": [],
      "limitations": ["Die nötige Startdauer ist für den fehlgeschlagenen Lauf nicht vollständig gemessen."]
    }
  ],
  "timeline": [
    {
      "at": null,
      "timeBasis": "unknown",
      "description": "Liveness-Fehler und anschliessender Neustart sind in der erfassten Ereignisfolge dokumentiert.",
      "evidence": [
        {"evidenceId": "EV-0004", "startLine": 1, "endLine": 8}
      ]
    }
  ],
  "recommendations": [
    {
      "description": "Eine Startup-Probe mit nachgewiesenem Health-Endpunkt und angemessenem Startbudget prüfen.",
      "rationale": "Die Startphase soll abgesichert werden, ohne die laufende Liveness-Prüfung unnötig zu verzögern.",
      "basedOnHypotheses": ["H-1"],
      "risk": "Ein zu grosses Budget könnte echte Startfehler später sichtbar machen.",
      "verification": "Nach menschlich freigegebener Änderung Startdauer, Ready-Zustand, Neustarts und Jenkins-Rolloutergebnis prüfen.",
      "executed": false
    }
  ],
  "openQuestions": ["Welches Startbudget deckt die beobachteten erfolgreichen Starts zuverlässig ab?"],
  "limitations": ["Synthetischer Beispieldatensatz; keine Live-Abnahme."],
  "actionsTaken": []
}
```

### 6.2 Regeln

MUST: `assessment` aus `cause_identified`, `probable_cause`, `multiple_hypotheses`, `insufficient_evidence`, `no_failure_evidenced` wählen. „Kein Fehler belegt“ bedeutet nicht „System ist fehlerfrei“.

MUST: Jede umgebungsspezifische Tatsachenbehauptung im Bericht auf Evidence oder auf bereits belegte Fact-IDs stützen. Allgemeines Runbookwissen ist kein Beleg dafür, dass ein Ereignis tatsächlich stattgefunden hat. Fact-/Hypothesen-/Evidence-Referenzen müssen existieren und zum selben Snapshot gehören.

MUST: `strength` qualitativ: `supported`, `plausible`, `weak`, `contradicted`. Keine frei erfundenen Prozentwerte. `cause_identified` verlangt eine nachvollziehbare beobachtete Kausalkette; insbesondere keine exakte historische Zuordnung aus `ambiguous`-/`unlinked`-Links.

MUST: `actionsTaken` bleibt in V1 leer. Die deterministische Darstellung ergänzt „Keine Änderungen an den untersuchten Systemen ausgeführt“. Lokale Speicherung ist im Bericht nicht als Reparatur zu zählen.

MUST: `timeline.at: null` zulassen. Keine Zeitstempel aus Logzeilennummern, Reihenfolge oder Annahmen erzeugen. Ableitungen als solche kennzeichnen.

MUST: `ops_store_report` prüft formale Konsistenz und plausibilisierbare Statusregeln. Ob ein Beleg den Text tatsächlich inhaltlich trägt, bleibt zusätzlich Gegenstand der Agentenabnahme und menschlichen Prüfung.

## 7. Fehlervertrag

```json
{
  "code": "TLS_UNTRUSTED_CA",
  "sourceId": "apps-deploy",
  "message": "Der konfigurierte Jenkins-Endpunkt konnte nicht mit den freigegebenen CA-Zertifikaten geprüft werden.",
  "retryable": false,
  "suggestedAction": "Die zuständige CA für diesen Jenkins-Endpunkt manuell prüfen und konfigurieren."
}
```

MUST mindestens unterscheiden:

`CONFIG_INVALID`, `CONFIG_INCOMPLETE`, `TARGET_AMBIGUOUS`, `TARGET_EXPIRED`, `SCOPE_DENIED`, `DNS_FAILURE`, `CONNECT_TIMEOUT`, `REQUEST_TIMEOUT`, `PROXY_AUTH_REQUIRED`, `TLS_UNTRUSTED_CA`, `AUTH_REQUIRED`, `FORBIDDEN`, `NOT_FOUND`, `UNSUPPORTED_WORKLOAD`, `NO_PREVIOUS_LOG`, `REVISION_UNKNOWN`, `REVISION_UNVERIFIED`, `REVISION_UNAVAILABLE`, `DATA_TRUNCATED`, `BUDGET_EXCEEDED`, `SOURCE_PARSE_ERROR`, `REPORT_INVALID`.

MUST: `NO_PREVIOUS_LOG` bei fehlenden vorherigen Logs nicht als erfolgreiche leere Logantwort verstecken. `NOT_FOUND` kann „gelöscht oder nicht sichtbar“ bedeuten; bei privaten GitHub-Repositories wäre etwa 404 kein sicherer Existenzbeweis – V1 verwendet dafür ohnehin den lokalen Git-Checkout.

MUST: Keine automatischen Auth-Fallbacks. Höchstens ein begrenzter Retry bei tatsächlich transientem Transportfehler innerhalb desselben Budgets, niemals bei 401/403/407 oder fehlendem Vertrauen.

## 8. Diagnoseclient und Agent-Anweisung

MUST: Eine gemeinsame deutschsprachige Diagnoseanweisung liefern. Ablauf: Ziel klären → Target auflösen → Snapshot sammeln oder vorhandenen Snapshot öffnen → relevante Evidence lesen → konkurrierende Erklärungen prüfen → strukturierten Bericht speichern → Kurzfassung mit Belegen anzeigen.

MUST: Nicht bloss den ersten Snapshot-Überblick umformulieren. Der tatsächliche Agentenlauf muss die relevanten Event-/Log-/Konfigurationsbelege über `ops_read_evidence` gelesen haben. Zusätzliche Leseaufrufe sollen gezielt sein und das Toolbudget respektieren.

MUST: Fixture-Diagnose von Codex und/oder OpenCode praktisch prüfen. Beide Client-Beispielkonfigurationen liefern, getestete Versionen angeben. Codex unterstützt lokale MCP-Server und Tool-Allowlists; verfügbare Clientoptionen sind versionsabhängig. [Q11](QUELLEN.md#q11), [Q12](QUELLEN.md#q12)

MUST: Diagnosekonfiguration von der Coding-Konfiguration trennen. Im Diagnosemodus nur `ops_*`-Werkzeuge aktivieren, freie Shell/Exec, allgemeine Edits und Websuche deaktivieren. Die konkrete Option nicht nur dokumentieren, sondern anhand verfügbarer Tools/Negativversuch verifizieren. Ein nicht eingeschränktes Coding-Profil nicht als sicheren Live-Diagnosemodus ausgeben.

MUST: Lokale Write-Sandbox des Clients nicht als Sicherheitsgrenze für extern gestartete MCP-Prozesse oder entfernte Systeme darstellen. Die erlaubte lokale Berichtsablage wird ausschliesslich vom MCP kontrolliert.

OUT: Eigener Codex-SDK-Wrapper, automatische Modellwahl, providerabhängiger Prompt im Java-Code, `danger-full-access` und automatische Hintergrundausführung.
