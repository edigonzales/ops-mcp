package ch.so.agi.ops.adapter.fixture;

import ch.so.agi.ops.application.DoctorReader;
import ch.so.agi.ops.config.ConfigurationDefinition;
import ch.so.agi.ops.config.EnvironmentDefinition;
import ch.so.agi.ops.config.JenkinsDefinition;
import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.OpsError;
import ch.so.agi.ops.domain.SourceStatus;
import ch.so.agi.ops.domain.git.RevisionProvenance;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lokale Diagnosepruefung des Fixturebetriebs.
 *
 * <p>Im Fixturebetrieb findet <em>kein</em> Livezugriff statt. Die Pruefungen dieses Lesers
 * sind deshalb rein lokal: Szenario vorhanden, Rohantworten lesbar, erwartete Dateien
 * vorhanden. Faehigkeiten, die einen Livezugriff erfordern ({@code oc}, kubeconfig,
 * Jenkins-Netzabfrage), liefern ausdruecklich {@link SourceStatus#NOT_APPLICABLE} mit dem
 * Hinweis, dass der Fixturebetrieb bewusst keinen Livezugriff ausfuehrt. Ein
 * {@code NOT_APPLICABLE} ist keine technische Luecke und keine Bestaetigung eines
 * Livezugriffs.</p>
 */
final class FixtureDoctorReader implements DoctorReader {

    private static final String FIXTURE_NOTE = "Der Fixturebetrieb fuehrt bewusst keinen Livezugriff aus. "
            + "Diese Faehigkeit wird hier nicht geprueft und nicht bestaetigt.";

    private final OpsConfig config;
    private final FixtureScenarios fixtures;

    FixtureDoctorReader(OpsConfig config, FixtureScenarios fixtures, JsonMapper mapper) {
        this.config = config;
        this.fixtures = fixtures;
        if (mapper == null) {
            throw new IllegalArgumentException("Ein JsonMapper ist fuer den Fixturebetrieb erforderlich.");
        }
    }

    @Override
    public CheckResult checkOcExecutable() {
        return CheckResult.of(Capability.OC_EXECUTABLE, SourceStatus.NOT_APPLICABLE,
                FIXTURE_NOTE + " Es wird kein oc ausgefuehrt, auch nicht zur Versionspruefung.",
                "Fuer eine oc-Pruefung den Livemodus mit freigegebener kubeconfig verwenden.");
    }

    @Override
    public CheckResult checkKubeconfig() {
        return CheckResult.of(Capability.KUBECONFIG_FILE, SourceStatus.NOT_APPLICABLE,
                FIXTURE_NOTE + " Es wird keine kubeconfig gelesen und keine Rechtepruefung abgeleitet.",
                "Fuer eine kubeconfig-Pruefung den Livemodus verwenden.");
    }

    @Override
    public CheckResult checkCluster(boolean includeWorkload) {
        FixtureData scenario;
        try {
            scenario = fixtures.requireDefault();
        } catch (RuntimeException ex) {
            return CheckResult.failed(Capability.CLUSTER_WORKLOAD, ErrorCode.CONFIG_INCOMPLETE,
                    "Kein Fixture-Szenario bestimmbar: " + ex.getMessage(),
                    "fixture.scenario in der Konfiguration auf ein vorhandenes Szenario setzen.");
        }
        List<String> missing = new ArrayList<>();
        if (!fixtures.exists(scenario, "k8s/deployment.json")
                && !fixtures.exists(scenario, "k8s/deploymentconfig.json")) {
            missing.add("k8s/deployment.json|k8s/deploymentconfig.json");
        }
        if (!fixtures.exists(scenario, "k8s/pods.json")) {
            missing.add("k8s/pods.json");
        }
        if (!includeWorkload) {
            return CheckResult.of(Capability.CLUSTER_WORKLOAD, SourceStatus.NOT_APPLICABLE,
                    FIXTURE_NOTE, "");
        }
        if (!missing.isEmpty()) {
            return CheckResult.failed(Capability.CLUSTER_WORKLOAD, ErrorCode.NOT_FOUND,
                    "Im Szenario " + scenario.id() + " fehlen erwartete Rohantworten: "
                            + String.join(", ", missing) + ".",
                    "Die synthetischen Rohantworten im Szenario ergaenzen.");
        }
        return CheckResult.of(Capability.CLUSTER_WORKLOAD, SourceStatus.COLLECTED,
                "Synthetische Cluster-Rohantworten im Szenario " + scenario.id() + " vorhanden. "
                        + "Es wurde kein Cluster kontaktiert.",
                "Fuer eine echte Clusterpruefung den Livemodus mit freigegebenem Context verwenden.");
    }

    @Override
    public CheckResult checkJenkinsConfiguration() {
        if (config.jenkins().isEmpty()) {
            return CheckResult.of(Capability.JENKINS_CONFIG, SourceStatus.NOT_APPLICABLE,
                    "In dieser Fixturekonfiguration ist keine Jenkins-Instanz definiert.", "");
        }
        List<String> details = new ArrayList<>();
        for (JenkinsDefinition jenkins : config.jenkins().values()) {
            details.add(jenkins.id() + " (Jobpfade: " + jenkins.allowedJobPaths().size()
                    + ", erlaubte Parameter: " + jenkins.allowedParameterNames().size() + ")");
        }
        return CheckResult.of(Capability.JENKINS_CONFIG, SourceStatus.COLLECTED,
                "Synthetische Jenkins-Konfiguration vorhanden: " + String.join(", ", details)
                        + ". Es wurde kein Jenkins kontaktiert.",
                "Fuer eine echte Jenkins-Pruefung den Livemodus verwenden.");
    }

    @Override
    public CheckResult checkJenkinsBuildMetadata() {
        return CheckResult.of(Capability.JENKINS_BUILD_METADATA, SourceStatus.NOT_APPLICABLE,
                FIXTURE_NOTE + " Es wird keine GET-Abfrage auf Buildmetadaten ausgefuehrt; die "
                        + "Rohantworten liegen als Szenariodateien vor.",
                "Fuer eine echte Jenkins-Abfrage den Livemodus verwenden.");
    }

    @Override
    public CheckResult checkGitCheckout(RevisionProvenance revision) {
        FixtureData scenario;
        try {
            scenario = fixtures.requireDefault();
        } catch (RuntimeException ex) {
            return CheckResult.failed(Capability.GIT_CHECKOUT, ErrorCode.CONFIG_INCOMPLETE,
                    "Kein Fixture-Szenario bestimmbar: " + ex.getMessage(),
                    "fixture.scenario in der Konfiguration auf ein vorhandenes Szenario setzen.");
        }
        if (revision == null || !revision.hasCommit()) {
            return CheckResult.of(Capability.GIT_CHECKOUT, SourceStatus.NOT_APPLICABLE,
                    "Ohne belegten Commit wird keine Revision geprueft. Es wird kein Ersatzcommit "
                            + "(HEAD oder main) verwendet.",
                    "Die Revisionszuordnung des Builds klaeren.");
        }
        Path repo = fixtures.repoPath(scenario);
        boolean git = FixtureScenarios.isGitCheckout(repo);
        boolean copies = Files.isDirectory(fixtures.repoFilesDir(scenario));
        if (!git && !copies) {
            return CheckResult.failed(Capability.GIT_CHECKOUT, ErrorCode.REVISION_UNAVAILABLE,
                    "Im Szenario " + scenario.id() + " liegt weder ein Git-Arbeitsbaum noch eine "
                            + "Revisionskopie vor.",
                    "Die synthetischen Revisionsdaten im Szenario ergaenzen.");
        }
        return CheckResult.of(Capability.GIT_CHECKOUT, SourceStatus.COLLECTED,
                "Synthetische Revisionsdaten vorhanden ("
                        + (git ? "Git-Arbeitsbaum des Szenarios" : "Revisionskopien repo-files")
                        + "). Der Arbeitsbaum wird nicht veraendert und es wird nichts geschrieben.",
                "");
    }

    @Override
    public CheckResult checkGitAllowedFiles(RevisionProvenance revision) {
        ConfigurationDefinition definition = defaultConfiguration();
        if (definition == null) {
            return CheckResult.of(Capability.GIT_ALLOWED_FILES, SourceStatus.NOT_APPLICABLE,
                    "In dieser Fixturekonfiguration ist keine Konfigurationszuordnung definiert.", "");
        }
        List<String> configured = definition.allConfiguredFiles();
        if (configured.isEmpty()) {
            return CheckResult.of(Capability.GIT_ALLOWED_FILES, SourceStatus.NOT_APPLICABLE,
                    "Es sind keine erlaubten Konfigurationsdateien konfiguriert.", "");
        }
        FixtureData scenario = fixtures.requireDefault();
        List<String> missing = new ArrayList<>();
        for (String configuredPath : configured) {
            // Vorhanden ist die Datei, wenn sie im Szenarioarbeitsbaum, in der Revisionskopie am
            // belegten Commit oder in der Referenzkopie des heutigen Stands liegt.
            String relative = fixtures.scenarioRelative(scenario, configuredPath);
            boolean present = fixtures.exists(scenario, configuredPath)
                    || fixtures.exists(scenario, relative)
                    || fixtures.exists(scenario, FixtureScenarios.REFERENCE_DIR + "/" + configuredPath)
                    || fixtures.exists(scenario, FixtureScenarios.DEFAULT_REPO_PATH + "/commits/commit-a/"
                            + relative)
                    || (revision != null && revision.hasCommit()
                            && (fixtures.exists(scenario, FixtureScenarios.REPO_FILES_DIR + "/"
                                        + revision.commit() + "/" + configuredPath)
                                || fixtures.exists(scenario, FixtureScenarios.REPO_FILES_DIR + "/"
                                        + revision.commit() + "/" + relative)));
            if (!present && revision != null && revision.hasCommit()) {
                // Ohne Revisionskopie bleibt der zur Laufzeit aufgebaute Git-Arbeitsbaum die
                // zweite zulaessige Quelle.
                present = FixtureScenarios.isGitCheckout(fixtures.repoPath(scenario));
            }
            if (!present) {
                missing.add(configuredPath);
            }
        }
        if (!missing.isEmpty()) {
            return new CheckResult(Capability.GIT_ALLOWED_FILES, SourceStatus.NOT_PRESENT,
                    "Erlaubte Dateien sind im Szenario nicht als Revisionskopie vorhanden: "
                            + String.join(", ", missing) + ".",
                    "Die synthetischen Revisionskopien ergaenzen oder einen Git-Arbeitsbaum bereitstellen.",
                    List.of(OpsError.of(ErrorCode.REVISION_UNAVAILABLE, Capability.GIT_ALLOWED_FILES.key(),
                            "Nicht alle erlaubten Konfigurationsdateien sind an der belegten Revision lesbar.")));
        }
        return CheckResult.of(Capability.GIT_ALLOWED_FILES, SourceStatus.COLLECTED,
                configured.size() + " erlaubte Konfigurationsdatei(en) sind im Szenario vorhanden. "
                        + "Der Inhalt wurde dabei nicht ausgewertet.",
                "");
    }

    private ConfigurationDefinition defaultConfiguration() {
        for (var application : config.applications().values()) {
            for (EnvironmentDefinition environment : application.environments().values()) {
                if (environment.configuration() != null) {
                    return environment.configuration();
                }
            }
        }
        return null;
    }
}
