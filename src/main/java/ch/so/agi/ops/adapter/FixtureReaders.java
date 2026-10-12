package ch.so.agi.ops.adapter;

import ch.so.agi.ops.application.DoctorReader;
import ch.so.agi.ops.application.port.BuildReader;
import ch.so.agi.ops.application.port.ClusterReader;
import ch.so.agi.ops.application.port.ConfigurationReader;

/**
 * Buendel der Fixtureadapter. Die Fixtureadapter verwenden <em>dieselben</em> Domaenenmodelle
 * und Parser wie die Live-Adapter; das Szenario enthaelt Rohantworten und Fake-Transporte,
 * nicht die gewuenschte Enddiagnose.
 *
 * <p>Implementierungen dieser Klasse duerfen ausschliesslich lokale Dateien lesen. Ein
 * Fixtureadapter darf niemals Netzwerkzugriffe ausfuehren und niemals im Livemodus verwendet
 * werden.</p>
 */
public interface FixtureReaders {

    /** Lesender Zugriff auf die synthetischen Clusterantworten. */
    ClusterReader clusterReader();

    /** Lesender Zugriff auf die synthetischen Jenkinsantworten. */
    BuildReader buildReader(String instanceId);

    /** Lesender Zugriff auf das synthetische Git-Repository und die Template-/Parameterdateien. */
    ConfigurationReader configurationReader();

    /** Lokale Diagnosepruefung ohne Livezugriff. */
    DoctorReader doctorReader();
}
