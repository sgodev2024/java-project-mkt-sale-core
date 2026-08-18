# Ubuntu 20.04 deployment

Target layout:

- application releases: `/opt/core-platform/releases/<timestamp>`
- active symlink: `/opt/core-platform/current`
- secrets: `/etc/core-platform/core-platform.env`
- service account: `coreplatform`
- local runtime port: `8080`
- public TLS endpoint: Nginx, for example `https://api.core.example.vn`

## 1. GitHub deploy key

On the server, create a dedicated key:

```bash
sudo -u coreplatform mkdir -p /home/coreplatform/.ssh
sudo -u coreplatform ssh-keygen -t ed25519 -C "java-core-production" -f /home/coreplatform/.ssh/id_ed25519 -N ""
sudo -u coreplatform cat /home/coreplatform/.ssh/id_ed25519.pub
```

Add only the public key to GitHub repository **Settings → Deploy keys → Add deploy key**. Read-only access is sufficient for the server. Verify GitHub's host fingerprint before accepting it, then test with `sudo -u coreplatform ssh -T git@github.com`.

## 2. Runtime packages

Install Java 21 JRE/JDK, Maven, Git, Nginx and curl. Confirm:

```bash
java -version
mvn -version
git --version
nginx -v
```

Use Maven 3.8 or newer. The deployment script runs the repository build with `mvn -B clean verify`.

## 3. PostgreSQL

Run as the PostgreSQL administrator and replace the password:

```sql
CREATE ROLE core_app LOGIN PASSWORD 'LONG_RANDOM_PASSWORD';
CREATE DATABASE core_platform OWNER core_app ENCODING 'UTF8';
REVOKE ALL ON DATABASE core_platform FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE core_platform TO core_app;
```

Keep PostgreSQL bound to localhost when the application runs on the same server. Enable encrypted off-host backups and WAL archiving to meet RPO 15 minutes; test restore within the RTO 1 hour target.

## 4. Service installation

```bash
sudo useradd --system --create-home --home-dir /home/coreplatform --shell /bin/bash coreplatform
sudo install -d -o coreplatform -g coreplatform /opt/core-platform/releases /var/log/core-platform /var/lib/core-platform
sudo install -d -m 0750 -o root -g coreplatform /etc/core-platform
sudo cp deploy/ubuntu20/core-platform.env.example /etc/core-platform/core-platform.env
sudo chown root:coreplatform /etc/core-platform/core-platform.env
sudo chmod 0640 /etc/core-platform/core-platform.env
sudo cp deploy/ubuntu20/core-platform.service /etc/systemd/system/core-platform.service
sudo systemctl daemon-reload
sudo systemctl enable core-platform
```

Edit `/etc/core-platform/core-platform.env` and set the real database password. Never commit this file.

## 5. Nginx and TLS

Replace `api.core.example.vn` in the supplied Nginx configuration, point DNS to the server, enable the site and obtain a TLS certificate with your approved ACME client. Only ports 22, 80 and 443 should be public; port 8080 remains local.

## 6. Deploy and rollback

Copy `deploy.sh` to `/usr/local/sbin/core-platform-deploy`, make it executable and run it as root. It builds a timestamped release, switches the active symlink, checks readiness and automatically restores the previous release when startup fails.

Useful checks:

```bash
sudo systemctl status core-platform
sudo journalctl -u core-platform -n 200 --no-pager
curl -fsS http://127.0.0.1:8080/actuator/health/readiness
```

After the API has a public TLS URL, set `NEXT_PUBLIC_CORE_API_URL` to that URL and rebuild/publish the frontend.
