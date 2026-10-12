package ch.so.agi.ops.adapter.git;

import ch.so.agi.ops.domain.git.ConfigValue;
import ch.so.agi.ops.domain.git.ContainerConfig;
import ch.so.agi.ops.domain.git.FileReadResult;
import ch.so.agi.ops.domain.k8s.ProbeConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests des Template-/Parameterparsers. V1 loest nur einfache Zuweisungen und einfache
 * {@code ${NAME}}-Referenzen auf; alles andere bleibt ausdruecklich offen.
 */
class TemplateParameterResolverTest {

    private static final String TEMPLATE_FILE = "templates/deployment.yaml";
    private static final String PARAMETER_FILE = "params/app.env";

    private static final List<String> ALLOWED = List.of(
            "IMAGE_REF", "MIN_READY_SECONDS", "HEALTH_PATH", "PROGRESS_DEADLINE");

    private static final String DEPLOYMENT = """
            apiVersion: apps/v1
            kind: Deployment
            metadata:
              name: fixture-app
            spec:
              progressDeadlineSeconds: 600
              minReadySeconds: ${MIN_READY_SECONDS}
              revisionHistoryLimit: 3
              template:
                spec:
                  containers:
                    - name: main
                      image: ${IMAGE_REF}
                      ports:
                        - name: http
                          containerPort: 8080
                      resources:
                        limits:
                          cpu: 500m
                          memory: 512Mi
                      env:
                        - name: DB_PASSWORD
                          value: fixture-env-canary-0123456789
                      livenessProbe:
                        httpGet:
                          path: /healthz
                          port: 8080
                        initialDelaySeconds: 10
                        failureThreshold: 3
                    - name: sidecar
                      image: registry.example.invalid/sidecar:1.2.3
            """;

    private static final String PARAMETERS = """
            # Synthetische Parameter; keine echten Zugangsdaten.
            IMAGE_REF=registry.example.invalid/fixture-app:1.0.0
            MIN_READY_SECONDS=5
            HEALTH_PATH="/healthz"
            export PROGRESS_DEADLINE=600
            NOT_ALLOWED_PARAM=fixture-not-allowed-canary-0123456789
            DB_PASSWORD=fixture-env-canary-0123456789
            """;

    private final TemplateParameterResolver resolver = new TemplateParameterResolver();

    private TemplateParameterResolver.Result resolve(String template, String parameters) {
        Map<String, String> templates = new LinkedHashMap<>();
        templates.put(TEMPLATE_FILE, template);
        Map<String, String> parameterFiles = new LinkedHashMap<>();
        if (parameters != null) {
            parameterFiles.put(PARAMETER_FILE, parameters);
        }
        return resolver.resolve(templates, parameterFiles, ALLOWED);
    }

