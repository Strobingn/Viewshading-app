# Oracle Cloud checklist (print / tick off)

- [ ] Account created at https://www.oracle.com/cloud/free/
- [ ] Home region chosen
- [ ] SSH key pair created (`ssh-keygen -t ed25519 -f oci_viewshed`)
- [ ] Compute instance running (Ubuntu + public IP)
- [ ] Ingress TCP **22** (SSH) open
- [ ] Ingress TCP **8000** open (Security List or NSG)
- [ ] SSH works: `ssh -i oci_viewshed ubuntu@PUBLIC_IP`
- [ ] `bash setup-on-vm.sh` completed
- [ ] `curl http://PUBLIC_IP:8000/health` → `{"status":"healthy"}`
- [ ] Browser: `http://PUBLIC_IP:8000/docs`
- [ ] (Later) Android backend URL set to that host
