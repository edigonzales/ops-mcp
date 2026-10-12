package ch.so.agi.ops.adapter.openshift;

/**
 * Synthetische Rohantworten fuer die Tests des OpenShift-Adapters.
 *
 * <p>Alle Werte sind erfunden. Es gibt keine echten Tokens, keine echten Benutzerkennungen und
 * keine echten Logkopien. {@code api.ocp.so.ch} erscheint ausschliesslich als
 * Konfigurationsbeispiel einer freigegebenen Clusteradresse. Die Canary-Werte dienen dem
 * Nachweis der Redaktion und sind ebenfalls synthetisch.</p>
 */
public final class OcFixtures {

    public static final String NAMESPACE = "fixture-integration";
    public static final String WORKLOAD = "fixture-modelfinder";
    public static final String SERVER = "https://api.ocp.so.ch:6443";
    public static final String CONTEXT = "fixture-context";
    public static final String KUBECONFIG_TOKEN = "fixture-kubeconfig-token-canary-0123456789";
    public static final String HEADER_CANARY = "Bearer fixture-canary-header-9f8e7d6c5b4a";
    public static final String EXEC_CANARY = "/bin/sh -c test -f /tmp/fixture-ready";
    public static final String STATE_CANARY = "fixture-canary-state-1234567890ab";
    public static final String EVENT_CANARY = "fixture-canary-event-0987654321fe";
    public static final String LOG_CANARY = "fixture-canary-log-abcdef123456";
    public static final String SECRET_CANARY = "fixture-canary-secret-5f4e3d2c1b0a";

    public static final String POD_A = "fixture-modelfinder-7c9f5d8b64-a1";
    public static final String POD_B = "fixture-modelfinder-7c9f5d8b64-b2";
    public static final String POD_A_UID = "fixture-pod-uid-a1";
    public static final String POD_B_UID = "fixture-pod-uid-b2";
    public static final String DEPLOYMENT_UID = "fixture-deployment-uid-1";
    public static final String RS_OLD_UID = "fixture-rs-uid-2";
    public static final String RS_NEW_UID = "fixture-rs-uid-3";
    public static final String RC_UID = "fixture-rc-uid-4";
    public static final String CONTAINER_MAIN = "fixture-app";
    public static final String CONTAINER_SIDECAR = "fixture-sidecar";
    public static final String CONTAINER_INIT = "fixture-init";

    private OcFixtures() {
    }

    /** Gueltige, statische kubeconfig mit Token und CA-Daten. */
    public static final String KUBECONFIG_VALID = """
            apiVersion: v1
            kind: Config
            current-context: fixture-context
            clusters:
            - name: fixture-cluster
              cluster:
                server: https://api.ocp.so.ch:6443
                certificate-authority-data: Zml4dHVyZS1jYS1kYXRh
            contexts:
            - name: fixture-context
              context:
                cluster: fixture-cluster
                user: fixture-user
                namespace: fixture-integration
            users:
            - name: fixture-user
              user:
                token: %s
            """.formatted(KUBECONFIG_TOKEN);

    /** kubeconfig mit verbotenem Credential-Exec-Plugin (S09). */
    public static final String KUBECONFIG_EXEC_PLUGIN = """
            apiVersion: v1
            kind: Config
            current-context: fixture-context
            clusters:
            - name: fixture-cluster
              cluster:
                server: https://api.ocp.so.ch:6443
            contexts:
            - name: fixture-context
              context:
                cluster: fixture-cluster
                user: fixture-user
            users:
            - name: fixture-user
              user:
                exec:
                  apiVersion: client.authentication.k8s.io/v1
                  command: /usr/local/bin/fixture-login-helper
                  args:
                  - get-token
            """;

    /** kubeconfig mit deaktivierter TLS-Pruefung (S08). */
    public static final String KUBECONFIG_INSECURE = """
            apiVersion: v1
            kind: Config
            current-context: fixture-context
            clusters:
            - name: fixture-cluster
              cluster:
                server: https://api.ocp.so.ch:6443
                insecure-skip-tls-verify: true
            contexts:
            - name: fixture-context
              context:
                cluster: fixture-cluster
                user: fixture-user
            users:
            - name: fixture-user
              user:
                token: %s
            """.formatted(KUBECONFIG_TOKEN);

    /** kubeconfig mit eigenem Proxyziel. */
    public static final String KUBECONFIG_PROXY_URL = """
            apiVersion: v1
            kind: Config
            current-context: fixture-context
            clusters:
            - name: fixture-cluster
              cluster:
                server: https://api.ocp.so.ch:6443
                proxy-url: http://proxy.example.invalid:3128
            contexts:
            - name: fixture-context
              context:
                cluster: fixture-cluster
                user: fixture-user
            users:
            - name: fixture-user
              user:
                token: %s
            """.formatted(KUBECONFIG_TOKEN);