    @Test
    void resolvesContainersProbesPortsResourcesAndRolloutParameters() {
        TemplateParameterResolver.Result result = resolve(DEPLOYMENT, PARAMETERS);

        assertEquals(List.of("main", "sidecar"), result.containerNames());
        Map<String, ContainerConfig> containers = result.containers();
        assertEquals(2, containers.size());

        ContainerConfig main = containers.get("main");
        assertNotNull(main);
        assertEquals("/spec/template/spec/containers/0", main.rawPointerBase());
        assertEquals("registry.example.invalid/fixture-app:1.0.0", main.image());
        assertEquals(PARAMETER_FILE, main.imageSource(), "das Image stammt aus der Parameterdatei");
        assertEquals(Map.of("http", 8080), main.ports());
        assertEquals("500m", main.resources().get("limits.cpu"));
        assertEquals("512Mi", main.resources().get("limits.memory"));
        assertEquals(List.of("liveness"), main.probeKinds());

        ProbeConfig liveness = main.probes().get("liveness");
        assertEquals(ProbeConfig.HandlerKind.HTTP_GET, liveness.handlerKind());
        assertEquals("/healthz", liveness.httpPath());
        assertEquals("8080", liveness.httpPort());
        assertEquals(10, liveness.initialDelaySeconds());
        assertEquals(3, liveness.failureThreshold());
        assertEquals(ProbeConfig.ProbeKind.LIVENESS, liveness.kind());
        assertEquals("main", liveness.containerName());

        ContainerConfig sidecar = containers.get("sidecar");
        assertNotNull(sidecar);
        assertEquals("/spec/template/spec/containers/1", sidecar.rawPointerBase());
        assertEquals("registry.example.invalid/sidecar:1.2.3", sidecar.image());
        assertEquals(List.of(), sidecar.probeKinds(), "fuer den Sidecar ist keine Probe konfiguriert");

        // Rollout-Zeitparameter: nur die vier erlaubten Felder, mit Herkunft.
        Map<String, ConfigValue> rollout = result.rolloutTimeParameters();
        assertEquals(3, rollout.size());
        assertEquals("600", rollout.get("progressDeadlineSeconds").value());
        assertEquals(ConfigValue.Resolution.TEMPLATE_LITERAL, rollout.get("progressDeadlineSeconds").resolution());
        assertEquals(TEMPLATE_FILE, rollout.get("progressDeadlineSeconds").templateFile());
        assertEquals(6, rollout.get("progressDeadlineSeconds").templateLine());
        assertNull(rollout.get("progressDeadlineSeconds").parameterFile());
        assertEquals("MIN_READY_SECONDS", rollout.get("minReadySeconds").parameterName());
        assertEquals(PARAMETER_FILE, rollout.get("minReadySeconds").parameterFile());
        assertEquals("5", rollout.get("minReadySeconds").value());
        assertEquals(3, rollout.get("minReadySeconds").parameterLine());
        assertEquals("3", rollout.get("revisionHistoryLimit").value());
        assertEquals(ConfigValue.Resolution.TEMPLATE_LITERAL, rollout.get("revisionHistoryLimit").resolution());
        assertEquals(TEMPLATE_FILE, rollout.get("revisionHistoryLimit").templateFile());
        assertEquals(8, rollout.get("revisionHistoryLimit").templateLine());
        assertNull(rollout.get("activeDeadlineSeconds"), "nicht vorhandene Parameter werden nicht erfunden");
    }

    @Test
    void everyConfigValueCarriesTemplateFileAndLine() {
        TemplateParameterResolver.Result result = resolve(DEPLOYMENT, PARAMETERS);

        ConfigValue image = result.values().get("/spec/template/spec/containers/0/image");
        assertNotNull(image);
        assertEquals(TEMPLATE_FILE, image.templateFile());
        assertEquals(13, image.templateLine());
        assertEquals(PARAMETER_FILE, image.parameterFile());
        assertEquals(2, image.parameterLine());
        assertEquals("IMAGE_REF", image.parameterName());
        assertTrue(image.isResolved());

        ConfigValue initialDelay = result.values()
                .get("/spec/template/spec/containers/0/livenessProbe/initialDelaySeconds");
        assertNotNull(initialDelay);
        assertEquals(TEMPLATE_FILE, initialDelay.templateFile());
        assertEquals(28, initialDelay.templateLine());
        assertEquals("10", initialDelay.value());
        assertNull(initialDelay.parameterFile());
        assertFalse(initialDelay.provenance().isBlank());

        ConfigValue port = result.values().get("/spec/template/spec/containers/0/ports/0/containerPort");
        assertNotNull(port);
        assertEquals(16, port.templateLine());
        assertEquals("8080", port.value());

        ConfigValue cpu = result.values().get("/spec/template/spec/containers/0/resources/limits/cpu");
        assertNotNull(cpu);
        assertEquals(19, cpu.templateLine());
        assertEquals("500m", cpu.value());

        ConfigValue sidecarImage = result.values().get("/spec/template/spec/containers/1/image");
        assertNotNull(sidecarImage);
        assertEquals(31, sidecarImage.templateLine());
    }

