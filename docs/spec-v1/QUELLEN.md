# Technische Quellen und Annahmen

**Geprüfter Dokumentationsstand:** 12. Oktober 2026.

Die Anforderungen mit MUST/SHOULD sind Entwurfsentscheidungen für diesen Auftrag. Die folgenden Primärquellen belegen das zugrunde liegende technische Verhalten. Sie ersetzen keine Prüfung der tatsächlich eingesetzten Cluster-, Jenkins-, CLI- und Client-Versionen.

<a id="q1"></a>

## Q1 – Bedeutung der Kubernetes-Probes

Kubernetes: [Liveness, Readiness, and Startup Probes](https://kubernetes.io/docs/concepts/workloads/pods/probes/).

Grundlage für die Unterscheidung von Bereitschaft, Neustartprüfung und Startabsicherung. Insbesondere darf ein Readiness-Fehler nicht als eigenständige Neustartursache ausgegeben werden.

<a id="q2"></a>

## Q2 – Startup-Probe und Startbudget

Kubernetes: [Configure Liveness, Readiness and Startup Probes](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/).

Grundlage für die bedingte Empfehlung einer Startup-Probe bei längerer Initialisierung. Die Spezifikation verlangt zusätzliche konkrete Belege und übernimmt keine Beispielwerte der Dokumentation als AGI-Konfiguration.

<a id="q3"></a>

## Q3 – Begrenzte Verfügbarkeit von Container-Logs und Events

Kubernetes: [Logging Architecture](https://kubernetes.io/docs/concepts/cluster-administration/logging/).

Kubernetes: [kube-apiserver reference](https://kubernetes.io/docs/reference/command-line-tools-reference/kube-apiserver/), insbesondere die konfigurierbare Eventaufbewahrung.

Grundlage für `--previous`, die Unterscheidung von Container-Neustart und gelöschtem Pod sowie explizite Lücken in einer historischen Untersuchung. Die tatsächliche AGI-Aufbewahrung wurde nicht ermittelt.

<a id="q4"></a>

## Q4 – Jenkins-Remote-API

Jenkins: [Remote Access API](https://www.jenkins.io/doc/book/using/remote-access-api/).

Grundlage für das Lesen von Buildinformationen und gesicherte API-Zugriffe. V1 beschränkt die grundsätzlich auch schreibfähige Jenkins-Schnittstelle auf freigegebene Leseendpunkte und setzt keine zusätzlichen UI-/Pipeline-Plugins voraus.

<a id="q5"></a>

## Q5 – MCP-Transport und Tools

Model Context Protocol: [Transports, Spezifikation 2025-06-18](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports).

Model Context Protocol: [Tools, Spezifikation 2026-07-28](https://modelcontextprotocol.io/specification/2026-07-28/server/tools).

Grundlage für stdio, Toolschemas, Fehler und strukturierte Ergebnisse. Die Implementierung muss die mit SDK und Client tatsächlich unterstützte Version aushandeln und dokumentieren; die Nennung neuerer Dokumentation fordert keinen selbst implementierten Protokoll-Forwardport.

<a id="q6"></a>

## Q6 – Offizielles Java-MCP-SDK

Model Context Protocol: [Java SDK](https://java.sdk.modelcontextprotocol.io/) und [offizielles Repository](https://github.com/modelcontextprotocol/java-sdk).

Das SDK soll Protokoll und Transport übernehmen. Konkrete Dependency-Versionen werden bei Implementierung fest gepinnt und getestet.

<a id="q7"></a>

## Q7 – Grenzen von Tool-Annotations

Model Context Protocol: [Tool Annotations as Risk Vocabulary](https://blog.modelcontextprotocol.io/posts/2026-03-16-tool-annotations/).

Annotations sind Hinweise, keine Zugriffskontrolle. Lokale Schreibvorgänge beim Snapshot/Report werden deshalb auch nicht unehrlich als vollständig seiteneffektfreie Operationen annotiert.

<a id="q8"></a>

## Q8 – Java-/Gradle-Kompatibilität

Gradle: [Compatibility Matrix](https://docs.gradle.org/current/userguide/compatibility.html).

Die Matrix nennt Java 25 ab Gradle 9.1.0. Der Auftrag pinnt Java 25 und verlangt eine passende stabile Wrapper-Version, aber keine dynamische Verwendung von `latest`.

<a id="q9"></a>

## Q9 – Kubernetes-RBAC

Kubernetes: [Role Based Access Control Good Practices](https://kubernetes.io/docs/concepts/security/rbac-good-practices/).

Grundlage für minimale technische Rechte und das Vermeiden von Eskalationsmöglichkeiten. Erfolgreiche Leseaufrufe beweisen nicht, dass die verwendete Identität keine Schreibrechte besitzt.

<a id="q10"></a>

## Q10 – OpenShift-Workloadtypen

Red Hat: [OpenShift 4.20 – Deployments](https://docs.redhat.com/en/documentation/openshift_container_platform/4.20/html/building_applications/deployments).

Grundlage für das getrennte Modellieren von Deployment/ReplicaSet und DeploymentConfig/ReplicationController. Die Referenz auf Version 4.20 ist keine Aussage, dass das AGI genau diese Version einsetzt. Verfügbarkeit und Typ des tatsächlichen Workloads müssen geprüft werden.

<a id="q11"></a>

## Q11 – Codex als MCP-Client

OpenAI: [Model Context Protocol](https://developers.openai.com/codex/mcp/) und [Configuration Reference](https://developers.openai.com/codex/config-reference/).

Die Dokumentationsadressen können auf die aktuellen OpenAI-Lernseiten weiterleiten. Grundlage für lokale MCP-Server, Tool-Allowlists, Timeoutkonfiguration und die gesondert zu prüfenden Shell-/Sandbox-Einstellungen. Keine ungeprüfte Konfiguration aus früheren Clientversionen übernehmen.

<a id="q12"></a>

## Q12 – OpenCode als MCP-Client

OpenCode: [MCP servers](https://opencode.ai/docs/mcp-servers/) und [Permissions](https://opencode.ai/docs/permissions/).

Grundlage für lokale MCP-Anbindung und eingeschränkte Diagnoseprofile. Konkrete Client-Version und wirksame Toolliste müssen in der Abnahme stehen.

<a id="q13"></a>

## Q13 – Bestehendes Agentenmuster des Benutzers

Repository: [Datenportal-Themenintegrator – README](https://github.com/edigonzales/datenportal-themenintegrator-agent/blob/main/README.md).

Die README wurde für diesen Spezifikationsauftrag über die GitHub-Verbindung gelesen. Sie beschreibt Codex-/OpenCode-Nutzung, MCP, einen Launcher sowie `init`/`doctor`. Sie ist ein Vorbild für den Arbeitsablauf, keine Vorgabe, Docker, GRETL oder die Publikationsinfrastruktur in den Ops-Agenten einzubauen.

## Nicht durch diese Quellen belegte Betriebsdetails

Die folgenden Angaben stammen aus der Benutzerbeschreibung bzw. sind noch offen:

- Die Rollen des AGI-Deployment-Jenkins, des separaten GRETL-Jenkins und der drei Konfigurationsrepositories.
- Der konkrete historische Modelfinder-Fehler und die Vermutung zu den Probes.
- Der tatsächliche Modelfinder-Jenkins-Pfad, Build, Namespace, Workload und Container.
- Pfade und Parameternamen in den geschützten Konfigurationsrepositories.
- Exakte Cluster-/Jenkins-Versionen, RBAC, Retention, TLS-Ketten und Jenkins-Plugins.
- Welche Logquellen ausser den API-Gateway-Logs tatsächlich nach Logit.io geliefert werden.

In diesem Spezifikationsauftrag wurden keine internen Jenkins-Läufe, OpenShift-Objekte oder geschützten Deploymentkonfigurationen gelesen. Es wurde keine Modelfinder-Ursache erneut verifiziert. Die Beispiele und Testfälle der Spezifikation sind ausdrücklich synthetisch.