    /** kubeconfig ohne statische Identitaet. */
    public static final String KUBECONFIG_WITHOUT_IDENTITY = """
            apiVersion: v1
            kind: Config
            current-context: fixture-context
            clusters:
            - name: fixture-cluster
              cluster:
                server: https://api.ocp.so.ch:6443
            contexts:
            - name: fixture-context
              context:
                cluster: fixture-cluster
                user: fixture-user
            users:
            - name: fixture-user
              user: {}
            """;

    /** kubeconfig mit einem anderen Cluster als dem im Target erlaubten. */
    public static final String KUBECONFIG_OTHER_SERVER = """
            apiVersion: v1
            kind: Config
            current-context: fixture-context
            clusters:
            - name: fixture-cluster
              cluster:
                server: https://api.other.invalid:6443
            contexts:
            - name: fixture-context
              context:
                cluster: fixture-cluster
                user: fixture-user
            users:
            - name: fixture-user
              user:
                token: %s
            """.formatted(KUBECONFIG_TOKEN);

    /** kubeconfig, die den konfigurierten Context nicht kennt. */
    public static final String KUBECONFIG_UNKNOWN_CONTEXT = """
            apiVersion: v1
            kind: Config
            current-context: other-context
            clusters:
            - name: fixture-cluster
              cluster:
                server: https://api.ocp.so.ch:6443
            contexts:
            - name: other-context
              context:
                cluster: fixture-cluster
                user: fixture-user
            users:
            - name: fixture-user
              user:
                token: %s
            """.formatted(KUBECONFIG_TOKEN);

    /** Deployment mit zwei Containern, Init-Container, Probes und Conditions. */
    public static final String DEPLOYMENT_JSON = """
            {
              "apiVersion": "apps/v1",
              "kind": "Deployment",
              "metadata": {
                "name": "fixture-modelfinder",
                "namespace": "fixture-integration",
                "uid": "fixture-deployment-uid-1",
                "generation": 7,
                "resourceVersion": "1042",
                "creationTimestamp": "2026-10-01T09:58:00Z",
                "labels": {"app": "fixture-modelfinder"},
                "annotations": {"deployment.kubernetes.io/revision": "3", "fixture.synthetic": "true"}
              },
              "spec": {
                "replicas": 1,
                "selector": {"matchLabels": {"app": "fixture-modelfinder"}},
                "template": {
                  "metadata": {
                    "uid": "fixture-pod-template-uid-3",
                    "labels": {"app": "fixture-modelfinder", "pod-template-hash": "7c9f5d8b64"}
                  },
                  "spec": {
                    "restartPolicy": "Always",
                    "terminationGracePeriodSeconds": 30,
                    "containers": [
                      {
                        "name": "fixture-app",
                        "image": "registry.example.invalid/fixture/modelfinder:42",
                        "ports": [{"name": "http", "containerPort": 8080, "protocol": "TCP"}],
                        "resources": {
                          "limits": {"cpu": "500m", "memory": "512Mi"},
                          "requests": {"cpu": "100m", "memory": "256Mi"}
                        },
                        "env": [
                          {"name": "FIXTURE_MODE", "value": "synthetic"},
                          {"name": "FIXTURE_TOKEN", "valueFrom": {"secretKeyRef": {"name": "fixture-secret", "key": "token"}}}
                        ],
                        "livenessProbe": {
                          "httpGet": {"path": "/healthz", "port": "http", "scheme": "HTTP"},
                          "initialDelaySeconds": 10,
                          "periodSeconds": 10,
                          "timeoutSeconds": 2,
                          "successThreshold": 1,
                          "failureThreshold": 3
                        },
                        "readinessProbe": {
                          "httpGet": {
                            "path": "/healthz",
                            "port": 8080,
                            "scheme": "HTTP",
                            "httpHeaders": [{"name": "X-Fixture-Header", "value": "Bearer fixture-canary-header-9f8e7d6c5b4a"}]
                          },
                          "initialDelaySeconds": 5,
                          "periodSeconds": 5,
                          "timeoutSeconds": 2,
                          "successThreshold": 1,
                          "failureThreshold": 3
                        },
                        "startupProbe": {
                          "exec": {"command": ["/bin/sh", "-c", "test -f /tmp/fixture-ready"]},
                          "periodSeconds": 2,
                          "timeoutSeconds": 1,
                          "successThreshold": 1,
                          "failureThreshold": 15
                        }
                      },
                      {
                        "name": "fixture-sidecar",
                        "image": "registry.example.invalid/fixture/sidecar:7",
                        "readinessProbe": {
                          "tcpSocket": {"port": 9090},
                          "initialDelaySeconds": 3,
                          "periodSeconds": 10,
                          "timeoutSeconds": 1,
                          "successThreshold": 1,
                          "failureThreshold": 3
                        }
                      }
                    ]
                  }
                }
              },
              "status": {
                "observedGeneration": 7,
                "replicas": 1,
                "updatedReplicas": 1,
                "readyReplicas": 0,
                "availableReplicas": 0,
                "conditions": [
                  {
                    "type": "Progressing",
                    "status": "True",
                    "reason": "ReplicaSetUpdated",
                    "message": "Deployment \\"fixture-modelfinder\\" is progressing.",
                    "lastUpdateTime": "2026-10-01T10:00:02Z",
                    "lastTransitionTime": "2026-10-01T09:58:00Z"
                  },
                  {
                    "type": "Available",
                    "status": "False",
                    "reason": "MinimumReplicasUnavailable",
                    "message": "Deployment does not have minimum availability.",
                    "lastUpdateTime": "2026-10-01T10:00:20Z",
                    "lastTransitionTime": "2026-10-01T09:58:05Z"
                  }
                ]
              }
            }
            """;

