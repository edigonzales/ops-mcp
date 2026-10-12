package ch.so.agi.ops.adapter.openshift;

import ch.so.agi.ops.config.ClusterDefinition;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Baut die Argumentlisten fuer die fest erlaubten, ausschliesslich lesenden
 * {@code oc}-Aufrufe des Clusteradapters.
 *
 * <p>Diese Klasse ist bewusst <em>keine</em> Shellschicht:</p>
 * <ul>
 *   <li>Es entstehen fertige Argumentlisten fuer {@link ProcessBuilder}; es wird kein
 *       Shelltext gebaut und es findet keine Interpretation von Metazeichen statt.</li>
 *   <li>Jedes Element der Liste ist genau ein Argument, auch wenn es Leerzeichen enthaelt.</li>
 *   <li>Erlaubt sind nur die lesenden Verben {@code get}, {@code version},
 *       {@code config current-context}, {@code whoami --show-server} und {@code logs}.
 *       Verbotene Verben wie {@code apply}, {@code patch}, {@code delete}, {@code scale},
 *       {@code rollout}, {@code exec}, {@code attach}, {@code port-forward},
 *       {@code login}, {@code project} oder {@code config use-context} sind hier nicht
 *       darstellbar.</li>
 *   <li>{@code logs} verwendet {@code --tail} bzw. {@code --limit-bytes} und niemals
 *       {@code --follow}. {@code --previous} wird nur bei einem belegten Neustart gesetzt.</li>
 *   <li>Werte stammen ausschliesslich aus freigegebener Konfiguration und validierten
 *       Objektkennungen, nie aus einer Modelleingabe.</li>
 * </ul>
 *
 * <p>Optionsinjektion wird abgewiesen: Kennungen und Selektoren duerfen nicht mit {@code -}
 * beginnen und keine Steuerzeichen enthalten. Shellmetazeichen sind als einzelnes Argument
 * unschaedlich, weil kein Shellprozess beteiligt ist.</p>
 */
public final class OcCommandBuilder {

    /** Flag der freigegebenen kubeconfig. */
    public static final String FLAG_KUBECONFIG = "--kubeconfig";
    /** Flag des explizit konfigurierten Contexts. */
    public static final String FLAG_CONTEXT = "--context";
    /** Namespaceflag. */
    public static final String FLAG_NAMESPACE = "-n";
    /** Ausgabeformatflag. */
    public static final String FLAG_OUTPUT = "-o";
    /** Label-Selektorflag. */
    public static final String FLAG_LABEL_SELECTOR = "-l";
    /** Containerflag von {@code oc logs}. */
    public static final String FLAG_CONTAINER = "-c";
    /** Zeilenbegrenzung von {@code oc logs}. */
    public static final String FLAG_TAIL = "--tail";
    /** Bytebegrenzung von {@code oc logs}. */
    public static final String FLAG_LIMIT_BYTES = "--limit-bytes";
    /** Vorheriger Containerlauf, nur bei belegtem Neustart. */
    public static final String FLAG_PREVIOUS = "--previous";
    /** Zeitfilter von {@code oc logs}. */
    public static final String FLAG_SINCE_TIME = "--since-time";

    /** Ressourcenname fuer {@code oc get}. */
    public static final String RESOURCE_DEPLOYMENT = "deployment";
    /** Ressourcenname fuer {@code oc get}. */
    public static final String RESOURCE_DEPLOYMENT_CONFIG = "deploymentconfig";
    /** Ressourcenname fuer {@code oc get}. */
    public static final String RESOURCE_REPLICA_SETS = "replicasets";
    /** Ressourcenname fuer {@code oc get}. */
    public static final String RESOURCE_REPLICATION_CONTROLLERS = "replicationcontrollers";
    /** Ressourcenname fuer {@code oc get}. */
    public static final String RESOURCE_PODS = "pods";
    /** Ressourcenname fuer {@code oc get} (core/v1). */
    public static final String RESOURCE_EVENTS = "events";
    /** Ressourcenname fuer {@code oc get} (events.k8s.io/v1). */
    public static final String RESOURCE_EVENTS_K8S_IO = "events.events.k8s.io";

    /** Zeilenbegrenzung, die ohne ausdrueckliche Angabe verwendet wird. */
    public static final int DEFAULT_LOG_TAIL_LINES = 1000;

    private final String kubeconfig;
    private final String context;

    public OcCommandBuilder(String kubeconfig, String context) {
        this.kubeconfig = requiredValue(kubeconfig, "kubeconfig");
        this.context = requiredValue(context, "context");
    }

    /** Erzeugt den Builder aus der freigegebenen Clusterdefinition. */
    public static OcCommandBuilder of(ClusterDefinition cluster) {
        Objects.requireNonNull(cluster, "cluster");
        return new OcCommandBuilder(cluster.kubeconfig() == null ? null : cluster.kubeconfig().toString(),
                cluster.context());
    }

    public String kubeconfig() {
        return kubeconfig;
    }

    public String context() {
        return context;
    }

    /** {@code oc version -o json}: Client-/Serverversion, rein lesend. */
    public List<String> version() {
        List<String> arguments = new ArrayList<>();
        arguments.add("version");
        arguments.addAll(globalFlags());
        arguments.add(FLAG_OUTPUT);
        arguments.add("json");
        return List.copyOf(arguments);
    }

    /**
     * {@code oc whoami --show-server}: nennt die API-Adresse, die dieser Context tatsaechlich
     * verwenden wuerde. Der Aufruf ist lesend.
     */
    public List<String> whoamiServer() {
        List<String> arguments = new ArrayList<>();
        arguments.add("whoami");
        arguments.add("--show-server");
        arguments.addAll(globalFlags());
        return List.copyOf(arguments);
    }

