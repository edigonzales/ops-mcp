package ch.so.agi.ops.config;

import ch.so.agi.ops.domain.DataMode;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.domain.OpsError;
import ch.so.agi.ops.util.PathResolver;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Laedt und validiert die explizit gewaehlte YAML-Konfiguration.
 *
 * <p>Eigenschaften: unbekannte Schluessel werden abgelehnt, Typen werden validiert, keine
 * automatische Suche nach produktiven Credentials, keine versteckte Umschaltung auf
 * Livebetrieb. Die Datei wird ueber einen begrenzten, nicht ausfuehrenden YAML-Parser gelesen;
 * es findet keine Objektinstanziierung aus YAML statt (kein {@code !!java}-Tag, keine Aliase
 * als Expansionsvehikel, kein rekursiver Aliasaufbau).</p>
 */
public final class ConfigLoader {

    /** Maximale Groesse der Konfigurationsdatei. */
    public static final long MAX_CONFIG_BYTES = 512 * 1024;

    private final YAMLMapper yamlMapper;

    public ConfigLoader() {
        this.yamlMapper = YamlSupport.strictMapper();
    }

    public OpsConfig load(Path configFile) {
        if (configFile == null) {
            throw ConfigException.invalid("Es wurde keine Konfigurationsdatei angegeben.");
        }
        Path absolute = configFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw ConfigException.invalid("Konfigurationsdatei nicht gefunden: " + absolute);
        }
        long size;
        try {
            size = Files.size(absolute);
        } catch (IOException ex) {
            throw ConfigException.invalid("Konfigurationsdatei nicht lesbar: " + absolute);
        }
        if (size > MAX_CONFIG_BYTES) {
            throw ConfigException.invalid("Konfigurationsdatei ist groesser als " + MAX_CONFIG_BYTES + " Bytes.");
        }
        String text;
        try {
            text = Files.readString(absolute, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw ConfigException.invalid("Konfigurationsdatei nicht lesbar: " + absolute);
        }
        if (YamlSupport.containsUnsafeTags(text)) {
            throw ConfigException.invalid("Konfigurationsdatei enthaelt unzulaessige YAML-Tags.");
        }
        if (countDocuments(text) > 1) {
            throw ConfigException.invalid("Konfigurationsdatei enthaelt mehrere YAML-Dokumente.");
        }

        JsonNode root;
        try {
            root = yamlMapper.readTree(text);
        } catch (JacksonException ex) {
            throw ConfigException.invalid("Konfigurationsdatei ist kein gueltiges YAML: "
                    + YamlSupport.describeParseError(ex));
        }
        if (root == null || root.isNull() || !root.isObject()) {
            throw ConfigException.invalid("Konfigurationsdatei muss ein YAML-Objekt auf oberster Ebene sein.");
        }

        Path baseDir = absolute.getParent();
        List<OpsError> problems = new ArrayList<>();
        rejectUnknownTopLevelKeys(root, problems);
        StrictNode document = StrictNode.root(root);
        OpsConfig config = parse(document, absolute, baseDir, problems);
        if (!problems.isEmpty()) {
            throw new ConfigException(problems.get(0).code(),
                    "Konfiguration enthaelt " + problems.size() + " Problem(e): "
                            + problems.stream().map(OpsError::message).reduce((a, b) -> a + " | " + b).orElse(""),
                    problems);
        }
        return config;
    }