    /** OpenShift DeploymentConfig mit eigenem Selektor und latestVersion. */
    public static final String DEPLOYMENT_CONFIG_JSON = """
            {
              "apiVersion": "apps.openshift.io/v1",
              "kind": "DeploymentConfig",
              "metadata": {
                "name": "fixture-modelfinder",
                "namespace": "fixture-integration",
                "uid": "fixture-dc-uid-1",
                "generation": 9,
                "creationTimestamp": "2026-10-01T09:57:00Z",
                "annotations": {"fixture.synthetic": "true"}
              },
              "spec": {
                "replicas": 1,
                "selector": {"app": "fixture-modelfinder", "deploymentconfig": "fixture-modelfinder"},
                "template": {
                  "metadata": {"uid": "fixture-pod-template-uid-9"},
                  "spec": {
                    "terminationGracePeriodSeconds": 45,
                    "containers": [
                      {
                        "name": "fixture-app",
                        "image": "registry.example.invalid/fixture/modelfinder:42",
                        "livenessProbe": {
                          "httpGet": {"path": "/healthz", "port": 8080, "scheme": "HTTP"},
                          "initialDelaySeconds": 10,
                          "periodSeconds": 10,
                          "timeoutSeconds": 2,
                          "successThreshold": 1,
                          "failureThreshold": 3
                        }
                      }
                    ]
                  }
                }
              },
              "status": {
                "latestVersion": 4,
                "observedGeneration": 9,
                "replicas": 1,
                "updatedReplicas": 1,
                "readyReplicas": 1,
                "availableReplicas": 1,
                "conditions": [
                  {
                    "type": "Available",
                    "status": "True",
                    "reason": "Available",
                    "message": "Deployment config has minimum availability.",
                    "lastUpdateTime": "2026-10-01T10:00:30Z",
                    "lastTransitionTime": "2026-10-01T09:57:20Z"
                  }
                ]
              }
            }
            """;

