package ch.so.agi.ops.application;

import ch.so.agi.ops.application.DoctorReader.Capability;
import ch.so.agi.ops.application.DoctorReader.CheckResult;
import ch.so.agi.ops.config.ClusterDefinition;
import ch.so.agi.ops.config.JenkinsDefinition;
import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.config.RepositoryDefinition;
import ch.so.agi.ops.domain.DataMode;
import ch.so.agi.ops.domain.SourceStatus;
import ch.so.agi.ops.util.FilePermissions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code doctor}: prueft Konfiguration, Dateien, {@code oc}-Version, Context-/Serveruebereinstimmung,
 * Workloadzugriff, Jenkins-Buildmetadaten, lokalen Git-Checkout und erlaubte Pfade.
 *
 * <p>Bei Fixtures findet <em>kein</em> Livezugriff statt. Im Livemodus sind nur erlaubte kleine
 * GET-Abfragen und lokale Lesepruefungen zulaessig, keine Mutation und keine automatische
 * Anmeldung. Ein erfolgreicher GET beweist nur Lesbarkeit, nicht das Fehlen von Schreibrechten;
 * die Rechtebeschraenkung der Identitaet wird deshalb nicht als bestaetigt ausgegeben. Es werden
 * keine vollstaendigen kubeconfig-Inhalte und keine globalen Namespace-Listen ausgegeben.</p>
 */
public final class DoctorService {

    private final OpsConfig config;
    private final DoctorReader reader;
    private final String applicationVersion;

    public DoctorService(OpsConfig config, DoctorReader reader) {
        this(config, reader, "unbekannt");
    }

    public DoctorService(OpsConfig config, DoctorReader reader, String applicationVersion) {
        this.config = config;
        this.reader = reader;
        this.applicationVersion = applicationVersion == null || applicationVersion.isBlank()
                ? "unbekannt"
                : applicationVersion;
    }

    /** Statusdarstellung einer Faehigkeit. */
    public enum CapabilityStatus {
        OK, WARN, FAIL, NOT_CONFIGURED
    }

    /**
     * Gesamtergebnis von {@code doctor}.
     *
     * @param dataMode            fixture oder live
     * @param storageRootWritable true, wenn der Ausgabebereich beschreibbar ist
     * @param capabilities        Ergebnisse je Faehigkeit
     * @param notes               zusaetzliche Hinweise
     */
    public record DoctorReport(DataMode dataMode, boolean storageRootWritable,
            Map<String, CapabilityReport> capabilities, List<String> notes) {

        public DoctorReport {
            capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }

        public boolean hasFailures() {
            return capabilities.values().stream().anyMatch(c -> c.status() == CapabilityStatus.FAIL);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("dataMode", dataMode.wireName());
            map.put("storageRootWritable", storageRootWritable);
            Map<String, Object> caps = new LinkedHashMap<>();
            capabilities.forEach((key, value) -> caps.put(key, value.toMap()));
            map.put("capabilities", caps);
            if (!notes.isEmpty()) {
                map.put("notes", notes);
            }
            return map;
        }
    }

