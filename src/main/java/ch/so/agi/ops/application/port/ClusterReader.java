package ch.so.agi.ops.application.port;

import ch.so.agi.ops.domain.CollectionBudget;
import ch.so.agi.ops.domain.SourceResult;
import ch.so.agi.ops.domain.k8s.ClusterCollection;

/**
 * Lesender Zugriff auf einen OpenShift-Cluster. Implementierungen duerfen ausschliesslich
 * freigegebene Leseoperationen verwenden; das Modell liefert weder Argumente noch
 * Shellbefehle.
 */
public interface ClusterReader {

    /**
     * Erfasst Workload, Revisionen, Pods, Events und begrenzte Container-Logs.
     * Ein technischer Fehler einer Teilabfrage fuehrt zu {@code TRUNCATED} mit
     * strukturiertem Fehler, nicht zu einer leeren Liste.
     */
    SourceResult<ClusterCollection> collect(ResolvedTarget target, CollectionBudget budget);

    /** Kennung der Quelle, z. B. {@code ocp}. */
    String sourceId();
}
