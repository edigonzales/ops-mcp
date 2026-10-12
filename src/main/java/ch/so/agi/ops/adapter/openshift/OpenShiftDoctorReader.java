package ch.so.agi.ops.adapter.openshift;

import ch.so.agi.ops.application.DoctorReader;
import ch.so.agi.ops.config.ClusterDefinition;
import ch.so.agi.ops.config.JenkinsDefinition;
import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.config.RepositoryDefinition;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.SourceStatus;
import ch.so.agi.ops.domain.git.RevisionProvenance;
import ch.so.agi.ops.security.Redactor;
import ch.so.agi.ops.util.DefaultProcessRunner;
import ch.so.agi.ops.util.ExecResult;
import ch.so.agi.ops.util.ProcessRunner;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lesende Diagnosepruefung fuer den Livemodus.
 *
 * <p>Verwendet dieselben Transport-, TLS-, Credential- und Proxy-Klassen wie die Diagnose:
 * die kubeconfig wird ueber {@link KubeconfigGuard} geprueft, {@code oc} ausschliesslich
 * ueber {@link OcCommandBuilder} mit festen Argumentlisten aufgerufen. Es findet
 * <em>keine</em> Mutation und <em>keine</em> automatische Anmeldung statt.</p>
 *
 * <p>Ein erfolgreicher Aufruf beweist nur Lesbarkeit, nicht das Fehlen von Schreibrechten.
 * Diese Klasse leitet daraus ausdruecklich keine RBAC-Garantie ab.</p>
 */
public final class OpenShiftDoctorReader implements DoctorReader {

    private static final Duration VERSION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(15);
    private static final long MAX_OUTPUT = 512L * 1024;

    private final OpsConfig config;
    private final ProcessRunner runner;
    private final JsonMapper mapper;
    private final ClusterDefinition cluster;

    public OpenShiftDoctorReader(OpsConfig config, ProcessRunner runner, JsonMapper mapper) {
        this.config = config;
        this.runner = runner == null ? DefaultProcessRunner.inheritingEnvironment() : runner;
        this.mapper = mapper;
        this.cluster = config.clusters().values().stream().findFirst().orElse(null);
    }

    @Override
    public CheckResult checkOcExecutable() {
        if (cluster == null) {
            return CheckResult.of(Capability.OC_EXECUTABLE, SourceStatus.NOT_ATTEMPTED,
                    "Es ist kein Cluster konfiguriert.", "clusters.<id> in der Konfiguration anlegen.");
        }
        Path executable = cluster.ocExecutable();
        if (executable == null) {
            return CheckResult.failed(Capability.OC_EXECUTABLE, ErrorCode.CONFIG_INCOMPLETE,
                    "clusters." + cluster.id() + ".ocExecutable ist nicht gesetzt.",
                    "Den Pfad des oc-Programms in der lokalen Konfiguration setzen.");
        }
        if (!Files.isExecutable(executable)) {
            return CheckResult.failed(Capability.OC_EXECUTABLE, ErrorCode.CONFIG_INVALID,
                    "Das konfigurierte oc-Programm ist nicht ausfuehrbar.",
                    "Den Pfad pruefen (in WSL z. B. /usr/local/bin/oc).");
        }
        ExecResult result = runner.exec(executable.toString(), List.of("version", "--client", "-o", "json"),
                null, null, VERSION_TIMEOUT, MAX_OUTPUT);
        if (!result.success()) {
            // Aeltere oc-Versionen kennen --client nicht; ein zweiter, harmloser Versuch.
            result = runner.exec(executable.toString(), List.of("version", "-o", "json"), null, null,
                    VERSION_TIMEOUT, MAX_OUTPUT);
        }
        if (!result.success()) {
            return CheckResult.failed(Capability.OC_EXECUTABLE, ErrorCode.CONFIG_INVALID,
                    "oc konnte nicht ausgefuehrt werden: " + shortReason(result),
                    "Das oc-Programm und die lokalen Rechte pruefen.");
        }
        String version = clientVersion(result.stdout());
        return CheckResult.of(Capability.OC_EXECUTABLE, SourceStatus.COLLECTED,
                "oc ist ausfuehrbar" + (version == null ? "." : ", Clientversion " + version + "."), "");
    }

