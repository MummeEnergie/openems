# OpenEMS Edge deployment

- Runs published fork image via Docker Compose at `../docker-compose.yml`.
- Config is persisted at `./conf` (mounted to `/var/opt/openems/config`).
  - If empty on first run, defaults are copied in by the container.
- Data is persisted at `./data` (mounted to `/var/opt/openems/data`).

Ports:
- 8080: Apache Felix Web Console
- 8075: UI Websocket
- 502: Modbus TCP

Run from the `docker` directory:

```bash
docker compose up -d
```