    @Test
    void envBlocksAreNotDelivered() {
        TemplateParameterResolver.Result result = resolve(DEPLOYMENT, PARAMETERS);

        List<String> delivered = result.deliverableLines().get(TEMPLATE_FILE);
        assertNotNull(delivered);
        assertEquals(TemplateParameterResolver.splitLines(DEPLOYMENT).size(), delivered.size(),
                "die Zeilenzahl muss erhalten bleiben");
        String joined = String.join("\n", delivered);
        assertFalse(joined.contains("fixture-env-canary-0123456789"), joined);
        assertTrue(joined.contains("[nicht uebernommen: env]"), joined);
        assertFalse(joined.contains("DB_PASSWORD"), joined);
        assertTrue(joined.contains("initialDelaySeconds: 10"), "nicht sensible Zeilen bleiben lesbar");

        for (ConfigValue value : result.values().values()) {
            assertFalse(value.pointer().contains("/env"), "env wird nicht ausgeliefert: " + value.pointer());
            assertFalse(String.valueOf(value.value()).contains("fixture-env-canary"), value.pointer());
        }
    }

    @Test
    void execArgumentsAndHeaderValuesAreRedacted() {
        String template = """
                kind: Deployment
                spec:
                  template:
                    spec:
                      containers:
                        - name: main
                          livenessProbe:
                            httpGet:
                              path: /healthz
                              port: 8080
                              httpHeaders:
                                - name: X-Probe-Token
                                  value: fixture-header-canary-0123456789
                          startupProbe:
                            exec:
                              command:
                                - /bin/sh
                                - -c
                                - check --token fixture-exec-canary-0123456789
                            failureThreshold: 30
                """;
        TemplateParameterResolver.Result result = resolve(template, PARAMETERS);
        ContainerConfig main = result.containers().get("main");
        assertNotNull(main);

        ProbeConfig http = main.probes().get("liveness");
        assertEquals(List.of("X-Probe-Token"), http.httpHeaderNames());
        assertTrue(http.redacted(), "Headerwerte werden nicht uebernommen");
        assertTrue(http.redactionNote().contains("Headerwerte"));

        ProbeConfig exec = main.probes().get("startup");
        assertNotNull(exec);
        assertEquals(ProbeConfig.HandlerKind.EXEC, exec.handlerKind());
        assertTrue(exec.execCommandRedacted());
        assertTrue(exec.redacted());
        assertTrue(exec.redactionNote().contains("Exec-Argumente"));

        String rendered = result.containers().values().toString() + result.values().values();
        assertFalse(rendered.contains("fixture-exec-canary-0123456789"), rendered);
        assertFalse(rendered.contains("fixture-header-canary-0123456789"), rendered);
    }

    @Test
    void unresolvableAndConflictingValuesStayOpen() {
        String template = """
                kind: Deployment
                spec:
                  template:
                    spec:
                      containers:
                        - name: main
                          image: ${NOT_DEFINED}
                          resources:
                            limits:
                              cpu: ${UNKNOWN_PARAM}
                """;
        TemplateParameterResolver.Result result = resolve(template, PARAMETERS);
        ConfigValue notDefined = result.values().get("/spec/template/spec/containers/0/image");
        assertNotNull(notDefined);
        assertEquals(ConfigValue.Resolution.UNRESOLVED, notDefined.resolution());
        assertNull(notDefined.value());
        assertEquals("NOT_DEFINED", notDefined.parameterName());
        assertFalse(notDefined.note().isBlank());
        assertEquals(7, notDefined.templateLine());
        assertFalse(result.notes().isEmpty());

        ConfigValue notAllowed = result.values().get("/spec/template/spec/containers/0/resources/limits/cpu");
        assertEquals(ConfigValue.Resolution.UNRESOLVED, notAllowed.resolution());
        assertNull(notAllowed.value());
        assertTrue(notAllowed.note().contains("nicht erlaubt"));

        Map<String, String> templates = new LinkedHashMap<>();
        templates.put(TEMPLATE_FILE, """
                kind: Deployment
                spec:
                  template:
                    spec:
                      containers:
                        - name: main
                          image: ${IMAGE_REF}
                """);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("params/a.env", "IMAGE_REF=registry.example.invalid/app:1.0.0\n");
        files.put("params/b.env", "IMAGE_REF=registry.example.invalid/app:2.0.0\n");
        TemplateParameterResolver.Result conflicting = resolver.resolve(templates, files, ALLOWED);
        ConfigValue value = conflicting.values().get("/spec/template/spec/containers/0/image");
        assertEquals(ConfigValue.Resolution.CONFLICTING, value.resolution());
        assertNull(value.value());
        assertTrue(value.note().contains("Widerspruechliche"));
        assertNull(conflicting.containers().get("main").image());
        assertTrue(conflicting.notes().stream().anyMatch(note -> note.contains("widerspruechlich")));
    }