    /** ReplicaSet-Liste mit alter und neuer Revision und identischem Image-Tag. */
    public static final String REPLICA_SETS_JSON = """
            {
              "apiVersion": "apps/v1",
              "kind": "ReplicaSetList",
              "metadata": {"resourceVersion": "1042"},
              "items": [
                {
                  "apiVersion": "apps/v1",
                  "kind": "ReplicaSet",
                  "metadata": {
                    "name": "fixture-modelfinder-5b8d4c7f9a",
                    "namespace": "fixture-integration",
                    "uid": "fixture-rs-uid-2",
                    "creationTimestamp": "2026-10-01T09:40:00Z",
                    "labels": {"app": "fixture-modelfinder", "pod-template-hash": "5b8d4c7f9a"},
                    "annotations": {"deployment.kubernetes.io/revision": "2"},
                    "ownerReferences": [
                      {
                        "apiVersion": "apps/v1",
                        "kind": "Deployment",
                        "name": "fixture-modelfinder",
                        "uid": "fixture-deployment-uid-1",
                        "controller": true,
                        "blockOwnerDeletion": true
                      }
                    ]
                  },
                  "spec": {
                    "replicas": 0,
                    "selector": {"matchLabels": {"app": "fixture-modelfinder", "pod-template-hash": "5b8d4c7f9a"}},
                    "template": {
                      "metadata": {"labels": {"app": "fixture-modelfinder", "pod-template-hash": "5b8d4c7f9a"}},
                      "spec": {
                        "terminationGracePeriodSeconds": 30,
                        "containers": [
                          {
                            "name": "fixture-app",
                            "image": "registry.example.invalid/fixture/modelfinder:42",
                            "livenessProbe": {
                              "httpGet": {"path": "/healthz", "port": 8080, "scheme": "HTTP"},
                              "initialDelaySeconds": 5,
                              "periodSeconds": 5,
                              "timeoutSeconds": 2,
                              "successThreshold": 1,
                              "failureThreshold": 3
                            }
                          }
                        ]
                      }
                    }
                  },
                  "status": {"replicas": 0, "readyReplicas": 0, "availableReplicas": 0, "observedGeneration": 5}
                },
                {
                  "apiVersion": "apps/v1",
                  "kind": "ReplicaSet",
                  "metadata": {
                    "name": "fixture-modelfinder-7c9f5d8b64",
                    "namespace": "fixture-integration",
                    "uid": "fixture-rs-uid-3",
                    "creationTimestamp": "2026-10-01T09:58:01Z",
                    "labels": {"app": "fixture-modelfinder", "pod-template-hash": "7c9f5d8b64"},
                    "annotations": {"deployment.kubernetes.io/revision": "3"},
                    "ownerReferences": [
                      {
                        "apiVersion": "apps/v1",
                        "kind": "Deployment",
                        "name": "fixture-modelfinder",
                        "uid": "fixture-deployment-uid-1",
                        "controller": true,
                        "blockOwnerDeletion": true
                      }
                    ]
                  },
                  "spec": {
                    "replicas": 1,
                    "selector": {"matchLabels": {"app": "fixture-modelfinder", "pod-template-hash": "7c9f5d8b64"}},
                    "template": {
                      "metadata": {
                        "uid": "fixture-pod-template-uid-3",
                        "labels": {"app": "fixture-modelfinder", "pod-template-hash": "7c9f5d8b64"}
                      },
                      "spec": {
                        "terminationGracePeriodSeconds": 30,
                        "containers": [
                          {
                            "name": "fixture-app",
                            "image": "registry.example.invalid/fixture/modelfinder:42",
                            "livenessProbe": {
                              "httpGet": {"path": "/healthz", "port": "http", "scheme": "HTTP"},
                              "initialDelaySeconds": 10,
                              "periodSeconds": 10,
                              "timeoutSeconds": 2,
                              "successThreshold": 1,
                              "failureThreshold": 3
                            }
                          }
                        ]
                      }
                    }
                  },
                  "status": {"replicas": 1, "readyReplicas": 0, "availableReplicas": 0, "observedGeneration": 7}
                }
              ]
            }
            """;

    /** ReplicationController-Liste einer DeploymentConfig. */
    public static final String REPLICATION_CONTROLLERS_JSON = """
            {
              "apiVersion": "v1",
              "kind": "ReplicationControllerList",
              "items": [
                {
                  "apiVersion": "v1",
                  "kind": "ReplicationController",
                  "metadata": {
                    "name": "fixture-modelfinder-4",
                    "namespace": "fixture-integration",
                    "uid": "fixture-rc-uid-4",
                    "creationTimestamp": "2026-10-01T09:58:10Z",
                    "labels": {"app": "fixture-modelfinder", "deploymentconfig": "fixture-modelfinder"},
                    "annotations": {
                      "openshift.io/deployment-config.latest-version": "4",
                      "openshift.io/deployment-config.name": "fixture-modelfinder"
                    },
                    "ownerReferences": [
                      {
                        "apiVersion": "apps.openshift.io/v1",
                        "kind": "DeploymentConfig",
                        "name": "fixture-modelfinder",
                        "uid": "fixture-dc-uid-1",
                        "controller": true
                      }
                    ]
                  },
                  "spec": {
                    "replicas": 1,
                    "selector": {"app": "fixture-modelfinder", "deploymentconfig": "fixture-modelfinder"},
                    "template": {
                      "metadata": {"labels": {"app": "fixture-modelfinder", "deploymentconfig": "fixture-modelfinder"}},
                      "spec": {
                        "terminationGracePeriodSeconds": 45,
                        "containers": [
                          {
                            "name": "fixture-app",
                            "image": "registry.example.invalid/fixture/modelfinder:42",
                            "livenessProbe": {
                              "httpGet": {"path": "/healthz", "port": 8080, "scheme": "HTTP"},
                              "initialDelaySeconds": 10,
                              "periodSeconds": 10,
                              "timeoutSeconds": 2,
                              "successThreshold": 1,
                              "failureThreshold": 3
                            }
                          }
                        ]
                      }
                    }
                  },
                  "status": {"replicas": 1, "readyReplicas": 1, "availableReplicas": 1, "observedGeneration": 9}
                }
              ]
            }
            """;