    @Override
    public CheckResult checkKubeconfig() {
        if (cluster == null) {
            return CheckResult.of(Capability.KUBECONFIG_FILE, SourceStatus.NOT_ATTEMPTED,
                    "Es ist kein Cluster konfiguriert.", "clusters.<id> in der Konfiguration anlegen.");
        }
        Path kubeconfig = cluster.kubeconfig();
        if (kubeconfig == null) {
            return CheckResult.failed(Capability.KUBECONFIG_FILE, ErrorCode.CONFIG_INCOMPLETE,
                    "clusters." + cluster.id() + ".kubeconfig ist nicht gesetzt.",
                    "Die freigegebene kubeconfig in der lokalen Konfiguration angeben.");
        }
        if (!Files.isRegularFile(kubeconfig)) {
            return CheckResult.failed(Capability.KUBECONFIG_FILE, ErrorCode.CONFIG_INCOMPLETE,
                    "Die konfigurierte kubeconfig wurde nicht gefunden.",
                    "Die lokale kubeconfig bereitstellen; es wird keine automatische Anmeldung ausgefuehrt.");
        }
        try {
            if (Files.size(kubeconfig) > KubeconfigGuard.MAX_BYTES) {
                return CheckResult.failed(Capability.KUBECONFIG_FILE, ErrorCode.CONFIG_INVALID,
                        "Die kubeconfig ist groesser als das zulaessige Mass.",
                        "Eine lokale, auf das Noetige begrenzte kubeconfig verwenden.");
            }
        } catch (IOException ex) {
            return CheckResult.failed(Capability.KUBECONFIG_FILE, ErrorCode.CONFIG_INVALID,
                    "Die kubeconfig ist nicht lesbar.", "Die Dateirechte pruefen.");
        }
        try {
            KubeconfigGuard.View view = KubeconfigGuard.inspect(kubeconfig, cluster.context());
            List<String> notes = new ArrayList<>();
            if (!view.staticIdentity()) {
                notes.add("Die Identitaet ist nicht als statisch erkannt.");
            }
            String detail = "Die kubeconfig ist lesbar; die Identitaet wurde als tokenbasiert/statisch geprueft "
                    + "(keine exec-Credential-Plugins, keine abgeschaltete TLS-Pruefung, kein fremder proxy-url).";
            if (!notes.isEmpty()) {
                detail = detail + " " + String.join(" ", notes);
            }
            return CheckResult.of(Capability.KUBECONFIG_FILE, SourceStatus.COLLECTED, detail, "");
        } catch (RuntimeException ex) {
            return CheckResult.failed(Capability.KUBECONFIG_FILE, ErrorCode.SCOPE_DENIED,
                    redact(ex.getMessage()),
                    "Eine tokenbasierte, statische kubeconfig verwenden; exec-Plugins und abgeschaltete "
                            + "TLS-Pruefung sind in V1 nicht zulaessig.");
        }
    }

