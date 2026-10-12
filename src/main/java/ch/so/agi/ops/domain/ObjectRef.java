package ch.so.agi.ops.domain;

import java.util.Objects;

/**
 * Referenz auf ein Betriebsobjekt. Eine UID ist der belastbare Zugehoerigkeitsbeweis,
 * ein Name allein nicht: gleichnamige, neu erzeugte Pods haben eine andere UID.
 *
 * @param kind      Kubernetes-Art, z. B. {@code Pod} oder {@code ReplicaSet}
 * @param namespace Namespace des Objekts
 * @param name      Name des Objekts
 * @param uid       UID des Objekts oder {@code null}, wenn die Quelle keine liefert
 */
public record ObjectRef(String kind, String namespace, String name, String uid) {

    public ObjectRef {
        Objects.requireNonNull(kind, "kind");
        namespace = namespace == null ? "" : namespace;
        name = name == null ? "" : name;
    }

    public static ObjectRef of(String kind, String namespace, String name) {
        return new ObjectRef(kind, namespace, name, null);
    }

    public boolean hasUid() {
        return uid != null && !uid.isBlank();
    }

    public String describe() {
        return kind + "/" + (namespace.isBlank() ? "" : namespace + "/") + name
                + (hasUid() ? "@" + uid : "");
    }
}
