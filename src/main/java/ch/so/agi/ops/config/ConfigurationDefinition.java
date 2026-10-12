package ch.so.agi.ops.config;

import java.util.List;

/**
 * Konfigurationszuordnung einer Umgebung.
 *
 * @param repository            Repositorykennung
 * @param templateFiles         Template-/Manifestdateien mit erlaubten Pfaden
 * @param parameterFiles        Parameterdateien ({@code .env}-aehnlich), nie ausgefuehrt
 * @param allowedParameterNames explizite Liste erlaubter Parameternamen
 * @param revision              Herkunft der Konfigurationsrevision
 */
public record ConfigurationDefinition(
        String repository,
        List<String> templateFiles,
        List<String> parameterFiles,
        List<String> allowedParameterNames,
        RevisionDefinition revision) {

    public ConfigurationDefinition {
        templateFiles = templateFiles == null ? List.of() : List.copyOf(templateFiles);
        parameterFiles = parameterFiles == null ? List.of() : List.copyOf(parameterFiles);
        allowedParameterNames = allowedParameterNames == null ? List.of() : List.copyOf(allowedParameterNames);
    }

    public List<String> missingFields() {
        List<String> missing = new java.util.ArrayList<>();
        if (repository == null || repository.isBlank()) {
            missing.add("configuration.repository");
        }
        if (templateFiles.isEmpty() && parameterFiles.isEmpty()) {
            missing.add("configuration.templateFiles");
        }
        if (revision == null) {
            missing.add("configuration.revision");
        } else {
            missing.addAll(revision.missingFields());
        }
        return missing;
    }

    public List<String> allConfiguredFiles() {
        List<String> all = new java.util.ArrayList<>(templateFiles);
        all.addAll(parameterFiles);
        return List.copyOf(all);
    }

    /**
     * Herkunft der Konfigurationsrevision.
     *
     * @param source       {@code jenkins-scm} oder {@code operator-supplied}
     * @param repositoryId Repositorykennung, die zugeordnet werden muss
     * @param commit       Commit bei {@code operator-supplied}
     * @param forBuild     Buildnummer bei {@code operator-supplied}; muss exakt passen
     */
    public record RevisionDefinition(Source source, String repositoryId, String commit, Integer forBuild) {

        public enum Source {
            JENKINS_SCM("jenkins-scm"),
            OPERATOR_SUPPLIED("operator-supplied");

            private final String wireName;

            Source(String wireName) {
                this.wireName = wireName;
            }

            public String wireName() {
                return wireName;
            }

            public static Source fromWireName(String value) {
                for (Source source : values()) {
                    if (source.wireName.equals(value)) {
                        return source;
                    }
                }
                throw new IllegalArgumentException("Unbekannte Revisionsquelle: " + value);
            }
        }

        public List<String> missingFields() {
            List<String> missing = new java.util.ArrayList<>();
            if (source == null) {
                missing.add("configuration.revision.source");
                return missing;
            }
            if (repositoryId == null || repositoryId.isBlank()) {
                missing.add("configuration.revision.repositoryId");
            }
            if (source == Source.OPERATOR_SUPPLIED) {
                if (commit == null || commit.isBlank()) {
                    missing.add("configuration.revision.commit");
                }
                if (forBuild == null) {
                    missing.add("configuration.revision.forBuild");
                }
            }
            return missing;
        }
    }
}
