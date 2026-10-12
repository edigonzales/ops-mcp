package ch.so.agi.ops.report;

import ch.so.agi.ops.domain.AssessmentStatus;
import ch.so.agi.ops.domain.CollectionStatus;
import ch.so.agi.ops.domain.DataMode;
import ch.so.agi.ops.report.DiagnosisDocument.EvidenceRef;
import ch.so.agi.ops.report.DiagnosisDocument.Fact;
import ch.so.agi.ops.report.DiagnosisDocument.Hypothesis;
import ch.so.agi.ops.report.DiagnosisDocument.Recommendation;
import ch.so.agi.ops.report.DiagnosisDocument.Strength;
import ch.so.agi.ops.report.DiagnosisDocument.TimelineEntry;
import ch.so.agi.ops.snapshot.SnapshotManifest;
import ch.so.agi.ops.snapshot.SourceSummary;
import ch.so.agi.ops.snapshot.StoredSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Deterministische Markdown-Darstellung eines Diagnoseberichts.
 *
 * <p>Die Reihenfolge folgt dem Dokument; gleiche Eingaben ergeben gleiche Ausgaben. Alle
 * Textinhalte werden HTML-escaped, es werden keine Markdown-Bilder und keine Links auf
 * fremde Hosts erzeugt (S10). Der Bericht endet immer mit dem Satz
 * {@code Keine Änderungen an den untersuchten Systemen ausgeführt.}</p>
 *
 * <p>Fehlende Zeitangaben werden als "nicht belegt" ausgewiesen; es werden keine
 * Zeitstempel aus Zeilennummern, Reihenfolge oder Annahmen erzeugt.</p>
 */
public final class DiagnosisMarkdownRenderer {

    /** Deterministischer Schlusssatz; lokale Speicherung ist keine Reparatur. */
    public static final String NO_CHANGES_SENTENCE = "Keine Änderungen an den untersuchten Systemen ausgeführt.";

    private static final String NOT_DOCUMENTED = "nicht belegt";
    private static final String NONE = "keine";

    public DiagnosisMarkdownRenderer() {
    }

    public String render(DiagnosisDocument document, StoredSnapshot snapshot) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(snapshot, "snapshot");
        SnapshotManifest manifest = snapshot.manifest();

        StringBuilder md = new StringBuilder(4096);
        md.append("# ").append(inlineOrDefault(document.title(), "Diagnosebericht")).append("\n\n");
        md.append("- **Bewertung:** ").append(assessmentLabel(document.assessment()))
                .append(" (`").append(document.assessment().wireName()).append("`)\n");
        md.append("- **Sprache:** ").append(inlineOrDefault(document.language(), NOT_DOCUMENTED)).append("\n\n");

        md.append("## Zusammenfassung\n\n");
        appendBlock(md, document.summary());

        md.append("## Untersuchter Lauf und Umgebung\n\n");
        renderRun(md, manifest);

        md.append("## Fakten\n\n");
        if (document.facts().isEmpty()) {
            md.append("Keine Fakten dokumentiert.\n\n");
        } else {
            for (int i = 0; i < document.facts().size(); i++) {
                Fact fact = document.facts().get(i);
                md.append(i + 1).append(". **").append(inline(fact.id())).append("** — ")
                        .append(inline(fact.statement())).append("\n");
                md.append("   - Belege: ").append(evidenceList(fact.evidence())).append("\n");
            }
            md.append("\n");
        }

        md.append("## Hypothesen\n\n");
        md.append("Die folgenden Hypothesen sind Erklärungen, keine belegten Fakten; ")
                .append("belegte Fakten stehen ausschließlich im Abschnitt \"Fakten\".\n\n");
        if (document.hypotheses().isEmpty()) {
            md.append("Keine Hypothesen dokumentiert.\n\n");
        } else {
            for (int i = 0; i < document.hypotheses().size(); i++) {
                Hypothesis hypothesis = document.hypotheses().get(i);
                md.append(i + 1).append(". **").append(inline(hypothesis.id())).append("** — Belegstärke: ")
                        .append(strengthLabel(hypothesis.strength())).append(" (`")
                        .append(hypothesis.strength().wireName()).append("`)\n");
                md.append("   - Kategorie: ").append(inlineOrDefault(hypothesis.category(), NOT_DOCUMENTED)).append("\n");
                md.append("   - Aussage: ").append(inline(hypothesis.statement())).append("\n");
                md.append("   - Stützt sich auf Fakten: ").append(listOrNone(hypothesis.supports())).append("\n");
                md.append("   - Gegenbelege: ").append(evidenceList(hypothesis.counterEvidence())).append("\n");
                md.append("   - Grenzen: ").append(listOrNone(hypothesis.limitations())).append("\n");
            }
            md.append("\n");
        }

