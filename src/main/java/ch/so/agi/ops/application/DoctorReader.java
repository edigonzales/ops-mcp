package ch.so.agi.ops.application;

import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.SourceResult;
import ch.so.agi.ops.domain.SourceStatus;
import ch.so.agi.ops.domain.git.RevisionProvenance;

/**
 * Zusaetzlicher, lesender Diagnosezugriff fuer {@code doctor}. Die Pruefungen verwenden
 * dieselben Transport-, TLS-, Credential- und Proxy-Klassen wie die Diagnose; sie fuehren
 * keine Mutation und keine automatische Anmeldung aus und liefern keine Zugangsdaten.
 */
public interface DoctorReader {

    /** Arbeitsschluessel der Faehigkeitspruefung. */
    enum Capability {
        OC_EXECUTABLE("oc-ausfuehrbar"),
        KUBECONFIG_FILE("kubeconfig-datei"),
        CLUSTER_CONTEXT("cluster-context-und-server"),
        CLUSTER_WORKLOAD("cluster-workload-lesen"),
        JENKINS_CONFIG("jenkins-konfiguration"),
        JENKINS_BUILD_METADATA("jenkins-buildmetadaten"),
        GIT_CHECKOUT("lokaler-git-checkout"),
        GIT_ALLOWED_FILES("erlaubte-konfigurationsdateien");

        private final String key;

        Capability(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    /** Ergebnis einer einzelnen Faehigkeitspruefung. */
    record CheckResult(Capability capability, SourceStatus status, String detail, String nextStep,
            java.util.List<ch.so.agi.ops.domain.OpsError> errors) {

        public CheckResult {
            errors = errors == null ? java.util.List.of() : java.util.List.copyOf(errors);
        }

        public static CheckResult of(Capability capability, SourceStatus status, String detail, String nextStep) {
            return new CheckResult(capability, status, detail, nextStep, java.util.List.of());
        }

        public static CheckResult failed(Capability capability, ErrorCode code, String detail, String nextStep) {
            return new CheckResult(capability, SourceStatus.UNREACHABLE, detail, nextStep,
                    java.util.List.of(ch.so.agi.ops.domain.OpsError.of(code, capability.key(), detail)));
        }
    }

    /** Prueft die lokale Ausfuehrbarkeit und Version von {@code oc}. */
    CheckResult checkOcExecutable();

    /** Prueft Existenz, Lesbarkeit und Art der kubeconfig (ohne Inhalte auszugeben). */
    CheckResult checkKubeconfig();

    /** Prueft Context und API-Serveruebereinstimmung und liest optional den konfigurierten Workload. */
    CheckResult checkCluster(boolean includeWorkload);

    /** Prueft die Jenkins-Basiskonfiguration (ohne Netzaufruf). */
    CheckResult checkJenkinsConfiguration();

    /** Fuehrt eine kleine erlaubte GET-Abfrage auf Buildmetadaten aus. */
    CheckResult checkJenkinsBuildMetadata();

    /** Prueft den lokalen Git-Checkout und die verfuegbare Revision. */
    CheckResult checkGitCheckout(RevisionProvenance revision);

    /** Prueft die erlaubten Konfigurationsdateien. */
    CheckResult checkGitAllowedFiles(RevisionProvenance revision);

    /** Fuehrt alle Pruefungen aus. */
    default java.util.List<CheckResult> checkAll(RevisionProvenance revision) {
        java.util.List<CheckResult> results = new java.util.ArrayList<>();
        results.add(checkOcExecutable());
        results.add(checkKubeconfig());
        results.add(checkCluster(true));
        results.add(checkJenkinsConfiguration());
        results.add(checkJenkinsBuildMetadata());
        results.add(checkGitCheckout(revision));
        results.add(checkGitAllowedFiles(revision));
        return java.util.List.copyOf(results);
    }

    /** Hilfsmittel: wandelt ein SourceResult in eine Faehigkeitspruefung um. */
    static CheckResult fromSourceResult(Capability capability, SourceResult<?> result, String okDetail,
            String nextStep) {
        if (result.status() == SourceStatus.COLLECTED) {
            return CheckResult.of(capability, SourceStatus.COLLECTED, okDetail, "");
        }
        String detail = result.errors().isEmpty()
                ? "Status: " + result.status().name()
                : result.errors().get(0).message();
        String action = result.errors().isEmpty() || result.errors().get(0).suggestedAction().isBlank()
                ? nextStep
                : result.errors().get(0).suggestedAction();
        return new CheckResult(capability, result.status(), detail, action, result.errors());
    }
}
