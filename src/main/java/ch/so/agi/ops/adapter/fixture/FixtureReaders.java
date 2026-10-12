package ch.so.agi.ops.adapter.fixture;

import ch.so.agi.ops.application.DoctorReader;
import ch.so.agi.ops.application.port.BuildReader;
import ch.so.agi.ops.application.port.ClusterReader;
import ch.so.agi.ops.application.port.ConfigurationReader;
import ch.so.agi.ops.config.OpsConfig;
import ch.so.agi.ops.domain.DataMode;
import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.snapshot.ArtifactIntegrityException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/**
 * Fixtureadapter des AGI Ops-Agenten.
 *
 * <p>Diese Klasse implementiert {@link ch.so.agi.ops.adapter.FixtureReaders} und wird von
 * {@link ch.so.agi.ops.adapter.ServiceFactory} reflektiv mit dem Konstruktor
 * {@code (OpsConfig, Clock, JsonMapper)} geladen. Sie liest ausschliesslich lokale Dateien
 * synthetischer Szenarien unter {@code fixture.root}.</p>
 *
 * <p>Die Fixtureadapter verwenden <em>dieselben</em> Parser und Domaenenmodelle wie die
 * Live-Adapter; das Szenario enthaelt Rohantworten und keine gewuenschte Enddiagnose. Es
 * findet kein Netzwerkzugriff statt, es werden keine Prozesse zur Datenbeschaffung gestartet
 * (Git wird ausschliesslich lesend fuer die Konfigurationsrevision verwendet) und es gibt
 * keinen stillen Fallback auf Live-Quellen.</p>
 */
public final class FixtureReaders implements ch.so.agi.ops.adapter.FixtureReaders {

    private final OpsConfig config;
    private final Clock clock;
    private final JsonMapper mapper;
    private final FixtureScenarios fixtures;

    /**
     * Konstruktor, der von der {@code ServiceFactory} erwartet wird.
     *
     * @param config validierte Konfiguration im Modus {@code fixture}
     * @param clock  injizierte Zeitquelle; im Fixturebetrieb fest
     * @param mapper JSON-Mapper fuer die Rohantworten
     */
    public FixtureReaders(OpsConfig config, Clock clock, JsonMapper mapper) {
        if (config == null) {
            throw new ArtifactIntegrityException(ErrorCode.CONFIG_INCOMPLETE,
                    "Der Fixtureadapter benoetigt eine geladene Konfiguration.");
        }
        if (config.mode() != DataMode.FIXTURE) {
            throw new ArtifactIntegrityException(ErrorCode.CONFIG_INVALID,
                    "Der Fixtureadapter ist ausschliesslich im Modus fixture zulaessig; es gibt keine "
                            + "automatische Umschaltung zwischen fixture und live.");
        }
        if (config.fixture() == null || config.fixture().root() == null) {
            throw new ArtifactIntegrityException(ErrorCode.CONFIG_INCOMPLETE,
                    "Im Fixturemodus fehlt fixture.root in der Konfiguration.");
        }
        this.config = config;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.mapper = mapper == null ? JsonMapper.builder().build() : mapper;
        this.fixtures = FixtureScenarios.of(config, this.mapper);
    }

    @Override
    public ClusterReader clusterReader() {
        return new FixtureClusterReader(config, fixtures, mapper, clock);
    }

    @Override
    public BuildReader buildReader(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new ArtifactIntegrityException(ErrorCode.CONFIG_INCOMPLETE,
                    "Der Fixtureadapter benoetigt eine Jenkins-Kennung.");
        }
        if (config.jenkins(instanceId) == null) {
            throw new ArtifactIntegrityException(ErrorCode.CONFIG_INVALID,
                    "Unbekannte Jenkins-Kennung: " + ch.so.agi.ops.security.PathSafety.describe(instanceId));
        }
        return new FixtureBuildReader(config, fixtures, mapper, clock, instanceId);
    }

    @Override
    public ConfigurationReader configurationReader() {
        return new FixtureConfigurationReader(config, fixtures, mapper, clock);
    }

    @Override
    public DoctorReader doctorReader() {
        return new FixtureDoctorReader(config, fixtures, mapper);
    }

    /** Kennungen der im Szenariowurzelverzeichnis gefundenen Szenarien; nur fuer Diagnoseausgaben. */
    public java.util.List<String> knownScenarios() {
        return fixtures.knownScenarios();
    }
}
