package ch.so.agi.ops.adapter.openshift;

import ch.so.agi.ops.domain.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueft die lokale, rein lesende kubeconfig-Pruefung (S08/S09) ohne jeden oc-Aufruf.
 */
class KubeconfigGuardTest {

    @TempDir
    Path tempDir;

    private Path write(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void gueltigeTokenKubeconfigWirdAkzeptiert() throws IOException {
        Path kubeconfig = write("valid.yaml", OcFixtures.KUBECONFIG_VALID);

        KubeconfigGuard.View view = KubeconfigGuard.inspect(kubeconfig, OcFixtures.CONTEXT);

        assertEquals(OcFixtures.CONTEXT, view.configuredContext());
        assertEquals(OcFixtures.CONTEXT, view.currentContext());
        assertEquals(OcFixtures.SERVER, view.server());
        assertTrue(view.staticIdentity());
        assertTrue(KubeconfigGuard.sameServer(view.server(), OcFixtures.SERVER));
    }

    @Test
    void execPluginWirdAbgelehnt() throws IOException {
        Path kubeconfig = write("exec.yaml", OcFixtures.KUBECONFIG_EXEC_PLUGIN);

        OcParseException ex = assertThrows(OcParseException.class,
                () -> KubeconfigGuard.inspect(kubeconfig, OcFixtures.CONTEXT));

        assertEquals(ErrorCode.SCOPE_DENIED, ex.code());
        assertTrue(ex.getMessage().contains("exec"));
        assertFalse(ex.getMessage().contains("fixture-login-helper"), "Kein Inhalt in der Meldung.");
    }

    @Test
    void deaktivierteTlsPruefungWirdAbgelehnt() throws IOException {
        Path kubeconfig = write("insecure.yaml", OcFixtures.KUBECONFIG_INSECURE);

        OcParseException ex = assertThrows(OcParseException.class,
                () -> KubeconfigGuard.inspect(kubeconfig, OcFixtures.CONTEXT));

        assertEquals(ErrorCode.SCOPE_DENIED, ex.code());
        assertTrue(ex.getMessage().contains("insecure-skip-tls-verify"));
    }

    @Test
    void alsTextGeschriebeneTlsAbschaltungWirdEbenfallsAbgelehnt() throws IOException {
        Path kubeconfig = write("insecure-text.yaml", OcFixtures.KUBECONFIG_INSECURE.replace(
                "insecure-skip-tls-verify: true", "insecure-skip-tls-verify: \"true\""));

        OcParseException ex = assertThrows(OcParseException.class,
                () -> KubeconfigGuard.inspect(kubeconfig, OcFixtures.CONTEXT));

        assertEquals(ErrorCode.SCOPE_DENIED, ex.code());
    }

    @Test
    void eigenerProxyUrlWirdAbgelehnt() throws IOException {
        Path kubeconfig = write("proxy.yaml", OcFixtures.KUBECONFIG_PROXY_URL);

        OcParseException ex = assertThrows(OcParseException.class,
                () -> KubeconfigGuard.inspect(kubeconfig, OcFixtures.CONTEXT));

        assertEquals(ErrorCode.SCOPE_DENIED, ex.code());
        assertTrue(ex.getMessage().contains("proxy-url"));
        assertFalse(ex.getMessage().contains("proxy.example.invalid"), "Kein Ziel in der Meldung.");
    }

    @Test
    void fehlendeDateiErgibtConfigIncomplete() {
        OcParseException ex = assertThrows(OcParseException.class,
                () -> KubeconfigGuard.inspect(tempDir.resolve("fehlt.yaml"), OcFixtures.CONTEXT));

        assertEquals(ErrorCode.CONFIG_INCOMPLETE, ex.code());
        assertFalse(ex.getMessage().contains(tempDir.toString()), "Kein vollstaendiger Pfad in der Meldung.");
    }

    @Test
    void unbekannterContextErgibtConfigIncomplete() throws IOException {
        Path kubeconfig = write("context.yaml", OcFixtures.KUBECONFIG_UNKNOWN_CONTEXT);

        OcParseException ex = assertThrows(OcParseException.class,
                () -> KubeconfigGuard.inspect(kubeconfig, OcFixtures.CONTEXT));

        assertEquals(ErrorCode.CONFIG_INCOMPLETE, ex.code());
    }

    @Test
    void fehlendeStatischeIdentitaetErgibtConfigIncomplete() throws IOException {
        Path kubeconfig = write("identity.yaml", OcFixtures.KUBECONFIG_WITHOUT_IDENTITY);

        OcParseException ex = assertThrows(OcParseException.class,
                () -> KubeconfigGuard.inspect(kubeconfig, OcFixtures.CONTEXT));

        assertEquals(ErrorCode.CONFIG_INCOMPLETE, ex.code());
        assertFalse(ex.getMessage().contains(OcFixtures.KUBECONFIG_TOKEN));
    }

    @Test
    void keinGueltigesYamlErgibtConfigInvalid() throws IOException {
        Path kubeconfig = write("broken.yaml", "apiVersion: v1\nkind: Config\nclusters: [\n");

        OcParseException ex = assertThrows(OcParseException.class,
                () -> KubeconfigGuard.inspect(kubeconfig, OcFixtures.CONTEXT));

        assertEquals(ErrorCode.CONFIG_INVALID, ex.code());
        assertFalse(ex.getMessage().contains("clusters"), "Kein Dateiinhalt in der Meldung.");
    }

    @Test
    void sameServerVergleichtSchemaHostUndPort() {
        assertTrue(KubeconfigGuard.sameServer("https://api.ocp.so.ch:6443", "https://api.ocp.so.ch:6443"));
        assertTrue(KubeconfigGuard.sameServer("https://api.ocp.so.ch", "https://api.ocp.so.ch:443"));
        assertFalse(KubeconfigGuard.sameServer("https://api.ocp.so.ch:6443", "https://api.ocp.so.ch:8443"));
        assertFalse(KubeconfigGuard.sameServer("https://api.ocp.so.ch:6443", "http://api.ocp.so.ch:6443"));
        assertFalse(KubeconfigGuard.sameServer("https://api.ocp.so.ch:6443", "https://api.other.invalid:6443"));
        assertFalse(KubeconfigGuard.sameServer("https://api.ocp.so.ch:6443", "nicht-lesbar"));
    }

    @Test
    void serverbeschreibungEnthaeltKeineZugangsdaten() {
        assertEquals("https://api.ocp.so.ch:6443", KubeconfigGuard.describeServer("https://api.ocp.so.ch:6443"));
        assertEquals("https://api.ocp.so.ch:443", KubeconfigGuard.describeServer("https://api.ocp.so.ch"));
        assertFalse(KubeconfigGuard.describeServer(null).contains("token"));
    }
}