    private OpsConfig parse(StrictNode document, Path sourceFile, Path baseDir, List<OpsError> problems) {
        int schemaVersion = document.requireInt("schemaVersion", problems, -1);
        if (schemaVersion != OpsConfig.SUPPORTED_SCHEMA_VERSION) {
            problems.add(error(ErrorCode.CONFIG_INVALID, "schemaVersion",
                    "schemaVersion muss " + OpsConfig.SUPPORTED_SCHEMA_VERSION + " sein, gefunden: " + schemaVersion));
        }
        String modeValue = document.requireString("mode", problems, null);
        DataMode mode = null;
        if (modeValue != null) {
            try {
                mode = DataMode.fromWireName(modeValue);
            } catch (IllegalArgumentException ex) {
                problems.add(error(ErrorCode.CONFIG_INVALID, "mode", "mode muss 'fixture' oder 'live' sein."));
            }
        }

        Path storageRoot = null;
        StrictNode storage = document.child("storage", problems, true);
        if (storage != null) {
            storageRoot = storage.resolvedPath("root", baseDir, problems, true);
        }
        if (storageRoot == null) {
            problems.add(error(ErrorCode.CONFIG_INCOMPLETE, "storage.root",
                    "storage.root fehlt; ohne lokalen Ausgabebereich kann kein Snapshot gespeichert werden."));
        }

        SecuritySettings security = parseSecurity(document.child("security", problems, false));
        Limits limits = parseLimits(document.child("limits", problems, false), problems);
        Map<String, ClusterDefinition> clusters = parseClusters(document.child("clusters", problems, true), baseDir,
                problems);
        Map<String, JenkinsDefinition> jenkins = parseJenkins(document.child("jenkins", problems, true), baseDir,
                problems);
        Map<String, RepositoryDefinition> repositories = parseRepositories(
                document.child("repositories", problems, true), baseDir, problems);
        Map<String, ApplicationDefinition> applications = parseApplications(document.child("applications", problems, true),
                problems);

        FixtureSettings fixture = null;
        StrictNode fixtureNode = document.child("fixture", problems, false);
        if (fixtureNode != null) {
            fixture = parseFixture(fixtureNode, baseDir, problems);
            if (mode == DataMode.LIVE) {
                problems.add(error(ErrorCode.CONFIG_INVALID, "fixture",
                        "Ein fixture-Abschnitt ist im Modus 'live' nicht zulaessig."));
            }
        }
        if (mode == DataMode.FIXTURE && fixture == null) {
            problems.add(error(ErrorCode.CONFIG_INCOMPLETE, "fixture",
                    "Im Modus 'fixture' fehlt der fixture-Abschnitt mit dem Szenariowurzelverzeichnis."));
        }

        // Die Allowed-Listen des Systems sind die obere Grenze; Anwendungseintraege duerfen
        // sie nur weiter einschraenken.
        validateScope(applications, clusters, jenkins, repositories, problems);

        return new OpsConfig(schemaVersion, mode == null ? DataMode.FIXTURE : mode, sourceFile, storageRoot,
                security, limits, clusters, jenkins, repositories, applications, fixture);
    }

    private SecuritySettings parseSecurity(StrictNode node) {
        if (node == null) {
            return new SecuritySettings(false, SecuritySettings.POLICY_INTERNAL_APPROVED_ONLY,
                    SecuritySettings.IDENTITY_RESTRICTED);
        }
        boolean allowPersonal = node.optionalBoolean("allowPersonalCredentials", false);
        String policy = node.optionalString("modelDataPolicy", SecuritySettings.POLICY_INTERNAL_APPROVED_ONLY);
        String identityMode = node.optionalString("identityMode", SecuritySettings.IDENTITY_RESTRICTED);
        return new SecuritySettings(allowPersonal, policy, identityMode);
    }

