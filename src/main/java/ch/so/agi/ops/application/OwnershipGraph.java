package ch.so.agi.ops.application;

import ch.so.agi.ops.domain.LinkStrength;
import ch.so.agi.ops.domain.k8s.ClusterCollection;
import ch.so.agi.ops.domain.k8s.OwnerReference;
import ch.so.agi.ops.domain.k8s.PodSnapshot;
import ch.so.agi.ops.domain.k8s.RevisionSnapshot;
import ch.so.agi.ops.domain.k8s.WorkloadSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Stellt die Zugehoerigkeit zwischen Workload, Revision und Pod ausschliesslich anhand von
 * UID und Kind her. Label-Selektoren und Namen dienen der Kandidatenauswahl, nie als
 * alleiniger Zugehoerigkeitsbeweis. Gleichnamige, neu erzeugte Pods haben eine andere UID.
 */
public final class OwnershipGraph {

    private final ClusterCollection cluster;

    public OwnershipGraph(ClusterCollection cluster) {
        this.cluster = cluster;
    }

    /**
     * Revisionen, deren Controller-Owner exakt der Workload-UID entspricht.
     *
     * <p>Verglichen wird die Owner-<em>UID</em> mit der Workload-UID. Das Kind der
     * Owner-Referenz ist die Workload-Art (z. B. {@code Deployment}), nicht die Art der
     * Revision; deshalb wird nur die UID geprueft und das Kind gegen die erwartete
     * Workload-Art gehalten.</p>
     */
    public List<RevisionSnapshot> revisionsOf(WorkloadSnapshot workload) {
        if (workload == null || workload.uid() == null) {
            return List.of();
        }
        return cluster.revisions().stream()
                .filter(revision -> revision.ownerReferences().stream()
                        .anyMatch(owner -> workload.uid().equals(owner.uid())
                                && owner.kind() != null
                                && owner.kind().equalsIgnoreCase(workload.kind())))
                .toList();
    }

    /** Pods, deren Controller-Owner exakt der UID einer der Revisionen entspricht. */
    public List<PodSnapshot> podsOf(List<RevisionSnapshot> revisions) {
        List<String> uids = revisions.stream().map(RevisionSnapshot::uid).filter(java.util.Objects::nonNull).toList();
        if (uids.isEmpty()) {
            return List.of();
        }
        return cluster.pods().stream()
                .filter(pod -> controllerOwner(pod) != null && uids.contains(controllerOwner(pod).uid()))
                .toList();
    }

    /**
     * Revisionsaufloesung eines Pods.
     *
     * @param pod      Pod
     * @param revision zugeordnete Revision oder {@code null}
     * @param strength Belegstaerke der Zuordnung
     * @param reason   Begruendung
     */
    public record PodRevisionLink(PodSnapshot pod, RevisionSnapshot revision, LinkStrength strength, String reason) {
    }

    public PodRevisionLink link(PodSnapshot pod) {
        OwnerReference owner = controllerOwner(pod);
        if (owner == null) {
            return new PodRevisionLink(pod, null, LinkStrength.UNLINKED,
                    "Der Pod hat keine Controller-Owner-Referenz; eine Revision ist nicht belegbar.");
        }
        RevisionSnapshot revision = cluster.revisions().stream()
                .filter(candidate -> owner.uid() != null && owner.uid().equals(candidate.uid()))
                .findFirst()
                .orElse(null);
        if (revision == null) {
            return new PodRevisionLink(pod, null, LinkStrength.UNLINKED,
                    "Die Owner-UID " + safe(owner.uid()) + " (" + safe(owner.kind())
                            + ") ist im erfassten Ausschnitt nicht enthalten.");
        }
        return new PodRevisionLink(pod, revision, LinkStrength.EXACT,
                "Controller-Owner-UID des Pods stimmt mit der UID der Revision " + revision.kind() + "/"
                        + revision.name() + " ueberein.");
    }

    /**
     * Mehrdeutigkeit der Build-zu-Revision-Zuordnung. Mehrere plausible Revisionen mit
     * unterschiedlicher UID und gleichem Image-Tag sind kein exakter Nachweis.
     */
    public record RevisionAmbiguity(boolean ambiguous, String reason, List<String> candidateUids) {
    }

    public RevisionAmbiguity ambiguity(WorkloadSnapshot workload) {
        List<RevisionSnapshot> revisions = revisionsOf(workload);
        if (revisions.size() <= 1) {
            return new RevisionAmbiguity(false,
                    revisions.isEmpty()
                            ? "Keine Revision mit passender Owner-UID gefunden."
                            : "Genau eine Revision gehoert zum Workload.",
                    revisions.stream().map(RevisionSnapshot::uid).toList());
        }
        List<String> uids = revisions.stream().map(RevisionSnapshot::uid).toList();
        return new RevisionAmbiguity(true,
                "Mehrere Revisionen (" + revisions.size()
                        + ") gehoeren zum Workload. Ohne eindeutige Image-Digest- oder Annotationszuordnung "
                        + "bleibt die Verbindung zum Jenkins-Lauf mehrdeutig.",
                uids);
    }

    /**
     * Gleichnamige Pods mit unterschiedlicher UID. Sie werden getrennt erfasst; Events der
     * alten UID duerfen nicht der neuen Instanz zugeordnet werden.
     */
    public List<String> duplicatePodNames() {
        List<String> duplicates = new ArrayList<>();
        cluster.pods().stream()
                .collect(java.util.stream.Collectors.groupingBy(PodSnapshot::name))
                .forEach((name, pods) -> {
                    long distinctUids = pods.stream().map(PodSnapshot::uid).distinct().count();
                    if (distinctUids > 1) {
                        duplicates.add(name + " (" + distinctUids + " UIDs)");
                    }
                });
        return List.copyOf(duplicates);
    }

    private static OwnerReference controllerOwner(PodSnapshot pod) {
        return pod.ownerReferences().stream()
                .filter(OwnerReference::isController)
                .findFirst()
                .orElseGet(() -> pod.ownerReferences().stream().findFirst().orElse(null));
    }

    private static String safe(String value) {
        return value == null ? "unbekannt" : value;
    }
}
