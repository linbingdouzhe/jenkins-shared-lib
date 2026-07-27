#!/usr/bin/env bash
# Installs ArgoCD into the local rancher-desktop k3s cluster and registers the
# emailservice Application. Idempotent -- safe to re-run.
#
# IMPORTANT: always targets the "rancher-desktop" kubeconfig context, never
# any of the other (real company) contexts that may be configured locally.
set -euo pipefail

CTX=(--context rancher-desktop)
ARGOCD_VERSION="v3.4.5"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "[bootstrap] target context: rancher-desktop (local k3s only)"

echo "[bootstrap] installing ArgoCD ${ARGOCD_VERSION}"
kubectl "${CTX[@]}" create namespace argocd --dry-run=client -o yaml | kubectl "${CTX[@]}" apply -f -
# --server-side is required: plain client-side `kubectl apply` fails on the
# applicationsets.argoproj.io CRD (its annotations exceed the 262144-byte
# last-applied-configuration limit). --force-conflicts resolves field-manager
# conflicts on repeat runs.
kubectl "${CTX[@]}" apply --server-side --force-conflicts -n argocd \
  -f "https://raw.githubusercontent.com/argoproj/argo-cd/${ARGOCD_VERSION}/manifests/install.yaml"

echo "[bootstrap] waiting for core components"
kubectl "${CTX[@]}" -n argocd rollout status deployment/argocd-repo-server --timeout=600s
kubectl "${CTX[@]}" -n argocd rollout status deployment/argocd-server --timeout=600s

echo "[bootstrap] exposing argocd-server UI on 8081/8443 (80/443 are already claimed by Traefik's svclb on this node)"
kubectl "${CTX[@]}" apply --server-side --force-conflicts -f "$SCRIPT_DIR/server-patch.yaml"

echo "[bootstrap] applying prereqs (registry NodePort -- see prereqs/registry-nodeport.yaml for why this is required)"
kubectl "${CTX[@]}" apply --server-side --force-conflicts -f "$SCRIPT_DIR/prereqs/registry-nodeport.yaml"

echo "[bootstrap] registering Applications"
kubectl "${CTX[@]}" apply -f "$SCRIPT_DIR/applications/"

echo "[bootstrap] done. Initial admin password (rotate/delete after first login):"
kubectl "${CTX[@]}" -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d
echo ""
echo "[bootstrap] UI: https://localhost:8443"