    private Limits parseLimits(StrictNode node, List<OpsError> problems) {
        Limits defaults = Limits.defaults();
        if (node == null) {
            return defaults;
        }
        Limits limits = new Limits(
                node.optionalInt("collectTimeoutSeconds", defaults.collectTimeoutSeconds()),
                node.optionalInt("requestTimeoutSeconds", defaults.requestTimeoutSeconds()),
                node.optionalInt("connectTimeoutSeconds", defaults.connectTimeoutSeconds()),
                node.optionalInt("maxParallelRequests", defaults.maxParallelRequests()),
                node.optionalInt("maxRequests", defaults.maxRequests()),
                node.optionalInt("maxPods", defaults.maxPods()),
                node.optionalInt("maxEvents", defaults.maxEvents()),
                node.optionalInt("maxGitFiles", defaults.maxGitFiles()),
                node.optionalLong("maxBuildLogBytes", defaults.maxBuildLogBytes()),
                node.optionalLong("maxContainerLogBytes", defaults.maxContainerLogBytes()),
                node.optionalLong("maxTotalInputBytes", defaults.maxTotalInputBytes()),
                node.optionalInt("maxEvidenceResponseBytes", defaults.maxEvidenceResponseBytes()),
                node.optionalInt("maxReportDraftBytes", defaults.maxReportDraftBytes()));
        checkLimit(problems, "limits.collectTimeoutSeconds", limits.collectTimeoutSeconds(), 1,
                Limits.HARD_MAX_COLLECT_TIMEOUT_SECONDS);
        checkLimit(problems, "limits.requestTimeoutSeconds", limits.requestTimeoutSeconds(), 1, 120);
        checkLimit(problems, "limits.connectTimeoutSeconds", limits.connectTimeoutSeconds(), 1, 60);
        checkLimit(problems, "limits.maxParallelRequests", limits.maxParallelRequests(), 1, 8);
        checkLimit(problems, "limits.maxRequests", limits.maxRequests(), 1, Limits.HARD_MAX_REQUESTS);
        checkLimit(problems, "limits.maxPods", limits.maxPods(), 1, Limits.HARD_MAX_PODS);
        checkLimit(problems, "limits.maxEvents", limits.maxEvents(), 1, Limits.HARD_MAX_EVENTS);
        checkLimit(problems, "limits.maxGitFiles", limits.maxGitFiles(), 1, 64);
        checkLimitLong(problems, "limits.maxBuildLogBytes", limits.maxBuildLogBytes(), 1024,
                64L * 1024 * 1024);
        checkLimitLong(problems, "limits.maxContainerLogBytes", limits.maxContainerLogBytes(), 1024,
                16L * 1024 * 1024);
        checkLimitLong(problems, "limits.maxTotalInputBytes", limits.maxTotalInputBytes(), 1024,
                Limits.HARD_MAX_TOTAL_INPUT_BYTES);
        checkLimit(problems, "limits.maxEvidenceResponseBytes", limits.maxEvidenceResponseBytes(), 1024,
                Limits.HARD_MAX_EVIDENCE_RESPONSE_BYTES);
        checkLimit(problems, "limits.maxReportDraftBytes", limits.maxReportDraftBytes(), 1024,
                Limits.HARD_MAX_REPORT_DRAFT_BYTES);
        return limits;
    }

    private void checkLimit(List<OpsError> problems, String field, int value, int min, int max) {
        if (value < min || value > max) {
            problems.add(error(ErrorCode.CONFIG_INVALID, field,
                    field + " muss zwischen " + min + " und " + max + " liegen (dokumentiertes Hardlimit)."));
        }
    }

    private void checkLimitLong(List<OpsError> problems, String field, long value, long min, long max) {
        if (value < min || value > max) {
            problems.add(error(ErrorCode.CONFIG_INVALID, field,
                    field + " muss zwischen " + min + " und " + max + " liegen (dokumentiertes Hardlimit)."));
        }
    }

