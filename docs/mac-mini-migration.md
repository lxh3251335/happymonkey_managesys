# 迁移到 Mac mini

## 保存位置和迁移范围

当前 Windows 项目完整目录：

```text
C:\Users\liuxi\Documents\ChatGPT\快乐猴_小象项目登记系统开发
```

| 路径（相对项目根目录） | 内容 | 迁移时处理 |
|---|---|---|
| backend/（除 target） | Java 源码、配置、SQL 迁移、测试；其中 data 为业务库 | 复制 |
| frontend/（除 node_modules、dist） | Vue / TypeScript 源码和依赖锁文件 | 复制 |
| backend/data/commerce.mv.db | 当前本机业务库：项目、报价、账号、回款等 | 停止 Windows 服务后复制，保留同一路径 |
| docs/source-seed.json | 已复核历史导入包 | 单独保留 |
| docs/source-workbooks.json、docs/import-review.json | 原表提取结果、隔离及待核对资料 | 单独保留 |
| scripts/、tools/、deploy/、docs/ | 启动脚本、辅助工具、部署、架构文档 | 复制 |
| compose*.yml、README.md、.github/ 和其他根目录配置 | 工程与部署配置 | 复制，包括隐藏文件 |
| .git/ | Git 元数据 | 可一并复制；通过 GitHub 克隆时会自动获得已推送的提交历史 |
| frontend/node_modules/、.m2/、.npm-cache/ | 当前 Windows 依赖和缓存 | 不复制，在 Mac 上重新安装 |
| backend/target/、frontend/dist/ | 构建产物 | 可不复制，在 Mac 上重新构建 |
| data/local-processes.json | Windows 的本地进程号 | 不复制，不是业务数据 |
| logs/、artifacts/、logs-*.txt | 日志及界面验证截图 | 可选，不影响业务数据 |

`backend/data/source-import-pre-review.mv.db` 是导入复核前留存的旧副本，不是当前运行库；如保留归档，请不要覆盖 commerce.mv.db。

原始两份 Excel 仍在用户原来的 D 盘微信目录，并不在项目根目录。若需在 Mac 重新核对或重新提取原表，请另外复制原 Excel 到自己的资料归档目录。

GitHub 只用于源码和工程配置同步。是否已推送，以实际远端分支及提交为准；数据库和导入 JSON 被 .gitignore 排除，通过 Git 迁移时仍需单独传输这些资料。

源码仓库：`https://github.com/lxh3251335/happymonkey_managesys`。`main` 用于迁移和稳定版本，`codex/initial-platform` 保留首版开发分支。推送完成后可在 Mac 克隆：

```bash
git clone https://github.com/lxh3251335/happymonkey_managesys.git tianshang-commerce
```

私有仓库需在 Mac 本机通过 GitHub Desktop、Git Credential Manager 或访问令牌完成 HTTPS 身份认证；SSH 是另一种可选方式。Git 推送不使用 GitHub 网页登录密码，认证信息不写进源码。克隆完成后，再将单独备份的业务数据库放入对应目录并启动。

## 按现有数据继续开发

1. 在 Windows 项目目录运行 `.\scripts\stop-local.ps1`，确认服务停止后再复制数据库。不要在原库仍被写入时直接复制。关闭数据库后复制是 [H2 官方备份说明](https://www.h2database.com/html/tutorial.html#backup) 支持的方式。
2. 复制上述源码、配置、`backend/data/commerce.mv.db` 和历史资料到 Mac，例如 `~/Projects/tianshang-commerce`。保留目录结构。
3. 安装 Java 17 **JDK**、Maven 3.9、Node.js 22.18+。Apple Silicon 选 macOS ARM64 / aarch64；Intel Mac 选 x64。来源：[Temurin](https://adoptium.net/installation)、[Maven](https://maven.apache.org/download.cgi)、[Node.js](https://nodejs.org/en/download)。
4. 在 Mac Terminal 中检查并启动：

```bash
cd ~/Projects/tianshang-commerce
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
mvn -version
node --version
bash scripts/start-local.sh
```

首次启动会安装 Mac 依赖、运行后端测试并重新构建前端。终端出现 Ready 后打开 `http://127.0.0.1:5173`。保持此终端运行；Ctrl+C 会向本脚本启动的两个服务发送退出信号并等待它们结束。

下一次没有改源码时，可以使用 `bash scripts/start-local.sh --skip-build`。首次迁移不要带这个参数，因为 Windows 的 node_modules 不能替代 Mac 依赖。

复制当前业务库后，原有账号及密码、项目、报价和已登记流水随库保留；修改过的管理员密码不会恢复为开发初始密码。已有相同历史导入批次不会再次入账。Mac 第一次启动后，请核对项目数量、代表性报价、已确认应收和净回款是否与迁移前一致。

`127.0.0.1` 永远指向正在打开浏览器的那台电脑。迁移后应在 Mac 浏览器访问该地址；其他电脑不能通过自己的 127.0.0.1 访问 Mac 上的软件。

## 原生开发与 Docker 部署

日常开发可分别在两个 Terminal 运行：

```bash
# 终端一，从项目根目录进入
cd backend
SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run -Dspring-boot.run.jvmArguments=-Duser.timezone=Asia/Shanghai
```

```bash
# 终端二，从项目根目录进入
cd frontend
npm ci
npm run dev
```

只从现有数据库继续开发，不必重新读取 Excel，也不要求安装 Python。原表提取工具需要另行安装 Python 3。浏览器回归测试可在 frontend 运行 `npx playwright install chromium`，不设置 Windows 专用的 BROWSER_EXECUTABLE 路径。

Docker 部署使用适合芯片的 [Docker Desktop for Mac](https://docs.docker.com/desktop/setup/install/mac-install/)。已把后端运行镜像改为 `eclipse-temurin:17-jre-noble`，官方清单包含 amd64、arm64v8；原来的 Java 17 Alpine 变体只发布 amd64，不能作为 Apple Silicon 原生镜像。[官方镜像架构清单](https://github.com/docker-library/official-images/blob/master/library/eclipse-temurin)

在 Mac 上从源码执行 `docker compose up -d --build`，不要搬 Windows 已构建镜像作为默认部署方式。首次部署仍须依照 [云部署说明](deployment.md) 设置 .env 和 HTTPS 证书。

**H2 数据文件不能直接当作 PostgreSQL 数据库使用。** 本机 H2 → Mac 本机 H2 是文件迁移；本机 H2 → Docker PostgreSQL 是另一项数据迁移。当前 compose.import.yml 只能导入原始历史报价包，不会迁移后来创建的用户、报价、应收或回款。

因此先用同版本 H2 完整迁移并核对现有业务，再另行实施 PostgreSQL 的结构化迁移、主外键校验和金额对账。不要直接用历史导入包替代完整业务数据库。

## 验证范围

当前在 Windows 中检查了 Bash 语法和 Compose 配置，并根据官方清单调整了 Java 镜像架构。本环境无法实际运行 macOS / Apple Silicon，也没有运行中的 Docker daemon，尚未完成 Mac 实机及 ARM64 容器构建验证。迁移前无需停止当前服务查看文档；只有实际复制数据库时才需停机。
