package ch.so.agi.ops.application.port;

import ch.so.agi.ops.domain.CollectionBudget;
import ch.so.agi.ops.domain.SourceResult;
import ch.so.agi.ops.domain.git.ResolvedConfiguration;
import ch.so.agi.ops.domain.git.RevisionProvenance;

/**
 * Lesender Zugriff auf einen lokalen Git-Checkout. Kein Clone, Fetch oder Pull, keine
 * Schreiboperation, keine Hooks und keine externen Diff-/Textconv-Programme.
 */
public interface ConfigurationReader {

    /**
     * Liest die erlaubten Konfigurationsdateien an der belegten Revision und loest die
     * erlaubten Probe-Parameter auf. Fehlt der Commit lokal, meldet der Adapter
     * {@code REVISION_UNAVAILABLE} und verwendet nicht stillschweigend {@code HEAD} oder
     * {@code main}.
     */
    SourceResult<ResolvedConfiguration> collect(ResolvedTarget target, RevisionProvenance revision,
            CollectionBudget budget);

    /** Kennung des Repositories, z. B. {@code deployment-config}. */
    String sourceId();
}