    private Map<String, ClusterDefinition> parseClusters(StrictNode node, Path baseDir, List<OpsError> problems) {
        Map<String, ClusterDefinition> result = new LinkedHashMap<>();
        if (node == null) {
            return result;
        }
        for (Map.Entry<String, StrictNode> entry : node.children().entrySet()) {
            if (isReservedExtensionKey(entry.getKey())) {
                continue;
            }
            String id = entry.getKey();
            StrictNode cluster = entry.getValue();
            if (!cluster.isObject()) {
                problems.add(error(ErrorCode.CONFIG_INVALID, "clusters." + id, "Clustereintrag muss ein Objekt sein."));
                continue;
            }
            String server = cluster.optionalString("server", null);
            Path ocExecutable = cluster.resolvedPath("ocExecutable", baseDir, problems, false);
            Path kubeconfig = cluster.resolvedPath("kubeconfig", baseDir, problems, false);
            String context = cluster.optionalString("context", null);
            String identityMode = cluster.optionalString("identityMode", SecuritySettings.IDENTITY_RESTRICTED);
            List<String> directHosts = cluster.stringList("directHosts");
            List<String> allowedNamespaces = cluster.stringList("allowedNamespaces");
            if (server != null && ClusterDefinition.hostOf(server) == null) {
                problems.add(error(ErrorCode.CONFIG_INVALID, "clusters." + id + ".server",
                        "server muss eine absolute HTTP(S)-Adresse mit Hostname sein."));
            }
            if (server != null && !server.startsWith("https://")) {
                problems.add(error(ErrorCode.CONFIG_INVALID, "clusters." + id + ".server",
                        "server muss https verwenden; TLS-Abschaltung ist nicht vorgesehen."));
            }
            result.put(id, new ClusterDefinition(id, server, ocExecutable, kubeconfig, context, identityMode,
                    directHosts, allowedNamespaces));
        }
        return result;
    }

    private Map<String, JenkinsDefinition> parseJenkins(StrictNode node, Path baseDir, List<OpsError> problems) {
        Map<String, JenkinsDefinition> result = new LinkedHashMap<>();
        if (node == null) {
            return result;
        }
        for (Map.Entry<String, StrictNode> entry : node.children().entrySet()) {
            String id = entry.getKey();
            StrictNode jenkins = entry.getValue();
            if (!jenkins.isObject()) {
                problems.add(error(ErrorCode.CONFIG_INVALID, "jenkins." + id, "Jenkins-Eintrag muss ein Objekt sein."));
                continue;
            }
            String baseUrl = jenkins.optionalString("baseUrl", null);
            if (baseUrl != null && !baseUrl.startsWith("https://")) {
                problems.add(error(ErrorCode.CONFIG_INVALID, "jenkins." + id + ".baseUrl",
                        "baseUrl muss https verwenden; TLS-Abschaltung ist nicht vorgesehen."));
            }
            String username = jenkins.optionalString("username", null);
            JenkinsDefinition.SecretRef token = parseSecretRef(jenkins.child("token", problems, false), baseDir,
                    "jenkins." + id + ".token", problems);
            JenkinsDefinition.TlsSettings tls = parseTls(jenkins.child("tls", problems, false), baseDir,
                    "jenkins." + id + ".tls", problems);
            JenkinsDefinition.ProxySettings proxy = parseProxy(jenkins.child("proxy", problems, false), baseDir,
                    "jenkins." + id + ".proxy", problems);
            List<List<String>> allowedJobPaths = jenkins.jobPathList("allowedJobPaths", problems,
                    "jenkins." + id + ".allowedJobPaths");
            List<String> allowedParameterNames = jenkins.stringList("allowedParameterNames");
            result.put(id, new JenkinsDefinition(id, baseUrl, username, token, tls, proxy, allowedJobPaths,
                    allowedParameterNames));
        }
        return result;
    }

    private JenkinsDefinition.SecretRef parseSecretRef(StrictNode node, Path baseDir, String field,
            List<OpsError> problems) {
        if (node == null) {
            return null;
        }
        Path file = node.resolvedPath("file", baseDir, problems, false);
        String envVar = node.optionalString("environmentVar", null);
        if (file == null && (envVar == null || envVar.isBlank())) {
            problems.add(error(ErrorCode.CONFIG_INCOMPLETE, field,
                    field + " braucht 'file' oder 'environmentVar'; Werte stehen nie direkt im YAML."));
            return null;
        }
        if (envVar != null && !envVar.matches("[A-Z][A-Z0-9_]{0,63}")) {
            problems.add(error(ErrorCode.CONFIG_INVALID, field + ".environmentVar",
                    "environmentVar muss ein Name in Grossbuchstaben sein."));
            return null;
        }
        return new JenkinsDefinition.SecretRef(file, envVar);
    }

