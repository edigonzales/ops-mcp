package ch.so.agi.ops.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON-Schemas der sechs V1-Werkzeuge. Die Namen und wesentlichen Argumente bilden den
 * stabilen V1-Vertrag; die Schemas werden dem Client ueber {@code tools/list} mitgeteilt und
 * vom SDK zur Eingabepruefung verwendet.
 */
public final class ToolSchemas {

    public static final String TOOL_LIST_APPLICATIONS = "ops_list_applications";
    public static final String TOOL_RESOLVE_TARGET = "ops_resolve_target";
    public static final String TOOL_COLLECT_SNAPSHOT = "ops_collect_snapshot";
    public static final String TOOL_GET_SNAPSHOT = "ops_get_snapshot";
    public static final String TOOL_READ_EVIDENCE = "ops_read_evidence";
    public static final String TOOL_STORE_REPORT = "ops_store_report";

    private ToolSchemas() {
    }

    public static List<String> toolNames() {
        return List.of(TOOL_LIST_APPLICATIONS, TOOL_RESOLVE_TARGET, TOOL_COLLECT_SNAPSHOT, TOOL_GET_SNAPSHOT,
                TOOL_READ_EVIDENCE, TOOL_STORE_REPORT);
    }

    public static Map<String, Object> listApplicationsInput() {
        return object(entry("applicationId", string(1, 64, "Optionale Anwendungskennung, z. B. modelfinder.")));
    }

    public static Map<String, Object> listApplicationsOutput() {
        Map<String, Object> environment = object(
                entry("environment", string(1, 64, "Umgebungsname")),
                entry("cluster", string(1, 64, "Clusterkennung")),
                entry("namespace", nullableString(128, "Namespace oder null")),
                entry("workloadKind", nullableString(32, "Deployment oder DeploymentConfig oder null")),
                entry("workloadName", nullableString(128, "Workload-Name oder null")),
                entry("jenkins", nullableString(64, "Jenkins-Kennung oder null")),
                entry("jobPath", array(string(1, 128, "Jobpfadsegment"))),
                entry("repository", nullableString(64, "Repositorykennung oder null")),
                entry("configurationStatus", enumeration("ready", "incomplete")),
                entry("missingFields", array(string(1, 128, "fehlende Angabe"))),
                entry("warnings", array(string(1, 512, "Hinweis"))));
        Map<String, Object> application = object(
                entry("applicationId", string(1, 64, "Anwendungskennung")),
                entry("displayName", string(1, 128, "Anzeigename")),
                // Der Datenmodus steht auch je Anwendung, damit eine einzelne Anwendung ohne
                // Kontextbezug eindeutig als synthetisch oder live erkennbar ist.
                entry("dataMode", enumeration("fixture", "live")),
                entry("environments", array(environment)));
        return object(
                entry("dataMode", enumeration("fixture", "live")),
                entry("applications", array(application)),
                required("dataMode", "applications"));
    }

    public static Map<String, Object> resolveTargetInput() {
        Map<String, Object> build = object(
                entry("kind", enumeration("number", "lastFailed")),
                entry("number", integer(1, "Konkrete Buildnummer bei kind=number.")),
                required("kind"));
        return object(
                entry("applicationId", string(1, 64, "Anwendungskennung.")),
                entry("environment", string(1, 64, "Umgebungsname.")),
                entry("build", build),
                entry("buildUrl", string(1, 512,
                        "Optionaler Browserlink auf einen Build; muss zur freigegebenen Zuordnung passen.")),
                required("applicationId", "environment", "build"));
    }