    @Test
    void shellExpressionsAreNeverEvaluated() {
        Map<String, String> templates = new LinkedHashMap<>();
        templates.put(TEMPLATE_FILE, """
                kind: Deployment
                spec:
                  template:
                    spec:
                      containers:
                        - name: main
                          image: ${IMAGE_REF}
                          resources:
                            limits:
                              cpu: ${MIN_READY_SECONDS:-default}
                """);
        Map<String, String> files = new LinkedHashMap<>();
        files.put(PARAMETER_FILE, "IMAGE_REF=$(cat /etc/passwd)\nMIN_READY_SECONDS=`id`\n");
        TemplateParameterResolver.Result result = resolver.resolve(templates, files, ALLOWED);

        ConfigValue image = result.values().get("/spec/template/spec/containers/0/image");
        assertEquals(ConfigValue.Resolution.UNRESOLVED, image.resolution());
        assertNull(image.value());
        ConfigValue expression = result.values().get("/spec/template/spec/containers/0/resources/limits/cpu");
        assertEquals(ConfigValue.Resolution.UNRESOLVED, expression.resolution());
        assertNull(expression.value());
        assertNull(result.containers().get("main").image());
    }

    @Test
    void secretDocumentsAreNotEvaluatedOrDelivered() {
        String template = """
                apiVersion: v1
                kind: Secret
                metadata:
                  name: fixture-secret
                stringData:
                  token: fixture-secret-canary-0123456789
                ---
                kind: Deployment
                spec:
                  template:
                    spec:
                      containers:
                        - name: main
                          image: registry.example.invalid/app:1.0.0
                """;
        TemplateParameterResolver.Result result = resolve(template, PARAMETERS);

        assertEquals(List.of("main"), result.containerNames());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("Secret")),
                result.notes().toString());
        String delivered = String.join("\n", result.deliverableLines().get(TEMPLATE_FILE));
        assertFalse(delivered.contains("fixture-secret-canary-0123456789"), delivered);
        assertTrue(delivered.contains("[nicht uebernommen: kind Secret wird nicht uebernommen]"), delivered);
        assertTrue(delivered.contains("image: registry.example.invalid/app:1.0.0"), delivered);
        assertEquals(FileReadResult.FileStatus.READ, result.statusOf(TEMPLATE_FILE));

        // Reine Secret-Datei: kein Workload-Dokument.
        Map<String, String> templates = new LinkedHashMap<>();
        templates.put("templates/secret.yaml", """
                apiVersion: v1
                kind: Secret
                stringData:
                  token: fixture-secret-canary-0123456789
                """);
        TemplateParameterResolver.Result onlySecret = resolver.resolve(templates, Map.of(), ALLOWED);
        assertEquals(FileReadResult.FileStatus.UNSUPPORTED_TYPE, onlySecret.statusOf("templates/secret.yaml"));
        assertTrue(onlySecret.containerNames().isEmpty());
    }

    @Test
    void multipleDocumentsAndAmbiguityAreDetected() {
        String ambiguous = """
                kind: Deployment
                metadata:
                  name: one
                spec:
                  template:
                    spec:
                      containers:
                        - name: main
                          image: registry.example.invalid/app:1.0.0
                ---
                kind: Deployment
                metadata:
                  name: two
                spec:
                  template:
                    spec:
                      containers:
                        - name: main
                          image: registry.example.invalid/app:2.0.0
                """;
        TemplateParameterResolver.Result result = resolve(ambiguous, PARAMETERS);
        assertEquals(FileReadResult.FileStatus.AMBIGUOUS, result.statusOf(TEMPLATE_FILE));
        assertTrue(result.containerNames().isEmpty(), "mehrdeutige Dokumente liefern keine Container");
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("mehrdeutig")), result.notes().toString());

        String withOtherKinds = """
                kind: ConfigMap
                metadata:
                  name: fixture-config
                data:
                  log.level: info
                ---
                kind: DeploymentConfig
                metadata:
                  name: fixture-dc
                spec:
                  template:
                    spec:
                      containers:
                        - name: main
                          image: registry.example.invalid/app:1.0.0
                """;
        TemplateParameterResolver.Result mixed = resolve(withOtherKinds, PARAMETERS);
        assertEquals(List.of("main"), mixed.containerNames());
        assertEquals(FileReadResult.FileStatus.READ, mixed.statusOf(TEMPLATE_FILE));
        assertTrue(mixed.notes().stream().anyMatch(note -> note.contains("ConfigMap")), mixed.notes().toString());
        String delivered = String.join("\n", mixed.deliverableLines().get(TEMPLATE_FILE));
        assertTrue(delivered.contains("[nicht uebernommen: data]"), delivered);
        assertFalse(delivered.contains("log.level: info"), delivered);
    }

    @Test
    void parseErrorsAreReportedWithoutDeliveringContent() {
        TemplateParameterResolver.Result result = resolve("kind: Deployment\nspec: [1, 2\n", PARAMETERS);
        assertEquals(FileReadResult.FileStatus.PARSE_ERROR, result.statusOf(TEMPLATE_FILE));
        assertTrue(result.containerNames().isEmpty());
        String delivered = String.join("\n", result.deliverableLines().get(TEMPLATE_FILE));
        assertTrue(delivered.contains("YAML nicht lesbar"), delivered);
    }

    @Test
    void parameterFileLinesAreFilteredButKeepLineNumbers() {
        TemplateParameterResolver.Result result = resolve(DEPLOYMENT, PARAMETERS);
        List<String> delivered = result.deliverableLines().get(PARAMETER_FILE);
        assertNotNull(delivered);
        assertEquals(7, delivered.size());
        assertEquals("# Synthetische Parameter; keine echten Zugangsdaten.", delivered.get(0));
        assertTrue(delivered.get(1).startsWith("IMAGE_REF=registry.example.invalid/fixture-app:1.0.0"));
        assertTrue(delivered.get(4).startsWith("export PROGRESS_DEADLINE=600"));
        assertTrue(delivered.get(5).contains("nicht erlaubter Parameter NOT_ALLOWED_PARAM"), delivered.get(5));
        assertTrue(delivered.get(6).contains("nicht erlaubter Parameter DB_PASSWORD"), delivered.get(6));
        assertFalse(String.join("\n", delivered).contains("fixture-not-allowed-canary-0123456789"));
        assertFalse(String.join("\n", delivered).contains("fixture-env-canary-0123456789"));
    }

    @Test
    void parameterAssignmentFormsAndCommentsAreHandled() {
        Map<String, String> templates = new LinkedHashMap<>();
        templates.put(TEMPLATE_FILE, """
                kind: Deployment
                spec:
                  template:
                    spec:
                      containers:
                        - name: main
                          image: ${IMAGE_REF}
                          resources:
                            limits:
                              cpu: ${HEALTH_PATH}
                              memory: ${MIN_READY_SECONDS}
                          livenessProbe:
                            initialDelaySeconds: ${PROGRESS_DEADLINE}
                """);
        Map<String, String> files = new LinkedHashMap<>();
        files.put(PARAMETER_FILE, """
                # Kommentar
                  # eingerueckter Kommentar
                IMAGE_REF = registry.example.invalid/app:1.0.0
                HEALTH_PATH='/healthz'
                export MIN_READY_SECONDS=5
                PROGRESS_DEADLINE=600 # nachgestellter Kommentar
                """);
        TemplateParameterResolver.Result result = resolver.resolve(templates, files, ALLOWED);
        assertEquals("registry.example.invalid/app:1.0.0",
                result.values().get("/spec/template/spec/containers/0/image").value());
        assertEquals("/healthz", result.values().get("/spec/template/spec/containers/0/resources/limits/cpu").value());
        assertEquals("5", result.values().get("/spec/template/spec/containers/0/resources/limits/memory").value());
        ConfigValue deadline = result.values()
                .get("/spec/template/spec/containers/0/livenessProbe/initialDelaySeconds");
        assertEquals("600", deadline.value());
        assertEquals(6, deadline.parameterLine(), "Herkunft der Zuweisung in der Parameterdatei");
        assertEquals(13, deadline.templateLine(), "Herkunft im Template");
        assertEquals(600, result.containers().get("main").probes().get("liveness").initialDelaySeconds());
    }

    @Test
    void missingProbesAreNotInvented() {
        String template = """
                kind: Deployment
                spec:
                  template:
                    spec:
                      containers:
                        - name: main
                          image: registry.example.invalid/app:1.0.0
                """;
        TemplateParameterResolver.Result result = resolve(template, PARAMETERS);
        ContainerConfig main = result.containers().get("main");
        assertNotNull(main);
        assertTrue(main.probes().isEmpty(), "fehlende Probes werden nicht erfunden");
        assertEquals(List.of(), main.probeKinds());
        assertEquals(List.of("main"), resolver.findContainerNames(Map.of(TEMPLATE_FILE, template), Map.of(), ALLOWED));
    }

    @Test
    void containersWithoutNameAreSkippedWithNote() {
        String template = """
                kind: Deployment
                spec:
                  template:
                    spec:
                      containers:
                        - image: registry.example.invalid/app:1.0.0
                        - name: main
                          image: registry.example.invalid/app:2.0.0
                """;
        TemplateParameterResolver.Result result = resolve(template, PARAMETERS);
        assertEquals(List.of("main"), result.containerNames());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("ohne Namen")), result.notes().toString());
    }

    @Test
    void unsupportedProbeFieldsAreNotSilentlyDropped() {
        String template = """
                kind: Deployment
                spec:
                  template:
                    spec:
                      containers:
                        - name: main
                          image: registry.example.invalid/app:1.0.0
                          livenessProbe:
                            httpGet:
                              path: /healthz
                              port: 8080
                            initialDelaySeconds: ${HEALTH_PATH}
                """;
        TemplateParameterResolver.Result result = resolve(template, PARAMETERS);
        ProbeConfig probe = result.containers().get("main").probes().get("liveness");
        assertNull(probe.initialDelaySeconds(), "ein unbrauchbarer Zeitwert wird nicht uebernommen");
        assertEquals("8080", probe.httpPort());
        assertEquals("/healthz", probe.httpPath());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("keine Zahl")), result.notes().toString());
    }

    @Test
    void lineIndexHandlesNestedSequencesAndInitContainers() {
        String template = """
                kind: Deployment
                spec:
                  template:
                    spec:
                      initContainers:
                        - name: init
                          image: registry.example.invalid/init:1.0.0
                      containers:
                        - name: main
                          ports:
                            - name: http
                              containerPort: 8080
                          env:
                            - name: A
                              value: 1
                          envFrom:
                            - configMapRef:
                                name: fixture-config
                          livenessProbe:
                            exec:
                              command:
                                - /bin/true
                """;
        TemplateParameterResolver.Result result = resolve(template, PARAMETERS);
        assertEquals(List.of("main", "init"), result.containerNames());
        ConfigValue containerPort = result.values()
                .get("/spec/template/spec/containers/0/ports/0/containerPort");
        assertNotNull(containerPort);
        assertEquals(12, containerPort.templateLine());
        assertNotNull(result.values().get("/spec/template/spec/initContainers/0/image"));
        assertNull(result.values().get("/spec/template/spec/containers/0/env/0/value"),
                "env-Werte werden bewusst nicht aufgeloest");
        String delivered = String.join("\n", result.deliverableLines().get(TEMPLATE_FILE));
        assertFalse(delivered.contains("value: 1"), delivered);
        assertFalse(delivered.contains("name: fixture-config"), delivered);
        assertTrue(delivered.contains("[nicht uebernommen: envFrom]"), delivered);
        assertTrue(delivered.contains("livenessProbe:"), delivered);
    }
}
