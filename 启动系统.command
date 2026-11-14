#!/bin/zsh
# macOS 双击启动：在 Finder 中双击本文件，终端会自动打开并启动系统。
# 保持终端窗口打开；按 Ctrl+C 停止。首次启动会安装依赖并构建，需要几分钟。
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:/opt/homebrew/opt/node@24/bin:/opt/homebrew/bin:$PATH"
cd "$(dirname "$0")" || exit 1

# 服务就绪后自动打开浏览器
(
  for i in {1..600}; do
    if curl --fail --silent --max-time 1 http://127.0.0.1:5173/ >/dev/null; then
      open http://127.0.0.1:5173
      exit 0
    fi
    sleep 2
  done
) &

bash scripts/start-local.sh "$@"
status=$?
echo
echo "系统已停止（退出码 $status）。可以关闭此窗口。"
read -k 1 -s "?按任意键关闭…"
