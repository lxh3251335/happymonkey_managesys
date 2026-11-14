#!/usr/bin/env bash
# macOS / Linux local preview. Keep this terminal open; Ctrl+C stops both services.
set -euo pipefail

TASK_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
skip_build=false
case "${1:-}" in
  --skip-build) skip_build=true ;;
  --help|-h) printf 'Usage: bash scripts/start-local.sh [--skip-build]\n'; exit 0 ;;
  '') ;;
  *) printf 'Unknown option: %s\n' "$1" >&2; exit 1 ;;
esac
if [ "$#" -gt 1 ]; then printf 'Only one optional argument is supported.\n' >&2; exit 1; fi

for tool in java node npm curl lsof; do
  command -v "$tool" >/dev/null 2>&1 || { printf 'Required command not found: %s\n' "$tool" >&2; exit 1; }
done
node -e 'const [major,minor]=process.versions.node.split(".").map(Number); if(!((major===22&&minor>=18)||major>=24)){console.error("Use Node.js 22.18+ (22.x), or 24+");process.exit(1)}'
for port in 8080 5173; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    printf 'Port %s is already in use. Check the existing service before retrying.\n' "$port" >&2
    exit 1
  fi
done

if [ "$skip_build" = false ]; then
  command -v mvn >/dev/null 2>&1 || { printf 'Install Maven 3.9 and a Java 17 JDK first.\n' >&2; exit 1; }
  (cd "$TASK_ROOT/backend" && mvn -B verify)
  (cd "$TASK_ROOT/frontend" && npm ci --no-audit --no-fund && npm run build)
fi
for file in backend/target/project-commerce-0.1.0.jar frontend/dist/index.html frontend/node_modules/vite/bin/vite.js; do
  [ -f "$TASK_ROOT/$file" ] || { printf 'Missing %s; run again without --skip-build.\n' "$file" >&2; exit 1; }
done

mkdir -p "$TASK_ROOT/logs"
export SPRING_PROFILES_ACTIVE=dev
export SOURCE_SEED="$TASK_ROOT/docs/source-seed.json"
export IMPORT_SOURCE=false
if [ -f "$SOURCE_SEED" ]; then export IMPORT_SOURCE=true; fi
export API_TARGET=http://127.0.0.1:8080
backend_pid=''
frontend_pid=''
cleanup() {
  for task_pid in "$frontend_pid" "$backend_pid"; do
    if [ -n "$task_pid" ]; then kill -TERM "$task_pid" 2>/dev/null || true; fi
  done
  for task_pid in "$frontend_pid" "$backend_pid"; do
    if [ -n "$task_pid" ]; then wait "$task_pid" 2>/dev/null || true; fi
  done
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

(cd "$TASK_ROOT/backend" && exec java -Duser.timezone=Asia/Shanghai -jar target/project-commerce-0.1.0.jar) >"$TASK_ROOT/logs/backend.mac.log" 2>&1 &
backend_pid=$!
(cd "$TASK_ROOT/frontend" && exec node node_modules/vite/bin/vite.js preview --configLoader native --host 127.0.0.1 --port 5173 --strictPort) >"$TASK_ROOT/logs/frontend.mac.log" 2>&1 &
frontend_pid=$!

ready=false
for ((attempt=0; attempt<30; attempt++)); do
  if ! kill -0 "$backend_pid" 2>/dev/null || ! kill -0 "$frontend_pid" 2>/dev/null; then break; fi
  if curl --fail --silent --max-time 1 http://127.0.0.1:8080/api/health >/dev/null && curl --fail --silent --max-time 1 http://127.0.0.1:5173/ >/dev/null; then ready=true; break; fi
  sleep 1
done
if [ "$ready" = false ]; then printf 'Startup did not complete. Check logs/backend.mac.log and logs/frontend.mac.log.\n' >&2; exit 1; fi
printf 'Ready: http://127.0.0.1:5173\nKeep this terminal open. Press Ctrl+C to stop both services.\n'
while kill -0 "$backend_pid" 2>/dev/null && kill -0 "$frontend_pid" 2>/dev/null; do sleep 1; done
printf 'A service exited. Check the logs.\n' >&2
exit 1
