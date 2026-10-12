package ch.so.agi.ops.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Definition einer Jenkins-Instanz. V1 aktiviert nur {@code apps-deploy}; dieselbe
 * Adapterklasse kann spaeter {@code gretl} bedienen.
 *
 * @param id                     Kennung, z. B. {@code apps-deploy}
 * @param baseUrl                feste Basisadresse
 * @param username               Benutzername oder {@code null}
 * @param token                  Secret-Referenz des API-Tokens
 * @param tls                    TLS-Einstellungen
 * @param proxy                  Proxy-Einstellungen
 * @param allowedJobPaths        obere Grenze erlaubter Folder-Jobpfade
 * @param allowedParameterNames  obere Grenze erlaubter Parameternamen
 */
public record JenkinsDefinition(
        String id,
        String baseUrl,
        String username,
        SecretRef token,
        TlsSettings tls,
        ProxySettings proxy,
        List<List<String>> allowedJobPaths,
        List<String> allowedParameterNames) {

    public JenkinsDefinition {
        Objects.requireNonNull(id, "id");
        allowedJobPaths = allowedJobPaths == null ? List.of() : List.copyOf(allowedJobPaths);
        allowedParameterNames = allowedParameterNames == null ? List.of() : List.copyOf(allowedParameterNames);
    }

    public boolean isComplete() {
        return baseUrl != null && !baseUrl.isBlank() && token != null && token.isResolvable();
    }

    public String host() {
        return ClusterDefinition.hostOf(baseUrl);
    }

    public boolean jobPathAllowed(List<String> jobPath) {
        return allowedJobPaths.stream().anyMatch(allowed -> allowed.equals(jobPath));
    }

    public Map<String, Object> toPublicMap() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", id);
        map.put("baseUrl", baseUrl);
        map.put("usernameConfigured", username != null && !username.isBlank());
        map.put("tokenConfigured", token != null);
        map.put("tls", tls == null ? null : tls.toPublicMap());
        map.put("proxy", proxy == null ? null : proxy.toPublicMap());
        map.put("allowedJobPathCount", allowedJobPaths.size());
        map.put("allowedParameterNames", allowedParameterNames);
        map.put("complete", isComplete());
        return map;
    }

    /**
     * Secret-Referenz. Zugangsdaten stehen nie im YAML, nie in einem CLI-Argument, nie im
     * Snapshot und nie im Diagnose-Prompt.
     *
     * @param file           Pfad einer lokalen Credential-Datei
     * @param environmentVar Name einer ausdruecklich konfigurierten Umgebungsvariable
     */
    public record SecretRef(Path file, String environmentVar) {

        public boolean isResolvable() {
            return file != null || (environmentVar != null && !environmentVar.isBlank());
        }

        public String describe() {
            if (file != null) {
                return "datei";
            }
            if (environmentVar != null && !environmentVar.isBlank()) {
                return "umgebungsvariable";
            }
            return "nicht-konfiguriert";
        }
    }

    /**
     * TLS-Einstellungen. Zusaetzliche CAs sind lokale Betreibereingaben und werden nur im
     * betreffenden HTTP-Client ergaenzt. Es gibt keine Option zum Abschalten der Pruefung.
     *
     * @param additionalCaFile zusaetzliche lokale CA-Datei
     */
    public record TlsSettings(Path additionalCaFile) {

        public Map<String, Object> toPublicMap() {
            return Map.of("additionalCaConfigured", additionalCaFile != null);
        }
    }

    /**
     * Proxy-Einstellungen. {@code mode: direct} oder {@code mode: explicit}.
     *
     * @param mode     direct oder explicit
     * @param host     Proxyhost bei {@code explicit}
     * @param port     Proxyport bei {@code explicit}
     * @param username Proxybenutzer oder {@code null}
     * @param password Secret-Referenz des Proxykennworts
     */
    public record ProxySettings(String mode, String host, Integer port, String username, SecretRef password) {

        public static final String MODE_DIRECT = "direct";
        public static final String MODE_EXPLICIT = "explicit";

        public ProxySettings {
            Objects.requireNonNull(mode, "mode");
        }

        public boolean isDirect() {
            return MODE_DIRECT.equals(mode);
        }

        public boolean isExplicit() {
            return MODE_EXPLICIT.equals(mode);
        }

        public Map<String, Object> toPublicMap() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("mode", mode);
            if (isExplicit()) {
                map.put("host", host);
                map.put("port", port);
                map.put("credentialsConfigured", password != null && password.isResolvable());
            }
            return map;
        }
    }
}