    /** Pod-Liste: ein neustartender Pod mit Init-Container, ein gesunder Pod. */
    public static final String PODS_JSON = """
            {
              "apiVersion": "v1",
              "kind": "PodList",
              "items": [
                {
                  "apiVersion": "v1",
                  "kind": "Pod",
                  "metadata": {
                    "name": "fixture-modelfinder-7c9f5d8b64-a1",
                    "namespace": "fixture-integration",
                    "uid": "fixture-pod-uid-a1",
                    "creationTimestamp": "2026-10-01T09:58:05Z",
                    "labels": {"app": "fixture-modelfinder", "pod-template-hash": "7c9f5d8b64"},
                    "ownerReferences": [
                      {
                        "apiVersion": "apps/v1",
                        "kind": "ReplicaSet",
                        "name": "fixture-modelfinder-7c9f5d8b64",
                        "uid": "fixture-rs-uid-3",
                        "controller": true,
                        "blockOwnerDeletion": true
                      }
                    ]
                  },
                  "spec": {
                    "nodeName": "fixture-node-1",
                    "restartPolicy": "Always",
                    "terminationGracePeriodSeconds": 30,
                    "initContainers": [
                      {
                        "name": "fixture-init",
                        "image": "registry.example.invalid/fixture/init:3"
                      }
                    ],
                    "containers": [
                      {
                        "name": "fixture-app",
                        "image": "registry.example.invalid/fixture/modelfinder:42",
                        "livenessProbe": {
                          "httpGet": {"path": "/healthz", "port": "http", "scheme": "HTTP"},
                          "initialDelaySeconds": 10,
                          "periodSeconds": 10,
                          "timeoutSeconds": 2,
                          "successThreshold": 1,
                          "failureThreshold": 3
                        },
                        "readinessProbe": {
                          "httpGet": {
                            "path": "/healthz",
                            "port": 8080,
                            "scheme": "HTTP",
                            "httpHeaders": [{"name": "X-Fixture-Header", "value": "Bearer fixture-canary-header-9f8e7d6c5b4a"}]
                          },
                          "initialDelaySeconds": 5,
                          "periodSeconds": 5,
                          "timeoutSeconds": 2,
                          "successThreshold": 1,
                          "failureThreshold": 3
                        },
                        "startupProbe": {
                          "exec": {"command": ["/bin/sh", "-c", "test -f /tmp/fixture-ready"]},
                          "periodSeconds": 2,
                          "timeoutSeconds": 1,
                          "successThreshold": 1,
                          "failureThreshold": 15
                        }
                      },
                      {
                        "name": "fixture-sidecar",
                        "image": "registry.example.invalid/fixture/sidecar:7"
                      }
                    ]
                  },
                  "status": {
                    "phase": "Running",
                    "conditions": [
                      {"type": "Initialized", "status": "True", "lastTransitionTime": "2026-10-01T09:58:06Z"},
                      {"type": "PodScheduled", "status": "True", "lastTransitionTime": "2026-10-01T09:58:05Z"},
                      {"type": "ContainersReady", "status": "False", "reason": "ContainersNotReady",
                       "message": "containers with unready status: [fixture-app]",
                       "lastTransitionTime": "2026-10-01T10:00:20Z"},
                      {"type": "Ready", "status": "False", "reason": "ContainersNotReady",
                       "message": "containers with unready status: [fixture-app]",
                       "lastTransitionTime": "2026-10-01T10:00:20Z"}
                    ],
                    "initContainerStatuses": [
                      {
                        "name": "fixture-init",
                        "image": "registry.example.invalid/fixture/init:3",
                        "imageID": "registry.example.invalid/fixture/init@sha256:1111111111111111111111111111111111111111111111111111111111111111",
                        "ready": true,
                        "restartCount": 0,
                        "state": {"terminated": {"reason": "Completed", "exitCode": 0,
                          "startedAt": "2026-10-01T09:58:06Z", "finishedAt": "2026-10-01T09:58:09Z"}}
                      }
                    ],
                    "containerStatuses": [
                      {
                        "name": "fixture-app",
                        "image": "registry.example.invalid/fixture/modelfinder:42",
                        "imageID": "registry.example.invalid/fixture/modelfinder@sha256:2222222222222222222222222222222222222222222222222222222222222222",
                        "ready": false,
                        "restartCount": 2,
                        "started": false,
                        "state": {"waiting": {"reason": "CrashLoopBackOff",
                          "message": "back-off 40s restarting failed container; token=fixture-canary-state-1234567890ab"}},
                        "lastState": {"terminated": {"reason": "Error", "exitCode": 137,
                          "message": "container process exited",
                          "startedAt": "2026-10-01T10:00:00Z", "finishedAt": "2026-10-01T10:00:31Z"}}
                      },
                      {
                        "name": "fixture-sidecar",
                        "image": "registry.example.invalid/fixture/sidecar:7",
                        "imageID": "registry.example.invalid/fixture/sidecar@sha256:3333333333333333333333333333333333333333333333333333333333333333",
                        "ready": true,
                        "restartCount": 0,
                        "started": true,
                        "state": {"running": {"startedAt": "2026-10-01T09:58:10Z"}}
                      }
                    ]
                  }
                },
                {
                  "apiVersion": "v1",
                  "kind": "Pod",
                  "metadata": {
                    "name": "fixture-modelfinder-7c9f5d8b64-b2",
                    "namespace": "fixture-integration",
                    "uid": "fixture-pod-uid-b2",
                    "creationTimestamp": "2026-10-01T09:58:06Z",
                    "labels": {"app": "fixture-modelfinder", "pod-template-hash": "7c9f5d8b64"},
                    "ownerReferences": [
                      {
                        "apiVersion": "apps/v1",
                        "kind": "ReplicaSet",
                        "name": "fixture-modelfinder-7c9f5d8b64",
                        "uid": "fixture-rs-uid-3",
                        "controller": true
                      }
                    ]
                  },
                  "spec": {
                    "nodeName": "fixture-node-1",
                    "restartPolicy": "Always",
                    "containers": [
                      {
                        "name": "fixture-app",
                        "image": "registry.example.invalid/fixture/modelfinder:42"
                      }
                    ]
                  },
                  "status": {
                    "phase": "Running",
                    "conditions": [
                      {"type": "Ready", "status": "True", "lastTransitionTime": "2026-10-01T09:58:20Z"}
                    ],
                    "containerStatuses": [
                      {
                        "name": "fixture-app",
                        "image": "registry.example.invalid/fixture/modelfinder:42",
                        "imageID": "registry.example.invalid/fixture/modelfinder@sha256:2222222222222222222222222222222222222222222222222222222222222222",
                        "ready": true,
                        "restartCount": 0,
                        "started": true,
                        "state": {"running": {"startedAt": "2026-10-01T09:58:10Z"}}
                      }
                    ]
                  }
                }
              ]
            }
            """;