    @Override
    public CheckResult checkCluster(boolean includeWorkload) {
        if (cluster == null) {
            return CheckResult.of(Capability.CLUSTER_CONTEXT, SourceStatus.NOT_ATTEMPTED,
                    "Es ist kein Cluster konfiguriert.", "clusters.<id> in der Konfiguration anlegen.");
        }
        if (!cluster.isComplete()) {
            return CheckResult.failed(Capability.CLUSTER_CONTEXT, ErrorCode.CONFIG_INCOMPLETE,
                    "clusters." + cluster.id() + " ist unvollstaendig (server, ocExecutable, kubeconfig, context).",
                    "Die fehlenden Angaben in der lokalen Konfiguration ergaenzen.");
        }
        OcCommandBuilder commands = OcCommandBuilder.of(cluster);
        ExecResult currentContext = runner.exec(cluster.ocExecutable().toString(),
                commands.configCurrentContext(), null, OcClusterReader.proxyEnvironment(cluster), QUERY_TIMEOUT, MAX_OUTPUT);
        if (!currentContext.success()) {
            return CheckResult.failed(Capability.CLUSTER_CONTEXT, ErrorCode.CONFIG_INVALID,
                    "Der kubeconfig-Context konnte nicht gelesen werden: " + shortReason(currentContext),
                    "Die kubeconfig und den Context pruefen.");
        }
        ExecResult server = runner.exec(cluster.ocExecutable().toString(), commands.whoamiServer(), null,
                OcClusterReader.proxyEnvironment(cluster), QUERY_TIMEOUT, MAX_OUTPUT);
        if (!server.success()) {
            return CheckResult.failed(Capability.CLUSTER_CONTEXT, classify(server),
                    "Der API-Server konnte nicht ermittelt werden: " + shortReason(server),
                    "Proxy-Ausnahme und CA fuer die OpenShift-API lokal pruefen.");
        }
        String reported = server.stdout().trim();
        if (!KubeconfigGuard.sameServer(reported, cluster.server())) {
            return CheckResult.failed(Capability.CLUSTER_CONTEXT, ErrorCode.SCOPE_DENIED,
                    "Der gemeldete API-Server passt nicht zur erlaubten Clusteradresse. Es wurde keine "
                            + "Inhaltsabfrage ausgefuehrt.",
                    "Den Context in der Konfiguration korrigieren.");
        }
        StringBuilder detail = new StringBuilder("Context '")
                .append(Redactor.redact(String.valueOf(currentContext.stdout().trim())).text())
                .append("' und API-Server stimmen mit der Konfiguration ueberein.");
        if (!includeWorkload) {
            return CheckResult.of(Capability.CLUSTER_CONTEXT, SourceStatus.COLLECTED, detail.toString(), "");
        }
        String namespace = firstNamespace();
        String workload = firstWorkloadName();
        if (namespace == null || workload == null) {
            detail.append(" Der konfigurierte Workload ist noch nicht bestimmt; es wurde daher keine "
                    + "Workload-Abfrage ausgefuehrt.");
            return CheckResult.of(Capability.CLUSTER_CONTEXT, SourceStatus.NOT_APPLICABLE, detail.toString(),
                    "Namespace, Workload-Typ, Name und Container in der lokalen Konfiguration bestaetigen.");
        }
        List<String> arguments = "DeploymentConfig".equalsIgnoreCase(firstWorkloadKind())
                ? commands.getDeploymentConfig(namespace, workload)
                : commands.getDeployment(namespace, workload);
        ExecResult workloadResult = runner.exec(cluster.ocExecutable().toString(), arguments, null,
                OcClusterReader.proxyEnvironment(cluster), QUERY_TIMEOUT, MAX_OUTPUT);
        if (!workloadResult.success()) {
            return CheckResult.failed(Capability.CLUSTER_WORKLOAD, classify(workloadResult),
                    "Der konfigurierte Workload ist nicht lesbar: " + shortReason(workloadResult),
                    "Namespace, Workload-Namen und die Leserechte der Identitaet pruefen.");
        }
        return CheckResult.of(Capability.CLUSTER_WORKLOAD, SourceStatus.COLLECTED,
                detail + " Der konfigurierte Workload ist lesbar. Ein erfolgreicher GET beweist nur Lesbarkeit, "
                        + "nicht das Fehlen von Schreibrechten.",
                "");
    }

    @Override
    public CheckResult checkJenkinsConfiguration() {
        if (config.jenkins().isEmpty()) {
            return CheckResult.of(Capability.JENKINS_CONFIG, SourceStatus.NOT_ATTEMPTED,
                    "Es ist keine Jenkins-Instanz konfiguriert.", "jenkins.<id> in der Konfiguration anlegen.");
        }
        List<String> details = new ArrayList<>();
        List<ch.so.agi.ops.domain.OpsError> errors = new ArrayList<>();
        for (JenkinsDefinition jenkins : config.jenkins().values()) {
            if (jenkins.baseUrl() == null || jenkins.baseUrl().isBlank()) {
                errors.add(ch.so.agi.ops.domain.OpsError.of(ErrorCode.CONFIG_INCOMPLETE,
                        jenkins.id(), "baseUrl fehlt."));
                continue;
            }
            if (jenkins.token() == null || !jenkins.token().isResolvable()) {
                errors.add(ch.so.agi.ops.domain.OpsError.of(ErrorCode.CONFIG_INCOMPLETE,
                        jenkins.id(), "Es ist keine Token-Referenz konfiguriert (token.file oder "
                                + "token.environmentVar)."));
                continue;
            }
            if (jenkins.token().file() != null && !Files.isRegularFile(jenkins.token().file())) {
                errors.add(ch.so.agi.ops.domain.OpsError.of(ErrorCode.CONFIG_INCOMPLETE,
                        jenkins.id(), "Die konfigurierte Credential-Datei wurde nicht gefunden."));
                continue;
            }
            details.add(jenkins.id() + ": Basisadresse und Credentialquelle sind konfiguriert");
        }
        if (!errors.isEmpty()) {
            return new CheckResult(Capability.JENKINS_CONFIG, SourceStatus.UNREACHABLE,
                    redact(errors.get(0).message()),
                    "Die Jenkins-Konfiguration vervollstaendigen; Credentials nur lokal bereitstellen.",
                    errors);
        }
        return CheckResult.of(Capability.JENKINS_CONFIG, SourceStatus.COLLECTED,
                String.join("; ", details) + ". Es wurde kein Netzaufruf ausgefuehrt.", "");
    }

