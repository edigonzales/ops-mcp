package ch.so.agi.ops.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Clusterdefinition. Der API-Server muss vor jedem Auftrag zur erlaubten Adresse passen.
 *
 * @param id                 Kennung aus der Konfiguration, z. B. {@code ocp}
 * @param server             erwartete API-Serveradresse
 * @param ocExecutable       Pfad des {@code oc}-Programms
 * @param kubeconfig         Pfad der freigegebenen kubeconfig
 * @param context            explizit konfigurierter Context; {@code null} loest {@code CONFIG_INCOMPLETE} aus
 * @param identityMode       Betreiberdeklaration (restricted oder personal-prototype)
 * @param directHosts        Hosts, die in {@code NO_PROXY}/{@code no_proxy} der Child-Umgebung gehoeren
 * @param allowedNamespaces  obere Berechtigungsgrenze der erlaubten Namespaces
 */
public record ClusterDefinition(
        String id,
        String server,
        Path ocExecutable,
        Path kubeconfig,
        String context,
        String identityMode,
        List<String> directHosts,
        List<String> allowedNamespaces) {

    public ClusterDefinition {
        Objects.requireNonNull(id, "id");
        directHosts = directHosts == null ? List.of() : List.copyOf(directHosts);
        allowedNamespaces = allowedNamespaces == null ? List.of() : List.copyOf(allowedNamespaces);
    }

    public boolean isComplete() {
        return server != null && !server.isBlank()
                && ocExecutable != null
                && kubeconfig != null
                && context != null && !context.isBlank();
    }

    public boolean namespaceAllowed(String namespace) {
        return allowedNamespaces.contains(namespace);
    }

    public boolean hostAllowed(String host) {
        if (host == null || server == null) {
            return false;
        }
        String serverHost = hostOf(server);
        return host.equalsIgnoreCase(serverHost);
    }

    public static String hostOf(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            return uri.getHost();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public Map<String, Object> toPublicMap() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", id);
        map.put("server", server);
        map.put("context", context);
        map.put("identityMode", identityMode);
        map.put("directHosts", directHosts);
        map.put("allowedNamespaces", allowedNamespaces);
        map.put("ocExecutableConfigured", ocExecutable != null);
        map.put("kubeconfigConfigured", kubeconfig != null);
        map.put("complete", isComplete());
        return map;
    }
}