    public static Map<String, Object> resolveTargetOutput() {
        return object(
                entry("targetId", string(1, 64, "Undurchsichtige, serverseitig erzeugte Kennung.")),
                entry("applicationId", string(1, 64, "Anwendungskennung.")),
                entry("environment", string(1, 64, "Umgebungsname.")),
                entry("dataMode", enumeration("fixture", "live")),
                entry("buildNumber", integer(1, "Konkrete Buildnummer.")),
                entry("jenkinsInstance", string(1, 64, "Jenkins-Kennung.")),
                entry("jobPath", array(string(1, 128, "Jobpfadsegment"))),
                entry("namespace", string(1, 128, "Erlaubter Namespace.")),
                entry("workloadKind", string(1, 32, "Workload-Art.")),
                entry("workloadName", string(1, 128, "Workload-Name.")),
                entry("resolvedAt", string(1, 40, "Aufloesungszeit (RFC3339, UTC).")),
                entry("expiresAt", string(1, 40, "Ablaufzeit des Handles (RFC3339, UTC).")),
                entry("warnings", array(string(1, 512, "Hinweis"))),
                required("targetId", "applicationId", "environment", "dataMode", "buildNumber",
                        "jenkinsInstance", "jobPath", "namespace", "workloadKind", "workloadName",
                        "resolvedAt", "expiresAt"));
    }

    public static Map<String, Object> collectSnapshotInput() {
        return object(
                entry("targetId", string(1, 64, "Target-Handle aus ops_resolve_target.")),
                required("targetId"));
    }

    public static Map<String, Object> collectSnapshotOutput() {
        return snapshotOutput(false);
    }

    public static Map<String, Object> getSnapshotInput() {
        return object(
                entry("snapshotId", string(1, 64, "Snapshot-ID aus ops_collect_snapshot.")),
                required("snapshotId"));
    }

