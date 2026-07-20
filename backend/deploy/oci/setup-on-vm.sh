#!/usr/bin/env bash
# Run ON the Oracle Cloud VM after SSH.
# Usage:
#   curl -fsSL ... | bash   OR
#   bash setup-on-vm.sh
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/Strobingn/Viewshading-app.git}"
BRANCH="${BRANCH:-grok}"
APP_DIR="${APP_DIR:-$HOME/Viewshading-app}"

echo "==> Installing Docker if missing"
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
  sudo usermod -aG docker "$USER" || true
  echo "Log out/in (or newgrp docker) if docker needs group refresh"
fi

echo "==> Cloning Viewshading-app ($BRANCH)"
if [ ! -d "$APP_DIR/.git" ]; then
  git clone --branch "$BRANCH" --depth 1 "$REPO_URL" "$APP_DIR"
else
  git -C "$APP_DIR" fetch origin "$BRANCH"
  git -C "$APP_DIR" checkout "$BRANCH"
  git -C "$APP_DIR" pull --ff-only origin "$BRANCH" || true
fi

cd "$APP_DIR/backend"
mkdir -p data uploads

echo "==> Building and starting backend"
docker compose up --build -d

echo "==> Waiting for health"
for i in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:8000/health" >/dev/null 2>&1; then
    echo "OK — backend healthy"
    break
  fi
  sleep 2
done

PUBLIC_IP="$(curl -fsS -m 3 http://169.254.169.254/opc/v2/vnics/ 2>/dev/null | grep -oE '"publicIp"\s*:\s*"[^"]+"' | head -1 | cut -d'"' -f4 || true)"
if [ -z "${PUBLIC_IP:-}" ]; then
  PUBLIC_IP="$(curl -fsS -m 3 ifconfig.me 2>/dev/null || hostname -I | awk '{print $1}')"
fi

echo ""
echo "=========================================="
echo " Viewshed backend on Oracle Cloud"
echo " Health:  http://${PUBLIC_IP}:8000/health"
echo " Docs:    http://${PUBLIC_IP}:8000/docs"
echo "=========================================="
echo " Open port 8000 in OCI: Security List / NSG → Ingress TCP 8000 from 0.0.0.0/0 (or your IP)"
echo " Android: set backend URL to http://${PUBLIC_IP}:8000"
echo ""