    private JenkinsDefinition.TlsSettings parseTls(StrictNode node, Path baseDir, String field,
            List<OpsError> problems) {
        if (node == null) {
            return new JenkinsDefinition.TlsSettings(null);
        }
        Path ca = node.resolvedPath("additionalCaFile", baseDir, problems, false);
        return new JenkinsDefinition.TlsSettings(ca);
    }

    private JenkinsDefinition.ProxySettings parseProxy(StrictNode node, Path baseDir, String field,
            List<OpsError> problems) {
        if (node == null) {
            return new JenkinsDefinition.ProxySettings(JenkinsDefinition.ProxySettings.MODE_DIRECT, null, null, null,
                    null);
        }
        String mode = node.optionalString("mode", JenkinsDefinition.ProxySettings.MODE_DIRECT);
        if (!JenkinsDefinition.ProxySettings.MODE_DIRECT.equals(mode)
                && !JenkinsDefinition.ProxySettings.MODE_EXPLICIT.equals(mode)) {
            problems.add(error(ErrorCode.CONFIG_INVALID, field + ".mode",
                    "mode muss 'direct' oder 'explicit' sein."));
            mode = JenkinsDefinition.ProxySettings.MODE_DIRECT;
        }
        String host = node.optionalString("host", null);
        Integer port = node.optionalIntOrNull("port");
        String username = node.optionalString("username", null);
        JenkinsDefinition.SecretRef password = parseSecretRef(node.child("password", problems, false), baseDir,
                field + ".password", problems);
        if (JenkinsDefinition.ProxySettings.MODE_EXPLICIT.equals(mode)) {
            if (host == null || host.isBlank() || port == null) {
                problems.add(error(ErrorCode.CONFIG_INCOMPLETE, field,
                        "Bei mode: explicit sind host und port erforderlich."));
            }
        }
        return new JenkinsDefinition.ProxySettings(mode, host, port, username, password);
    }

    private Map<String, RepositoryDefinition> parseRepositories(StrictNode node, Path baseDir,
            List<OpsError> problems) {
        Map<String, RepositoryDefinition> result = new LinkedHashMap<>();
        if (node == null) {
            return result;
        }
        for (Map.Entry<String, StrictNode> entry : node.children().entrySet()) {
            String id = entry.getKey();
            StrictNode repository = entry.getValue();
            if (!repository.isObject()) {
                problems.add(error(ErrorCode.CONFIG_INVALID, "repositories." + id,
                        "Repositoryeintrag muss ein Objekt sein."));
                continue;
            }
            String repositoryId = repository.optionalString("repositoryId", null);
            if (repositoryId == null || repositoryId.isBlank()) {
                problems.add(error(ErrorCode.CONFIG_INCOMPLETE, "repositories." + id + ".repositoryId",
                        "repositoryId fehlt."));
            }
            Path localPath = repository.resolvedPath("localPath", baseDir, problems, false);
            List<String> allowedFiles = repository.stringList("allowedFiles");
            for (String file : allowedFiles) {
                if (!ch.so.agi.ops.security.PathSafety.isSafeRelativePath(file)) {
                    problems.add(error(ErrorCode.CONFIG_INVALID, "repositories." + id + ".allowedFiles",
                            "Unzulaessiger relativer Pfad in allowedFiles: " + file));
                }
            }
            result.put(id, new RepositoryDefinition(id, repositoryId, localPath, allowedFiles));
        }
        return result;
    }

