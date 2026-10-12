package ch.so.agi.ops.domain.jenkins;

/** Art eines Jenkins-Repository-Checkouts im Build. */
public record RepositoryDescriptor(String repositoryUrl, String branch, String commit) {

    public String repositoryIdHint() {
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            return "";
        }
        String cleaned = repositoryUrl.trim();
        if (cleaned.endsWith(".git")) {
            cleaned = cleaned.substring(0, cleaned.length() - 4);
        }
        if (cleaned.startsWith("git@")) {
            int colon = cleaned.indexOf(':');
            return colon >= 0 ? cleaned.substring(colon + 1) : cleaned;
        }
        int scheme = cleaned.indexOf("://");
        String withoutScheme = scheme >= 0 ? cleaned.substring(scheme + 3) : cleaned;
        int slash = withoutScheme.indexOf('/');
        if (slash < 0) {
            return "";
        }
        String hostAndPath = withoutScheme.substring(slash + 1);
        // Nur der Repositorypfad ohne Host, ohne Zugangsdaten und ohne Port.
        int at = hostAndPath.indexOf('@');
        if (at >= 0) {
            hostAndPath = hostAndPath.substring(at + 1);
        }
        return hostAndPath;
    }
}