    /** Einzelner Pod mit gleichem Namen wie {@link #POD_A}, aber anderer UID (T11). */
    public static final String POD_OLD_GENERATION_JSON = """
            {
              "apiVersion": "v1",
              "kind": "Pod",
              "metadata": {
                "name": "fixture-modelfinder-7c9f5d8b64-a1",
                "namespace": "fixture-integration",
                "uid": "fixture-pod-uid-old-99",
                "creationTimestamp": "2026-10-01T09:20:00Z",
                "labels": {"app": "fixture-modelfinder", "pod-template-hash": "5b8d4c7f9a"},
                "ownerReferences": [
                  {
                    "apiVersion": "apps/v1",
                    "kind": "ReplicaSet",
                    "name": "fixture-modelfinder-5b8d4c7f9a",
                    "uid": "fixture-rs-uid-2",
                    "controller": true
                  }
                ]
              },
              "spec": {
                "nodeName": "fixture-node-1",
                "containers": [{"name": "fixture-app", "image": "registry.example.invalid/fixture/modelfinder:42"}]
              },
              "status": {
                "phase": "Succeeded",
                "containerStatuses": [
                  {
                    "name": "fixture-app",
                    "image": "registry.example.invalid/fixture/modelfinder:42",
                    "ready": false,
                    "restartCount": 1,
                    "state": {"terminated": {"reason": "Completed", "exitCode": 0,
                      "startedAt": "2026-10-01T09:20:01Z", "finishedAt": "2026-10-01T09:21:00Z"}}
                  }
                ]
              }
            }
            """;

    /** Pod ohne Containerstatus: Restartcount ist nicht bekannt. */
    public static final String POD_WITHOUT_STATUS_JSON = """
            {
              "apiVersion": "v1",
              "kind": "Pod",
              "metadata": {
                "name": "fixture-modelfinder-7c9f5d8b64-c3",
                "namespace": "fixture-integration",
                "uid": "fixture-pod-uid-c3",
                "creationTimestamp": "2026-10-01T09:58:07Z",
                "labels": {"app": "fixture-modelfinder"}
              },
              "spec": {
                "containers": [{"name": "fixture-app", "image": "registry.example.invalid/fixture/modelfinder:42"}]
              },
              "status": {"phase": "Pending"}
            }
            """;