    private Map<String, ApplicationDefinition> parseApplications(StrictNode node, List<OpsError> problems) {
        Map<String, ApplicationDefinition> result = new LinkedHashMap<>();
        if (node == null) {
            return result;
        }
        for (Map.Entry<String, StrictNode> entry : node.children().entrySet()) {
            String id = entry.getKey();
            StrictNode application = entry.getValue();
            if (!application.isObject()) {
                problems.add(error(ErrorCode.CONFIG_INVALID, "applications." + id,
                        "Anwendungseintrag muss ein Objekt sein."));
                continue;
            }
            String displayName = application.optionalString("displayName", id);
            Map<String, EnvironmentDefinition> environments = new LinkedHashMap<>();
            StrictNode envNode = application.child("environments", problems, true);
            if (envNode != null) {
                for (Map.Entry<String, StrictNode> envEntry : envNode.children().entrySet()) {
                    String envName = envEntry.getKey();
                    StrictNode env = envEntry.getValue();
                    String prefix = "applications." + id + ".environments." + envName;
                    if (!env.isObject()) {
                        problems.add(error(ErrorCode.CONFIG_INVALID, prefix, "Umgebungseintrag muss ein Objekt sein."));
                        continue;
                    }
                    String cluster = env.optionalString("cluster", null);
                    String namespace = env.optionalString("namespace", null);
                    StrictNode workloadNode = env.child("workload", problems, false);
                    WorkloadDefinition workload = null;
                    if (workloadNode != null) {
                        WorkloadDefinition.WorkloadKind kind = null;
                        String kindValue = workloadNode.optionalString("kind", null);
                        if (kindValue != null) {
                            try {
                                kind = WorkloadDefinition.WorkloadKind.fromConfigValue(kindValue);
                            } catch (IllegalArgumentException ex) {
                                problems.add(error(ErrorCode.CONFIG_INVALID, prefix + ".workload.kind",
                                        "Nicht unterstuetzte Workload-Art: " + kindValue
                                                + " (unterstuetzt: Deployment, DeploymentConfig)."));
                            }
                        }
                        String name = workloadNode.optionalString("name", null);
                        List<String> containers = workloadNode.stringList("containerNames");
                        boolean allContainers = workloadNode.optionalBoolean("allWorkloadContainers", false);
                        workload = new WorkloadDefinition(kind, name, containers, allContainers);
                    }
                    StrictNode deploymentNode = env.child("deployment", problems, false);
                    DeploymentDefinition deployment = null;
                    if (deploymentNode != null) {
                        String jenkinsId = deploymentNode.optionalString("jenkins", null);
                        List<String> jobPath = deploymentNode.stringList("jobPath");
                        deployment = new DeploymentDefinition(jenkinsId, jobPath);
                    }
                    StrictNode configNode = env.child("configuration", problems, false);
                    ConfigurationDefinition configuration = null;
                    if (configNode != null) {
                        String repository = configNode.optionalString("repository", null);
                        List<String> templateFiles = configNode.stringList("templateFiles");
                        List<String> parameterFiles = configNode.stringList("parameterFiles");
                        List<String> allowedParameters = configNode.stringList("allowedParameterNames");
                        ConfigurationDefinition.RevisionDefinition revision = null;
                        StrictNode revisionNode = configNode.child("revision", problems, false);
                        if (revisionNode != null) {
                            ConfigurationDefinition.RevisionDefinition.Source source = null;
                            String sourceValue = revisionNode.optionalString("source", null);
                            if (sourceValue != null) {
                                try {
                                    source = ConfigurationDefinition.RevisionDefinition.Source
                                            .fromWireName(sourceValue);
                                } catch (IllegalArgumentException ex) {
                                    problems.add(error(ErrorCode.CONFIG_INVALID, prefix + ".configuration.revision.source",
                                            "source muss 'jenkins-scm' oder 'operator-supplied' sein."));
                                }
                            }
                            String revRepositoryId = revisionNode.optionalString("repositoryId", null);
                            String commit = revisionNode.optionalString("commit", null);
                            Integer forBuild = revisionNode.optionalIntOrNull("forBuild");
                            revision = new ConfigurationDefinition.RevisionDefinition(source, revRepositoryId, commit,
                                    forBuild);
                        }
                        for (String file : templateFiles) {
                            if (!ch.so.agi.ops.security.PathSafety.isSafeRelativePath(file)) {
                                problems.add(error(ErrorCode.CONFIG_INVALID, prefix + ".configuration.templateFiles",
                                        "Unzulaessiger relativer Pfad: " + file));
                            }
                        }
                        for (String file : parameterFiles) {
                            if (!ch.so.agi.ops.security.PathSafety.isSafeRelativePath(file)) {
                                problems.add(error(ErrorCode.CONFIG_INVALID, prefix + ".configuration.parameterFiles",
                                        "Unzulaessiger relativer Pfad: " + file));
                            }
                        }
                        configuration = new ConfigurationDefinition(repository, templateFiles, parameterFiles,
                                allowedParameters, revision);
                    }
                    environments.put(envName,
                            new EnvironmentDefinition(cluster, namespace, workload, deployment, configuration));
                }
            }
            result.put(id, new ApplicationDefinition(id, displayName, environments));
        }
        return result;
    }

