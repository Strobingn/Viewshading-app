# Deploy Viewshed backend on Oracle Cloud (OCI)

Use an **Always Free** compute instance (Ampere A1 or AMD Micro), open port **8000**, run Docker.

## 1. Create free Oracle Cloud account

1. Go to https://www.oracle.com/cloud/free/  
2. Sign up (credit card required for verification; Always Free resources don’t bill if you stay free-tier).  
3. Pick a home region close to you (e.g. `us-ashburn-1`, `us-phoenix-1`).

## 2. Create a Compute VM

**Console:** Menu → **Compute** → **Instances** → **Create instance**

| Setting | Recommended |
|---------|-------------|
| Name | `viewshed-backend` |
| Image | **Canonical Ubuntu 22.04** (or Oracle Linux 8) |
| Shape | **VM.Standard.A1.Flex** (Ampere) 1 OCPU / 6 GB — Always Free  
|         or **VM.Standard.E2.1.Micro** if Ampere capacity is full |
| Networking | Create VCN or use default; assign **public IP** |
| SSH keys | Generate or paste your public key |
| Cloud-init (optional) | Paste contents of [`cloud-init.yaml`](./cloud-init.yaml) |

Click **Create**. Wait until state = **Running**. Copy the **Public IP**.

## 3. Open firewall in OCI (required)

Traffic is blocked until you open the port.

### Security List (classic VCN)

1. Instance → Subnet → **Security Lists** → default  
2. **Add Ingress Rules**:
   - Source: `0.0.0.0/0` (or your home IP `/32` for safer access)  
   - IP Protocol: **TCP**  
   - Destination port: **8000**  
   - Also ensure **SSH 22** is open if not already  

### Network Security Group (if used)

Same: Ingress TCP **8000**.

## 4. SSH into the VM

```powershell
# Windows (PowerShell) — path to the private key you saved
ssh -i $env:USERPROFILE\.ssh\oci_viewshed ubuntu@YOUR_PUBLIC_IP
# Oracle Linux images often use user: opc
# ssh -i ... opc@YOUR_PUBLIC_IP
```

## 5. Install and start the backend

On the VM:

```bash
# One-shot (Docker + clone + compose)
curl -fsSL -o setup.sh https://raw.githubusercontent.com/Strobingn/Viewshading-app/grok/backend/deploy/oci/setup-on-vm.sh
# Or scp from your PC:
# scp -i ~/.ssh/oci_viewshed backend/deploy/oci/setup-on-vm.sh ubuntu@IP:~/

bash setup-on-vm.sh
# Or with branch:
# BRANCH=grok bash setup-on-vm.sh
```

Manual alternative:

```bash
sudo apt update && sudo apt install -y git
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
newgrp docker

git clone --branch grok https://github.com/Strobingn/Viewshading-app.git
cd Viewshading-app/backend
mkdir -p data uploads
docker compose up --build -d
curl http://127.0.0.1:8000/health
```

## 6. Verify from your PC

```powershell
Invoke-RestMethod http://YOUR_PUBLIC_IP:8000/health
# Open in browser:
# http://YOUR_PUBLIC_IP:8000/docs
```

## 7. Point the Android app (next step)

When ready, set backend base URL to:

```text
http://YOUR_PUBLIC_IP:8000
```

(Use HTTPS + reverse proxy later for production; free-tier HTTP is fine for testing.)

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `curl` from PC times out | OCI Security List / NSG missing TCP 8000; confirm public IP |
| Works on VM, not outside | Same — OCI network rules, not only `ufw` |
| Ampere shape unavailable | Try another AD or E2.1.Micro |
| Docker permission denied | `newgrp docker` or re-login after `usermod` |
| Build OOM on Micro | Use Ampere with ≥6 GB RAM, or build image elsewhere and `docker pull` |

## Costs / Always Free notes

- Stay on Always Free shapes and limits.  
- Stop the instance when unused if you want (public IP may change unless reserved).  
- Prefer ingress limited to your IP for security on free HTTP.

## Optional: HTTPS with Caddy (later)

```bash
# Point a domain A record → public IP, then:
# docker run -d -p 80:80 -p 443:443 -v caddy_data:/data caddy caddy reverse-proxy --from your.domain --to localhost:8000
```