    @Override
    public CheckResult checkJenkinsBuildMetadata() {
        // Der HTTP-Teil wird vom Jenkins-Adapter geprueft. Diese Faehigkeit bleibt hier
        // ausdruecklich offen, statt einen Erfolg zu behaupten.
        return CheckResult.of(Capability.JENKINS_BUILD_METADATA, SourceStatus.NOT_APPLICABLE,
                "Die Erreichbarkeit der Jenkins-Buildmetadaten wird vom Jenkins-Adapter geprueft und hier "
                        + "nicht erneut behauptet.",
                "Den ersten collect-Lauf mit einer konkreten Buildnummer ausfuehren.");
    }

    @Override
    public CheckResult checkGitCheckout(RevisionProvenance revision) {
        RepositoryDefinition repository = config.repositories().values().stream().findFirst().orElse(null);
        if (repository == null) {
            return CheckResult.of(Capability.GIT_CHECKOUT, SourceStatus.NOT_ATTEMPTED,
                    "Es ist kein Repository konfiguriert.", "repositories.<id> in der Konfiguration anlegen.");
        }
        if (repository.localPath() == null) {
            return CheckResult.failed(Capability.GIT_CHECKOUT, ErrorCode.CONFIG_INCOMPLETE,
                    "repositories." + repository.id() + ".localPath ist nicht gesetzt.",
                    "Einen lokalen, vom Menschen vorbereiteten Checkout angeben.");
        }
        if (!Files.isDirectory(repository.localPath())) {
            return CheckResult.failed(Capability.GIT_CHECKOUT, ErrorCode.CONFIG_INCOMPLETE,
                    "Der konfigurierte lokale Checkout wurde nicht gefunden.",
                    "Den Pfad des lokalen Checkouts von " + repository.repositoryId() + " pruefen.");
        }
        if (!Files.isDirectory(repository.localPath().resolve(".git"))) {
            return CheckResult.failed(Capability.GIT_CHECKOUT, ErrorCode.CONFIG_INVALID,
                    "Der konfigurierte Pfad ist kein Git-Checkout.",
                    "Den lokalen Checkout von " + repository.repositoryId() + " vorbereiten.");
        }
        if (revision != null && revision.hasCommit()) {
            return CheckResult.of(Capability.GIT_CHECKOUT, SourceStatus.COLLECTED,
                    "Der lokale Checkout ist vorhanden; die Revision fuer diesen Auftrag ist belegt.",
                    "");
        }
        return CheckResult.of(Capability.GIT_CHECKOUT, SourceStatus.COLLECTED,
                "Der lokale Checkout ist vorhanden. Eine Revision fuer einen konkreten Auftrag ist hier noch "
                        + "nicht belegt; sie wird aus den Jenkins-Daten oder einer Betreiberangabe bestimmt.",
                "Beim collect-Lauf die Revision pruefen (REVISION_UNKNOWN ist ein regulaeres Ergebnis).");
    }