        md.append("## Zeitleiste\n\n");
        if (document.timeline().isEmpty()) {
            md.append("Keine Ereignisse dokumentiert.\n\n");
        } else {
            for (int i = 0; i < document.timeline().size(); i++) {
                TimelineEntry entry = document.timeline().get(i);
                md.append(i + 1).append(". ")
                        .append(entry.at() == null || entry.at().isBlank() ? "Zeitpunkt nicht belegt" : inline(entry.at()))
                        .append(" (Zeitbasis: ").append(inlineOrDefault(entry.timeBasis(), NOT_DOCUMENTED)).append(") — ")
                        .append(inline(entry.description())).append("\n");
                md.append("   - Belege: ").append(evidenceList(entry.evidence())).append("\n");
            }
            md.append("\n");
        }

        md.append("## Vorschläge (nicht ausgeführt)\n\n");
        if (document.recommendations().isEmpty()) {
            md.append("Keine Vorschläge dokumentiert.\n\n");
        } else {
            for (int i = 0; i < document.recommendations().size(); i++) {
                Recommendation recommendation = document.recommendations().get(i);
                md.append(i + 1).append(". ").append(inline(recommendation.description())).append("\n");
                md.append("   - Begründung: ").append(inlineOrDefault(recommendation.rationale(), NOT_DOCUMENTED)).append("\n");
                md.append("   - Beruht auf Hypothesen: ").append(listOrNone(recommendation.basedOnHypotheses())).append("\n");
                md.append("   - Risiko: ").append(inlineOrDefault(recommendation.risk(), NOT_DOCUMENTED)).append("\n");
                md.append("   - Verifikation: ").append(inlineOrDefault(recommendation.verification(), NOT_DOCUMENTED)).append("\n");
                md.append("   - Status: ").append(recommendation.executed()
                        ? "ausgeführt (in V1 unzulässig)" : "nicht ausgeführt").append("\n");
            }
            md.append("\n");
        }

        md.append("## Offene Fragen\n\n");
        if (document.openQuestions().isEmpty()) {
            md.append("Keine offenen Fragen dokumentiert.\n\n");
        } else {
            for (String question : document.openQuestions()) {
                md.append("- ").append(inline(question)).append("\n");
            }
            md.append("\n");
        }

        md.append("## Grenzen der Untersuchung\n\n");
        if (document.limitations().isEmpty()) {
            md.append("Keine Grenzen dokumentiert.\n\n");
        } else {
            for (String limitation : document.limitations()) {
                md.append("- ").append(inline(limitation)).append("\n");
            }
            md.append("\n");
        }

        md.append("## Herkunft\n\n");
        md.append("- Snapshot-ID: ").append(inlineOrDefault(manifest.snapshotId(), NOT_DOCUMENTED)).append("\n");
        md.append("- Datenmodus: ").append(dataModeLabel(manifest.dataMode())).append("\n");
        md.append("- Erfassungsstatus: ").append(collectionStatusLabel(manifest.collectionStatus())).append("\n");
        if (manifest.redaction() != null && manifest.redaction().ruleVersion() != null) {
            md.append("- Regelversion der Redaktion: ").append(inline(manifest.redaction().ruleVersion())).append("\n");
        }
        md.append("- Quellen:\n");
        if (manifest.sourceResults().isEmpty()) {
            md.append("  - ").append(NOT_DOCUMENTED).append("\n");
        } else {
            for (SourceSummary source : manifest.sourceResults()) {
                md.append("  - ").append(inlineOrDefault(source.sourceId(), NOT_DOCUMENTED)).append(": ")
                        .append(source.status() == null ? NOT_DOCUMENTED : source.status().name())
                        .append(" (").append(source.evidenceIds().size()).append(" Belege)\n");
            }
        }
        md.append("\n");

