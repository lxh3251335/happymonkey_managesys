#!/bin/zsh
# macOS 双击更新正式系统：停止 → 备份数据 → 换成 GitHub 上 main 分支的最新代码 → 重新启动。
# 只在正式系统的文件夹里使用。这个文件夹不做开发，代码只来自 main；要改代码请走合并请求（见 CONTRIBUTING.md）。
# 整个过程写在一个函数里：更新会换掉本文件，先整体读入再执行，不受换文件影响。
update_system() {
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:/opt/homebrew/opt/node@24/bin:/opt/homebrew/bin:$PATH"
export BACKEND_PORT="${BACKEND_PORT:-18080}"
export FRONTEND_PORT="${FRONTEND_PORT:-5173}"
cd "$(dirname "$0")" || exit 1
here="$(pwd -P)"

stopped=false
finish() {
  echo
  echo "$1"
  if [ "$stopped" = true ]; then echo "注意：系统现在是停止的。处理好上面的问题后，双击“启动系统.command”重新启动。"; fi
  read -k 1 -s "?按任意键关闭…"
  exit "$2"
}

[ -f .dev-workspace ] && finish "这个文件夹是开发用的，不在这里更新。正式系统在：$(cat .dev-workspace)" 1
[ "$(git branch --show-current)" = "main" ] || finish "没有更新：这个文件夹不在 main 分支上。" 1
[ -z "$(git status --porcelain)" ] || finish "没有更新：这个文件夹里有改过的文件。正式系统的文件夹不应该改代码：
$(git status --short)" 1

# 先看有没有新版本：没有就不用停机
echo "正在查看 GitHub 上的最新版本…"
git fetch --quiet origin main || finish "没有更新：连不上 GitHub，请检查网络后再试。系统没有受影响。" 1
before="$(git rev-parse --short HEAD)"
after="$(git rev-parse --short origin/main)"
if [ "$before" = "$after" ]; then finish "已经是最新版本（$before），不需要更新。系统没有受影响。" 0; fi
git merge-base --is-ancestor HEAD origin/main || finish "没有更新：这个文件夹的版本和 main 对不上，请找开发人员处理。系统没有受影响。" 1
echo "将从 $before 更新到 $after："
git log --format='  · %s' HEAD..origin/main | head -20

# 停止正在运行的系统（只停从这个文件夹启动的）
pid="$(lsof -nP -iTCP:"$BACKEND_PORT" -sTCP:LISTEN -t 2>/dev/null | head -1)"
if [ -n "$pid" ]; then
  cwd="$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p')"
  [ "$cwd" = "$here/backend" ] || finish "没有更新：端口 $BACKEND_PORT 上运行的不是这个文件夹里的系统（它在 $cwd）。" 1
  parent="$(ps -o ppid= -p "$pid" | tr -d ' ')"
  echo "正在停止系统…"
  # 先让启动脚本自己收尾（它会一并停掉前端）；15 秒还没停，就直接停后端和前端
  if ps -o command= -p "$parent" | grep -q "scripts/start-local.sh"; then kill -TERM "$parent"; else kill -TERM "$pid"; fi
  for i in {1..30}; do
    if ! lsof -nP -iTCP:"$BACKEND_PORT" -sTCP:LISTEN >/dev/null 2>&1 && ! lsof -nP -iTCP:"$FRONTEND_PORT" -sTCP:LISTEN >/dev/null 2>&1; then break; fi
    if [ "$i" = 15 ]; then
      for port in "$BACKEND_PORT" "$FRONTEND_PORT"; do
        for p in $(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null); do kill -TERM "$p" 2>/dev/null; done
      done
    fi
    sleep 1
  done
  if lsof -nP -iTCP:"$BACKEND_PORT" -sTCP:LISTEN >/dev/null 2>&1 || lsof -nP -iTCP:"$FRONTEND_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    finish "没有更新：系统 30 秒内没有停下来。" 1
  fi
  stopped=true
fi

# 备份数据（数据库和附件），核对一致后才继续
if [ -d backend/data ]; then
  backup="artifacts/data-backup-$(date +%Y%m%d-%H%M%S)"
  echo "正在备份数据到 $backup …"
  mkdir -p "$backup" && cp -Rp backend/data/. "$backup"/ || finish "没有更新：备份数据失败。" 1
  diff -rq backend/data "$backup" >/dev/null || finish "没有更新：备份和原数据不一致。" 1
fi

git merge --quiet --ff-only origin/main || finish "没有更新：换成新版本时出错。" 1
echo "代码已更新到 $after（原来是 $before；要退回可找开发人员用这个编号恢复）。正在重新启动…"
exec ./启动系统.command
}
update_system
