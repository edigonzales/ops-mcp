package ch.so.agi.ops.security;

import ch.so.agi.ops.config.Limits;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.mcp.EvidenceReadBudget;
import ch.so.agi.ops.mcp.OpsTools;
import ch.so.agi.ops.snapshot.EvidencePage;
import ch.so.agi.ops.snapshot.SnapshotId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Budgets – Zeilen- und Bytebudget der Evidence-Ausgabe.
 *
 * <p>Belegt: {@code readEvidence} liefert hoechstens 200 Zeilen, auch wenn {@code maxLines}
 * deutlich groesser angefordert wird, und setzt {@code hasMore}/{@code nextStartLine} korrekt.
 * Das auftragsweite {@link EvidenceReadBudget} begrenzt wiederholte Aufrufe.</p>
 */
class EvidenceReadBudgetAcceptanceTest {

    @TempDir
    Path tempDir;

    private static final int HARD_LIMIT = Limits.HARD_MAX_EVIDENCE_LINES;

    private AcceptanceSupport.Harness harnessMitZeilen(int zeilen) {
        AcceptanceSupport.Harness harness = AcceptanceSupport.harness(AcceptanceSupport.inMemoryConfig(tempDir));
        harness.save(List.of(AcceptanceSupport.logEvidence("k8s-log-1",
                AcceptanceSupport.numberedLines(zeilen, "container"))));
        return harness;
    }

    @Test
    @DisplayName("Budgets: readEvidence liefert maximal 200 Zeilen trotz maxLines=10000")
    void readEvidenceLiefertMaximal200Zeilen() {
        AcceptanceSupport.Harness harness = harnessMitZeilen(250);
        SnapshotId id = harness.snapshots().list().getFirst();

        EvidencePage seite = harness.snapshots().readEvidence(id, "EV-0001", 1, 10000);

        assertEquals(HARD_LIMIT, seite.lines().size());
        assertEquals(250, seite.totalLineCount());
        assertEquals(1, seite.startLine());
        assertEquals(200, seite.endLine());
        assertTrue(seite.hasMore());
        assertEquals(201, seite.nextStartLine());
        assertFalse(seite.truncatedLine());
    }

    @Test
    @DisplayName("Budgets: hasMore und nextStartLine stimmen am Ende der Evidence")
    void hasMoreUndNextStartLineAmEnde() {
        AcceptanceSupport.Harness harness = harnessMitZeilen(250);
        SnapshotId id = harness.snapshots().list().getFirst();

        EvidencePage zweiteSeite = harness.snapshots().readEvidence(id, "EV-0001", 51, 10000);

        assertEquals(HARD_LIMIT, zweiteSeite.lines().size());
        assertEquals(51, zweiteSeite.startLine());
        assertEquals(250, zweiteSeite.endLine());
        assertFalse(zweiteSeite.hasMore());
        assertNull(zweiteSeite.nextStartLine());

        EvidencePage letzteZeile = harness.snapshots().readEvidence(id, "EV-0001", 250, 10000);
        assertEquals(1, letzteZeile.lines().size());
        assertFalse(letzteZeile.hasMore());
        assertNull(letzteZeile.nextStartLine());
    }

    @Test
    @DisplayName("Budgets: explizite Seitengroesse 200 wird eingehalten")
    void expliziteSeitengroesseWirdEingehalten() {
        AcceptanceSupport.Harness harness = harnessMitZeilen(1000);
        SnapshotId id = harness.snapshots().list().getFirst();

        EvidencePage seite = harness.snapshots().readEvidence(id, "EV-0001", 1, 200);

        assertEquals(200, seite.lines().size());
        assertEquals(1000, seite.totalLineCount());
        assertTrue(seite.hasMore());
        assertEquals(201, seite.nextStartLine());
    }

    @Test
    @DisplayName("Budgets: EvidenceReadBudget begrenzt wiederholte Aufrufe")
    void evidenceReadBudgetBegrenztWiederholteAufrufe() {
        EvidenceReadBudget budget = new EvidenceReadBudget(64);

        assertFalse(budget.exhausted("20261001T100000Z-abcd1234"));
        assertEquals(64, budget.remaining("20261001T100000Z-abcd1234"));

        assertTrue(budget.consume("20261001T100000Z-abcd1234", 32));
        assertFalse(budget.exhausted("20261001T100000Z-abcd1234"));

        assertFalse(budget.consume("20261001T100000Z-abcd1234", 64),
                "Nach dem Aufbrauchen meldet consume die Ueberschreitung.");
        assertTrue(budget.exhausted("20261001T100000Z-abcd1234"));
        assertEquals(0, budget.remaining("20261001T100000Z-abcd1234"));

        budget.reset("20261001T100000Z-abcd1234");
        assertFalse(budget.exhausted("20261001T100000Z-abcd1234"));
    }

    @Test
    @DisplayName("Budgets: nach aufgebrauchtem Lesebudget liefert ops_read_evidence BUDGET_EXCEEDED")
    void aufgebrauchtesLesebudgetLiefertBudgetExceeded() {
        AcceptanceSupport.Harness harness = AcceptanceSupport.harness(AcceptanceSupport.inMemoryConfig(tempDir),
                null, 16);
        SnapshotId id = harness.save(List.of(AcceptanceSupport.logEvidence("k8s-log-1",
                AcceptanceSupport.numberedLines(20, "a"))));

        Map<String, Object> ersteAntwort = harness.tools()
                .readEvidence(Map.of("snapshotId", id.value(), "evidenceId", "EV-0001", "maxLines", 20));

        @SuppressWarnings("unchecked")
        Map<String, Object> budget = (Map<String, Object>) ersteAntwort.get("readBudget");
        assertEquals(Boolean.TRUE, budget.get("exhausted"), budget.toString());

        OpsTools.OpsToolException error = assertThrows(OpsTools.OpsToolException.class,
                () -> harness.tools().readEvidence(Map.of("snapshotId", id.value(), "evidenceId", "EV-0001",
                        "maxLines", 20)));

        assertEquals(ErrorCode.BUDGET_EXCEEDED, error.code());
    }
}