    @Override
    public CheckResult checkGitAllowedFiles(RevisionProvenance revision) {
        List<String> configured = config.applications().values().stream()
                .flatMap(application -> application.environments().values().stream())
                .filter(environment -> environment.configuration() != null)
                .flatMap(environment -> environment.configuration().allConfiguredFiles().stream())
                .distinct()
                .toList();
        if (configured.isEmpty()) {
            return CheckResult.of(Capability.GIT_ALLOWED_FILES, SourceStatus.NOT_APPLICABLE,
                    "Es sind noch keine Konfigurationsdateien zugeordnet.",
                    "templateFiles und parameterFiles in der lokalen Konfiguration bestaetigen.");
        }
        return CheckResult.of(Capability.GIT_ALLOWED_FILES, SourceStatus.COLLECTED,
                configured.size() + " erlaubte Konfigurationsdateien sind zugeordnet. Der Inhalt wird am "
                        + "belegten Commit gelesen; der Arbeitsbaum wird nicht veraendert.",
                "");
    }

    private String firstNamespace() {
        return config.applications().values().stream()
                .flatMap(application -> application.environments().values().stream())
                .map(ch.so.agi.ops.config.EnvironmentDefinition::namespace)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private String firstWorkloadName() {
        return config.applications().values().stream()
                .flatMap(application -> application.environments().values().stream())
                .map(ch.so.agi.ops.config.EnvironmentDefinition::workload)
                .filter(java.util.Objects::nonNull)
                .map(ch.so.agi.ops.config.WorkloadDefinition::name)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private String firstWorkloadKind() {
        return config.applications().values().stream()
                .flatMap(application -> application.environments().values().stream())
                .map(ch.so.agi.ops.config.EnvironmentDefinition::workload)
                .filter(java.util.Objects::nonNull)
                .map(ch.so.agi.ops.config.WorkloadDefinition::kind)
                .filter(java.util.Objects::nonNull)
                .map(ch.so.agi.ops.config.WorkloadDefinition.WorkloadKind::kubernetesKind)
                .findFirst()
                .orElse(null);
    }

    /** Uebersetzt Transportfehler in unterscheidbare Fehlercodes, ohne Zugangsdaten auszugeben. */
    private static ErrorCode classify(ExecResult result) {
        String stderr = result.stderr().toLowerCase(java.util.Locale.ROOT);
        if (result.timedOut()) {
            return ErrorCode.REQUEST_TIMEOUT;
        }
        if (stderr.contains("forbidden") || stderr.contains("unauthorized")) {
            return stderr.contains("unauthorized") ? ErrorCode.AUTH_REQUIRED : ErrorCode.FORBIDDEN;
        }
        if (stderr.contains("x509") || stderr.contains("certificate")) {
            return ErrorCode.TLS_UNTRUSTED_CA;
        }
        if (stderr.contains("no such host") || stderr.contains("unable to connect to the server")) {
            return ErrorCode.DNS_FAILURE;
        }
        if (stderr.contains("timeout") || stderr.contains("deadline")) {
            return ErrorCode.CONNECT_TIMEOUT;
        }
        if (stderr.contains("proxyconnect") || stderr.contains("407")) {
            return ErrorCode.PROXY_AUTH_REQUIRED;
        }
        return ErrorCode.CONFIG_INVALID;
    }

    private static String shortReason(ExecResult result) {
        String text = result.stderr().isBlank() ? result.stdout() : result.stderr();
        String redacted = redact(text).replace('\n', ' ').trim();
        return redacted.length() > 200 ? redacted.substring(0, 200) + "..." : redacted;
    }

    private static String redact(String value) {
        return Redactor.redact(value == null ? "" : value).text();
    }

    private static String clientVersion(String stdout) {
        try {
            var node = ch.so.agi.ops.config.YamlSupport.jsonMapper().readTree(stdout);
            var clientVersion = node.get("clientVersion");
            if (clientVersion != null && clientVersion.isObject()) {
                var gitVersion = clientVersion.get("gitVersion");
                if (gitVersion != null && gitVersion.isString()) {
                    return gitVersion.asString();
                }
            }
            return null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Nur fuer Tests: die tatsaechlich verwendeten Argumentlisten. */
    static Map<String, List<String>> commandSurface(ClusterDefinition cluster) {
        OcCommandBuilder commands = OcCommandBuilder.of(cluster);
        return Map.of(
                "currentContext", commands.configCurrentContext(),
                "server", commands.whoamiServer(),
                "version", commands.version());
    }
}
