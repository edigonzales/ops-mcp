package ch.so.agi.ops.config;

import java.util.Map;
import java.util.Objects;

/**
 * Sicherheitseinstellungen. {@code identityMode} ist eine Betreiberdeklaration, kein
 * automatisch verifizierter Rechtebeweis.
 *
 * @param allowPersonalCredentials Freigabe eines persoenlichen Prototypzugangs
 * @param modelDataPolicy          Freigabepolitik fuer Modelluebermittlung
 * @param identityMode             restricted oder personal-prototype
 */
public record SecuritySettings(
        boolean allowPersonalCredentials,
        String modelDataPolicy,
        String identityMode) {

    public static final String IDENTITY_RESTRICTED = "restricted";
    public static final String IDENTITY_PERSONAL_PROTOTYPE = "personal-prototype";
    public static final String POLICY_INTERNAL_APPROVED_ONLY = "internal-approved-only";

    public SecuritySettings {
        Objects.requireNonNull(modelDataPolicy, "modelDataPolicy");
        Objects.requireNonNull(identityMode, "identityMode");
    }

    public boolean isPersonalPrototype() {
        return IDENTITY_PERSONAL_PROTOTYPE.equals(identityMode);
    }

    public Map<String, Object> toMap() {
        return Map.of(
                "allowPersonalCredentials", allowPersonalCredentials,
                "modelDataPolicy", modelDataPolicy,
                "identityMode", identityMode);
    }
}
