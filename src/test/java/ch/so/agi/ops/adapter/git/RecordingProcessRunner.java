package ch.so.agi.ops.adapter.git;

import ch.so.agi.ops.util.DefaultProcessRunner;
import ch.so.agi.ops.util.ExecResult;
import ch.so.agi.ops.util.ProcessRunner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fuehrt die Git-Aufrufe wirklich aus und zeichnet sie gleichzeitig auf.
 *
 * <p>Die Basisumgebung ist absichtlich gesetzt, damit die Tests belegen, dass die
 * Einschraenkungen des Adapters auch bei einer vorbereiteten Elternumgebung greifen.</p>
 */
final class RecordingProcessRunner implements ProcessRunner {

    private final ProcessRunner delegate;
    private final List<FakeProcessRunner.Call> calls = new ArrayList<>();

    RecordingProcessRunner(Map<String, String> baseEnvironment) {
        this.delegate = new DefaultProcessRunner(baseEnvironment);
    }

    /** Basisumgebung mit praeparierten Pager-/Diff-/Home-Variablen. */
    static RecordingProcessRunner withHostileEnvironment(Path hostileDir) {
        Map<String, String> environment = new LinkedHashMap<>();
        String path = System.getenv("PATH");
        environment.put("PATH", path == null || path.isBlank() ? "/usr/bin:/bin" : path);
        environment.put("HOME", hostileDir.toString());
        environment.put("GIT_PAGER", hostileDir.resolve("hostile-pager.sh").toString());
        environment.put("GIT_EXTERNAL_DIFF", hostileDir.resolve("hostile-diff.sh").toString());
        return new RecordingProcessRunner(environment);
    }

    /** Basisumgebung wie im Produktivpfad. */
    static RecordingProcessRunner restricted() {
        return new RecordingProcessRunner(GitProcess.restrictedBaseEnvironment());
    }

    @Override
    public ExecResult exec(String executable, List<String> arguments, Path workingDir,
            Map<String, String> extraEnv, Duration timeout, long maxOutputBytes) {
        calls.add(new FakeProcessRunner.Call(executable, List.copyOf(arguments), workingDir,
                extraEnv == null ? Map.of() : new LinkedHashMap<>(extraEnv), timeout, maxOutputBytes));
        return delegate.exec(executable, arguments, workingDir, extraEnv, timeout, maxOutputBytes);
    }

    List<FakeProcessRunner.Call> calls() {
        return List.copyOf(calls);
    }

    List<String> subcommands() {
        return calls.stream().map(FakeProcessRunner.Call::subcommand).toList();
    }

    List<List<String>> gitArgumentLists() {
        return calls.stream().map(FakeProcessRunner.Call::gitArguments).toList();
    }

    /** Zaehlt Aufrufe, deren Argumentliste einen Text enthaelt. */
    long countContaining(String text) {
        return calls.stream().filter(call -> call.joined().contains(text)).count();
    }
}
