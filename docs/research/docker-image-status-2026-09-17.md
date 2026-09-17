# Docker image status — 2026-09-17

This note records the initial inspection before the same-day Compose and CI fixes.
For current setup instructions, see [Docker installation](../../docker/README.md).

## Result

Edge and Edge UI images were successfully built and published by GitHub Actions
on 2026-09-17. Both published AMD64 images passed a local startup smoke test.
The repository moved to `MummeEnergie` today (confirmed by the user), but the
successful publishing run still used owner `Femumme`.

| Component | Published image | Tags | Platforms |
| --- | --- | --- | --- |
| Edge | `ghcr.io/femumme/openems-edge` | `2025.10.0-6`, `latest` | linux/amd64, linux/arm64 |
| Edge UI | `ghcr.io/femumme/openems-ui-edge` | `2025.10.0-6`, `latest` | linux/amd64, linux/arm64 |

Sources: [successful Docker run #6](https://github.com/MummeEnergie/openems/actions/runs/35209909639),
[Edge build/push log](https://github.com/MummeEnergie/openems/actions/runs/35209909639/job/105164692616),
[UI build/push log](https://github.com/MummeEnergie/openems/actions/runs/35209909639/job/105164692758).
Both jobs built commit `816123a41da8d52f395ce80c2cdcf2c422c612ec` on `main`.
The five earlier Docker runs returned by the Actions API failed.

## Registry and runtime verification

Live anonymous GHCR manifest and tag-list requests returned HTTP 200 for both
`femumme` images. `latest` resolved to the following OCI indexes, matching the CI
push logs and the subsequently pulled versioned images:

- Edge: `sha256:08a4c58c69a5d3f8da0cae03db32a89baab745e723c8edd20ef8e79e20d2e613`
- UI: `sha256:0e5f61e9ec8483a8ad5ede9a49dc0a6aa3d94e305b014092a2246e39b6561546`

Registry endpoints: [Edge tags](https://ghcr.io/v2/femumme/openems-edge/tags/list),
[Edge manifest](https://ghcr.io/v2/femumme/openems-edge/manifests/latest),
[UI tags](https://ghcr.io/v2/femumme/openems-ui-edge/tags/list),
[UI manifest](https://ghcr.io/v2/femumme/openems-ui-edge/manifests/latest).
These registry endpoints require the normal GHCR anonymous bearer-token exchange.

Anonymous token requests for the corresponding `mummeenergie` and `mumme-it`
names returned HTTP 403 DENIED. No publicly pullable images under those names
were verified; this does not distinguish missing from private packages.

Local smoke test around 10:44 UTC used Docker on linux/amd64, the versioned tags,
`--network none`, no published host ports, no host mounts and disposable anonymous
volumes. Both image pulls succeeded. Observed results:

- Edge remained running, logged OpenEMS 2025.10.0-SNAPSHOT startup, and served
  HTTP on port 8080 (root path returned 404). Port 8075 returned a successful
  `101 Web Socket Protocol Handshake`. The curl probe was intentionally stopped
  by a two-second timeout after the upgrade.
- UI remained running, served the application HTML with HTTP 200 on port 80,
  and generated `/assets/env.js` with the supplied `UI_WEBSOCKET` value.
- Edge logged a local-hostname lookup error in this network-disabled test, but
  subsequently started its HTTP and WebSocket services.
- Both test containers and their anonymous volumes were removed afterwards.

This verifies basic startup and endpoints on AMD64. ARM64 runtime, browser login,
hardware communication, production configuration and long-term operation were
not tested.

## Publishing and deployment gaps

1. The [Docker workflow](../../.github/workflows/docker.yml#L3) runs on `main`
   pushes or manual dispatch, and only pushes images for `main`. It dynamically
   uses `github.repository_owner`, authenticates with `GITHUB_TOKEN`, and builds
   both architectures. A subsequent run under the new owner will target
   `ghcr.io/mummeenergie/openems-*`; successful publication and package visibility
   under that owner still need verification. The repository's default branch was
   `develop` at inspection time, while the publish condition explicitly uses
   `main`: [repository metadata](https://api.github.com/repos/MummeEnergie/openems).
2. [Production Compose](../../docker/docker-compose.yml#L4) still references
   `ghcr.io/mumme-it/openems-edge:latest` and `openems-ui-edge:latest`. These do not
   match the verified published images or the new owner.
3. [Compose UI_WEBSOCKET](../../docker/docker-compose.yml#L23) uses
   `ws://edge:8075`. The [init script](../../tools/docker/ui/root/etc/s6-overlay/s6-rc.d/init-nginx/run#L7)
   writes it to browser-loaded JavaScript, and the [Angular environment](../../ui/src/themes/openems/environments/edge-docker.ts#L15)
   uses it directly. Ordinary external browsers cannot resolve the Compose-only
   hostname; deployment needs a browser-reachable hostname/IP or a WebSocket proxy.
4. [mise start:remote-edge](../../mise.toml#L47) still launches the
   [legacy Compose file](../../tools/docker/edge/docker-compose.yml), which uses
   upstream Docker Hub images and a `<hostname>` placeholder.
5. Docker publishing has no runtime smoke tests and does not depend on the
   separate [normal build workflow](../../.github/workflows/build.yml).
   That [same-commit run failed](https://github.com/MummeEnergie/openems/actions/runs/35209909787):
   [UI npm ci](https://github.com/MummeEnergie/openems/actions/runs/35209909787/job/105164692999)
   reported package/lockfile mismatch; [Java](https://github.com/MummeEnergie/openems/actions/runs/35209909787/job/105164693256)
   failed `:io.openems.edge.common:test` (118 tests, 1 failed, 1 skipped).
   The [UI Dockerfile](../../tools/docker/ui/Dockerfile#L22) uses `npm install`,
   while the [Edge Dockerfile](../../tools/docker/edge/Dockerfile#L16) runs `buildEdge`;
   Docker success therefore does not establish a passing full test suite.

## Recommended next work

Publish and verify both packages under `MummeEnergie`; align Compose and the mise
entry point with those image names; configure a browser-reachable WebSocket URL;
fix the separate CI failures; add startup/HTTP/WebSocket smoke tests to publishing.
No workflow, image, deployment configuration or application source was changed
during this investigation.
