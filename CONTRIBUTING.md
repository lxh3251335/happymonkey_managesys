# 协作说明

多人一起开发这个系统时的做法。目标只有一个：`main` 分支上的代码随时可以部署。

## 流程

`main` 是共用的主干，受保护：不能直接推送，只能通过合并请求（Pull Request）进入，并且自动检查通过后才能合并。

1. 拉取最新的 `main`，从它建一个自己的分支。
2. 在分支上修改、提交、推送。
3. 在 GitHub 上发起合并请求，写清楚改了什么、怎么验证的。
4. 自动检查（`web`、`api` 两项）通过，另一位同事看过，再合并。
5. 合并后删掉分支。下一件事从最新的 `main` 重新开分支。

```bash
git switch main && git pull
git switch -c feature/简短说明
# 修改、提交
git push -u origin feature/简短说明
```

一个分支只做一件事，尽量几天内合并。分支拖得越久，和别人的改动冲突越多。

分支命名：新功能用 `feature/…`，修问题用 `fix/…`。提交说明沿用现在的写法，以 `feat:`、`fix:`、`chore:`、`docs:` 开头，后面一句话说明做了什么。

## 在自己的电脑上运行

需要 Java 17、Maven 3.9、Node.js 22.18 以上。

- macOS / Linux：`bash scripts/start-local.sh`
- Windows：`.\scripts\start-local.ps1`

脚本会先跑测试和构建，再启动后端和前端，访问 http://127.0.0.1:5173 。开发账号见 [README](README.md)。

克隆下来的是一个空系统：没有客户、合同等业务数据。数据在各自电脑的 `backend/data/` 里，不进仓库。

边改边看时可以手动启动：在 `backend` 运行 `mvn spring-boot:run`，在 `frontend` 运行 `npm ci` 和 `npm run dev`。

## 推送前自己先检查

GitHub 上会跑同样的检查，先在本机跑一遍能省掉来回。

```bash
cd backend && mvn verify
cd ../frontend && npm run build && npm run test:unit
npm run format:check && npm run format:java:check
```

格式不对时运行 `npm run format` 和 `npm run format:java` 自动整理。

GitHub 上的后端测试会在 PostgreSQL 上再跑一遍。本机默认用 H2，个别 SQL 写法两边表现不同，以 GitHub 上的结果为准。

## 约定

**数据库升级脚本**（`backend/src/main/resources/db/migration`）

- 编号不能重复。两个人各加一个同号脚本，合并后系统启动会失败。要加脚本之前先在群里说一声占号。
- 已经合并进 `main` 的脚本不能再改，哪怕只改注释。要调整就加一个新脚本。
- 合并请求里如果 `main` 上已经有了同号脚本，先把自己的改成下一个号再合并。

**数据和密码**

- 这是公开仓库。真实的客户、合同、金额数据不要放进代码、测试、文档或截图里，测试数据用编的。
- `backend/data/`、备份、日志、历史导入表都已被 `.gitignore` 排除，不要强行加进来。
- 密码、企业微信机器人的 Secret 等只在系统页面里填，不写进任何文件。

**模块划分**

- 一个模块只管一件事。项目台账管项目执行和物流，客户报价和合同台账管钱；机器人的消息处理在 `bot` 包，和企业微信的连接在 `wecom` 包。详见 [架构约定](docs/architecture.md)。
- 权限检查写在服务里，不写在页面上。新接口要想清楚业务员、只读账号能看到什么。

**粘贴识别规则**

- 网页和机器人各有一份实现，共用 `shared/parsing` 里的用例。改规则时先改用例，再同时改 `frontend/src/parsing` 和后端的 `DeliveryParser`、`ReceiptParser`。

**每个功能都带测试**，并在 [迭代记录](docs/roadmap.md) 里补一段说明。

## 正式系统

正式系统和开发用的文件夹是分开的两份。正式系统的文件夹只运行 `main` 上已经合并的代码，不在里面改代码；真实数据（`backend/data/`）和数据备份（`artifacts/`）也只在那里。

- 启动：在正式系统的文件夹里双击 `启动系统.command`。
- 更新到 `main` 的最新版本：双击 `更新系统.command`。它会先看有没有新版本，有的话停止系统、备份数据、换成新代码、重新启动；没有新版本或中途出问题时会说明原因。
- 开发用的文件夹里放一个 `.dev-workspace` 文件（内容写正式系统所在的文件夹，不进仓库）。有了它，在开发文件夹里双击上面两个文件都不会执行，避免把开发副本当成正式系统启动。开发时用 `bash scripts/start-local.sh`，端口用 `BACKEND_PORT`、`FRONTEND_PORT` 换开。

开发到一半的分支不要部署到正式系统。
