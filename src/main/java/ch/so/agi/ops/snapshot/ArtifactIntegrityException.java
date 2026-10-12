package ch.so.agi.ops.snapshot;

import ch.so.agi.ops.domain.ErrorCode;

/**
 * Ein lokales Artefakt (Manifest, Evidence oder Bericht) ist beschaedigt, unvollstaendig oder
 * wurde nachtraeglich veraendert. Hashintegritaet ist kein Vertrauens- oder Kausalitaetsbeweis,
 * aber ein erkennbarer Hinweis auf Manipulation oder Abbruch.
 */
public class ArtifactIntegrityException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;

    public ArtifactIntegrityException(String message) {
        this(ErrorCode.ARTIFACT_INTEGRITY, message);
    }

    public ArtifactIntegrityException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
