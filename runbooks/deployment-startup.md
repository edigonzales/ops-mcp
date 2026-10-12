# Runbook: Deployment-Startfehler in OpenShift

Fachliches Wissen für die Diagnose von Deploymentfehlern. Dieses Runbook ist **Hintergrundwissen**
und **kein Beleg** dafür, dass ein bestimmtes Ereignis in einem konkreten Fall stattgefunden hat.
Jede umgebungsspezifische Aussage im Bericht braucht eine Evidence-Referenz.

---

## 1. Probe-Semantik

| Probe | Zweck | Startet den Container neu? |
|---|---|---|
| `readinessProbe` | Bereitschaft für Serviceverkehr. Bei Fehlschlag wird der Pod aus den Endpoints genommen. | **Nein** |
| `livenessProbe` | Erkennt einen hängenden Container. Wiederholtes Fehlschlagen führt zum Neustart. | **Ja, nach `failureThreshold` Fehlschlägen** |
| `startupProbe` | Sichert eine lange Initialisierung ab. Solange sie läuft, sind Liveness und Readiness ausgesetzt. | Ja, bei wiederholtem Fehlschlagen |

Konsequenzen für die Diagnose:

- Eine fehlgeschlagene **Readiness** ist **keine** Neustartursache. „Readiness failed“ darf nie
  als bewiesene Neustartursache ausgegeben werden.
- `restartCount` und `lastState` sind die belastbaren Neustartbelege am Container.
- **Exitcode 137** bedeutet „durch Signal 9 beendet“. Das kann OOMKilled sein, muss aber nicht.
  Erst der Beendigungsgrund `OOMKilled` belegt einen OOM.
- Ein exakt berechneter Kill-Zeitpunkt aus
  `initialDelaySeconds + failureThreshold × periodSeconds` ist **keine** Belegaussage.
  Massgeblich sind beobachtete Zeiten, Probe-Timeouts und Eventaggregation.

### 1.1 Startbudget und Startup-Probe

Eine Startup-Probe ist **nur bei passendem Problem** eine mögliche Verbesserung. Das erforderliche
Budget muss aus der **beobachteten** Startdauer und einer begründeten Reserve entstehen.

- Kein Health-Pfad und keine „richtige“ Anzahl Sekunden erfinden.
- Ohne Endpunktnachweis ist eine **bedingte** Empfehlung richtig, kein kopierfertiger,
  scheinbar verifizierter YAML-Block.
- Eine vorgeschlagene Probe darf keine echte Fehlkonfiguration oder dauerhaft unerreichbare
  Abhängigkeit verdecken.

---

## 2. Zu unterscheidende Ursachen

| Hypothese | Erforderliche Prüfung | Typische Belege |
|---|---|---|
| Liveness greift zu früh in den Start ein | Startup-Phase im Log, Liveness-Fehler, Killing/Restart, passender Container und Zeitablauf | `Liveness probe failed`, `Killing container`, `restartCount > 0`, `lastState.reason` |
| Einzelne Probe hat ein zu kleines Antwortzeitlimit | `timeoutSeconds`, Timeout-Meldung, belastbare Beobachtung der Antwort-/Lastsituation | `Probe timed out`, `timeoutSeconds` in der Konfiguration |
| Readiness bleibt negativ | Probe-Fehler und NotReady; **keine** Neustartbehauptung | `Readiness probe failed`, `Ready=False`, `restartCount == 0` |
| Falscher Pfad, Port oder Protokoll | tatsächliche Probe-Konfiguration und konkreter Fehler (z. B. 404); Endpointbelege | `HTTP probe failed with statuscode: 404`, abweichender Pfad im Startlog |
| Echter Anwendungsfehler | fataler Startfehler, Exit, OOMKilled oder Abhängigkeitsfehler; Probe kann Folge sein | Stacktrace, `FATAL`, `Connection refused` auf eine Abhängigkeit |
| Jenkins wartet zu kurz | Rollout-/Pipeline-Zeitlimit und tatsächlicher Startfortschritt | Console-Log-Timeout, `ProgressDeadlineExceeded` |
| Fehler vor dem Containerstart | ImagePull, Scheduling, Mounts, Berechtigung oder fehlgeschlagener Jenkins-Schritt | `FailedScheduling`, `ImagePullBackOff`, `FailedMount` |
| Historische Belege fehlen | Ursache als unklar ausgeben und fehlende Belege benennen | fehlende Pod-UIDs, keine Events im Fenster, kein vorheriges Log |

