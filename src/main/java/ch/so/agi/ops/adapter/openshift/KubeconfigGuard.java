package ch.so.agi.ops.adapter.openshift;

import ch.so.agi.ops.config.YamlSupport;
import ch.so.agi.ops.domain.ErrorCode;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Lokale, rein lesende Pruefung der freigegebenen {@code kubeconfig}.
 *
 * <p>Geprueft wird, bevor irgendein {@code oc}-Aufruf startet:</p>
 * <ul>
 *   <li>Die Datei existiert, ist lesbar und begrenzt gross.</li>
 *   <li>Sie enthaelt <em>kein</em> {@code exec}-Credential-Plugin, kein
 *       {@code insecure-skip-tls-verify: true} und keinen fremden {@code proxy-url}-Eintrag;
 *       solche Varianten werden abgelehnt statt stillschweigend uebernommen.</li>
 *   <li>Der konfigurierte Context ist aufloesbar und nennt einen API-Server.</li>
 *   <li>Die Identitaet ist statisch (Token bzw. lokales Clientzertifikat), nicht interaktiv.</li>
 *   <li>Der API-Server der kubeconfig passt zur erlaubten Clusteradresse.</li>
 * </ul>
 *
 * <p>Es wird nichts ausgefuehrt, nichts geschrieben und kein Tokenwert zurueckgegeben oder in
 * eine Meldung uebernommen. Tokenwerte werden ausschliesslich auf Vorhandensein geprueft.</p>
 */
public final class KubeconfigGuard {

    /** Obergrenze der gelesenen kubeconfig-Datei. */
    public static final long MAX_BYTES = 512L * 1024;

    private KubeconfigGuard() {
    }

    /**
     * Ergebnis der Pruefung.
     *
     * @param configuredContext konfigurierter Context
     * @param currentContext    in der Datei vermerkter aktueller Context, soweit vorhanden
     * @param server            API-Server des konfigurierten Contexts
     * @param staticIdentity    true, wenn eine statische Identitaet (Token/Clientzertifikat) vorliegt
     */
    public record View(String configuredContext, String currentContext, String server, boolean staticIdentity) {
    }