    private FixtureSettings parseFixture(StrictNode node, Path baseDir, List<OpsError> problems) {
        Path root = node.resolvedPath("root", baseDir, problems, true);
        String scenario = node.optionalString("scenario", null);
        String clock = node.optionalString("clock", FixtureSettings.CLOCK_FIXED);
        String fixedNow = node.optionalString("fixedNow", null);
        if (!FixtureSettings.CLOCK_FIXED.equals(clock) && !FixtureSettings.CLOCK_SYSTEM.equals(clock)) {
            problems.add(error(ErrorCode.CONFIG_INVALID, "fixture.clock", "clock muss 'fixed' oder 'system' sein."));
            clock = FixtureSettings.CLOCK_FIXED;
        }
        if (FixtureSettings.CLOCK_FIXED.equals(clock) && (fixedNow == null || fixedNow.isBlank())) {
            problems.add(error(ErrorCode.CONFIG_INCOMPLETE, "fixture.fixedNow",
                    "Bei clock: fixed ist fixedNow erforderlich (RFC3339, UTC)."));
        }
        if (fixedNow != null) {
            try {
                java.time.Instant.parse(fixedNow);
            } catch (java.time.format.DateTimeParseException ex) {
                problems.add(error(ErrorCode.CONFIG_INVALID, "fixture.fixedNow",
                        "fixedNow muss ein RFC3339-Zeitpunkt in UTC sein, z.B. 2026-10-01T10:10:00Z."));
            }
        }
        return new FixtureSettings(root, scenario, clock, fixedNow);
    }