    public static Map<String, Object> getSnapshotOutput() {
        Map<String, Object> schema = snapshotOutput(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        properties.put("useCase", string(1, 64, "Verwendungszweck; in V1 deployment-failure."));
        properties.put("collectionStartedAt", nullableString(40, "Beginn der Erfassung."));
        properties.put("collectionFinishedAt", nullableString(40, "Ende der Erfassung."));
        properties.put("findings", array(findingSchema()));
        properties.put("revision", object(
                entry("repositoryId", string(1, 128, "Repositorykennung.")),
                entry("commit", nullableString(72, "Commit oder null.")),
                entry("source", string(1, 64, "Herkunft der Revision.")),
                entry("provenanceLevel", string(1, 64, "Provenienzstufe.")),
                entry("forBuild", nullableInteger("Buildnummer einer Betreiberangabe oder null.")),
                entry("evidenceRef", nullableString(128, "Verweis auf den tragenden Beleg oder null.")),
                entry("note", string(0, 512, "Hinweis."))));
        properties.put("readBudget", object(
                entry("consumedBytes", integer(0, "Verbrauchte Bytes.")),
                entry("limitBytes", integer(1, "Gesamtbudget in Bytes.")),
                entry("remainingBytes", integer(0, "Verbleibende Bytes.")),
                entry("exhausted", Map.of("type", "boolean", "description", "Budget aufgebraucht."))));
        schema.put("required", List.of("snapshotId", "collectionStatus", "dataMode", "applicationId",
                "environment", "buildNumber", "jenkinsInstance", "jobPath", "namespace", "investigationWindow",
                "sourceResults", "links", "evidenceIndex", "findings", "readBudget"));
        return schema;
    }

    private static Map<String, Object> snapshotOutput(boolean withDetails) {
        Map<String, Object> entries = object(
                entry("snapshotId", string(1, 64, "Snapshot-ID.")),
                entry("collectionStatus", enumeration("complete", "partial", "failed")),
                entry("dataMode", enumeration("fixture", "live")),
                entry("applicationId", string(1, 64, "Anwendungskennung.")),
                entry("environment", string(1, 64, "Umgebungsname.")),
                entry("buildNumber", integer(1, "Untersuchte Buildnummer.")),
                entry("jenkinsInstance", string(1, 64, "Jenkins-Kennung.")),
                entry("jobPath", array(string(1, 128, "Jobpfadsegment"))),
                entry("namespace", string(1, 128, "Namespace.")),
                entry("workloadKind", nullableString(32, "Workload-Art.")),
                entry("workloadName", nullableString(128, "Workload-Name.")),
                entry("buildRef", buildRefSchema()),
                entry("target", targetSchema()),
                entry("investigationWindow", windowSchema()),
                entry("sourceResults", array(sourceSummarySchema())),
                entry("links", array(linkSchema())),
                entry("evidenceIndex", array(evidenceIndexSchema())),
                entry("warnings", array(string(1, 512, "Warnung"))),
                entry("errors", array(errorSchema())));
        List<String> requiredNames = new ArrayList<>(List.of("snapshotId", "collectionStatus", "dataMode",
                "applicationId", "environment", "buildNumber", "jenkinsInstance", "jobPath", "namespace",
                "investigationWindow", "sourceResults", "links", "evidenceIndex"));
        entries.put("required", requiredNames);
        return entries;
    }

    public static Map<String, Object> readEvidenceInput() {
        Map<String, Object> properties = object(
                entry("snapshotId", string(1, 64, "Snapshot-ID.")),
                entry("evidenceId", string(1, 32, "Evidence-ID, z. B. EV-0004.")),
                entry("startLine", integer(1, "Erste Zeile, 1-basiert. Standard 1.")),
                entry("maxLines", boundedInteger(1, 200, "Maximale Zeilenzahl; wird auf 200 begrenzt. Standard 100.")),
                required("snapshotId", "evidenceId"));
        return properties;
    }

    public static Map<String, Object> readEvidenceOutput() {
        return object(
                entry("snapshotId", string(1, 64, "Snapshot-ID.")),
                entry("evidenceId", string(1, 32, "Evidence-ID.")),
                entry("type", string(1, 64, "Evidence-Art.")),
                entry("sourceId", string(1, 64, "Quellenkennung.")),
                entry("text", string(0, 65536, "Redigierter Ausschnitt mit Zeilennummern.")),
                entry("startLine", integer(1, "Erste gelieferte Zeile.")),
                entry("endLine", integer(0, "Letzte gelieferte Zeile.")),
                entry("totalLineCount", integer(0, "Gesamtzahl der Zeilen.")),
                entry("hasMore", Map.of("type", "boolean", "description", "true, wenn weitere Zeilen vorhanden sind.")),
                entry("nextStartLine", nullableInteger("Naechste Startzeile oder null.")),
                entry("truncatedLine", Map.of("type", "boolean",
                        "description", "true, wenn eine ueberlange Einzelzeile gekuerzt wurde.")),
                entry("byteBudgetHit", Map.of("type", "boolean",
                        "description", "true, wenn das Bytebudget die Antwort begrenzt hat.")),
                entry("hardLimitNote", string(0, 256, "Hinweis, wenn maxLines auf das Hardlimit begrenzt wurde.")),
                entry("budgetWarning", string(0, 256, "Hinweis, wenn das auftragsweite Lesebudget erschoepft ist.")),
                entry("gaps", array(string(1, 512, "Erfassungsluecke oder Hinweis"))),
                entry("readBudget", object(
                        entry("consumedBytes", integer(0, "Verbrauchte Bytes.")),
                        entry("limitBytes", integer(1, "Gesamtbudget in Bytes.")),
                        entry("remainingBytes", integer(0, "Verbleibende Bytes.")),
                        entry("exhausted", Map.of("type", "boolean", "description", "Budget aufgebraucht.")))),
                required("snapshotId", "evidenceId", "type", "sourceId", "text", "startLine", "endLine",
                        "totalLineCount", "hasMore", "truncatedLine", "readBudget"));
    }

    public static Map<String, Object> storeReportInput() {
        return object(
                entry("snapshotId", string(1, 64, "Snapshot-ID, an die der Bericht gebunden wird.")),
                entry("diagnosis", Map.of("type", "object",
                        "description", "Diagnosebericht nach diagnosis-v1.schema.json.")),
                required("snapshotId", "diagnosis"));
    }

    public static Map<String, Object> storeReportOutput() {
        return object(
                entry("reportId", string(1, 64, "Berichtskennung.")),
                entry("snapshotId", string(1, 64, "Snapshot-ID.")),
                entry("jsonPath", string(1, 256, "Relativer Pfad der JSON-Datei.")),
                entry("markdownPath", string(1, 256, "Relativer Pfad der Markdown-Datei.")),
                entry("valid", Map.of("type", "boolean", "description", "Validierungsstatus.")),
                entry("createdAt", nullableString(40, "Speicherzeit (RFC3339, UTC).")),
                entry("storageNote", string(0, 512, "Hinweis zum ausschliesslich lokalen Ablageort.")),
                entry("warnings", array(string(1, 512, "Hinweis"))),
                required("reportId", "snapshotId", "jsonPath", "markdownPath", "valid"));
    }

    private static Map<String, Object> buildRefSchema() {
        return object(
                entry("instanceId", string(1, 64, "Jenkins-Kennung.")),
                entry("jobPath", array(string(1, 128, "Jobpfadsegment"))),
                entry("buildNumber", integer(1, "Konkrete Buildnummer.")),
                entry("metadataAvailable", Map.of("type", "boolean",
                        "description", "true, wenn strukturierte Buildmetadaten vorlagen.")),
                required("instanceId", "jobPath", "buildNumber"));
    }

    private static Map<String, Object> targetSchema() {
        return object(
                entry("clusterId", nullableString(64, "Clusterkennung.")),
                entry("namespace", nullableString(128, "Namespace.")),
                entry("workloadKind", nullableString(32, "Workload-Art.")),
                entry("workloadName", nullableString(128, "Workload-Name.")));
    }

    private static Map<String, Object> windowSchema() {
        return object(
                entry("from", nullableString(40, "Fensterbeginn (RFC3339, UTC) oder null.")),
                entry("to", nullableString(40, "Fensterende (RFC3339, UTC) oder null.")),
                entry("timeBasis", string(1, 64, "Herleitung des Fensters.")),
                entry("derivation", string(1, 1024, "Klartextherleitung.")),
                required("timeBasis", "derivation"));
    }

    private static Map<String, Object> sourceSummarySchema() {
        return object(
                entry("sourceId", string(1, 128, "Quellenkennung.")),
                entry("status", enumeration("COLLECTED", "NOT_PRESENT", "NOT_APPLICABLE", "NOT_ALLOWED",
                        "TRUNCATED", "UNREACHABLE", "NOT_ATTEMPTED")),
                entry("collectedAt", nullableString(40, "Erfassungszeit oder null.")),
                entry("evidenceCount", integer(0, "Anzahl Evidence.")),
                entry("evidenceIds", array(string(1, 32, "Evidence-ID"))),
                entry("notes", array(string(1, 512, "Hinweis"))),
                entry("errors", array(errorSchema())),
                required("sourceId", "status", "evidenceCount"));
    }

    private static Map<String, Object> linkSchema() {
        return object(
                entry("from", string(1, 256, "Ausgangsknoten.")),
                entry("to", string(1, 256, "Zielknoten.")),
                entry("relation", string(1, 64, "Beziehungsart.")),
                entry("strength", enumeration("exact", "probable", "ambiguous", "unlinked")),
                entry("reason", string(1, 1024, "Begruendung.")),
                entry("evidenceRefs", array(string(1, 32, "Evidence-ID"))),
                entry("limitations", array(string(1, 512, "Grenze der Zuordnung"))),
                required("from", "to", "relation", "strength", "reason"));
    }

    private static Map<String, Object> evidenceIndexSchema() {
        return object(
                entry("evidenceId", string(1, 32, "Evidence-ID.")),
                entry("id", string(1, 32, "Evidence-ID.")),
                entry("type", string(1, 64, "Evidence-Art.")),
                entry("sourceId", string(1, 64, "Quellenkennung.")),
                entry("observedAt", nullableString(40, "Fachlicher Zeitbezug oder null.")),
                entry("collectedAt", nullableString(40, "Erfassungszeit oder null.")),
                entry("description", string(0, 256, "Kurzbeschreibung.")),
                entry("sha256", string(1, 64, "Hash des gespeicherten bereinigten Inhalts.")),
                entry("lineCount", integer(0, "Zeilenzahl.")),
                entry("byteSize", integer(0, "Groesse in Bytes.")),
                entry("dataMode", enumeration("fixture", "live")),
                entry("synthetic", Map.of("type", "boolean", "description",
                        "true, wenn der Snapshot aus synthetischen Fixturedaten stammt.")),
                entry("description", string(0, 256, "Kurzbeschreibung.")),
                entry("locator", object(
                        entry("path", string(1, 256, "Relativer Pfad im Snapshot.")),
                        entry("lineCount", integer(0, "Zeilenzahl.")),
                        entry("byteSize", integer(0, "Groesse in Bytes.")),
                        required("path", "lineCount"))),
                entry("object", object(
                        entry("kind", string(1, 64, "Objektart.")),
                        entry("namespace", string(0, 128, "Namespace.")),
                        entry("name", string(0, 128, "Objektname.")),
                        entry("uid", nullableString(128, "UID oder null.")))),
                entry("provenance", nullableString(1024, "Herkunft im Quellsystem.")),
                entry("notes", array(string(1, 512, "Hinweis"))),
                // Pflicht ist nur, was jede Evidence-Sicht sicher traegt. ops_get_snapshot
                // liefert zusaetzlich byteSize, sha256, dataMode und object, sofern vorhanden.
                required("evidenceId", "type", "sourceId", "lineCount", "locator"));
    }

    private static Map<String, Object> findingSchema() {
        return object(
                entry("kind", string(1, 64, "Befundart.")),
                entry("severity", enumeration("INFO", "NOTE", "WARN")),
                entry("container", nullableString(128, "betroffener Container oder null.")),
                entry("statement", string(1, 2000, "Beobachtung.")),
                entry("basis", string(1, 256, "worauf die Beobachtung beruht.")),
                entry("evidenceRefs", array(string(1, 32, "Evidence-ID"))),
                required("kind", "severity", "statement", "basis"));
    }

    private static Map<String, Object> errorSchema() {
        return object(
                entry("code", string(1, 64, "Stabiler Fehlercode.")),
                entry("sourceId", string(1, 128, "Quellenkennung.")),
                entry("message", string(1, 2000, "Redigierte Erklaerung.")),
                entry("retryable", Map.of("type", "boolean",
                        "description", "Wiederholbar innerhalb des Budgets.")),
                entry("suggestedAction", string(0, 1024, "Manueller naechster Schritt.")),
                required("code", "sourceId", "message"));
    }

    private static Map<String, Object> object(Object... entries) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> requiredNames = new ArrayList<>();
        for (Object entry : entries) {
            Map.Entry<String, Object> item = asEntry(entry);
            if ("required".equals(item.getKey())) {
                requiredNames.addAll(asList(item.getValue()));
            } else {
                properties.put(item.getKey(), item.getValue());
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        if (!requiredNames.isEmpty()) {
            schema.put("required", requiredNames);
        }
        return schema;
    }

    private static Map<String, Object> string(int minLength, int maxLength, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("minLength", minLength);
        schema.put("maxLength", maxLength);
        schema.put("description", description);
        return schema;
    }

    private static Map<String, Object> nullableString(int maxLength, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", List.of("string", "null"));
        schema.put("maxLength", maxLength);
        schema.put("description", description);
        return schema;
    }

    private static Map<String, Object> integer(int minimum, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "integer");
        schema.put("minimum", minimum);
        schema.put("description", description);
        return schema;
    }

    private static Map<String, Object> boundedInteger(int minimum, int maximum, String description) {
        Map<String, Object> schema = integer(minimum, description);
        schema.put("maximum", maximum);
        return schema;
    }

    private static Map<String, Object> nullableInteger(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", List.of("integer", "null"));
        schema.put("description", description);
        return schema;
    }

    private static Map<String, Object> enumeration(String... values) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("enum", List.of(values));
        return schema;
    }

    private static Map<String, Object> array(Map<String, Object> items) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", items);
        return schema;
    }

    private static Map.Entry<String, Object> entry(String key, Object value) {
        return Map.entry(key, value);
    }

    private static Map.Entry<String, Object> required(String... names) {
        return Map.entry("required", List.of(names));
    }

    @SuppressWarnings("unchecked")
    private static Map.Entry<String, Object> asEntry(Object value) {
        if (value instanceof Map.Entry<?, ?> entry) {
            return Map.entry(String.valueOf(entry.getKey()), entry.getValue());
        }
        throw new IllegalArgumentException("Ungueltiger Schemaeintrag: " + value);
    }

    private static List<String> asList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            list.forEach(item -> result.add(String.valueOf(item)));
            return result;
        }
        if (value instanceof String text) {
            return List.of(text);
        }
        throw new IllegalArgumentException("Ungueltige required-Liste: " + value);
    }
}
