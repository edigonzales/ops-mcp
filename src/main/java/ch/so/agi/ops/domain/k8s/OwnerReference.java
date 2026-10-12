package ch.so.agi.ops.domain.k8s;

import java.util.Objects;

/**
 * Owner-Referenz eines Kubernetes-Objekts. Zugehoerigkeit wird ueber UID und Kind
 * nachvollzogen, nicht ueber Namen oder Label allein.
 *
 * @param apiVersion         apiVersion des Owners
 * @param kind               Kind des Owners
 * @param name               Name des Owners
 * @param uid                UID des Owners
 * @param controller         true, wenn dies der steuernde Owner ist
 * @param blockOwnerDeletion blockOwnerDeletion, soweit vorhanden
 */
public record OwnerReference(
        String apiVersion,
        String kind,
        String name,
        String uid,
        Boolean controller,
        Boolean blockOwnerDeletion) {

    public OwnerReference {
        Objects.requireNonNull(kind, "kind");
        name = name == null ? "" : name;
    }

    public boolean isController() {
        return Boolean.TRUE.equals(controller);
    }
}
