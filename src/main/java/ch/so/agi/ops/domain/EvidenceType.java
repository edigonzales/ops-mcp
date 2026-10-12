package ch.so.agi.ops.domain;

/** Generische Evidence-Arten. In V1 werden nur die folgenden Werte erzeugt. */
public enum EvidenceType {
    JENKINS_BUILD_METADATA("jenkins-build-metadata"),
    JENKINS_CONSOLE_LOG("jenkins-console-log"),
    KUBERNETES_WORKLOAD("kubernetes-workload"),
    KUBERNETES_REVISION("kubernetes-revision"),
    KUBERNETES_POD("kubernetes-pod"),
    KUBERNETES_CONTAINER_LOG("kubernetes-container-log"),
    KUBERNETES_EVENT("kubernetes-event"),
    GIT_CONFIGURATION("git-configuration"),
    FIXTURE_SCENARIO("fixture-scenario");

    private final String wireName;

    EvidenceType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static EvidenceType fromWireName(String value) {
        for (EvidenceType type : values()) {
            if (type.wireName.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unbekannte Evidence-Art: " + value);
    }
}