        md.append("## Ausgeführte Änderungen\n\n");
        md.append(NO_CHANGES_SENTENCE);
        return md.toString();
    }

    private static void renderRun(StringBuilder md, SnapshotManifest manifest) {
        md.append("- Anwendung: ").append(inlineOrDefault(manifest.applicationId(), NOT_DOCUMENTED)).append("\n");
        md.append("- Umgebung: ").append(inlineOrDefault(manifest.environment(), NOT_DOCUMENTED)).append("\n");
        if (manifest.buildRef() == null) {
            md.append("- Build: ").append(NOT_DOCUMENTED).append("\n");
        } else {
            SnapshotManifest.BuildRefView buildRef = manifest.buildRef();
            String jobPath = buildRef.jobPath().isEmpty() ? NOT_DOCUMENTED : String.join("/", buildRef.jobPath());
            String instance = buildRef.instanceId() == null || buildRef.instanceId().isBlank()
                    ? "" : inline(buildRef.instanceId()) + " ";
            md.append("- Build: ").append(instance).append(inline(jobPath))
                    .append(" #").append(buildRef.buildNumber())
                    .append(" (Metadaten verfügbar: ").append(buildRef.metadataAvailable() ? "ja" : "nein").append(")\n");
        }
        md.append("- Cluster: ").append(inlineOrDefault(manifest.clusterId(), NOT_DOCUMENTED)).append("\n");
        md.append("- Namespace: ").append(inlineOrDefault(manifest.namespace(), NOT_DOCUMENTED)).append("\n");
        md.append("- Workload: ").append(workload(manifest)).append("\n");
        md.append("- Erfassung: ").append(instant(manifest.collectionStartedAt())).append(" bis ")
                .append(instant(manifest.collectionFinishedAt())).append("\n");
        if (manifest.investigationWindow() == null) {
            md.append("- Untersuchungsfenster: ").append(NOT_DOCUMENTED).append("\n\n");
        } else {
            md.append("- Untersuchungsfenster: ").append(instant(manifest.investigationWindow().from())).append(" bis ")
                    .append(instant(manifest.investigationWindow().to())).append(" (Zeitbasis: ")
                    .append(manifest.investigationWindow().timeBasis() == null
                            ? NOT_DOCUMENTED : manifest.investigationWindow().timeBasis().wireName())
                    .append(")\n\n");
        }
    }

    private static String workload(SnapshotManifest manifest) {
        String kind = manifest.workloadKind();
        String name = manifest.workloadName();
        if ((kind == null || kind.isBlank()) && (name == null || name.isBlank())) {
            return NOT_DOCUMENTED;
        }
        return inline(kind) + "/" + inline(name);
    }

    private static String instant(Instant value) {
        return value == null ? NOT_DOCUMENTED : value.toString();
    }

    private static void appendBlock(StringBuilder md, String text) {
        md.append(text == null || text.isBlank() ? NOT_DOCUMENTED : block(text)).append("\n\n");
    }

    private static String evidenceList(List<EvidenceRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return NONE;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < refs.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(reference(refs.get(i)));
        }
        return sb.toString();
    }

    /** Belegreferenz als reiner Text, zum Beispiel {@code EV-0004 Zeile 3-6} oder {@code EV-0004}. */
    private static String reference(EvidenceRef ref) {
        StringBuilder sb = new StringBuilder(inline(ref.evidenceId()));
        if (ref.startLine() != null && ref.endLine() != null) {
            sb.append(" Zeile ").append(ref.startLine()).append("-").append(ref.endLine());
        } else if (ref.startLine() != null) {
            sb.append(" Zeile ").append(ref.startLine());
        } else if (ref.endLine() != null) {
            sb.append(" Zeile ").append(ref.endLine());
        }
        return sb.toString();
    }

    private static String listOrNone(List<String> values) {
        if (values == null || values.isEmpty()) {
            return NONE;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(inline(values.get(i)));
        }
        return sb.toString();
    }

    private static String assessmentLabel(AssessmentStatus assessment) {
        return switch (assessment) {
            case CAUSE_IDENTIFIED -> "Ursache belegt";
            case PROBABLE_CAUSE -> "Wahrscheinliche Ursache";
            case MULTIPLE_HYPOTHESES -> "Mehrere Hypothesen";
            case INSUFFICIENT_EVIDENCE -> "Belege unzureichend";
            case NO_FAILURE_EVIDENCED -> "Kein Fehler belegt";
        };
    }

    private static String strengthLabel(Strength strength) {
        return switch (strength) {
            case SUPPORTED -> "belegt";
            case PLAUSIBLE -> "plausibel";
            case WEAK -> "schwach";
            case CONTRADICTED -> "widersprochen";
        };
    }

    private static String dataModeLabel(DataMode dataMode) {
        if (dataMode == null) {
            return NOT_DOCUMENTED;
        }
        return dataMode.wireName() + " (" + (dataMode == DataMode.FIXTURE
                ? "synthetische Daten" : "Erfassung am System") + ")";
    }

    private static String collectionStatusLabel(CollectionStatus status) {
        if (status == null) {
            return NOT_DOCUMENTED;
        }
        return switch (status) {
            case COMPLETE -> "complete (geplante Erfassung ohne technische Lücke abgeschlossen)";
            case PARTIAL -> "partial (Erfassung mit Lücken)";
            case FAILED -> "failed (keine verwertbare Untersuchung)";
        };
    }

    /** Einzeilige Darstellung: HTML-escaped, Zeilenumbrüche werden zu Leerzeichen. */
    private static String inline(String value) {
        if (value == null) {
            return "";
        }
        return block(value).replace('\n', ' ');
    }

    private static String inlineOrDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return inline(value);
    }

    /**
     * Blockdarstellung: HTML-escaped und auf {@code \n} normalisiert. Die Sequenz {@code ![}
     * wird neutralisiert, damit aus Textinhalten kein automatisch geladenes Bild entsteht.
     */
    private static String block(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder sb = new StringBuilder(normalized.length() + 16);
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString().replace("![", "!\\[");
    }
}