    /** Events im Format core/v1, inklusive aggregierter Zaehler und fehlender Zeiten. */
    public static final String EVENTS_CORE_JSON = """
            {
              "apiVersion": "v1",
              "kind": "EventList",
              "items": [
                {
                  "apiVersion": "v1",
                  "kind": "Event",
                  "metadata": {"name": "fixture-event-1", "namespace": "fixture-integration", "uid": "fixture-event-uid-1"},
                  "involvedObject": {"kind": "Pod", "namespace": "fixture-integration",
                    "name": "fixture-modelfinder-7c9f5d8b64-a1", "uid": "fixture-pod-uid-a1"},
                  "reason": "Unhealthy",
                  "message": "Liveness probe failed: HTTP probe failed with statuscode: 500",
                  "type": "Warning",
                  "source": {"component": "kubelet", "host": "fixture-node-1"},
                  "firstTimestamp": "2026-10-01T10:00:10Z",
                  "lastTimestamp": "2026-10-01T10:00:30Z",
                  "count": 3
                },
                {
                  "apiVersion": "v1",
                  "kind": "Event",
                  "metadata": {"name": "fixture-event-2", "namespace": "fixture-integration", "uid": "fixture-event-uid-2"},
                  "involvedObject": {"kind": "Pod", "namespace": "fixture-integration",
                    "name": "fixture-modelfinder-7c9f5d8b64-a1", "uid": "fixture-pod-uid-a1"},
                  "reason": "Killing",
                  "message": "Container fixture-app failed liveness probe, will be restarted",
                  "type": "Normal",
                  "source": {"component": "kubelet", "host": "fixture-node-1"},
                  "firstTimestamp": "2026-10-01T10:00:31Z",
                  "lastTimestamp": "2026-10-01T10:00:31Z",
                  "count": 1
                },
                {
                  "apiVersion": "v1",
                  "kind": "Event",
                  "metadata": {"name": "fixture-event-3", "namespace": "fixture-integration", "uid": "fixture-event-uid-3"},
                  "involvedObject": {"kind": "Deployment", "namespace": "fixture-integration",
                    "name": "fixture-modelfinder", "uid": "fixture-deployment-uid-1"},
                  "reason": "ScalingReplicaSet",
                  "message": "Scaled up replica set fixture-modelfinder-7c9f5d8b64 to 1",
                  "type": "Normal",
                  "source": {"component": "deployment-controller"},
                  "firstTimestamp": "2026-10-01T09:58:01Z",
                  "lastTimestamp": "2026-10-01T09:58:01Z",
                  "count": 1
                },
                {
                  "apiVersion": "v1",
                  "kind": "Event",
                  "metadata": {"name": "fixture-event-4", "namespace": "fixture-integration", "uid": "fixture-event-uid-4"},
                  "involvedObject": {"kind": "Pod", "namespace": "fixture-integration",
                    "name": "fixture-modelfinder-7c9f5d8b64-a1", "uid": "fixture-pod-uid-a1"},
                  "reason": "Failed",
                  "message": "Failed to pull image: unauthorized token=fixture-canary-event-0987654321fe",
                  "type": "Warning",
                  "source": {"component": "kubelet"},
                  "firstTimestamp": "2026-10-01T09:59:00Z",
                  "lastTimestamp": "2026-10-01T09:59:05Z",
                  "count": 1
                },
                {
                  "apiVersion": "v1",
                  "kind": "Event",
                  "metadata": {"name": "fixture-event-5", "namespace": "fixture-integration", "uid": "fixture-event-uid-5"},
                  "involvedObject": {"kind": "Pod", "namespace": "fixture-integration",
                    "name": "fixture-modelfinder-7c9f5d8b64-a1", "uid": "fixture-pod-uid-old-99"},
                  "reason": "Unhealthy",
                  "message": "Liveness probe failed on an earlier pod instance with the same name",
                  "type": "Warning",
                  "source": {"component": "kubelet"},
                  "firstTimestamp": "2026-10-01T09:25:00Z",
                  "lastTimestamp": "2026-10-01T09:25:30Z",
                  "count": 2
                },
                {
                  "apiVersion": "v1",
                  "kind": "Event",
                  "metadata": {"name": "fixture-event-6", "namespace": "fixture-integration", "uid": "fixture-event-uid-6"},
                  "involvedObject": {"kind": "Service", "namespace": "fixture-integration",
                    "name": "fixture-unrelated-service", "uid": "fixture-service-uid-1"},
                  "reason": "EnsuringLoadBalancer",
                  "message": "Unrelated namespace event",
                  "type": "Normal",
                  "source": {"component": "service-controller"},
                  "firstTimestamp": "2026-10-01T09:30:00Z",
                  "lastTimestamp": "2026-10-01T09:30:00Z",
                  "count": 1
                },
                {
                  "apiVersion": "v1",
                  "kind": "Event",
                  "metadata": {"name": "fixture-event-7", "namespace": "fixture-integration", "uid": "fixture-event-uid-7"},
                  "involvedObject": {"kind": "Pod", "namespace": "fixture-integration",
                    "name": "fixture-modelfinder-7c9f5d8b64-a1", "uid": "fixture-pod-uid-a1"},
                  "reason": "Scheduled",
                  "message": "Successfully assigned fixture-integration/fixture-modelfinder-7c9f5d8b64-a1 to fixture-node-1",
                  "type": "Normal",
                  "source": {"component": "default-scheduler"},
                  "count": 1
                }
              ]
            }
            """;

