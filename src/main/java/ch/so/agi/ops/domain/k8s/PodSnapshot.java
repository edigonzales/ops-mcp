package ch.so.agi.ops.domain.k8s;

import java.time.Instant;
import java.util.List;

/**
 * Normalisierter Pod.
 *
 * @param namespace          Namespace
 * @param name               Name
 * @param uid                UID; der belastbare Zugehoerigkeitsbeweis
 * @param phase              Pending, Running, Succeeded, Failed, Unknown
 * @param nodeName           Knotenname, soweit vorhanden
 * @param creationTimestamp  Erstellungszeit
 * @param deletionTimestamp  Loeschzeit, falls der Pod verschwindet
 * @param conditions         Pod-Conditions mit Zeitbezug
 * @param ownerReferences    Owner-Kette, aus der die Zugehoerigkeit abgeleitet wird
 * @param containers         Hauptcontainer und Sidecars
 * @param initContainers     Init-Container, getrennt gefuehrt
 * @param probesFromPod      Probe-Konfiguration aus dem tatsaechlichen Pod
 * @param podTemplateUid     UID des Revisions-/Template-Objekts, aus dem der Pod stammt, soweit bekannt
 * @param revisionHint       Revisionshinweis (z. B. pod-template-hash oder openshift.io/deployment-config.latest-version)
 * @param restartPolicy      restartPolicy
 * @param labels             ausgewaehlte Labels
 */
public record PodSnapshot(
        String namespace,
        String name,
        String uid,
        String phase,
        String nodeName,
        Instant creationTimestamp,
        Instant deletionTimestamp,
        List<PodCondition> conditions,
        List<OwnerReference> ownerReferences,
        List<ContainerSnapshot> containers,
        List<ContainerSnapshot> initContainers,
        List<ProbeConfig> probesFromPod,
        String podTemplateUid,
        String revisionHint,
        String restartPolicy,
        java.util.Map<String, String> labels) {

    public PodSnapshot {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        ownerReferences = ownerReferences == null ? List.of() : List.copyOf(ownerReferences);
        containers = containers == null ? List.of() : List.copyOf(containers);
        initContainers = initContainers == null ? List.of() : List.copyOf(initContainers);
        probesFromPod = probesFromPod == null ? List.of() : List.copyOf(probesFromPod);
        labels = labels == null ? java.util.Map.of() : java.util.Map.copyOf(labels);
    }

    public boolean isRunning() {
        return "Running".equalsIgnoreCase(phase);
    }

    public boolean isReady() {
        return conditions.stream()
                .anyMatch(c -> "Ready".equalsIgnoreCase(c.type()) && c.isTrue());
    }

    public boolean isContainersReady() {
        return conditions.stream()
                .anyMatch(c -> "ContainersReady".equalsIgnoreCase(c.type()) && c.isTrue());
    }

    public List<String> notReadyConditionTypes() {
        return conditions.stream()
                .filter(c -> !c.isTrue())
                .map(PodCondition::type)
                .toList();
    }

    public ContainerSnapshot container(String name) {
        return containers.stream().filter(c -> c.name().equals(name)).findFirst().orElse(null);
    }

    public int maxRestartCount() {
        return containers.stream()
                .map(ContainerSnapshot::restartCount)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
    }

    /** Kurzform fuer Uebersichten. */
    public java.util.Map<String, Object> toSummaryMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("name", name);
        map.put("uid", uid);
        map.put("phase", phase);
        map.put("ready", isReady());
        map.put("maxRestartCount", maxRestartCount());
        map.put("containers", containers.stream().map(ContainerSnapshot::toSummaryMap).toList());
        return map;
    }

    /**
     * Pod-Condition.
     *
     * @param type               z. B. Ready, ContainersReady, PodScheduled, Initialized
     * @param status             True/False/Unknown
     * @param reason             Reason, soweit vorhanden
     * @param message            Meldung, redigiert
     * @param lastTransitionTime Zeitbezug
     */
    public record PodCondition(String type, String status, String reason, String message, Instant lastTransitionTime) {
        public boolean isTrue() {
            return "True".equalsIgnoreCase(status);
        }
    }
}
