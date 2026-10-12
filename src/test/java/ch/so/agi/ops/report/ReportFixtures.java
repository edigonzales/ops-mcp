package ch.so.agi.ops.report;

import ch.so.agi.ops.config.YamlSupport;
import ch.so.agi.ops.domain.AssessmentStatus;
import ch.so.agi.ops.domain.CollectionStatus;
import ch.so.agi.ops.domain.DataMode;
import ch.so.agi.ops.domain.EvidenceType;
import ch.so.agi.ops.domain.LinkStrength;
import ch.so.agi.ops.domain.Locator;
import ch.so.agi.ops.domain.ObjectRef;
import ch.so.agi.ops.domain.SourceStatus;
import ch.so.agi.ops.report.DiagnosisDocument.EvidenceRef;
import ch.so.agi.ops.report.DiagnosisDocument.Fact;
import ch.so.agi.ops.report.DiagnosisDocument.Hypothesis;
import ch.so.agi.ops.report.DiagnosisDocument.Recommendation;
import ch.so.agi.ops.report.DiagnosisDocument.Strength;
import ch.so.agi.ops.report.DiagnosisDocument.TimelineEntry;
import ch.so.agi.ops.security.Redactor;
import ch.so.agi.ops.snapshot.EvidenceIndexEntry;
import ch.so.agi.ops.snapshot.InvestigationWindow;
import ch.so.agi.ops.snapshot.RedactionSummary;
import ch.so.agi.ops.snapshot.SnapshotLink;
import ch.so.agi.ops.snapshot.SnapshotManifest;
import ch.so.agi.ops.snapshot.SourceSummary;
import ch.so.agi.ops.snapshot.StoredSnapshot;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Synthetische Fixtures fuer die Berichtstests. Keine Live-Daten, kein Netz, keine echten
 * Betriebsangaben; die Namen sind ausdruecklich fiktiv.
 */
final class ReportFixtures {

    static final String SNAPSHOT_ID = "20261001T100000Z-abcd1234";
    static final String OTHER_SNAPSHOT_ID = "20261002T100000Z-efgh5678";
    static final String EVIDENCE_LOG = "EV-0001";
    static final String EVIDENCE_EVENT = "EV-0004";

    private static final Instant COLLECTED_AT = Instant.parse("2026-10-01T10:01:00Z");
    private static final Instant STARTED_AT = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant FINISHED_AT = Instant.parse("2026-10-01T10:01:00Z");

    private ReportFixtures() {
    }

    static StoredSnapshot snapshot() {
        return snapshot(SNAPSHOT_ID, LinkStrength.EXACT, CollectionStatus.COMPLETE, 12);
    }

    static StoredSnapshot snapshot(String snapshotId) {
        return snapshot(snapshotId, LinkStrength.EXACT, CollectionStatus.COMPLETE, 12);
    }

    static StoredSnapshot snapshot(String snapshotId, LinkStrength linkStrength) {
        return snapshot(snapshotId, linkStrength, CollectionStatus.COMPLETE, 12);
    }

