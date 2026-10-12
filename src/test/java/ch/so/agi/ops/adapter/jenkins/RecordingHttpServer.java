package ch.so.agi.ops.adapter.jenkins;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lokaler HTTP-Testserver auf {@code 127.0.0.1} mit Port {@code 0}. Er zeichnet Methode, Pfad,
 * Query und ausgewaehlte Header (insbesondere {@code Authorization}, {@code Range}) auf und
 * erlaubt konfigurierbare Antworten: Status, Inhaltstyp, Koerper, Weiterleitungsziel, Range-
 * Verhalten, Verzoegerung.
 *
 * <p>Es wird ausschliesslich lokal getestet; es findet keine Namensaufloesung und kein Zugriff
 * auf echte Systeme statt.</p>
 */
final class RecordingHttpServer implements AutoCloseable {

    private static final List<String> RECORDED_HEADERS =
            List.of("Authorization", "Proxy-Authorization", "Range", "Accept", "User-Agent");

    /** Eine aufgezeichnete Anfrage. */
    record Recorded(String method, String rawPath, String rawQuery, Map<String, String> headers) {

        String query() {
            return rawQuery == null ? "" : rawQuery;
        }

        String decodedPath() {
            return URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        }

        String header(String name) {
            return headers.get(name);
        }

        String authorization() {
            return headers.get("Authorization");
        }

        String range() {
            return headers.get("Range");
        }
    }

    /** Konfigurierbare Antwort. */
    static final class Response {

        private int status = 200;
        private String contentType = "text/plain; charset=utf-8";
        private byte[] body = new byte[0];
        private String location;
        private boolean rangeSupport = true;
        private boolean headNotSupported;
        private boolean omitContentLength;
        private long delayMillis;

        static Response of(int status) {
            Response response = new Response();
            response.status = status;
            return response;
        }

        static Response json(String body) {
            return new Response().contentType("application/json; charset=utf-8").body(body);
        }

        static Response text(String body) {
            return new Response().body(body);
        }

        static Response html(String body) {
            return new Response().contentType("text/html; charset=utf-8").body(body);
        }

        static Response redirect(int status, String location) {
            return new Response().status(status).location(location);
        }

        Response status(int value) {
            this.status = value;
            return this;
        }

        Response contentType(String value) {
            this.contentType = value;
            return this;
        }

        Response body(String value) {
            this.body = value.getBytes(StandardCharsets.UTF_8);
            return this;
        }

        Response body(byte[] value) {
            this.body = value.clone();
            return this;
        }

        Response location(String value) {
            this.location = value;
            return this;
        }

        /** Der Server ignoriert {@code Range} und liefert immer den ganzen Koerper mit Status 200. */
        Response noRangeSupport() {
            this.rangeSupport = false;
            return this;
        }

        Response headNotSupported() {
            this.headNotSupported = true;
            return this;
        }

        /** Antwort ohne {@code Content-Length} (chunked), damit die Groesse unbekannt bleibt. */
        Response omitContentLength() {
            this.omitContentLength = true;
            return this;
        }

        Response delay(long millis) {
            this.delayMillis = millis;
            return this;
        }
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Response> exact = new ConcurrentHashMap<>();
    private final List<Map.Entry<String, Response>> prefixes = new CopyOnWriteArrayList<>();

    private RecordingHttpServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    static RecordingHttpServer start() throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "recording-http-server");
            thread.setDaemon(true);
            return thread;
        });
        httpServer.setExecutor(pool);
        RecordingHttpServer recording = new RecordingHttpServer(httpServer, pool);
        httpServer.createContext("/", recording::handle);
        httpServer.start();
        return recording;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int port() {
        return server.getAddress().getPort();
    }

    /** Antwort fuer einen exakten Pfad (ohne Query). */
    void respond(String rawPath, Response response) {
        exact.put(rawPath, response);
    }

    /** Antwort fuer alle Pfade mit diesem Praefix. */
    void respondPrefix(String rawPathPrefix, Response response) {
        prefixes.add(Map.entry(rawPathPrefix, response));
    }

    List<Recorded> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    int requestCount() {
        return requests.size();
    }

    List<Recorded> requestsWithQueryContaining(String fragment) {
        return requests().stream().filter(request -> request.query().contains(fragment)).toList();
    }

    List<Recorded> requestsForPath(String rawPath) {
        return requests().stream().filter(request -> request.rawPath().equals(rawPath)).toList();
    }

    private Response find(String rawPath) {
        Response direct = exact.get(rawPath);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, Response> entry : prefixes) {
            if (rawPath.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void handle(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        String rawPath = exchange.getRequestURI().getRawPath();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : RECORDED_HEADERS) {
            String value = exchange.getRequestHeaders().getFirst(name);
            if (value != null) {
                headers.put(name, value);
            }
        }
        requests.add(new Recorded(method, rawPath, rawQuery, Map.copyOf(headers)));

        Response response = find(rawPath);
        try {
            if (response == null) {
                byte[] body = ("kein Testpfad: " + rawPath).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(404, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                return;
            }
            if (response.delayMillis > 0) {
                try {
                    Thread.sleep(response.delayMillis);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (response.location != null) {
                exchange.getResponseHeaders().set("Location", response.location);
                exchange.sendResponseHeaders(response.status, -1);
                return;
            }
            if ("HEAD".equals(method)) {
                if (response.headNotSupported) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", response.contentType);
                exchange.getResponseHeaders().set("Content-Length", Integer.toString(response.body.length));
                exchange.sendResponseHeaders(response.status, -1);
                return;
            }
            byte[] body = response.body;
            String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
            if (rangeHeader != null && response.rangeSupport) {
                long[] bounds = parseRange(rangeHeader, body.length);
                if (bounds == null) {
                    exchange.sendResponseHeaders(416, -1);
                    return;
                }
                int from = (int) bounds[0];
                int to = (int) bounds[1];
                byte[] slice = Arrays.copyOfRange(body, from, to + 1);
                exchange.getResponseHeaders().set("Content-Type", response.contentType);
                exchange.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + to + "/" + body.length);
                exchange.sendResponseHeaders(206, slice.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(slice);
                }
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", response.contentType);
            if (response.omitContentLength) {
                exchange.sendResponseHeaders(response.status, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                return;
            }
            if (body.length == 0) {
                exchange.sendResponseHeaders(response.status, -1);
                return;
            }
            exchange.sendResponseHeaders(response.status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (IOException | RuntimeException ex) {
            // Der Client hat die Verbindung beendet (z. B. nach einem Timeout). Fuer den Test
            // ist das ein erwarteter Fall und kein Serverfehler.
        } finally {
            exchange.close();
        }
    }

    private static long[] parseRange(String header, int total) {
        if (!header.startsWith("bytes=") || total == 0) {
            return null;
        }
        String spec = header.substring("bytes=".length()).trim();
        if (spec.contains(",")) {
            return null;
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            long from = Long.parseLong(spec.substring(0, dash).trim());
            String toPart = spec.substring(dash + 1).trim();
            long to = toPart.isEmpty() ? total - 1 : Long.parseLong(toPart);
            if (from < 0 || from >= total) {
                return null;
            }
            to = Math.min(to, total - 1);
            if (to < from) {
                return null;
            }
            return new long[] {from, to};
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