    private void validateScope(Map<String, ApplicationDefinition> applications, Map<String, ClusterDefinition> clusters,
            Map<String, JenkinsDefinition> jenkins, Map<String, RepositoryDefinition> repositories,
            List<OpsError> problems) {
        for (ApplicationDefinition application : applications.values()) {
            for (Map.Entry<String, EnvironmentDefinition> entry : application.environments().entrySet()) {
                String prefix = "applications." + application.id() + ".environments." + entry.getKey();
                EnvironmentDefinition environment = entry.getValue();
                ClusterDefinition cluster = clusters.get(environment.cluster());
                if (cluster == null) {
                    problems.add(error(ErrorCode.CONFIG_INVALID, prefix + ".cluster",
                            "Unbekannte Clusterkennung: " + environment.cluster()));
                    continue;
                }
                if (environment.namespace() != null && !cluster.allowedNamespaces().isEmpty()
                        && !cluster.namespaceAllowed(environment.namespace())) {
                    problems.add(error(ErrorCode.SCOPE_DENIED, prefix + ".namespace",
                            "Der Namespace steht nicht in clusters." + cluster.id()
                                    + ".allowedNamespaces; Anwendungseintraege duerfen die obere Grenze nur einschraenken."));
                }
                if (environment.deployment() != null && environment.deployment().jenkins() != null) {
                    JenkinsDefinition jenkinsDefinition = jenkins.get(environment.deployment().jenkins());
                    if (jenkinsDefinition == null) {
                        problems.add(error(ErrorCode.CONFIG_INVALID, prefix + ".deployment.jenkins",
                                "Unbekannte Jenkins-Kennung: " + environment.deployment().jenkins()));
                    } else if (!jenkinsDefinition.allowedJobPaths().isEmpty()
                            && !jenkinsDefinition.jobPathAllowed(environment.deployment().jobPath())) {
                        problems.add(error(ErrorCode.SCOPE_DENIED, prefix + ".deployment.jobPath",
                                "Der Jobpfad steht nicht in jenkins." + jenkinsDefinition.id()
                                        + ".allowedJobPaths; Anwendungseintraege duerfen die obere Grenze nur einschraenken."));
                    }
                }
                if (environment.configuration() != null && environment.configuration().repository() != null) {
                    RepositoryDefinition repository = repositories.get(environment.configuration().repository());
                    if (repository == null) {
                        problems.add(error(ErrorCode.CONFIG_INVALID, prefix + ".configuration.repository",
                                "Unbekannte Repositorykennung: " + environment.configuration().repository()));
                    } else {
                        for (String file : environment.configuration().allConfiguredFiles()) {
                            if (!repository.allowedFiles().isEmpty() && !repository.fileAllowed(file)) {
                                problems.add(error(ErrorCode.SCOPE_DENIED, prefix + ".configuration",
                                        "Die Datei steht nicht in repositories." + repository.id()
                                                + ".allowedFiles: " + file));
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Unbekannte Schluessel auf oberster Ebene werden abgewiesen. Namespacete
     * Erweiterungsfelder ({@code x-...}) bleiben zulaessig.
     */
    private static void rejectUnknownTopLevelKeys(JsonNode root, List<OpsError> problems) {
        Set<String> allowed = allowedTopLevelKeys();
        root.properties().forEach(entry -> {
            String key = entry.getKey();
            if (allowed.contains(key) || isReservedExtensionKey(key)) {
                return;
            }
            problems.add(error(ErrorCode.CONFIG_INVALID, key,
                    "Unbekannter Konfigurationsschluessel: " + key));
        });
    }

    static boolean isReservedExtensionKey(String key) {
        return key != null && key.startsWith("x-");
    }

    static OpsError error(ErrorCode code, String field, String message) {
        return new OpsError(code, field, message, false,
                "Die Konfiguration korrigieren und den Befehl erneut ausfuehren.");
    }

    /**
     * Zaehlt YAML-Dokumenttrenner. Ein fuehrender Trenner eroeffnet das erste Dokument und
     * ist kein zweites. Alles ab dem zweiten Trenner ist ein weiteres Dokument und wird
     * abgewiesen.
     */
    private static int countDocuments(String text) {
        int separators = 0;
        boolean first = true;
        for (String rawLine : text.split("\n", -1)) {
            String trimmed = rawLine.trim();
            boolean isSeparator = trimmed.equals("---") || trimmed.startsWith("--- ");
            if (!isSeparator) {
                continue;
            }
            if (first && separators == 0) {
                // Ein fuehrender Trenner gehoert zum ersten Dokument.
                first = false;
                continue;
            }
            separators++;
        }
        return 1 + separators;
    }

    /** Setzt die zulaessigen Felder der Konfigurationsdatei fest. */
    static Set<String> allowedTopLevelKeys() {
        return Set.of("schemaVersion", "mode", "storage", "security", "limits", "clusters", "jenkins",
                "repositories", "applications", "fixture");
    }
}
