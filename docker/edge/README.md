# OpenEMS Edge

- Pulled from `ghcr.io/mummeenergie/openems-edge` via `docker/docker-compose.yml`.
- Config is persisted at `docker/edge/config` (mounted to `/var/opt/openems/config`).
  - If empty on first run, defaults are copied in by the container.
- Data is persisted at `docker/edge/data` (mounted to `/var/opt/openems/data`).

Ports:
- 8080: Apache Felix Web Console
- 8075: UI Websocket
- 502: Modbus TCP

Run from the repository root:

```bash
cp docker/.env.example docker/.env
# Edit docker/.env: set UI_WEBSOCKET to ws://<Docker-host-IP>:8075.
docker compose -f docker/docker-compose.yml pull
docker compose -f docker/docker-compose.yml up -d
```
Set `OPENEMS_VERSION` in `docker/.env` to pin both images to a published tag.
`mise run start:remote-edge` starts the same Compose deployment.

## Migrating an existing deployment

The former `mise run start:remote-edge` used `tools/docker/edge/docker-compose.yml`
with named volumes and the containers `openems_edge` / `openems_ui`. The current
deployment uses the directories above and different container names. Before
switching an existing installation, stop the old stack and copy its state:

```bash
docker compose -f tools/docker/edge/docker-compose.yml stop
mkdir -p docker/edge/config docker/edge/data
docker cp openems_edge:/var/opt/openems/config/. docker/edge/config/
docker cp openems_edge:/var/opt/openems/data/. docker/edge/data/
docker compose -f tools/docker/edge/docker-compose.yml down
```

Back up existing state first and only copy into empty destination directories.
The old named volumes are retained by `down` without `--volumes`. If you customized
the UI's nginx configuration or certificates, migrate those separately before
starting the new stack. Do not run both stacks simultaneously: they use the same
host ports. Then follow the setup commands above.
