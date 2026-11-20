#!/bin/zsh
# macOS 双击启动：在 Finder 中双击本文件，终端会自动打开并启动系统。
# 保持终端窗口打开；按 Ctrl+C 停止。首次启动会安装依赖并构建，需要几分钟。
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:/opt/homebrew/opt/node@24/bin:/opt/homebrew/bin:$PATH"
# 本机 8080 常被其他软件占用，后端改用 18080；前端仍为 5173（Tailscale Serve 转发此端口）。
export BACKEND_PORT="${BACKEND_PORT:-18080}"
export FRONTEND_PORT="${FRONTEND_PORT:-5173}"
cd "$(dirname "$0")" || exit 1

# 服务就绪后自动打开浏览器
(
  for i in {1..600}; do
    if curl --fail --silent --max-time 1 "http://127.0.0.1:$FRONTEND_PORT/" >/dev/null; then
      open "http://127.0.0.1:$FRONTEND_PORT"
      exit 0
    fi
    sleep 2
  done
) &
opener_pid=$!

bash scripts/start-local.sh "$@"
exit_code=$?
kill "$opener_pid" 2>/dev/null
echo
echo "系统已停止（退出码 $exit_code）。可以关闭此窗口。"
read -k 1 -s "?按任意键关闭…"
