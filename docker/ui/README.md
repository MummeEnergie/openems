# OpenEMS UI deployment

- Runs published fork image via Docker Compose at `../docker-compose.yml`.
- Runtime env `WEBSOCKET_HOST` and `WEBSOCKET_PORT` define websocket target.

Run from the `docker` directory:

```bash
docker compose up -d
```

Open `http://localhost/` in your browser.