    static StoredSnapshot snapshot(String snapshotId, LinkStrength linkStrength, CollectionStatus collectionStatus,
            int eventLineCount) {
        SnapshotLink link = new SnapshotLink(
                "build:apps-deploy/datenportal#42",
                "workload:ocp/fixture-integration/Deployment/fixture-modelfinder",
                "build-to-workload",
                linkStrength,
                "Synthetischer Link fuer den Test.",
                List.of(EVIDENCE_LOG),
                List.of());
        SnapshotManifest manifest = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION,
                snapshotId,
                SnapshotManifest.USE_CASE_DEPLOYMENT_FAILURE,
                DataMode.FIXTURE,
                "modelfinder",
                "integration",
                new SnapshotManifest.BuildRefView("apps-deploy", List.of("datenportal"), 42, true),
                "ocp",
                "fixture-integration",
                "Deployment",
                "fixture-modelfinder",
                STARTED_AT,
                FINISHED_AT,
                new InvestigationWindow(STARTED_AT, FINISHED_AT,
                        InvestigationWindow.TimeBasis.BUILD_START_AND_END, "Synthetische Herleitung fuer den Test."),
                collectionStatus,
                List.of(new SourceSummary("ocp", SourceStatus.COLLECTED, COLLECTED_AT,
                        List.of(EVIDENCE_LOG, EVIDENCE_EVENT), List.of(), List.of())),
                List.of(link),
                List.of(
                        evidence(EVIDENCE_LOG, EvidenceType.KUBERNETES_CONTAINER_LOG, 5),
                        evidence(EVIDENCE_EVENT, EvidenceType.KUBERNETES_EVENT, eventLineCount)),
                RedactionSummary.empty(Redactor.RULE_VERSION),
                Map.of("java", "25"),
                List.of(),
                List.of());
        return new StoredSnapshot(manifest, YamlSupport.jsonMapper().readTree("{}"),
                Path.of("build", "tmp", "fixture-snapshot-" + snapshotId));
    }

    /** Snapshot ohne jede Zeitangabe und ohne Links; fuer den Nachweis, dass nichts erfunden wird. */
    static StoredSnapshot snapshotWithoutTimesAndLinks() {
        SnapshotManifest manifest = new SnapshotManifest(
                SnapshotManifest.SCHEMA_VERSION,
                SNAPSHOT_ID,
                SnapshotManifest.USE_CASE_DEPLOYMENT_FAILURE,
                DataMode.FIXTURE,
                "modelfinder",
                "integration",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                CollectionStatus.PARTIAL,
                List.of(),
                List.of(),
                List.of(evidence(EVIDENCE_EVENT, EvidenceType.KUBERNETES_EVENT, 12)),
                null,
                Map.of(),
                List.of(),
                List.of());
        return new StoredSnapshot(manifest, YamlSupport.jsonMapper().readTree("{}"),
                Path.of("build", "tmp", "fixture-snapshot-without-times"));
    }

    private static EvidenceIndexEntry evidence(String id, EvidenceType type, int lineCount) {
        return new EvidenceIndexEntry(
                id,
                type,
                "ocp",
                ObjectRef.of("Pod", "fixture-integration", "fixture-modelfinder-a1"),
                COLLECTED_AT,
                COLLECTED_AT,
                "evidence/" + id + ".txt",
                "0".repeat(64),
                lineCount,
                128L,
                Locator.lines("fixtures/" + id, 1, lineCount),
                DataMode.FIXTURE,
                List.of(),
                "Synthetischer Beleg fuer den Test.",
                List.of());
    }

    static DocumentBuilder document() {
        return new DocumentBuilder();
    }

    static Fact fact(String id, String statement, EvidenceRef... evidence) {
        return new Fact(id, statement, List.of(evidence));
    }

    static EvidenceRef ref(String evidenceId) {
        return EvidenceRef.of(evidenceId);
    }

    static EvidenceRef ref(String evidenceId, Integer startLine, Integer endLine) {
        return new EvidenceRef(evidenceId, startLine, endLine);
    }

    static Hypothesis hypothesis(String id, Strength strength, List<String> supports) {
        return new Hypothesis(id, "startup-budget", "Synthetische Hypothese.", strength, supports,
                List.of(), List.of("Synthetische Grenze."));
    }

    static Recommendation recommendation(String description, boolean executed) {
        return new Recommendation(description, "Synthetische Begruendung.", List.of("H-1"),
                "Synthetisches Risiko.", "Synthetische Verifikation.", executed);
    }

    static TimelineEntry timelineEntry(String at) {
        return new TimelineEntry(at, at == null ? "unknown" : "event-lastTimestamp",
                "Synthetisches Ereignis.", List.of(ref(EVIDENCE_EVENT)));
    }

    /** Builder mit gueltigen Standardwerten; einzelne Felder werden je Test ersetzt. */
    static final class DocumentBuilder {

        private String snapshotId = SNAPSHOT_ID;
        private int schemaVersion = DiagnosisDocument.SCHEMA_VERSION;
        private String language = DiagnosisDocument.LANGUAGE_DE_CH;
        private AssessmentStatus assessment = AssessmentStatus.PROBABLE_CAUSE;
        private String title = "Liveness-Prüfung unterbricht die Startphase";
        private String summary = "Der Container wird während der Initialisierung neu gestartet.";
        private List<Fact> facts = List.of(fact("F-1",
                "Das erfasste Event nennt einen Neustart nach fehlgeschlagener Liveness-Prüfung.",
                ref(EVIDENCE_EVENT, 3, 6)));
        private List<Hypothesis> hypotheses = List.of(hypothesis("H-1", Strength.SUPPORTED, List.of("F-1")));
        private List<TimelineEntry> timeline = List.of(timelineEntry(null));
        private List<Recommendation> recommendations = List.of(recommendation("Startbudget prüfen.", false));
        private List<String> openQuestions = List.of("Welches Startbudget deckt die beobachteten Starts ab?");
        private List<String> limitations = List.of("Synthetischer Beispieldatensatz; keine Live-Abnahme.");
        private List<String> actionsTaken = List.of();

        private DocumentBuilder() {
        }

        DocumentBuilder snapshotId(String value) {
            this.snapshotId = value;
            return this;
        }

        DocumentBuilder schemaVersion(int value) {
            this.schemaVersion = value;
            return this;
        }

        DocumentBuilder language(String value) {
            this.language = value;
            return this;
        }

        DocumentBuilder assessment(AssessmentStatus value) {
            this.assessment = value;
            return this;
        }

        DocumentBuilder title(String value) {
            this.title = value;
            return this;
        }

        DocumentBuilder summary(String value) {
            this.summary = value;
            return this;
        }

        DocumentBuilder facts(List<Fact> value) {
            this.facts = value;
            return this;
        }

        DocumentBuilder facts(Fact... value) {
            return facts(List.of(value));
        }

        DocumentBuilder hypotheses(List<Hypothesis> value) {
            this.hypotheses = value;
            return this;
        }

        DocumentBuilder hypotheses(Hypothesis... value) {
            return hypotheses(List.of(value));
        }

        DocumentBuilder timeline(List<TimelineEntry> value) {
            this.timeline = value;
            return this;
        }

        DocumentBuilder timeline(TimelineEntry... value) {
            return timeline(List.of(value));
        }

        DocumentBuilder recommendations(List<Recommendation> value) {
            this.recommendations = value;
            return this;
        }

        DocumentBuilder recommendations(Recommendation... value) {
            return recommendations(List.of(value));
        }

        DocumentBuilder openQuestions(List<String> value) {
            this.openQuestions = value;
            return this;
        }

        DocumentBuilder limitations(List<String> value) {
            this.limitations = value;
            return this;
        }

        DocumentBuilder actionsTaken(List<String> value) {
            this.actionsTaken = value;
            return this;
        }

        DiagnosisDocument build() {
            return new DiagnosisDocument(schemaVersion, snapshotId, language, assessment, title, summary, facts,
                    hypotheses, timeline, recommendations, openQuestions, limitations, actionsTaken);
        }
    }
}
