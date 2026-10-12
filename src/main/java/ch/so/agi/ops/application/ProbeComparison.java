package ch.so.agi.ops.application;

import ch.so.agi.ops.domain.k8s.ContainerLog;
import ch.so.agi.ops.domain.k8s.ContainerSnapshot;
import ch.so.agi.ops.domain.k8s.EventSnapshot;
import ch.so.agi.ops.domain.k8s.PodSnapshot;
import ch.so.agi.ops.domain.k8s.ProbeConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Vergleicht Probe-Konfigurationen und Containerzustaende und erzeugt daraus rein
 * mechanische, deterministische Befunde.
 *
 * <p>Diese Befunde sind <em>keine</em> Diagnose. Sie sind objektive Beobachtungen, die der
 * Agent lesen kann: Konfigurationsunterschiede, Neustartbelege und Probe-Fehlermuster. Eine
 * Kausalaussage (etwa "die Liveness-Pruefung hat den Container beendet") entsteht nur dort,
 * wo die Quelle sie tatsaechlich hergibt, zum Beispiel in einem expliziten Killing-Event.</p>
 *
 * <p>Aus {@code initialDelaySeconds + failureThreshold * periodSeconds} wird ausdruecklich
 * kein exakter Kill-Zeitpunkt abgeleitet.</p>
 */
public final class ProbeComparison {

    /** Schweregrad eines mechanischen Befunds. */
    public enum Severity {
        /** Reine Beobachtung ohne Hinweis auf ein Problem. */
        INFO,
        /** Auffaelligkeit, die weitere Belege braucht. */
        NOTE,
        /** Widerspruch oder Luecke, die die Diagnose begrenzt. */
        WARN
    }

