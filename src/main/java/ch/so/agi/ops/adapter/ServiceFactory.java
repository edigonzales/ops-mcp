package ch.so.agi.ops.adapter;

import ch.so.agi.ops.application.DoctorReader;
import ch.so.agi.ops.application.port.BuildReader;
import ch.so.agi.ops.application.port.ClusterReader;
import ch.so.agi.ops.application.port.ConfigurationReader;
import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.domain.DataMode;
import ch.so.agi.ops.util.VersionInfo;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.function.Function;

/**
 * Verdrahtet die Adapter fuer den gewaehlten Modus.
 *
 * <p>Der Modus ist entweder {@code fixture} oder {@code live}; es gibt keine automatische
 * Umschaltung bei Fehlern. Im Fixturebetrieb werden ausschliesslich synthetische Szenarien
 * gelesen, im Livemodus ausschliesslich die echten, read-only Adapter.</p>
 *
 * <p>Die konkreten Adapterklassen werden ueber ihre Klassennamen aufgeloest. Dadurch bleibt
 * die Verdrahtung an einer Stelle und der Fixturebetrieb lauffaehig, auch wenn ein
 * Live-Adapter in einer Umgebung nicht verwendbar ist. Ein fehlender Live-Adapter fuehrt zu
 * einem erklaerten Fehler und niemals zu einem stillen Fixture-Fallback.</p>
 */
public final class ServiceFactory {

    private static final String OC_CLUSTER_READER = "ch.so.agi.ops.adapter.openshift.OcClusterReader";
    private static final String JENKINS_HTTP_READER = "ch.so.agi.ops.adapter.jenkins.JenkinsHttpReader";
    private static final String LOCAL_GIT_READER = "ch.so.agi.ops.adapter.git.LocalGitConfigurationReader";
    private static final String FIXTURE_FACTORY = "ch.so.agi.ops.adapter.fixture.FixtureReaders";

    private final OpsConfig config;
    private final Clock clock;
    private final JsonMapper mapper;
    private final VersionInfo versionInfo;

    private ServiceFactory(OpsConfig config, Clock clock, JsonMapper mapper, VersionInfo versionInfo) {
        this.config = config;
        this.clock = clock;
        this.mapper = mapper;
        this.versionInfo = versionInfo;
    }

    public static ServiceFactory forConfig(OpsConfig config, Clock clock, JsonMapper mapper,
            VersionInfo versionInfo) {
        return new ServiceFactory(config, clock, mapper, versionInfo);
    }

    public boolean isFixture() {
        return config.mode() == DataMode.FIXTURE;
    }

    /**
     * Liefert den BuildReader einer Jenkins-Kennung.
     *
     * @param instanceId Jenkins-Kennung aus der Konfiguration
     * @return Reader oder {@code null}, wenn die Kennung unbekannt ist
     */
    public BuildReader buildReader(String instanceId) {
        if (instanceId == null) {
            return null;
        }
        if (config.jenkins(instanceId) == null) {
            return null;
        }
        if (isFixture()) {
            return fixtureReaders().buildReader(instanceId);
        }
        return Instantiation.construct(JENKINS_HTTP_READER,
                new Class<?>[] {OpsConfig.class, String.class, Clock.class, JsonMapper.class},
                new Object[] {config, instanceId, clock, mapper},
                BuildReader.class,
                "Der Jenkins-Adapter ist in dieser Auslieferung nicht verfuegbar.");
    }

    public ClusterReader clusterReader() {
        if (config.clusters().isEmpty()) {
            return null;
        }
        if (isFixture()) {
            return fixtureReaders().clusterReader();
        }
        return Instantiation.construct(OC_CLUSTER_READER,
                new Class<?>[] {OpsConfig.class, ch.so.agi.ops.util.ProcessRunner.class, Clock.class, JsonMapper.class},
                new Object[] {config, ch.so.agi.ops.util.DefaultProcessRunner.inheritingEnvironment(), clock, mapper},
                ClusterReader.class,
                "Der OpenShift-Adapter ist in dieser Auslieferung nicht verfuegbar.");
    }

