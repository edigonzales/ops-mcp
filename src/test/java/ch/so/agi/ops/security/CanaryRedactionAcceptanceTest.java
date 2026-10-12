package ch.so.agi.ops.security;

import ch.so.agi.ops.domain.RawEvidence;
import ch.so.agi.ops.report.DiagnosisDocument;
import ch.so.agi.ops.report.StoredReport;
import ch.so.agi.ops.snapshot.SnapshotId;
import ch.so.agi.ops.snapshot.StoredSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S06 – Canary-Tokens an allen Ausgabestellen.
 *
 * <p>Belegt: Canary-Werte aus Logzeilen, {@code .env}-artigen Texten, HTTP-Fehlertexten,
 * Probe-Headern und einem Berichtsentwurf erscheinen weder im Manifest, noch in einer
 * {@code evidence/EV-*.txt}, in {@code normalized.json}, im Markdown-Bericht oder in der
 * strukturierten Antwort von {@code ops_read_evidence}.</p>
 */
class CanaryRedactionAcceptanceTest {

    @TempDir
    Path tempDir;

    private static final String C = AcceptanceSupport.CANARY;

    private static List<RawEvidence> evidenceMitCanaries() {
        List<RawEvidence> evidence = new ArrayList<>();
        evidence.add(AcceptanceSupport.logEvidence("k8s-log-1", List.of(
                "2026-10-01T10:00:01Z INFO Container gestartet",
                "Authorization: Bearer " + C,
                "Set-Cookie: session=" + C)));
        evidence.add(new RawEvidence("git-configuration", ch.so.agi.ops.domain.EvidenceType.GIT_CONFIGURATION,
                AcceptanceSupport.CLUSTER_ID, null, null, ch.so.agi.ops.domain.Locator.of("git:fixture/env"),
                List.of("TARGET_NAMESPACE=fixture-integration", "DB_PASSWORD=" + C, "export JENKINS_TOKEN=" + C),
                null, "Parameterdatei (synthetisch)", List.of()));
        evidence.add(AcceptanceSupport.logEvidence("jenkins-http-fehler", List.of(
                "java.io.IOException: connect failed for https://fixture-reader:" + C
                        + "@jenkins.invalid/api (token=" + C + ")",
                "Proxy-Authorization: Basic " + C)));
        evidence.add(new RawEvidence("k8s-pod-1", ch.so.agi.ops.domain.EvidenceType.KUBERNETES_POD,
                AcceptanceSupport.CLUSTER_ID, null, null, ch.so.agi.ops.domain.Locator.of("pod/fixture-app"),
                List.of("livenessProbe: httpGet /healthz port 8080", "X-Probe-Authorization: " + C,
                        "X-Auth-Token: " + C),
                null, "Pod-Probes (synthetisch)", List.of()));
        return List.copyOf(evidence);
    }

