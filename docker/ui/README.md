# OpenEMS Edge UI

- Built with Angular config `openems,openems-edge-docker`.
- Pulled from `ghcr.io/mummeenergie/openems-ui-edge`.
- Set `UI_WEBSOCKET` in `docker/.env` to a URL reachable from the browser,
  e.g. `ws://192.168.89.204:8075`. The Compose service name `edge` is only
  resolvable inside Docker. Use `wss://` with a TLS-enabled WebSocket proxy
  when serving the UI over HTTPS.

Run from the repository root:

```bash
cp docker/.env.example docker/.env
# Edit docker/.env before starting.
docker compose -f docker/docker-compose.yml pull
docker compose -f docker/docker-compose.yml up -d
```

Open `http://<Docker-host-IP>/` in your browser.