    public ConfigurationReader configurationReader() {
        if (config.repositories().isEmpty()) {
            return null;
        }
        if (isFixture()) {
            return fixtureReaders().configurationReader();
        }
        // Die Kindumgebung des Git-Adapters wird bewusst auf die von GitProcess gehärtete
        // Basisumgebung gesetzt: Die Haertung erfolgt ohnehin ueber extraEnv, aber eine
        // ausdruecklich leere Elternumgebung schliesst ein von aussen gesetztes GIT_DIR aus.
        return Instantiation.construct(LOCAL_GIT_READER,
                new Class<?>[] {OpsConfig.class, ch.so.agi.ops.util.ProcessRunner.class, Clock.class, JsonMapper.class},
                new Object[] {config,
                        new ch.so.agi.ops.util.DefaultProcessRunner(
                                ch.so.agi.ops.adapter.git.GitProcess.restrictedBaseEnvironment()),
                        clock, mapper},
                ConfigurationReader.class,
                "Der Git-Adapter ist in dieser Auslieferung nicht verfuegbar.");
    }

    /** Zusaetzlicher, ausschliesslich lesender Diagnosezugriff fuer {@code doctor}. */
    public DoctorReader doctorReader() {
        if (isFixture()) {
            return fixtureReaders().doctorReader();
        }
        String className = "ch.so.agi.ops.adapter.openshift.OpenShiftDoctorReader";
        return Instantiation.construct(className,
                new Class<?>[] {OpsConfig.class, ch.so.agi.ops.util.ProcessRunner.class, JsonMapper.class},
                new Object[] {config, ch.so.agi.ops.util.DefaultProcessRunner.inheritingEnvironment(), mapper},
                DoctorReader.class,
                "Die Live-Doctorpruefung ist in dieser Auslieferung nicht verfuegbar.");
    }

    private FixtureReaders fixtureReaders() {
        return Instantiation.construct(FIXTURE_FACTORY,
                new Class<?>[] {OpsConfig.class, Clock.class, JsonMapper.class},
                new Object[] {config, clock, mapper},
                FixtureReaders.class,
                "Der Fixture-Reader ist in dieser Auslieferung nicht verfuegbar.");
    }

    /** Kennungen der verfuegbaren Jenkins-Instanzen. */
    public Function<String, BuildReader> buildReaders() {
        return this::buildReader;
    }

    /** Beschreibung der Verdrahtung ohne Zugangsdaten. */
    public java.util.Map<String, String> describe() {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        map.put("mode", config.mode().wireName());
        map.put("clusterReader", isFixture() ? "fixture" : availability(OC_CLUSTER_READER));
        map.put("jenkinsReader", isFixture() ? "fixture" : availability(JENKINS_HTTP_READER));
        map.put("configurationReader", isFixture() ? "fixture" : availability(LOCAL_GIT_READER));
        map.put("doctorReader", isFixture() ? "fixture" : availability("ch.so.agi.ops.adapter.openshift.OpenShiftDoctorReader"));
        map.put("toolVersions", versionInfo.toMap().toString());
        return map;
    }

    private static String availability(String className) {
        try {
            Class.forName(className);
            return "verfuegbar";
        } catch (ClassNotFoundException ex) {
            return "nicht-verfuegbar";
        }
    }

    /** Kleine, bewusst einfache Aufloesung konkreter Adapterklassen. */
    private static final class Instantiation {

        private Instantiation() {
        }

        static <T> T construct(String className, Class<?>[] parameterTypes, Object[] arguments, Class<T> expected,
                String failureMessage) {
            Class<?> type;
            try {
                type = Class.forName(className);
            } catch (ClassNotFoundException ex) {
                throw new AdapterUnavailableException(failureMessage + " (" + className + ")");
            }
            try {
                var constructor = type.getConstructor(parameterTypes);
                Object instance = constructor.newInstance(arguments);
                return expected.cast(instance);
            } catch (ReflectiveOperationException ex) {
                throw new AdapterUnavailableException(failureMessage + ": " + ex.getClass().getSimpleName());
            }
        }
    }

    /** Ein benoetigter Adapter ist in dieser Auslieferung nicht verfuegbar. */
    public static class AdapterUnavailableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public AdapterUnavailableException(String message) {
            super(message);
        }
    }
}
