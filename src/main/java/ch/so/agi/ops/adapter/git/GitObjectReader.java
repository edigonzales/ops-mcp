package ch.so.agi.ops.adapter.git;

import ch.so.agi.ops.domain.ErrorCode;
import ch.so.agi.ops.security.Redactor;
import ch.so.agi.ops.util.ExecResult;

import java.util.List;
import java.util.Objects;

/**
 * Liest einzelne Objekte aus einem lokalen Git-Checkout. Ausschliesslich lesend, ohne
 * Worktree-Aenderung, ohne Checkout, ohne Netzwerkzugriff.
 *
 * <p>Alle Rueckgaben sind redigiert: Git-Ausgaben koennen URLs mit signierten Parametern
 * enthalten, und ein Fehlertext darf keine Zugangsdaten in die Toolausgabe tragen.</p>
 *
 * <p>Der Aufruf erfolgt an genau definierten Argumentlisten:</p>
 * <ul>
 *   <li>{@code git cat-file -e <commit>^{commit}} – Existenzpruefung</li>
 *   <li>{@code git show <commit>:<path>} – Dateiinhalt am Commit</li>
 *   <li>{@code git ls-tree --name-only <commit> -- <path>} – Pfadexistenz</li>
 *   <li>{@code git ls-tree <commit> -- <path>} – Moduspruefung (Symlink {@code 120000})</li>
 *   <li>{@code git rev-parse --verify HEAD^{commit}} – nur Diagnose ({@link #resolveHead()})</li>
 *   <li>{@code git rev-parse --show-toplevel} – Arbeitsbaumwurzel ({@link #currentWorktreePath()})</li>
 * </ul>
 *
 * <p>{@link #resolveHead()} ist ausdruecklich <em>kein</em> Ersatz fuer eine fehlende Revision.
 * Fehlt der belegte Commit, meldet der Aufrufer {@code REVISION_UNAVAILABLE}.</p>
 */
public final class GitObjectReader {

    private static final String SYMLINK_MODE = "120000";

    private final GitProcess git;

    public GitObjectReader(GitProcess git) {
        this.git = Objects.requireNonNull(git, "git");
    }

    public GitProcess process() {
        return git;
    }

    /**
     * Prueft, ob der Commit lokal vorhanden und als Commit aufloesbar ist.
     *
     * @return {@code false}, wenn das Objekt fehlt; technische Fehler werfen eine
     *         {@link GitReadException}
     */
    public boolean commitExists(String commit) {
        GitProcess.requireCommit(commit);
        ExecResult result = git.run(List.of("cat-file", "-e", commit + "^{commit}"));
        requireTechnicalSuccess(result, "Commit " + shortOf(commit) + " konnte nicht geprueft werden.");
        return result.exitCode() == 0;
    }

    /**
     * Liest den Inhalt einer Datei an einem Commit. Der Inhalt ist redigiert.
     *
     * @throws GitReadException {@code NOT_FOUND}, wenn die Datei am Commit nicht existiert
     */
    public String readFileAtCommit(String commit, String relativePath) {
        GitProcess.requireCommit(commit);
        GitProcess.requireRelativePath(relativePath);
        ExecResult result = git.run(List.of("show", commit + ":" + relativePath));
        requireTechnicalSuccess(result, "Datei konnte nicht gelesen werden: " + relativePath);
        if (result.exitCode() != 0) {
            throw new GitReadException(ErrorCode.NOT_FOUND,
                    "Datei ist am Commit " + shortOf(commit) + " nicht vorhanden: " + relativePath,
                    "Erlaubte Dateiliste und belegte Revision pruefen.");
        }
        if (result.outputTruncated()) {
            throw new GitReadException(ErrorCode.DATA_TRUNCATED,
                    "Die Datei ueberschreitet das Ausgabebudget und wurde abgeschnitten: " + relativePath,
                    "Dateigroesse oder Ausgabebudget pruefen.");
        }
        return Redactor.redact(result.stdout()).text();
    }

