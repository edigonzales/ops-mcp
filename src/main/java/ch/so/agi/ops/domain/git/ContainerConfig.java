package ch.so.agi.ops.domain.git;

import ch.so.agi.ops.domain.k8s.ProbeConfig;

import java.util.List;
import java.util.Map;

/**
 * Containerkonfiguration aus dem Template, soweit sie fuer den Probe-Vergleich noetig ist.
 * Keine vollstaendigen Secret- oder {@code .env}-Inhalte.
 *
 * @param containerName  Containername
 * @param image          Image-Referenz
 * @param imageSource    Datei, aus der das Image stammt
 * @param ports          Ports (Name -> Containerport)
 * @param probes         Probe-Konfigurationen je Probe-Art
 * @param resources      ausgewaehlte Ressourcenlimits
 * @param rawPointerBase Pointerbasis dieses Containers, z. B. {@code /spec/template/spec/containers/0}
 */
public record ContainerConfig(
        String containerName,
        String image,
        String imageSource,
        Map<String, Integer> ports,
        Map<String, ProbeConfig> probes,
        Map<String, String> resources,
        String rawPointerBase) {

    public ContainerConfig {
        ports = ports == null ? Map.of() : Map.copyOf(ports);
        probes = probes == null ? Map.of() : Map.copyOf(probes);
        resources = resources == null ? Map.of() : Map.copyOf(resources);
    }

    public static ContainerConfig empty(String containerName) {
        return new ContainerConfig(containerName, null, null, Map.of(), Map.of(), Map.of(), "");
    }

    public List<String> probeKinds() {
        return probes.keySet().stream().sorted().toList();
    }
}
