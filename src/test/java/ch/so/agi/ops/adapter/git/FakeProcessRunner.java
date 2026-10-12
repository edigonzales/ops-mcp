package ch.so.agi.ops.adapter.git;

import ch.so.agi.ops.util.ExecResult;
import ch.so.agi.ops.util.ProcessRunner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Fake-Prozessrunner fuer die Adaptertests: zeichnet jeden Aufruf vollstaendig auf
 * (Programm, Argumente, Arbeitsverzeichnis, Umgebung, Timeout, Budget) und liefert
 * vorgegebene Antworten. Nicht beantwortete Aufrufe enden mit Exitcode 127, damit ein
 * unerwarteter Git-Aufruf im Test sichtbar wird.
 */
final class FakeProcessRunner implements ProcessRunner {

    /** Vollstaendig aufgezeichneter Aufruf. */
    record Call(String executable, List<String> arguments, Path workingDir, Map<String, String> environment,
            Duration timeout, long maxOutputBytes) {

        String joined() {
            return String.join(" ", arguments);
        }

        /** Argumente ohne die feste Haertung ({@code -C}, {@code -c core.hooksPath=...}, ...). */
        List<String> gitArguments() {
            List<String> base = baseArguments();
            if (arguments.size() <= base.size()) {
                return List.of();
            }
            return List.copyOf(arguments.subList(base.size(), arguments.size()));
        }

        private List<String> baseArguments() {
            return arguments.size() >= 8 && "-C".equals(arguments.get(0))
                    ? List.copyOf(arguments.subList(0, 8))
                    : List.of();
        }

        String subcommand() {
            List<String> gitArguments = gitArguments();
            return gitArguments.isEmpty() ? "" : gitArguments.get(0);
        }
    }

    private final List<Call> calls = new ArrayList<>();
    private final List<Function<Call, ExecResult>> responders = new ArrayList<>();

    @Override
    public ExecResult exec(String executable, List<String> arguments, Path workingDir,
            Map<String, String> extraEnv, Duration timeout, long maxOutputBytes) {
        Call call = new Call(executable, List.copyOf(arguments), workingDir,
                extraEnv == null ? Map.of() : new LinkedHashMap<>(extraEnv), timeout, maxOutputBytes);
        calls.add(call);
        for (Function<Call, ExecResult> responder : responders) {
            ExecResult result = responder.apply(call);
            if (result != null) {
                return result;
            }
        }
        return new ExecResult(127, "", "FakeProcessRunner: unerwarteter Aufruf: " + call.joined(), false, false, 0,
                call.arguments());
    }

    /** Antwort fuer einen Git-Befehl; der erste passende Responder gewinnt. */
    void respond(String subcommand, String contains, int exitCode, String stdout) {
        respond(subcommand, contains, exitCode, stdout, "");
    }

    void respond(String subcommand, String contains, int exitCode, String stdout, String stderr) {
        responders.add(call -> {
            if (!subcommand.equals(call.subcommand())) {
                return null;
            }
            if (contains != null && !call.joined().contains(contains)) {
                return null;
            }
            return new ExecResult(exitCode, stdout, stderr, false, false, 1, call.arguments());
        });
    }

    /** Antwort auf einen vorbereiteten {@link ExecResult}, z. B. mit {@code timedOut}. */
    void respondWith(String subcommand, String contains, ExecResult result) {
        responders.add(call -> {
            if (!subcommand.equals(call.subcommand())) {
                return null;
            }
            if (contains != null && !call.joined().contains(contains)) {
                return null;
            }
            return result;
        });
    }

    List<Call> calls() {
        return List.copyOf(calls);
    }

    List<String> subcommands() {
        return calls.stream().map(Call::subcommand).toList();
    }

    /** Alle Git-Argumentlisten ohne die feste Haertung. */
    List<List<String>> gitArgumentLists() {
        return calls.stream().map(Call::gitArguments).toList();
    }

    Call lastCall() {
        return calls.isEmpty() ? null : calls.get(calls.size() - 1);
    }
}
