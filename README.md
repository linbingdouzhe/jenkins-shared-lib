# jenkins-shared-lib

Jenkins Shared Library + local-cluster CI/CD infrastructure-as-code for the
[microservices-demo](https://github.com/linbingdouzhe/microservices-demo)
learning project (rancher-desktop k3s, `--context rancher-desktop`).

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│  microservices-demo (fork: linbingdouzhe/microservices-demo)         │
│  ├─ src/<service>/Jenkinsfile        ← 5行，调用共享库                  │
│  ├─ src/<service>/test_*.py          ← 单测                           │
│  └─ kubernetes-manifests/<svc>.yaml  ← 部署清单(image字段被CD stage改写) │
└─────────────────────────────────────────────────────────────────────┘
                │ Jenkins 从这里 checkout                    ▲
                ▼                                            │ CD stage 写回新tag
┌─────────────────────────────────────────────────────────────────────┐
│  Jenkins (namespace: jenkins, localhost:8080)                        │
│  Checkout → Unit Test → SonarQube Analysis → Quality Gate            │
│  → Build & Push (Kaniko) → [emailservice已验证] Update Deploy Manifest│
└─────────────────────────────────────────────────────────────────────┘
                │ push image                    │ SSH deploy key
                ▼                                ▼
┌──────────────────────────┐      ┌──────────────────────────────────┐
│ 本地 Registry (namespace:  │      │  jenkins-shared-lib (本仓库)        │
│ registry)                │      │  vars/pythonServicePipeline.groovy│
│ - 集群DNS: push用          │      │  argocd/  ← ArgoCD自身的IaC        │
│ - NodePort 30500: pull用   │      └──────────────────────────────────┘
└──────────────────────────┘
                │ kubelet pull (走NodePort)
                ▼
┌─────────────────────────────────────────────────────────────────────┐
│  ArgoCD (namespace: argocd, https://localhost:8443)                  │
│  Application(emailservice) --sync(自动,~3min轮询+selfHeal)--> Deployment │
└─────────────────────────────────────────────────────────────────────┘
```

**Two registry addresses, on purpose:** Kaniko pushes from inside a pod, so
it can use the cluster-DNS name (`registry.registry.svc.cluster.local:5000`).
Deployed manifests are pulled by kubelet on the node itself, which can't
resolve that name — they must reference the NodePort address
(`localhost:30500`) instead. Same registry backend, two different front
doors. See `vars/pythonServicePipeline.groovy`'s `registry` vs
`deployRegistry` config keys.

## Repo layout

- `vars/pythonServicePipeline.groovy` / `goServicePipeline.groovy` /
  `javaServicePipeline.groovy` — one shared Pipeline template per language.
  Services call these from a 5-line `Jenkinsfile` in their own repo, passing
  `Map config` to override defaults.
- `argocd/` — IaC for the ArgoCD installation itself (`bootstrap.sh`,
  idempotent) and its `Application` CRs (`applications/`).
- `terraform/sonarqube/` — SonarQube project/quality-gate config as Terraform.

## Service status

| Service | Jenkins CI | Image in registry | ArgoCD CD |
|---|---|---|---|
| emailservice | full 5-stage pipeline verified | yes (tags 1/3/6/9) | `Application` live, `sync=Synced health=Healthy` |
| recommendationservice | job configured, not yet run | no | pending first successful build |
| loadgenerator | job configured, not yet run | no | pending first successful build |
| shoppingassistantservice | not onboarded (heavy GCP deps, deferred) | — | — |
