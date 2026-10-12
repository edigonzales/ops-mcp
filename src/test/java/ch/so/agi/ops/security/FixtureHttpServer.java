package ch.so.agi.ops.security;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minimaler lokaler HTTP-Testserver auf {@code 127.0.0.1} mit einem festen Rumpf.
 *
 * <p>Er kann {@code HEAD}, ganze {@code GET}-Antworten, {@code Range}-Antworten (mit und ohne
 * Unterstuetzung) und eine einzelne Weiterleitung beantworten. Alle eingegangenen Anfragen
 * werden aufgezeichnet, damit Tests belegen koennen, dass ein fremder Host gar nicht erst
 * kontaktiert und kein {@code Authorization}-Header dorthin gesendet wurde.</p>
 *
 * <p>Es wird ausschliesslich die Loopbackadresse verwendet; es findet keine Namensaufloesung
 * und kein Zugriff auf ein Firmensystem statt.</p>
 */
final class FixtureHttpServer implements AutoCloseable {

    /** Aufgezeichnete Anfrage ohne Rumpf; Credentials werden nur auf Vorhandensein geprueft. */
    record Request(String method, String path, String range, boolean authorizationHeaderPresent) {
    }

    private final HttpServer server;
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private final byte[] body;
    private final String redirectLocation;
    private final boolean rangesSupported;

    private FixtureHttpServer(byte[] body, String redirectLocation, boolean rangesSupported) {
        this.body = body == null ? new byte[0] : body.clone();
        this.redirectLocation = redirectLocation;
        this.rangesSupported = rangesSupported;
        try {
            this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Server mit festem Rumpf; unterstuetzt {@code HEAD}, {@code GET} und {@code Range}. */
    static FixtureHttpServer serving(byte[] body) {
        return start(new FixtureHttpServer(body, null, true));
    }

    /** Server mit festem Rumpf, der {@code Range}-Anfragen ignoriert (liefert immer HTTP 200). */
    static FixtureHttpServer ignoringRanges(byte[] body) {
        return start(new FixtureHttpServer(body, null, false));
    }

    /** Server mit festem Rumpf und einer Weiterleitung auf ein anderes Ziel. */
    static FixtureHttpServer redirectingTo(String location) {
        return start(new FixtureHttpServer(("weiterleitung").getBytes(StandardCharsets.UTF_8), location, true));
    }

    private static FixtureHttpServer start(FixtureHttpServer fixture) {
        fixture.server.createContext("/", fixture::handle);
        fixture.server.start();
        return fixture;
    }

    int port() {
        return server.getAddress().getPort();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    List<Request> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    int requestCount() {
        return requests().size();
    }

    boolean anyAuthorizationHeaderReceived() {
        return requests().stream().anyMatch(Request::authorizationHeaderPresent);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String range = exchange.getRequestHeaders().getFirst("Range");
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(), range,
                authorization != null && !authorization.isBlank()));
        try {
            if (redirectLocation != null) {
                exchange.getResponseHeaders().add("Location", redirectLocation);
                exchange.sendResponseHeaders(302, -1);
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Content-Length", Integer.toString(body.length));
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            long[] slice = sliceFor(range);
            if (slice == null) {
                exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                }
                return;
            }
            int from = (int) slice[0];
            int to = (int) slice[1];
            int length = to - from + 1;
            exchange.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + to + "/" + body.length);
            exchange.sendResponseHeaders(206, length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body, from, length);
            }
        } finally {
            exchange.close();
        }
    }

    /** Liefert {@code {from, to}} fuer eine Range-Anfrage oder {@code null} fuer eine Vollantwort. */
    private long[] sliceFor(String range) {
        if (range == null || !rangesSupported) {
            return null;
        }
        String value = range.trim();
        if (!value.startsWith("bytes=")) {
            return null;
        }
        String spec = value.substring("bytes=".length()).split(",", -1)[0].trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String fromText = spec.substring(0, dash).trim();
        String toText = spec.substring(dash + 1).trim();
        long from;
        try {
            from = fromText.isEmpty() ? 0 : Long.parseLong(fromText);
        } catch (NumberFormatException ex) {
            return null;
        }
        long to = body.length - 1L;
        if (!toText.isEmpty()) {
            try {
                to = Math.min(to, Long.parseLong(toText));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        if (from < 0 || from > to) {
            return null;
        }
        return new long[] {from, to};
    }
}