    /** Prueft, ob der Pfad am Commit existiert (Datei, Symlink oder Verzeichniseintrag). */
    public boolean pathExistsAtCommit(String commit, String relativePath) {
        GitProcess.requireCommit(commit);
        GitProcess.requireRelativePath(relativePath);
        ExecResult result = git.run(List.of("ls-tree", "--name-only", commit, "--", relativePath));
        requireTechnicalSuccess(result, "Pfad konnte nicht geprueft werden: " + relativePath);
        if (result.exitCode() != 0) {
            return false;
        }
        return !result.stdout().isBlank();
    }

    /** Prueft ueber den Baumodus, ob der Pfad am Commit ein symbolischer Link ist. */
    public boolean isSymbolicLinkAtCommit(String commit, String relativePath) {
        GitProcess.requireCommit(commit);
        GitProcess.requireRelativePath(relativePath);
        ExecResult result = git.run(List.of("ls-tree", commit, "--", relativePath));
        requireTechnicalSuccess(result, "Baumodus konnte nicht geprueft werden: " + relativePath);
        if (result.exitCode() != 0 || result.stdout().isBlank()) {
            return false;
        }
        for (String line : result.stdout().split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int space = trimmed.indexOf(' ');
            if (space > 0 && SYMLINK_MODE.equals(trimmed.substring(0, space))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Loest {@code HEAD} auf. Ausschliesslich fuer Diagnoseausgaben (z. B. {@code doctor});
     * niemals als Ersatz fuer eine fehlende belegte Revision.
     *
     * @return vollstaendiger Commit oder {@code null}, wenn nicht aufloesbar
     */
    public String resolveHead() {
        ExecResult result = git.run(List.of("rev-parse", "--verify", "HEAD^{commit}"));
        if (result.timedOut() || result.exitCode() != 0) {
            return null;
        }
        String value = Redactor.redact(result.stdout().strip()).text();
        return value.isEmpty() ? null : value;
    }

    /** Arbeitsbaumwurzel des Checkouts; nur fuer Diagnose und Pfadgrenzenpruefung. */
    public String currentWorktreePath() {
        ExecResult result = git.run(List.of("rev-parse", "--show-toplevel"));
        if (result.timedOut() || result.exitCode() != 0) {
            return null;
        }
        String value = Redactor.redact(result.stdout().strip()).text();
        return value.isEmpty() ? null : value;
    }

    /**
     * Technische Fehler (Timeout, nicht startbarer Prozess) werden als Ausnahme gemeldet;
     * ein fachlicher Exitcode bleibt dem Aufrufer erhalten.
     */
    private static void requireTechnicalSuccess(ExecResult result, String message) {
        if (result.timedOut()) {
            throw new GitReadException(ErrorCode.REQUEST_TIMEOUT, message + " Zeitueberschreitung.",
                    "Laufzeit des lokalen Git-Aufrufs oder das Erfassungsbudget pruefen.");
        }
        if (result.exitCode() == -1) {
            throw new GitReadException(ErrorCode.CONFIG_INVALID,
                    message + " Der Git-Prozess konnte nicht gestartet werden.",
                    "Installation von git im PATH pruefen.");
        }
        if (result.outputTruncated()) {
            throw new GitReadException(ErrorCode.DATA_TRUNCATED, message + " Ausgabe abgeschnitten.",
                    "Ausgabebudget oder Dateigroesse pruefen.");
        }
    }

    private static String shortOf(String commit) {
        return commit.length() > 8 ? commit.substring(0, 8) : commit;
    }

    /** Redigierte, gekuerzte stderr-Ausgabe fuer Notizen. */
    static String describeFailure(ExecResult result) {
        if (result == null) {
            return "";
        }
        return GitProcess.redactedSnippet(result.stderr());
    }
}
