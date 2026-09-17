# OpenEMS Edge mit Docker installieren

Voraussetzungen: Linux mit Docker Engine und Docker Compose, Git und eine
AMD64- oder ARM64-CPU (z. B. Raspberry Pi mit 64-Bit-Betriebssystem).

## Erstinstallation

```bash
git clone --branch main https://github.com/MummeEnergie/openems.git
cd openems
cp docker/.env.example docker/.env
```

In `docker/.env` die vom Browser erreichbare Adresse des Docker-Hosts eintragen:

```dotenv
UI_WEBSOCKET=ws://192.168.89.204:8075
OPENEMS_VERSION=latest
```

Die Beispiel-IP durch die eigene IP oder einen auflösbaren Hostnamen ersetzen.
`localhost` funktioniert nur, wenn der Browser auf dem Docker-Host läuft.
Für die UI über HTTPS ist ein WebSocket-Proxy mit TLS und einer `wss://`-URL nötig.

Alle folgenden Befehle werden im Repository-Verzeichnis ausgeführt:

```bash
docker compose --env-file docker/.env -f docker/docker-compose.yml pull
docker compose --env-file docker/.env -f docker/docker-compose.yml up -d
docker compose --env-file docker/.env -f docker/docker-compose.yml ps
```

Die Images heißen `ghcr.io/mummeenergie/openems-edge` und
`ghcr.io/mummeenergie/openems-ui-edge`. Die UI ist unter `http://<Host-IP>/`
erreichbar, die Felix-Konsole unter `http://<Host-IP>:8080/system/console`.
Der Standard-Edge-Login ist `admin` / `admin`. Geräte und Controller müssen für
die jeweilige Anlage konfiguriert werden.

## Betrieb und Updates

- Konfiguration: `docker/edge/config`
- Laufzeitdaten: `docker/edge/data`
- Ports: 80/443 (UI), 8075 (WebSocket), 8080 (Felix), 502 (Modbus)
- Beide Container starten nach einem Neustart des Docker-Hosts automatisch.

Logs ansehen:

```bash
docker compose --env-file docker/.env -f docker/docker-compose.yml logs --tail=100 -f
```

Für ein Update erneut `pull` und `up -d` ausführen. Die Konfiguration und Daten
bleiben in den oben genannten Verzeichnissen erhalten. Für eine feste Version
`OPENEMS_VERSION` auf einen für beide Images veröffentlichten Tag setzen; damit
lassen sich auch frühere Versionen gezielt wieder starten.

Vorhandene Installationen mit der alten Compose-Datei unter `tools/docker/edge/`
müssen zuerst ihre Daten übernehmen: [Migrationsanleitung](edge/README.md#migrating-an-existing-deployment).