    /**
     * Mechanischer Befund.
     *
     * @param kind         Befundart
     * @param severity     Schweregrad
     * @param container    betroffener Container
     * @param statement    Beschreibung der Beobachtung
     * @param basis        worauf die Beobachtung beruht
     * @param evidenceRefs tragende Rohbelege
     */
    public record Finding(Kind kind, Severity severity, String container, String statement, String basis,
            List<String> evidenceRefs) {

        public Finding {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }

        public java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("kind", kind.name());
            map.put("severity", severity.name());
            if (container != null) {
                map.put("container", container);
            }
            map.put("statement", statement);
            map.put("basis", basis);
            map.put("evidenceRefs", evidenceRefs);
            return map;
        }
    }

    /** Arten mechanischer Befunde. */
    public enum Kind {
        /** Der Pod fuehrt eine Probe aus der Revision, die von der Konfiguration abweicht. */
        PROBE_CONFIG_MISMATCH,
        /** Im Pod ist keine Probe konfiguriert, in der Konfiguration aber schon (oder umgekehrt). */
        PROBE_PRESENCE_MISMATCH,
        /** Die Konfiguration konnte nicht an der belegten Revision aufgeloest werden. */
        PROBE_CONFIG_UNAVAILABLE,
        /** Der Pod ist nicht bereit, ohne dass ein Neustart belegt ist. */
        NOT_READY_WITHOUT_RESTART,
        /** Ein oder mehrere Container wurden neu gestartet. */
        CONTAINER_RESTARTED,
        /** Ein Container endete mit einem Beendigungsgrund. */
        CONTAINER_TERMINATED,
        /** Ein Container wartet mit einem Grund. */
        CONTAINER_WAITING,
        /** Ein Event nennt einen Killing-/Liveness-Zusammenhang. */
        EVENT_KILLING_AFTER_LIVENESS,
        /** Ein Event nennt eine fehlgeschlagene Readiness-Pruefung. */
        EVENT_READINESS_FAILED,
        /** Ein Event nennt einen Probe-Timeout. */
        EVENT_PROBE_TIMEOUT,
        /** Ein Event nennt einen Bild- oder Schedulingfehler vor dem Containerstart. */
        EVENT_BEFORE_CONTAINER_START,
        /** Ein Event nennt einen Speicherfehler. */
        EVENT_OOM_KILLED,
        /** Keine Startup-Probe konfiguriert, obwohl die Startphase lang erscheint. */
        NO_STARTUP_PROBE,
        /** Vorheriges Log wurde versucht und fehlt. */
        PREVIOUS_LOG_UNAVAILABLE,
        /** Eine Quelle ist nicht erreichbar oder nicht erlaubt. */
        SOURCE_GAP,
        /** Exitcode 137 ohne OOM-Begruendung. */
        EXIT_CODE_WITHOUT_REASON
    }

    private ProbeComparison() {
    }

    /**
     * Vergleicht die Probe-Konfiguration des Pods mit der aus der Revision aufgeloesten Konfiguration.
     *
     * @param podProbes    Probes aus dem Pod
     * @param configProbes Probes aus der Konfigurationsdatei, nach Container benannt
     * @param evidenceRefs zugehoerige Evidence-IDs
     */
    public static List<Finding> compareProbes(List<ProbeConfig> podProbes,
            java.util.Map<String, List<ProbeConfig>> configProbes, List<String> evidenceRefs) {
        List<Finding> findings = new ArrayList<>();
        if (configProbes.isEmpty()) {
            findings.add(new Finding(Kind.PROBE_CONFIG_UNAVAILABLE, Severity.WARN, null,
                    "Die Probe-Konfiguration des betroffenen Revisions-Templates konnte nicht aufgeloest werden; "
                            + "ein Vergleich zwischen Laufzeit und Konfiguration ist nicht belegbar.",
                    "konfigurationsquelle", evidenceRefs));
            return List.copyOf(findings);
        }
        java.util.Map<String, List<ProbeConfig>> podByContainer = new java.util.LinkedHashMap<>();
        for (ProbeConfig probe : podProbes) {
            podByContainer.computeIfAbsent(probe.containerName(), key -> new ArrayList<>()).add(probe);
        }
        for (java.util.Map.Entry<String, List<ProbeConfig>> entry : configProbes.entrySet()) {
            String container = entry.getKey();
            List<ProbeConfig> fromConfig = entry.getValue();
            List<ProbeConfig> fromPod = podByContainer.getOrDefault(container, List.of());
            for (ProbeConfig configProbe : fromConfig) {
                ProbeConfig podProbe = find(fromPod, configProbe.kind());
                if (podProbe == null) {
                    findings.add(new Finding(Kind.PROBE_PRESENCE_MISMATCH, Severity.WARN, container,
                            "Im Pod ist keine " + label(configProbe.kind())
                                    + "-Probe vorhanden, in der aufgeloesten Konfiguration aber schon.",
                            "pod-vs-konfiguration", evidenceRefs));
                    continue;
                }
                List<String> differences = differences(podProbe, configProbe);
                if (!differences.isEmpty()) {
                    findings.add(new Finding(Kind.PROBE_CONFIG_MISMATCH, Severity.WARN, container,
                            "Die " + label(configProbe.kind())
                                    + "-Probe im Pod weicht von der aufgeloesten Konfiguration ab: "
                                    + String.join("; ", differences) + ".",
                            "pod-vs-konfiguration", evidenceRefs));
                }
            }
            for (ProbeConfig podProbe : fromPod) {
                if (find(fromConfig, podProbe.kind()) == null) {
                    findings.add(new Finding(Kind.PROBE_PRESENCE_MISMATCH, Severity.NOTE, container,
                            "Im Pod ist eine " + label(podProbe.kind())
                                    + "-Probe vorhanden, die in der aufgeloesten Konfiguration nicht vorkommt.",
                            "pod-vs-konfiguration", evidenceRefs));
                }
            }
        }
        return List.copyOf(findings);
    }

    private static ProbeConfig find(List<ProbeConfig> probes, ProbeConfig.ProbeKind kind) {
        return probes.stream().filter(probe -> probe.kind() == kind).findFirst().orElse(null);
    }

    /**
     * Kubernetes-Defaults der Probe-Felder. Ein fehlendes Feld im YAML bedeutet den
     * Default und ist <em>kein</em> Konfigurationsunterschied zum explizit gesetzten
     * Default. Ohne diese Normalisierung entstehen falsche "Mismatch"-Befunde.
     */
    private static final String DEFAULT_HTTP_SCHEME = "HTTP";
    private static final int DEFAULT_INITIAL_DELAY_SECONDS = 0;
    private static final int DEFAULT_PERIOD_SECONDS = 10;
    private static final int DEFAULT_TIMEOUT_SECONDS = 1;
    private static final int DEFAULT_SUCCESS_THRESHOLD = 1;
    private static final int DEFAULT_FAILURE_THRESHOLD = 3;

    private static List<String> differences(ProbeConfig pod, ProbeConfig config) {
        List<String> differences = new ArrayList<>();
        compare(differences, "Pfad", pod.httpPath(), config.httpPath());
        compare(differences, "Port", pod.httpPort(), config.httpPort());
        compare(differences, "Schema", scheme(pod), scheme(config));
        compare(differences, "Handler", pod.handlerKind(), config.handlerKind());
        compare(differences, "initialDelaySeconds", orDefault(pod.initialDelaySeconds(),
                DEFAULT_INITIAL_DELAY_SECONDS), orDefault(config.initialDelaySeconds(),
                DEFAULT_INITIAL_DELAY_SECONDS));
        compare(differences, "periodSeconds", orDefault(pod.periodSeconds(), DEFAULT_PERIOD_SECONDS),
                orDefault(config.periodSeconds(), DEFAULT_PERIOD_SECONDS));
        compare(differences, "timeoutSeconds", orDefault(pod.timeoutSeconds(), DEFAULT_TIMEOUT_SECONDS),
                orDefault(config.timeoutSeconds(), DEFAULT_TIMEOUT_SECONDS));
        compare(differences, "failureThreshold", orDefault(pod.failureThreshold(), DEFAULT_FAILURE_THRESHOLD),
                orDefault(config.failureThreshold(), DEFAULT_FAILURE_THRESHOLD));
        compare(differences, "successThreshold", orDefault(pod.successThreshold(), DEFAULT_SUCCESS_THRESHOLD),
                orDefault(config.successThreshold(), DEFAULT_SUCCESS_THRESHOLD));
        return differences;
    }

    /** Das Schema gilt nur fuer HTTP-Probes; bei anderen Handlern wird es nicht verglichen. */
    private static String scheme(ProbeConfig probe) {
        if (probe.handlerKind() != null && probe.handlerKind() != ProbeConfig.HandlerKind.HTTP_GET) {
            return null;
        }
        return probe.httpScheme() == null ? DEFAULT_HTTP_SCHEME : probe.httpScheme().toUpperCase(Locale.ROOT);
    }

    private static Integer orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static void compare(List<String> differences, String field, Object podValue, Object configValue) {
        String left = podValue == null ? "fehlt" : String.valueOf(podValue);
        String right = configValue == null ? "fehlt" : String.valueOf(configValue);
        if (!left.equals(right)) {
            differences.add(field + " (Pod: " + left + ", Konfiguration: " + right + ")");
        }
    }

    /**
     * Mechanische Befunde aus Containerdaten.
     *
     * @param pod          Pod
     * @param evidenceRefs Evidence-IDs des Pods
     * @param configProbes aufgeloeste Probe-Konfiguration je Container (leer, wenn unbekannt)
     */
    public static List<Finding> containerFindings(PodSnapshot pod, List<String> evidenceRefs,
            java.util.Map<String, List<ProbeConfig>> configProbes) {
        List<Finding> findings = new ArrayList<>();
        boolean anyRestart = false;
        for (ContainerSnapshot container : pod.containers()) {
            ContainerSnapshot.ContainerObservations observations = container.observations();
            if (observations.hadRestart()) {
                anyRestart = true;
                findings.add(new Finding(Kind.CONTAINER_RESTARTED, Severity.NOTE, container.name(),
                        "Der Container wurde " + container.restartCount() + " Mal neu gestartet."
                                + (observations.lastTerminationReason() == null
                                        ? " Der Beendigungsgrund des letzten Versuchs ist nicht angegeben."
                                        : " Letzter Beendigungsgrund: " + observations.lastTerminationReason()
                                                + (observations.lastExitCode() == null
                                                        ? ""
                                                        : ", Exitcode " + observations.lastExitCode()) + "."),
                        "container.status", evidenceRefs));
            }
            if (observations.lastTerminationReason() != null
                    && "OOMKilled".equalsIgnoreCase(observations.lastTerminationReason())) {
                findings.add(new Finding(Kind.EVENT_OOM_KILLED, Severity.NOTE, container.name(),
                        "Der letzte Zustand des Containers ist mit OOMKilled begruendet.",
                        "container.lastState", evidenceRefs));
            } else if (observations.lastExitCode() != null && observations.lastExitCode() == 137) {
                findings.add(new Finding(Kind.EXIT_CODE_WITHOUT_REASON, Severity.WARN, container.name(),
                        "Der letzte Zustand des Containers endete mit Exitcode 137, ohne dass ein "
                                + "OOMKilled-Grund dokumentiert ist. Exitcode 137 allein belegt keinen OOM.",
                        "container.lastState", evidenceRefs));
            }
            if (container.state() != null && container.state().isWaiting() && container.state().reason() != null) {
                findings.add(new Finding(Kind.CONTAINER_WAITING, Severity.NOTE, container.name(),
                        "Der Container wartet; Grund: " + container.state().reason() + ".",
                        "container.state", evidenceRefs));
            }
        }
        for (ContainerSnapshot container : pod.initContainers()) {
            if (container.state() != null && container.state().isTerminated()
                    && container.state().exitCode() != null && container.state().exitCode() != 0) {
                findings.add(new Finding(Kind.CONTAINER_TERMINATED, Severity.NOTE, container.name(),
                        "Ein Init-Container endete mit Exitcode " + container.state().exitCode()
                                + "; Init-Container sind nicht die Hauptcontainer des Workloads.",
                        "container.initStatus", evidenceRefs));
            }
        }
        if (!pod.isReady() && !anyRestart) {
            findings.add(new Finding(Kind.NOT_READY_WITHOUT_RESTART, Severity.NOTE, null,
                    "Der Pod ist nicht bereit (Phase " + pod.phase() + ", nicht erfuellte Conditions: "
                            + String.join(", ", pod.notReadyConditionTypes())
                            + "), und es ist kein Containerneustart belegt. Eine fehlende Bereitschaft "
                            + "startet einen Container nicht neu.",
                    "pod.status", evidenceRefs));
        }
        for (ContainerSnapshot container : pod.containers()) {
            if (hasProbe(configProbes, container.name(), ProbeConfig.ProbeKind.STARTUP)) {
                continue;
            }
            if (hasProbe(configProbes, container.name(), ProbeConfig.ProbeKind.LIVENESS)
                    && observationsSuggestStartupPhase(container)) {
                findings.add(new Finding(Kind.NO_STARTUP_PROBE, Severity.NOTE, container.name(),
                        "Fuer diesen Container ist eine Liveness-Probe, aber keine Startup-Probe konfiguriert.",
                        "konfiguration und pod", evidenceRefs));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean observationsSuggestStartupPhase(ContainerSnapshot container) {
        return container.observations().hadRestart();
    }

    private static boolean hasProbe(java.util.Map<String, List<ProbeConfig>> probes, String container,
            ProbeConfig.ProbeKind kind) {
        List<ProbeConfig> list = probes.get(container);
        if (list == null || list.isEmpty()) {
            return true;
        }
        return list.stream().anyMatch(probe -> probe.kind() == kind);
    }

    /**
     * Mechanische Befunde aus Events. Es wird nur beschrieben, was im Event tatsaechlich steht.
     *
     * @param events       erfasste Events
     * @param evidenceRefs Evidence-ID je Event-Schluessel
     */
    public static List<Finding> eventFindings(List<EventSnapshot> events, java.util.Map<String, String> evidenceRefs) {
        List<Finding> findings = new ArrayList<>();
        for (EventSnapshot event : events) {
            if (event.isDuplicate()) {
                continue;
            }
            String reason = event.reason() == null ? "" : event.reason();
            String message = event.message() == null ? "" : event.message();
            String combined = (reason + " " + message).toLowerCase(Locale.ROOT);
            String ref = evidenceRefs.get(event.dedupeKey());
            List<String> refs = ref == null ? List.of() : List.of(ref);
            String container = containerFromMessage(message);
            if (combined.contains("liveness") && (combined.contains("kill") || combined.contains("restart")
                    || combined.contains("failed"))) {
                findings.add(new Finding(Kind.EVENT_KILLING_AFTER_LIVENESS, Severity.NOTE, container,
                        "Ein erfasstes Event nennt eine fehlgeschlagene Liveness-Pruefung"
                                + (countSuffix(event)) + ".",
                        "kubernetes-event", refs));
            } else if (combined.contains("readiness")) {
                findings.add(new Finding(Kind.EVENT_READINESS_FAILED, Severity.NOTE, container,
                        "Ein erfasstes Event nennt eine fehlgeschlagene Readiness-Pruefung"
                                + (countSuffix(event)) + ". Eine Readiness-Pruefung startet den Container nicht neu.",
                        "kubernetes-event", refs));
            } else if (combined.contains("timeout") && combined.contains("probe")) {
                findings.add(new Finding(Kind.EVENT_PROBE_TIMEOUT, Severity.NOTE, container,
                        "Ein erfasstes Event nennt eine Zeitueberschreitung einer Probe"
                                + (countSuffix(event)) + ".",
                        "kubernetes-event", refs));
            } else if (combined.contains("imagepull") || combined.contains("failedmount")
                    || combined.contains("failedscheduling") || combined.contains("backoff")) {
                findings.add(new Finding(Kind.EVENT_BEFORE_CONTAINER_START, Severity.NOTE, container,
                        "Ein erfasstes Event nennt einen Fehler vor dem Containerstart: " + safe(reason) + ".",
                        "kubernetes-event", refs));
            } else if (combined.contains("oomkill")) {
                findings.add(new Finding(Kind.EVENT_OOM_KILLED, Severity.NOTE, container,
                        "Ein erfasstes Event nennt einen OOMKilled-Vorgang" + (countSuffix(event)) + ".",
                        "kubernetes-event", refs));
            }
        }
        return List.copyOf(findings);
    }

    private static String countSuffix(EventSnapshot event) {
        if (event.isAggregated()) {
            return " (aggregierter Zaehler: " + event.count() + " Beobachtungen, erste "
                    + safe(event.firstTimestamp() == null ? null : event.firstTimestamp().toString()) + ", letzte "
                    + safe(event.bestKnownTime() == null ? null : event.bestKnownTime().toString()) + ")";
        }
        if (event.bestKnownTime() != null) {
            return " (letzte Beobachtung " + event.bestKnownTime() + ")";
        }
        return " (ohne Zeitangabe in der Quelle)";
    }

    private static String containerFromMessage(String message) {
        if (message == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)container[= ]\"?([a-z0-9][a-z0-9.-]{0,62})")
                .matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Befund fuer ein fehlendes vorheriges Log. */
    public static Finding previousLogUnavailable(String container, String reason, List<String> evidenceRefs) {
        return new Finding(Kind.PREVIOUS_LOG_UNAVAILABLE, Severity.WARN, container,
                "Vorherige Logs sind nicht verfuegbar: " + reason
                        + ". Ein geloeschter Container wird dadurch nicht wiederhergestellt.",
                "container-log", evidenceRefs);
    }

    public static List<Finding> previousLogFindings(List<ContainerLog> logs) {
        List<Finding> findings = new ArrayList<>();
        for (ContainerLog log : logs) {
            if (log.previous() && !log.available() && log.errorCode() != null) {
                findings.add(previousLogUnavailable(log.containerName(), log.errorMessage(), List.of()));
            }
        }
        return List.copyOf(findings);
    }

    static ProbeConfig.ProbeKind kind(String name) {
        try {
            return ProbeConfig.ProbeKind.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Deutsches Label einer Probe-Art. Hinweis fuer den Agenten, keine Ursachenbehauptung:
     * Readiness steuert die Servicebereitschaft und startet den Container nicht neu.
     */
    public static String label(ProbeConfig.ProbeKind kind) {
        if (kind == null) {
            return "Probe";
        }
        return switch (kind) {
            case LIVENESS -> "Liveness";
            case READINESS -> "Readiness";
            case STARTUP -> "Startup";
        };
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "nicht angegeben" : value;
    }
}