    /** Events im Format events.k8s.io/v1, teils Dubletten, teils nur hier vorhanden. */
    public static final String EVENTS_K8S_IO_JSON = """
            {
              "apiVersion": "events.k8s.io/v1",
              "kind": "EventList",
              "items": [
                {
                  "apiVersion": "events.k8s.io/v1",
                  "kind": "Event",
                  "metadata": {"name": "fixture-event-1.17f0", "namespace": "fixture-integration",
                    "uid": "fixture-event-uid-1-dup"},
                  "regarding": {"kind": "Pod", "namespace": "fixture-integration",
                    "name": "fixture-modelfinder-7c9f5d8b64-a1", "uid": "fixture-pod-uid-a1"},
                  "reason": "Unhealthy",
                  "note": "Liveness probe failed: HTTP probe failed with statuscode: 500",
                  "message": "Liveness probe failed: HTTP probe failed with statuscode: 500",
                  "type": "Warning",
                  "reportingController": "kubelet",
                  "eventTime": "2026-10-01T10:00:30.000000Z",
                  "series": {"count": 3, "lastObservedTime": "2026-10-01T10:00:30Z"},
                  "deprecatedCount": 3,
                  "deprecatedFirstTimestamp": "2026-10-01T10:00:10Z",
                  "deprecatedLastTimestamp": "2026-10-01T10:00:30Z"
                },
                {
                  "apiVersion": "events.k8s.io/v1",
                  "kind": "Event",
                  "metadata": {"name": "fixture-event-8.17f1", "namespace": "fixture-integration",
                    "uid": "fixture-event-uid-8"},
                  "regarding": {"kind": "Pod", "namespace": "fixture-integration",
                    "name": "fixture-modelfinder-7c9f5d8b64-a1", "uid": "fixture-pod-uid-a1"},
                  "reason": "OOMKilling",
                  "note": "Memory cgroup out of memory",
                  "type": "Warning",
                  "reportingController": "kubelet",
                  "eventTime": "2026-10-01T10:00:20.000000Z",
                  "series": {"count": 2, "lastObservedTime": "2026-10-01T10:00:25Z"},
                  "deprecatedCount": 2,
                  "deprecatedFirstTimestamp": "2026-10-01T10:00:05Z",
                  "deprecatedLastTimestamp": "2026-10-01T10:00:25Z"
                },
                {
                  "apiVersion": "events.k8s.io/v1",
                  "kind": "Event",
                  "metadata": {"name": "fixture-event-9.17f2", "namespace": "fixture-integration",
                    "uid": "fixture-event-uid-9"},
                  "regarding": {"kind": "Pod", "namespace": "fixture-integration",
                    "name": "fixture-unrelated-pod", "uid": "fixture-pod-uid-unrelated"},
                  "reason": "Pulled",
                  "note": "Container image already present on machine",
                  "type": "Normal",
                  "reportingController": "kubelet",
                  "eventTime": "2026-10-01T09:31:00.000000Z",
                  "series": {"count": 1, "lastObservedTime": "2026-10-01T09:31:00Z"}
                }
              ]
            }
            """;

    /** Container-Log des aktuellen Laufs, mit einem zu redigierenden Zugangswert. */
    public static final String LOG_CURRENT = """
            2026-10-01T10:00:05Z INFO  fixture-modelfinder startet (synthetisch)
            2026-10-01T10:00:06Z INFO  Konfiguration geladen
            2026-10-01T10:00:12Z WARN  Authorization: Bearer fixture-canary-log-abcdef123456
            2026-10-01T10:00:20Z ERROR health endpoint not ready yet
            2026-10-01T10:00:31Z INFO  container stopped
            """;

    /** Container-Log des vorherigen Laufs. */
    public static final String LOG_PREVIOUS = """
            2026-10-01T09:58:05Z INFO  fixture-modelfinder startet (synthetisch)
            2026-10-01T09:58:09Z ERROR fataler Startfehler: abhaengigkeit nicht erreichbar
            """;

    /** Antwort auf {@code oc version -o json}. */
    public static final String VERSION_JSON = """
            {
              "clientVersion": {"major": "4", "minor": "14", "gitVersion": "4.14.0"},
              "serverVersion": {"major": "4", "minor": "14", "gitVersion": "4.14.3",
                "platform": "linux/amd64"},
              "openshiftVersion": "4.14.3"
            }
            """;

    /** Ein Deployment-Objekt mit falschem Typ fuer den DeploymentConfig-Fall. */
    public static final String WRONG_KIND_JSON = """
            {
              "apiVersion": "apps/v1",
              "kind": "Deployment",
              "metadata": {"name": "fixture-modelfinder", "namespace": "fixture-integration",
                "uid": "fixture-deployment-uid-1"},
              "spec": {"selector": {"matchLabels": {"app": "fixture-modelfinder"}}, "template": {"spec": {"containers": []}}}
            }
            """;
}
