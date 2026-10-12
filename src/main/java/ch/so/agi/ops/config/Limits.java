package ch.so.agi.ops.config;

import java.util.Map;

/**
 * Ressourcengrenzen. Lokale Aenderungen sind nur innerhalb der dokumentierten Hardlimits
 * zulaessig; die Validierung erzwingt sie.
 *
 * @param collectTimeoutSeconds     Gesamtbudget der Erfassung
 * @param requestTimeoutSeconds     Timeout eines einzelnen HTTP-Aufrufs
 * @param connectTimeoutSeconds     Verbindungsaufbau-Timeout
 * @param maxParallelRequests       maximale Parallelitaet
 * @param maxRequests               maximale Anzahl Quellaufrufe
 * @param maxPods                   maximale Anzahl erfasster Pods
 * @param maxEvents                 maximale Anzahl erfasster Events
 * @param maxGitFiles               maximale Anzahl gelesener Git-Dateien
 * @param maxBuildLogBytes          Bytebudget des Jenkins-Console-Logs
 * @param maxContainerLogBytes      Bytebudget je Container-Log
 * @param maxTotalInputBytes        Gesamtbudget des Eingangs
 * @param maxEvidenceResponseBytes  Bytebudget einer einzelnen MCP-Evidenceantwort
 * @param maxReportDraftBytes       Bytebudget eines Berichtsentwurfs
 */
public record Limits(
        int collectTimeoutSeconds,
        int requestTimeoutSeconds,
        int connectTimeoutSeconds,
        int maxParallelRequests,
        int maxRequests,
        int maxPods,
        int maxEvents,
        int maxGitFiles,
        long maxBuildLogBytes,
        long maxContainerLogBytes,
        long maxTotalInputBytes,
        int maxEvidenceResponseBytes,
        int maxReportDraftBytes) {

    /** Dokumentierte Hardlimits fuer V1 (Entwurfswerte, keine gemessenen AGI-Anforderungen). */
    public static final int HARD_MAX_COLLECT_TIMEOUT_SECONDS = 180;
    public static final int HARD_MAX_PODS = 16;
    public static final int HARD_MAX_EVENTS = 1000;
    public static final int HARD_MAX_REQUESTS = 120;
    public static final long HARD_MAX_TOTAL_INPUT_BYTES = 64L * 1024 * 1024;
    public static final int HARD_MAX_EVIDENCE_RESPONSE_BYTES = 64 * 1024;
    public static final int HARD_MAX_REPORT_DRAFT_BYTES = 256 * 1024;
    /** Maximale Zeilen einer einzelnen Evidence-Antwort, unabhaengig von der Agentenanfrage. */
    public static final int HARD_MAX_EVIDENCE_LINES = 200;

    public static Limits defaults() {
        return new Limits(90, 15, 5, 3, 60, 8, 500, 12, 8 * 1024 * 1024, 1024 * 1024, 32 * 1024 * 1024,
                32 * 1024, 256 * 1024);
    }

    public java.time.Duration collectTimeout() {
        return java.time.Duration.ofSeconds(collectTimeoutSeconds);
    }

    public java.time.Duration requestTimeout() {
        return java.time.Duration.ofSeconds(requestTimeoutSeconds);
    }

    public java.time.Duration connectTimeout() {
        return java.time.Duration.ofSeconds(connectTimeoutSeconds);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("collectTimeoutSeconds", collectTimeoutSeconds);
        map.put("requestTimeoutSeconds", requestTimeoutSeconds);
        map.put("connectTimeoutSeconds", connectTimeoutSeconds);
        map.put("maxParallelRequests", maxParallelRequests);
        map.put("maxRequests", maxRequests);
        map.put("maxPods", maxPods);
        map.put("maxEvents", maxEvents);
        map.put("maxGitFiles", maxGitFiles);
        map.put("maxBuildLogBytes", maxBuildLogBytes);
        map.put("maxContainerLogBytes", maxContainerLogBytes);
        map.put("maxTotalInputBytes", maxTotalInputBytes);
        map.put("maxEvidenceResponseBytes", maxEvidenceResponseBytes);
        map.put("maxReportDraftBytes", maxReportDraftBytes);
        return map;
    }
}
