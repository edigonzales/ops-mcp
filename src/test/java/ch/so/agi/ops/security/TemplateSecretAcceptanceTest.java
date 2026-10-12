package ch.so.agi.ops.security;

import ch.so.agi.ops.adapter.git.TemplateParameterResolver;
import ch.so.agi.ops.domain.git.ContainerConfig;
import ch.so.agi.ops.domain.git.FileReadResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S07 – Kubernetes-{@code Secret}-Dokument zwischen YAML-Templates.
 *
 * <p>Belegt: Ein {@code kind: Secret}-Dokument wird nicht ausgewertet; seine Werte erscheinen
 * weder in der aufgeloesten Konfiguration noch in den an das Modell ausgelieferten Zeilen.
 * Das danebenstehende Deployment desselben Templates bleibt auswertbar, damit die Maskierung
 * nicht pauschal die ganze Datei verwirft.</p>
 */
class TemplateSecretAcceptanceTest {

    private final TemplateParameterResolver resolver = new TemplateParameterResolver();

    private static final String SECRET_WERT = AcceptanceSupport.CANARY;

    private static final String TEMPLATE = """
            apiVersion: v1
            kind: Secret
            metadata:
              name: fixture-secret
            type: Opaque
            data:
              db-password: %s
            stringData:
              api-token: %s
            ---
            apiVersion: apps/v1
            kind: Deployment
            metadata:
              name: fixture-app
            spec:
              template:
                spec:
                  containers:
                    - name: app
                      image: registry.invalid/fixture/app:1.2.3
                      env:
                        - name: DB_PASSWORD
                          value: %s
                      livenessProbe:
                        httpGet:
                          path: /healthz
                          port: 8080
                        initialDelaySeconds: 10
                        failureThreshold: 3
            """.formatted(Base64.getEncoder().encodeToString(SECRET_WERT.getBytes(StandardCharsets.UTF_8)),
            SECRET_WERT, SECRET_WERT);

    @Test
    @DisplayName("S07: kind Secret wird nicht ausgewertet und der Secretwert erscheint nicht")
    void secretDokumentWirdNichtAusgewertet() {
        TemplateParameterResolver.Result result = resolver.resolve(Map.of("fixture.yaml", TEMPLATE), Map.of(),
                List.of());

        String ausgeliefert = String.join("\n", result.deliverableLines().get("fixture.yaml"));
        assertFalse(ausgeliefert.contains(SECRET_WERT), ausgeliefert);
        assertFalse(ausgeliefert.contains(Base64.getEncoder()
                .encodeToString(SECRET_WERT.getBytes(StandardCharsets.UTF_8))), ausgeliefert);
        assertTrue(ausgeliefert.contains("nicht uebernommen"), ausgeliefert);
        assertTrue(ausgeliefert.contains("kind Secret"), ausgeliefert);

        // Nichts im gesamten Ergebnis enthaelt den Secretwert.
        String gesamtesErgebnis = result.containers() + " " + result.values() + " "
                + result.rolloutTimeParameters() + " " + result.fileNotes() + " " + result.notes();
        assertFalse(gesamtesErgebnis.contains(SECRET_WERT), gesamtesErgebnis);
        assertFalse(gesamtesErgebnis.contains(Base64.getEncoder()
                .encodeToString(SECRET_WERT.getBytes(StandardCharsets.UTF_8))), gesamtesErgebnis);
        assertTrue(result.values().values().stream().noneMatch(wert -> wert.value() != null
                && wert.value().contains(SECRET_WERT)));
    }

    @Test
    @DisplayName("S07: das Deployment neben dem Secret bleibt auswertbar")
    void deploymentNebenDemSecretBleibtAuswertbar() {
        TemplateParameterResolver.Result result = resolver.resolve(Map.of("fixture.yaml", TEMPLATE), Map.of(),
                List.of());

        assertTrue(result.containerNames().contains("app"), result.containerNames().toString());
        ContainerConfig container = result.containers().get("app");
        assertNotNull(container, "Der Container muss aufgeloest werden.");
        assertTrue(container.image().contains("registry.invalid/fixture/app"), container.image());
        assertTrue(container.probes().containsKey("liveness"), container.probes().toString());
        assertTrue(result.statusOf("fixture.yaml") == FileReadResult.FileStatus.READ,
                "Die Datei bleibt lesbar: " + result.statusOf("fixture.yaml"));
    }

    @Test
    @DisplayName("S07: env-Werte im Deployment werden maskiert und nicht als Konfigurationswert uebernommen")
    void envWerteWerdenMaskiert() {
        TemplateParameterResolver.Result result = resolver.resolve(Map.of("fixture.yaml", TEMPLATE), Map.of(),
                List.of());

        String ausgeliefert = String.join("\n", result.deliverableLines().get("fixture.yaml"));
        assertFalse(ausgeliefert.contains(SECRET_WERT), ausgeliefert);
        long maskenzeilen = ausgeliefert.lines()
                .filter(zeile -> zeile.contains("nicht uebernommen")).count();
        assertTrue(maskenzeilen >= 9,
                "Alle Zeilen des Secret-Dokuments muessen maskiert sein; gefunden: " + maskenzeilen);
        assertTrue(result.values().values().stream().noneMatch(wert -> wert.value() != null
                && wert.value().contains(SECRET_WERT)));
    }
}