    /**
     * Liest und prueft die kubeconfig.
     *
     * @throws OcParseException mit {@code CONFIG_INCOMPLETE}, {@code CONFIG_INVALID} oder
     *         {@code SCOPE_DENIED}; die Meldung nennt den Grund, aber keinen Inhalt.
     */
    public static View inspect(Path kubeconfig, String configuredContext) {
        if (kubeconfig == null) {
            throw new OcParseException(ErrorCode.CONFIG_INCOMPLETE,
                    "Es ist keine kubeconfig konfiguriert.");
        }
        if (configuredContext == null || configuredContext.isBlank()) {
            throw new OcParseException(ErrorCode.CONFIG_INCOMPLETE,
                    "Es ist kein Context konfiguriert. Ein Defaultcontext wird nicht geraten.");
        }
        if (!Files.isRegularFile(kubeconfig) || !Files.isReadable(kubeconfig)) {
            throw new OcParseException(ErrorCode.CONFIG_INCOMPLETE,
                    "Die konfigurierte kubeconfig-Datei ist nicht lesbar.");
        }
        long size;
        try {
            size = Files.size(kubeconfig);
        } catch (IOException ex) {
            throw new OcParseException(ErrorCode.CONFIG_INCOMPLETE,
                    "Die Groesse der konfigurierten kubeconfig-Datei ist nicht lesbar.", ex);
        }
        if (size > MAX_BYTES) {
            throw new OcParseException(ErrorCode.CONFIG_INVALID,
                    "Die kubeconfig-Datei ueberschreitet die zulaessige Groesse von " + MAX_BYTES + " Bytes.");
        }
        String text;
        try {
            text = Files.readString(kubeconfig, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new OcParseException(ErrorCode.CONFIG_INCOMPLETE,
                    "Die konfigurierte kubeconfig-Datei konnte nicht gelesen werden.", ex);
        }
        if (YamlSupport.containsUnsafeTags(text)) {
            throw new OcParseException(ErrorCode.SCOPE_DENIED,
                    "Die kubeconfig enthaelt YAML-Tags, die eine Objektinstanziierung ausloesen koennten; "
                            + "die Datei wird abgelehnt.");
        }
        JsonNode root;
        try {
            root = YamlSupport.strictMapper().readTree(text);
        } catch (RuntimeException ex) {
            throw new OcParseException(ErrorCode.CONFIG_INVALID,
                    "Die kubeconfig ist kein gueltiges, begrenzt parsebares YAML. Der Inhalt wird nicht ausgegeben.",
                    ex);
        }
        if (root == null || !root.isObject()) {
            throw new OcParseException(ErrorCode.CONFIG_INVALID,
                    "Die kubeconfig hat keine YAML-Struktur mit Schluessel/Wert-Paaren.");
        }

        rejectUnsafeCredentials(root);
        rejectUnsafeClusterEntries(root);

        String currentContext = textValue(root.get("current-context"));
        JsonNode contextEntry = findByMetadataName(root.get("contexts"), configuredContext);
        if (contextEntry == null) {
            throw new OcParseException(ErrorCode.CONFIG_INCOMPLETE,
                    "Der konfigurierte Context '" + safe(configuredContext) + "' ist in der kubeconfig nicht enthalten.");
        }
        JsonNode contextBody = contextEntry.get("context");
        if (contextBody == null || !contextBody.isObject()) {
            throw new OcParseException(ErrorCode.CONFIG_INCOMPLETE,
                    "Der konfigurierte Context '" + safe(configuredContext) + "' hat keinen gueltigen Kontextblock.");
        }
        String clusterName = textValue(contextBody.get("cluster"));
        if (clusterName == null || clusterName.isBlank()) {
            throw new OcParseException(ErrorCode.CONFIG_INCOMPLETE,
                    "Der konfigurierte Context '" + safe(configuredContext) + "' nennt keinen Cluster.");
        }
        JsonNode clusterEntry = findByMetadataName(root.get("clusters"), clusterName);
        if (clusterEntry == null) {
            throw new OcParseException(ErrorCode.CONFIG_INCOMPLETE,
                    "Der Cluster '" + safe(clusterName) + "' des konfigurierten Contexts fehlt in der kubeconfig.");
        }
        JsonNode clusterBody = clusterEntry.get("cluster");
        String server = clusterBody == null ? null : textValue(clusterBody.get("server"));
        if (server == null || server.isBlank()) {
            throw new OcParseException(ErrorCode.CONFIG_INCOMPLETE,
                    "Der Cluster '" + safe(clusterName) + "' nennt keine API-Serveradresse.");
        }
        String userName = textValue(contextBody.get("user"));
        boolean staticIdentity = hasStaticIdentity(root.get("users"), userName);
        if (!staticIdentity) {
            throw new OcParseException(ErrorCode.CONFIG_INCOMPLETE,
                    "Die kubeconfig enthaelt fuer den Context '" + safe(configuredContext)
                            + "' keine statische Identitaet (Token oder lokales Clientzertifikat). "
                            + "V1 unterstuetzt nur statische, tokenbasierte Zugaenge.");
        }
        return new View(configuredContext, currentContext, server, true);
    }

    /**
     * Vergleicht zwei API-Serveradressen ueber Schema, Host und effektiven Port. Eine reine
     * Textgleichheit genuegt nicht, weil Schreibweisen mit und ohne Standardport variieren.
     */
    public static boolean sameServer(String left, String right) {
        URI a = parseUri(left);
        URI b = parseUri(right);
        if (a == null || b == null) {
            return false;
        }
        String schemeA = schemeOf(a);
        String schemeB = schemeOf(b);
        String hostA = hostOf(a);
        String hostB = hostOf(b);
        return !hostA.isEmpty() && schemeA.equals(schemeB) && hostA.equals(hostB) && portOf(a) == portOf(b);
    }

    /** Host und effektiver Port, fuer Meldungen ohne Zugangsdaten. */
    public static String describeServer(String server) {
        URI uri = parseUri(server);
        if (uri == null) {
            return "<unlesbare Adresse>";
        }
        String host = hostOf(uri);
        return schemeOf(uri) + "://" + (host.isEmpty() ? "<ohne Host>" : host) + ":" + portOf(uri);
    }

    private static void rejectUnsafeCredentials(JsonNode root) {
        JsonNode users = root.get("users");
        if (users == null || !users.isArray()) {
            return;
        }
        for (JsonNode user : users) {
            JsonNode body = user.get("user");
            if (body == null || !body.isObject()) {
                continue;
            }
            JsonNode exec = body.get("exec");
            if (exec != null && exec.isObject()) {
                throw new OcParseException(ErrorCode.SCOPE_DENIED,
                        "Die kubeconfig enthaelt ein exec-Credential-Plugin. V1 fuehrt keine "
                                + "Credential-Programme aus und lehnt diese Variante ab.");
            }
        }
    }

    private static void rejectUnsafeClusterEntries(JsonNode root) {
        JsonNode clusters = root.get("clusters");
        if (clusters == null || !clusters.isArray()) {
            return;
        }
        for (JsonNode cluster : clusters) {
            JsonNode body = cluster.get("cluster");
            if (body == null || !body.isObject()) {
                continue;
            }
            JsonNode insecure = body.get("insecure-skip-tls-verify");
            if (insecure != null && (insecure.isBoolean() && insecure.asBoolean()
                    || insecure.isString() && "true".equalsIgnoreCase(insecure.asString().trim()))) {
                throw new OcParseException(ErrorCode.SCOPE_DENIED,
                        "Die kubeconfig deaktiviert die TLS-Pruefung (insecure-skip-tls-verify: true). "
                                + "V1 lehnt diese Variante ab.");
            }
            JsonNode proxyUrl = body.get("proxy-url");
            if (proxyUrl != null && !proxyUrl.isNull() && !proxyUrl.asString().isBlank()) {
                throw new OcParseException(ErrorCode.SCOPE_DENIED,
                        "Die kubeconfig enthaelt einen eigenen proxy-url-Eintrag. Fremde Proxyziele werden "
                                + "nicht stillschweigend uebernommen.");
            }
        }
    }

    private static boolean hasStaticIdentity(JsonNode users, String userName) {
        if (users == null || !users.isArray() || userName == null || userName.isBlank()) {
            return false;
        }
        JsonNode entry = findByMetadataName(users, userName);
        if (entry == null) {
            return false;
        }
        JsonNode body = entry.get("user");
        if (body == null || !body.isObject()) {
            return false;
        }
        return isPresent(body.get("token")) || isPresent(body.get("tokenFile"))
                || isPresent(body.get("client-certificate-data"));
    }

    private static boolean isPresent(JsonNode node) {
        return node != null && !node.isNull() && !node.asString().isBlank();
    }

    private static JsonNode findByMetadataName(JsonNode array, String name) {
        if (array == null || !array.isArray() || name == null) {
            return null;
        }
        for (JsonNode entry : array) {
            String entryName = textValue(entry.get("name"));
            if (name.equals(entryName)) {
                return entry;
            }
        }
        return null;
    }

    private static String textValue(JsonNode node) {
        if (node == null || !node.isString()) {
            return null;
        }
        return node.asString();
    }

    private static URI parseUri(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value.trim());
            return uri.getHost() == null ? null : uri;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String schemeOf(URI uri) {
        return uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    }

    private static String hostOf(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static int portOf(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return switch (schemeOf(uri)) {
            case "https" -> 443;
            case "http" -> 80;
            default -> -1;
        };
    }

    private static String safe(String value) {
        return ch.so.agi.ops.security.PathSafety.describe(value);
    }
}