    /**
     * Ergebnis einer Faehigkeitspruefung.
     *
     * @param status    OK, WARN, FAIL oder NOT_CONFIGURED
     * @param detail    redigierte Erklaerung
     * @param nextStep  konkreter manueller naechster Schritt
     */
    public record CapabilityReport(CapabilityStatus status, String detail, String nextStep) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("status", status.name());
            map.put("detail", detail);
            if (!nextStep.isBlank()) {
                map.put("nextStep", nextStep);
            }
            return map;
        }
    }

    public DoctorReport run() {
        Map<String, CapabilityReport> capabilities = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();

        capabilities.put("configuration", new CapabilityReport(CapabilityStatus.OK,
                "Konfiguration geladen und validiert; unbekannte Schluessel und Typfehler werden abgewiesen.",
                ""));
        capabilities.put("mode", modeReport(config.mode()));

        boolean storageWritable = checkStorage(capabilities);
        capabilities.put("runtime", new CapabilityReport(CapabilityStatus.OK,
                "Java " + System.getProperty("java.version") + ", ops-agent " + applicationVersion
                        + ", Modus " + config.mode().wireName() + ".",
                ""));

        if (config.isFixture()) {
            notes.add("Fixturemodus: doctor fuehrt keinen Livezugriff aus. Netzwerk-, TLS-, Proxy- und "
                    + "Rechtepruefungen bleiben im Fixturebetrieb absichtlich offen.");
            capabilities.put("fixture-scenario", fixtureReport());
        } else {
            if (reader == null) {
                capabilities.put("doctor-reader", new CapabilityReport(CapabilityStatus.NOT_CONFIGURED,
                        "Fuer den Livemodus ist keine Doctor-Pruefung verdrahtet.",
                        "Die Live-Adapter konfigurieren oder im Fixturemodus arbeiten."));
            } else {
                for (CheckResult result : reader.checkAll(null)) {
                    capabilities.put(result.capability().key(), toReport(result));
                }
            }
            notes.add("Ein erfolgreicher GET beweist nur Lesbarkeit, nicht das Fehlen von Schreibrechten. "
                    + "Die Rechtebeschraenkung der technischen Identitaet ist damit nicht bestaetigt.");
            if (config.security().isPersonalPrototype()) {
                notes.add("PERSONAL_CREDENTIALS_UNVERIFIED_PRIVILEGES: Der Zugang ist als "
                        + "personal-prototype deklariert. Die Anwendung leitet daraus keine RBAC-Garantie ab.");
            }
        }

        capabilities.put("scope", scopeReport());
        capabilities.put("limits", new CapabilityReport(CapabilityStatus.OK,
                "Erfassungsbudget " + config.limits().collectTimeoutSeconds() + " s, maximal "
                        + config.limits().maxRequests() + " Quellaufrufe, " + config.limits().maxPods()
                        + " Pods, " + config.limits().maxEvents() + " Events.",
                ""));
        notes.add("doctor repariert keine Einstellungen und bietet keine automatische Zertifikats- oder "
                + "Proxyreparatur an.");

        return new DoctorReport(config.mode(), storageWritable, capabilities, notes);
    }

    private CapabilityReport modeReport(DataMode mode) {
        if (mode == DataMode.FIXTURE) {
            return new CapabilityReport(CapabilityStatus.OK,
                    "Modus fixture. Es werden ausschliesslich synthetische Daten aus dem Szenarioverzeichnis "
                            + "verwendet; eine automatische Umschaltung auf live findet nicht statt.",
                    "");
        }
        return new CapabilityReport(CapabilityStatus.WARN,
                "Modus live. Es werden echte Systeme ausschliesslich lesend angesprochen.",
                "Vor der ersten Live-Diagnose die Freigabe des Modellanbieters fuer Betriebsdaten klaeren.");
    }

    private boolean checkStorage(Map<String, CapabilityReport> capabilities) {
        Path root = config.storageRoot();
        if (root == null) {
            capabilities.put("storage", new CapabilityReport(CapabilityStatus.NOT_CONFIGURED,
                    "Kein Ausgabebereich konfiguriert.", "storage.root setzen."));
            return false;
        }
        try {
            FilePermissions.createDirectory(root);
            Path probe = Files.createTempFile(root, ".doctor-", ".tmp");
            Files.deleteIfExists(probe);
            FilePermissions.applyDirectory(root);
            boolean restricted = !FilePermissions.posixSupported(root)
                    || Files.getPosixFilePermissions(root).contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ)
                    || Files.getPosixFilePermissions(root).contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ);
            capabilities.put("storage", new CapabilityReport(
                    restricted ? CapabilityStatus.WARN : CapabilityStatus.OK,
                    "Ausgabebereich ist beschreibbar: " + root
                            + (restricted
                                    ? " (die Rechte konnten nicht auf 0700 begrenzt werden)"
                                    : " (Verzeichnisrechte restriktiv gesetzt)"),
                    restricted ? "Die Dateisystemrechte des Ausgabebereichs manuell pruefen." : ""));
            return true;
        } catch (IOException ex) {
            capabilities.put("storage", new CapabilityReport(CapabilityStatus.FAIL,
                    "Ausgabebereich ist nicht beschreibbar: " + root,
                    "Schreibrechte auf den konfigurierten Ausgabebereich herstellen."));
            return false;
        }
    }

    private CapabilityReport fixtureReport() {
        if (config.fixture() == null || config.fixture().root() == null) {
            return new CapabilityReport(CapabilityStatus.FAIL,
                    "Im Fixturemodus fehlt das Szenariowurzelverzeichnis.",
                    "fixture.root in der Konfiguration setzen.");
        }
        Path root = config.fixture().root();
        if (!Files.isDirectory(root)) {
            return new CapabilityReport(CapabilityStatus.FAIL,
                    "Fixture-Wurzelverzeichnis nicht gefunden.",
                    "fixture.root auf ein vorhandenes Verzeichnis zeigen lassen.");
        }
        List<String> scenarios = new ArrayList<>();
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(ch.so.agi.ops.adapter.fixture.FixtureData::isValidScenarioId)
                    .sorted()
                    .forEach(scenarios::add);
        } catch (IOException ex) {
            return new CapabilityReport(CapabilityStatus.FAIL,
                    "Fixture-Wurzelverzeichnis ist nicht lesbar.",
                    "Die Dateisystemrechte pruefen.");
        }
        if (scenarios.isEmpty()) {
            return new CapabilityReport(CapabilityStatus.FAIL,
                    "Kein synthetisches Szenario gefunden.",
                    "Ein Szenarioverzeichnis mit scenario.json anlegen.");
        }
        boolean defaultPresent = config.fixture().scenario() == null
                || scenarios.contains(config.fixture().scenario());
        return new CapabilityReport(defaultPresent ? CapabilityStatus.OK : CapabilityStatus.WARN,
                scenarios.size() + " synthetische Szenarien gefunden: " + String.join(", ", scenarios)
                        + ". Standard: " + (config.fixture().scenario() == null
                                ? "(nicht gesetzt)"
                                : config.fixture().scenario()),
                defaultPresent ? "" : "fixture.scenario auf ein vorhandenes Szenario setzen.");
    }

    private CapabilityReport scopeReport() {
        List<String> details = new ArrayList<>();
        for (ClusterDefinition cluster : config.clusters().values()) {
            details.add("Cluster " + cluster.id() + ": " + cluster.allowedNamespaces().size()
                    + " freigegebene Namespaces, identityMode " + cluster.identityMode());
        }
        for (JenkinsDefinition jenkins : config.jenkins().values()) {
            details.add("Jenkins " + jenkins.id() + ": " + jenkins.allowedJobPaths().size()
                    + " freigegebene Jobpfade, " + jenkins.allowedParameterNames().size()
                    + " erlaubte Parameternamen");
        }
        for (RepositoryDefinition repository : config.repositories().values()) {
            details.add("Repository " + repository.id() + ": " + repository.allowedFiles().size()
                    + " erlaubte Dateien");
        }
        return new CapabilityReport(details.isEmpty() ? CapabilityStatus.NOT_CONFIGURED : CapabilityStatus.OK,
                details.isEmpty() ? "Keine Cluster, Jenkins-Instanzen oder Repositories konfiguriert."
                        : String.join("; ", details),
                "");
    }

    private CapabilityReport toReport(CheckResult result) {
        CapabilityStatus status = switch (result.status()) {
            case COLLECTED -> CapabilityStatus.OK;
            // Eine bewusst nicht ausgefuehrte Pruefung ist keine Warnung, sondern eine
            // dokumentierte Nichtanwendbarkeit.
            case NOT_APPLICABLE, NOT_ATTEMPTED -> CapabilityStatus.NOT_CONFIGURED;
            case NOT_PRESENT, TRUNCATED -> CapabilityStatus.WARN;
            case NOT_ALLOWED, UNREACHABLE -> CapabilityStatus.FAIL;
        };
        return new CapabilityReport(status, result.detail(), result.nextStep());
    }

    /** Faehigkeiten, die im Fixturebetrieb bewusst offen bleiben. */
    public static List<Capability> liveOnlyCapabilities() {
        return List.of(Capability.KUBECONFIG_FILE, Capability.CLUSTER_CONTEXT, Capability.CLUSTER_WORKLOAD,
                Capability.JENKINS_BUILD_METADATA, Capability.GIT_CHECKOUT);
    }

    /** Statuswerte fuer strukturierte Ausgaben. */
    public static Map<String, String> statusLegend() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("OK", "Die Faehigkeit wurde lokal bzw. ueber einen erlaubten Leseaufruf bestaetigt.");
        map.put("WARN", "Die Faehigkeit ist eingeschraenkt; eine manuelle Pruefung ist noetig.");
        map.put("FAIL", "Die Faehigkeit ist nicht nutzbar; eine Diagnose ist so nicht moeglich.");
        map.put("NOT_CONFIGURED", "Die Faehigkeit ist in dieser Konfiguration nicht vorgesehen.");
        return map;
    }

    static SourceStatus asSourceStatus(CapabilityStatus status) {
        return switch (status) {
            case OK -> SourceStatus.COLLECTED;
            case WARN -> SourceStatus.TRUNCATED;
            case FAIL -> SourceStatus.UNREACHABLE;
            case NOT_CONFIGURED -> SourceStatus.NOT_ATTEMPTED;
        };
    }
}
