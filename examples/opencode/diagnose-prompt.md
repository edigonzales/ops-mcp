# Diagnoseauftrag für OpenCode

Diesen Text als Prompt in OpenCode verwenden (Diagnoseprofil aus `opencode.json`). Der Auftrag nennt **nur** Anwendung, Umgebung und Build –
nicht die erwartete Ursache.

---

Untersuche den fehlgeschlagenen Deploymentlauf und erkläre mit Belegen, warum er gescheitert
ist. Verändere nichts an den untersuchten Systemen. Verwende ausschliesslich die `ops_*`-Werkzeuge.

- Anwendung: `modelfinder`
- Umgebung: `integration`
- Build: `42`

Vorgehen:

1. Auflösen des Ziels mit `ops_resolve_target`.
2. Sammeln eines Snapshots mit `ops_collect_snapshot`.
3. Lesen der relevanten Belege mit `ops_read_evidence`: Jenkins-Console-Log an der Fehlerstelle,
   Pod- und Containerzustände, Killing-/Readiness-/Timeout-Events, Startlog und die aufgelöste
   Probe-Konfiguration. Die Snapshotübersicht allein genügt **nicht**.
4. Prüfen konkurrierender Erklärungen (Startbudget der Liveness-Prüfung, Antwortzeitlimit einer
   Probe, negativ bleibende Readiness, falscher Pfad/Port/Protokoll, echter Anwendungsfehler,
   zu kurzes Jenkins-Zeitlimit, Fehler vor dem Containerstart, fehlende historische Belege).
5. Speichern des strukturierten Berichts mit `ops_store_report`.

Anforderungen an den Bericht:

- Trenne **Fakten** (mit `evidenceId` und Zeilenbereich belegt) von **Hypothesen**
  (qualitative `strength`: `supported`, `plausible`, `weak`, `contradicted`).
- Behaupte **nicht**, eine fehlgeschlagene Readiness-Prüfung habe den Container neu gestartet.
- Erfinde keine Zeitstempel, Endpunkte, Budgetwerte oder Job-/Namespace-/Revisionsangaben.
  Wenn eine Messung fehlt, benenne sie als fehlend.
- `actionsTaken` bleibt leer. Vorschläge sind Text mit `executed: false`, Risiko und
  vorgeschlagener menschlicher Verifikation.
- Nenne am Ende die Kurzfassung mit den tragenden Belegreferenzen und das, was offen bleibt.

---

## Erwartete Werkzeugfolge

```text
ops_resolve_target   -> targetId, konkrete Buildnummer, Namespace, Workload
ops_collect_snapshot -> snapshotId, collectionStatus, Quellenstatus, Evidence-Index
ops_get_snapshot     -> Überblick und mechanische Befunde
ops_read_evidence    -> mehrere gezielte Ausschnitte (Event, Pod, Log, Konfiguration)
ops_store_report     -> reportId, lokaler relativer Pfad, Validierungsstatus
```
