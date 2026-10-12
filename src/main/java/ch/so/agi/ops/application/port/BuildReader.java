package ch.so.agi.ops.application.port;

import ch.so.agi.ops.domain.CollectionBudget;
import ch.so.agi.ops.domain.SourceResult;
import ch.so.agi.ops.domain.jenkins.BuildSelection;
import ch.so.agi.ops.domain.jenkins.BuildSelector;
import ch.so.agi.ops.domain.jenkins.BuildSnapshot;
import ch.so.agi.ops.domain.jenkins.ConsoleLogEvidence;

/**
 * Lesender Zugriff auf eine Jenkins-Instanz. Nur feste GET-Endpunkte, begrenzte
 * {@code tree}-Abfragen und optional sichere HEAD-Abfragen. Keine {@code config.xml},
 * keine Script Console, keine Pipelineausfuehrung, keine Workspace-/Credential-Downloads.
 */
public interface BuildReader {

    /**
     * Loest die Buildauswahl in eine konkrete Nummer auf. {@code lastFailed} darf nicht mit
     * {@code lastUnsuccessful} gleichgesetzt werden; ein laufender oder abgebrochener Build
     * ist nicht automatisch ein Deploymentfehler.
     */
    SourceResult<BuildSelection> resolveBuild(ResolvedTarget target, BuildSelector selector);

    /** Erfasst Buildmetadaten und einen begrenzten Console-Logausschnitt. */
    SourceResult<BuildSnapshot> collectMetadata(ResolvedTarget target, CollectionBudget budget);

    /** Erfasst den Console-Logausschnitt, grosse Logs streaming- und budgetbegrenzt. */
    SourceResult<ConsoleLogEvidence> collectConsoleLog(ResolvedTarget target, CollectionBudget budget);

    /** Kennung der Jenkins-Instanz, z. B. {@code apps-deploy}. */
    String sourceId();
}