---

## 3. Workload- und Rollout-Zustände

| Beobachtung | Bedeutung |
|---|---|
| `ProgressDeadlineExceeded` | Der Rollout hat sein Zeitlimit überschritten. Das ist **kein** Beweis für eine Probe als Ursache. |
| `updatedReplicas < desiredReplicas` | Der Rollout läuft noch. |
| `readyReplicas == desiredReplicas` und `updatedReplicas == desiredReplicas` | Der Rollout ist abgeschlossen. |
| `observedGeneration < generation` | Der Controller hat die letzte Spec-Änderung noch nicht verarbeitet. |
| `ReplicaFailure=True` | Der Controller konnte Pods nicht erzeugen. |

„Jenkins meldet Rollout-Timeout“ und „die Anwendung wurde durch eine Probe neu gestartet“ sind
**verschiedene** Aussagen.

---

## 4. Owner-Ketten und Zuordnung

```text
Deployment           -> ReplicaSet             -> Pod
DeploymentConfig     -> ReplicationController  -> Pod
```

- Zugehörigkeit wird über **UID und Kind** nachgewiesen, nicht über Namen oder Labels.
- Gleichnamige, neu erzeugte Pods haben eine **andere UID**. Events der alten UID gehören nicht
  zur neuen Instanz.
- Bei mehreren plausiblen Revisionen mit gleichem Image-Tag bleibt die Verbindung zum
  Jenkins-Lauf **mehrdeutig** (`ambiguous`). Zeitnähe und gleicher Image-Tag sind kein exakter
  Nachweis.

---

## 5. Zeitliche Begrenztheit der Belege

- Aktuelle OpenShift-Abfragen liefern den **jetzt** verfügbaren Zustand, keine historische
  Zeitreise – auch bei einer `since`-Filterung der Logs.
- Kubernetes-Events werden begrenzt aufbewahrt. Ein Event, das über mehrere Zeitpunkte
  aggregiert wurde, ist ein **zeitlich überlappender** Beleg; seine Zählung darf nicht
  vollständig dem gewählten Fenster zugeschrieben werden.
- `--previous` liefert nur Logs des **vorherigen** Containerprozesses im **gleichen** Pod.
  Ein gelöschter Pod wird dadurch **nicht** wiederhergestellt.
- Ein inzwischen gesundes Deployment widerlegt einen früheren Fehler nicht. Ein aktuelles
  Probe-Problem beweist nicht die Ursache eines älteren Jenkins-Laufs.
- Fehlen die historischen Belege, ist `insufficient_evidence` ein **korrektes** Ergebnis.

---

## 6. Jenkins-Fehlerstufen

| Stufe | Erkennungsmerkmal | Konsequenz |
|---|---|---|
| Jenkins-Infrastruktur | Checkout-Fehler, Pipeline-/Agentenzugriff, Berechtigung | Kein Rollout. Probes einer alten Version sind **nicht** ursächlich. |
| Deployment-Anwendung | Fehler beim Anwenden der Manifeste | Prüfen, ob überhaupt ein neuer Rollout entstanden ist. |
| Rollout/Containerstart | Warten auf Rollout, `ProgressDeadlineExceeded` | Startfortschritt und Probe-Konfiguration prüfen. |
| Anwendung selbst | fataler Startfehler, Exit, OOMKilled | Probe kann **Folge** statt Ursache sein. |

Ein laufender oder abgebrochener Build ist **nicht automatisch** ein Deploymentfehler.
`lastFailed` ist **nicht** `lastUnsuccessful`.

---

## 7. Empfehlungen formulieren

Jede Empfehlung ist **Text** mit `executed: false` und enthält:

1. die Beschreibung der vorgeschlagenen, **nicht ausgeführten** Änderung,
2. die Begründung mit Bezug auf Hypothesen-IDs,
3. das **Risiko** (z. B. „ein zu grosses Startbudget könnte echte Startfehler später sichtbar
   machen“),
4. den Vorschlag zur **späteren menschlichen Verifikation** (z. B. „nach freigegebener Änderung
   Startdauer, Ready-Zustand, Neustarts und Jenkins-Rolloutergebnis prüfen“).

Keine Prozent-Konfidenz. Keine erfundene „richtige“ Sekundenzahl. Keine angewendete Änderung.