    /**
     * {@code oc config current-context}: reine Leseabfrage des aktiven Contexts. Bewusst ohne
     * {@code --context}, weil die Abfrage genau den in der Datei vermerkten Context nennen soll.
     */
    public List<String> configCurrentContext() {
        List<String> arguments = new ArrayList<>();
        arguments.add("config");
        arguments.add("current-context");
        arguments.add(FLAG_KUBECONFIG);
        arguments.add(kubeconfig);
        return List.copyOf(arguments);
    }

    public List<String> getDeployment(String namespace, String name) {
        return get(RESOURCE_DEPLOYMENT, namespace, name, null);
    }

    public List<String> getDeploymentConfig(String namespace, String name) {
        return get(RESOURCE_DEPLOYMENT_CONFIG, namespace, name, null);
    }

    public List<String> getReplicaSets(String namespace, String labelSelector) {
        return get(RESOURCE_REPLICA_SETS, namespace, null, labelSelector);
    }

    public List<String> getReplicationControllers(String namespace, String labelSelector) {
        return get(RESOURCE_REPLICATION_CONTROLLERS, namespace, null, labelSelector);
    }

    public List<String> getPods(String namespace, String labelSelector) {
        return get(RESOURCE_PODS, namespace, null, labelSelector);
    }

    public List<String> getPod(String namespace, String name) {
        return get(RESOURCE_PODS, namespace, name, null);
    }

    /** Events im Format {@code core/v1}. */
    public List<String> getEventsCore(String namespace) {
        return get(RESOURCE_EVENTS, namespace, null, null);
    }

    /** Events im Format {@code events.k8s.io/v1}. */
    public List<String> getEventsEventsK8sIo(String namespace) {
        return get(RESOURCE_EVENTS_K8S_IO, namespace, null, null);
    }

    /**
     * {@code oc logs} eines expliziten Containers. Kein {@code --follow}, kein {@code exec}.
     *
     * @param namespace  Namespace
     * @param pod        Podname
     * @param container  Containername
     * @param previous   true setzt {@code --previous}
     * @param sinceTime  Zeitfilter oder {@code null}; ohne bekannte Buildzeit wird nichts gesetzt
     * @param maxBytes   Bytebudget fuer {@code --limit-bytes}; {@code <= 0} laesst das Flag weg
     */
    public List<String> logs(String namespace, String pod, String container, boolean previous, Instant sinceTime,
            long maxBytes) {
        return logs(namespace, pod, container, previous, sinceTime, maxBytes, DEFAULT_LOG_TAIL_LINES);
    }

    /** Wie {@link #logs(String, String, String, boolean, Instant, long)} mit expliziter Zeilengrenze. */
    public List<String> logs(String namespace, String pod, String container, boolean previous, Instant sinceTime,
            long maxBytes, int tailLines) {
        List<String> arguments = new ArrayList<>();
        arguments.add("logs");
        arguments.add(requiredName(pod, "pod"));
        arguments.add(FLAG_CONTAINER);
        arguments.add(requiredName(container, "container"));
        arguments.addAll(globalFlags());
        arguments.add(FLAG_NAMESPACE);
        arguments.add(requiredName(namespace, "namespace"));
        if (tailLines > 0) {
            arguments.add(FLAG_TAIL);
            arguments.add(Integer.toString(tailLines));
        }
        if (maxBytes > 0) {
            arguments.add(FLAG_LIMIT_BYTES);
            arguments.add(Long.toString(maxBytes));
        }
        if (previous) {
            arguments.add(FLAG_PREVIOUS);
        }
        if (sinceTime != null) {
            arguments.add(FLAG_SINCE_TIME);
            arguments.add(DateTimeFormatter.ISO_INSTANT.format(sinceTime));
        }
        return List.copyOf(arguments);
    }

    private List<String> get(String resource, String namespace, String name, String labelSelector) {
        List<String> arguments = new ArrayList<>();
        arguments.add("get");
        arguments.add(resource);
        if (name != null) {
            arguments.add(requiredName(name, "name"));
        }
        arguments.addAll(globalFlags());
        arguments.add(FLAG_NAMESPACE);
        arguments.add(requiredName(namespace, "namespace"));
        if (labelSelector != null) {
            arguments.add(FLAG_LABEL_SELECTOR);
            arguments.add(requiredSelector(labelSelector));
        }
        arguments.add(FLAG_OUTPUT);
        arguments.add("json");
        return List.copyOf(arguments);
    }

    private List<String> globalFlags() {
        return List.of(FLAG_KUBECONFIG, kubeconfig, FLAG_CONTEXT, context);
    }

    private static String requiredValue(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Fehlender Wert fuer " + field + ".");
        }
        if (containsControlCharacter(value)) {
            throw new IllegalArgumentException("Unzulaessige Steuerzeichen in " + field + ".");
        }
        return value;
    }

    /** Kennung: zusaetzlich ohne fuehrenden Bindestrich, damit keine Option injiziert wird. */
    private static String requiredName(String value, String field) {
        String checked = requiredValue(value, field);
        if (checked.startsWith("-")) {
            throw new IllegalArgumentException("Optionsinjektion in " + field + " abgewiesen.");
        }
        return checked;
    }

    private static String requiredSelector(String value) {
        String checked = requiredValue(value, "labelSelector");
        if (checked.startsWith("-")) {
            throw new IllegalArgumentException("Optionsinjektion im Label-Selektor abgewiesen.");
        }
        return checked;
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\0' || c == '\n' || c == '\r' || c == '\t') {
                return true;
            }
        }
        return false;
    }
}