    @Test
    @DisplayName("S06: kein Canary in Manifest, Evidence, normalized.json, Bericht und Toolantwort")
    void keinCanaryInAusgabestellen() throws IOException {
        AcceptanceSupport.Harness harness = AcceptanceSupport.harness(AcceptanceSupport.inMemoryConfig(tempDir));
        SnapshotId id = harness.save(evidenceMitCanaries());
        StoredSnapshot snapshot = harness.read(id);

        // Der Canary war tatsaechlich im Rohbeleg vorhanden.
        assertTrue(evidenceMitCanaries().get(0).contentLines().get(1).contains(C));

        Path snapshotDir = tempDir.resolve("snapshots").resolve(id.value());
        AcceptanceSupport.assertNoCanary(AcceptanceSupport.readString(snapshotDir.resolve("manifest.json")),
                "manifest.json");
        AcceptanceSupport.assertNoCanary(AcceptanceSupport.readString(snapshotDir.resolve("normalized.json")),
                "normalized.json");

        List<Path> evidenceDateien;
        try (Stream<Path> stream = Files.list(snapshotDir.resolve("evidence"))) {
            evidenceDateien = stream.sorted().toList();
        }
        assertEquals(4, evidenceDateien.size());
        for (Path datei : evidenceDateien) {
            String text = AcceptanceSupport.readString(datei);
            AcceptanceSupport.assertNoCanary(text, datei.getFileName().toString());
            assertTrue(text.contains(Redactor.MASK),
                    "Die Redaktion muss sichtbar sein: " + datei.getFileName());
        }
        assertTrue(snapshot.manifest().redaction().totalCount() >= 5,
                "Der Redaktionsnachweis muss die Ersetzungen zaehlen: "
                        + snapshot.manifest().redaction().toMap());

        // Berichtsentwurf mit Canary: vor dem Speichern erneut redigiert. Der Canary steht in
        // der Form, in der ein Secret in einem Bericht tatsaechlich auftreten kann.
        DiagnosisDocument entwurf = AcceptanceSupport.withTexts(
                AcceptanceSupport.validDocument(id.value(), "EV-0001", 3),
                "Canary im Titel", "Zugang: password=" + C);
        assertTrue(entwurf.summary().contains(C));
        StoredReport report = harness.reports().save(snapshot, entwurf);
        String json = AcceptanceSupport.readString(tempDir.resolve(report.jsonPath()));
        String markdown = AcceptanceSupport.readString(tempDir.resolve(report.markdownPath()));
        AcceptanceSupport.assertNoCanary(json, "diagnosis.json");
        AcceptanceSupport.assertNoCanary(markdown, "diagnosis.md");
        assertTrue(report.validation().warnings().stream().anyMatch(warnung -> warnung.contains("redigiert")),
                report.validation().warnings().toString());

        // Strukturierte Antwort von ops_read_evidence.
        Map<String, Object> antwort = harness.tools().readEvidence(Map.of("snapshotId", id.value(), "evidenceId",
                "EV-0001", "maxLines", 50));
        String antwortText = harness.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(antwort);
        AcceptanceSupport.assertNoCanary(antwortText, "ops_read_evidence-Antwort");
        assertTrue(antwortText.contains("Container gestartet"), antwortText);

        // Und die Antwort aller vier Evidence-Dateien bleibt canaryfrei.
        for (String evidenceId : List.of("EV-0001", "EV-0002", "EV-0003", "EV-0004")) {
            Map<String, Object> seite = harness.tools().readEvidence(Map.of("snapshotId", id.value(), "evidenceId",
                    evidenceId, "maxLines", 50));
            AcceptanceSupport.assertNoCanary(
                    harness.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(seite),
                    "ops_read_evidence " + evidenceId);
        }
        assertEquals(4, snapshot.manifest().evidenceIndex().size());
    }

    @Test
    @DisplayName("S06: der Bericht mit Canary wird ohne Klartextwert gespeichert")
    void berichtMitCanaryWirdOhneKlartextwertGespeichert() {
        AcceptanceSupport.Harness harness = AcceptanceSupport.harness(AcceptanceSupport.inMemoryConfig(tempDir));
        SnapshotId id = harness.save(List.of(AcceptanceSupport.logEvidence("k8s-log-1",
                AcceptanceSupport.numberedLines(3, "container"))));
        StoredSnapshot snapshot = harness.read(id);

        StoredReport report = harness.reports().save(snapshot, AcceptanceSupport.withTexts(
                AcceptanceSupport.validDocument(id.value(), "EV-0001", 3), "Titel", "Zugang: password=" + C));

        assertFalse(AcceptanceSupport.readString(tempDir.resolve(report.markdownPath())).contains(C));
        assertFalse(AcceptanceSupport.readString(tempDir.resolve(report.jsonPath())).contains(C));
        assertTrue(harness.reports().list(id).size() == 1);
    }

    @Test
    @DisplayName("S06: die Grenzen der Redaktion sind im Manifest ausgewiesen")
    void grenzenDerRedaktionSindImManifestAusgewiesen() {
        AcceptanceSupport.Harness harness = AcceptanceSupport.harness(AcceptanceSupport.inMemoryConfig(tempDir));
        SnapshotId id = harness.save(evidenceMitCanaries());

        List<String> grenzen = harness.read(id).manifest().redaction().limitations();

        assertTrue(grenzen.stream().anyMatch(text -> text.contains("vollstaendige Anonymisierung")),
                grenzen.toString());
        assertTrue(grenzen.stream().anyMatch(text -> text.contains("Nicht erkannte Secretformate")),
                grenzen.toString());
        assertEquals(Redactor.RULE_VERSION, harness.read(id).manifest().redaction().ruleVersion());
    }
}
