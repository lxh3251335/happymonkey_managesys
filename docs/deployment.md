# 云部署与运行

## 当前状态

本机可运行；Docker Compose、Nginx HTTPS、PostgreSQL 及容器构建配置已提供。当前电脑 Docker daemon 未启动，因此没有声称已完成容器或 PostgreSQL 部署验收。GitHub Actions 中配置了真实 PostgreSQL 测试，推送后才会执行。

正式平台当前按单企业、单后端实例部署。会话保存在后端内存，重启后需重新登录。横向扩容前应加入集中会话存储和相应测试。

Mac mini 迁移参见 [专门说明](mac-mini-migration.md)。后端运行镜像采用支持 amd64 / arm64v8 的 Temurin 17 Ubuntu Noble 变体，避免 Java 17 Alpine 镜像的架构限制。当前 H2 业务数据进入 PostgreSQL 需要另行迁移，不能仅复制 .mv.db 或重新导入历史报价包。

## 首次部署

以下命令在云服务器的 Linux shell 中运行。

1. 安装 Docker Engine 和 Compose，将源码放入服务器。
2. 将 `.env.example` 复制为 `.env`，替换数据库密码和管理员密码，管理员密码至少 12 位，不得使用本机开发密码。
3. 准备域名和 HTTPS 证书，目录内放置 `fullchain.pem`、`privkey.pem`，在 `.env` 设置 `TLS_DIR`。限制私钥与 .env 文件权限。
4. 开放服务所需 80 / 443 端口；数据库和后端均无宿主公开端口。
5. 执行：

```bash
docker compose config --quiet
docker compose up -d --build
docker compose ps
docker compose logs --tail=100 backend
```

访问 `https://你的域名`，使用 `admin` 和 `.env` 指定的初始密码登录。首次登录后修改密码，再建立内部账号。管理员密码只在空用户表时用于初始化，之后不能通过改 .env 来重置现有账户。

HTTP 入口重定向到 HTTPS。生产 Cookie 为 Secure，不支持直接用 HTTP 完成登录。不要为临时访问而关闭生产 Cookie 安全属性。

## 导入本轮历史数据

默认云环境为空业务数据库。若要导入本轮资料，先将已复核的 `docs/source-seed.json` 安全传到服务器，运行：

```bash
docker compose -f compose.yml -f compose.import.yml up -d --build
```

检查系统管理中的批次和项目数量，随后用基础配置运行：

```bash
docker compose up -d
```

相同批次不重复入账。导入文件包含客户报价与成本，传输、备份和服务器访问权限应按企业商业资料管理。

## 备份与升级

Linux 上备份 PostgreSQL：

```bash
mkdir -p backups
docker compose exec -T db pg_dump -U commerce -d commerce -Fc > backups/commerce.dump
```

按备份周期保存到独立存储，验证备份文件并定期恢复到独立空数据库演练。生产恢复前制定维护窗口，不直接在正在使用的数据卷中尝试。

更新前先备份，运行测试与构建，再 `docker compose up -d --build`。Flyway 自动执行新增迁移。涉及不兼容数据库迁移时，回滚需要经过验证的迁移策略，不能只回退镜像。

生产发布前应锁定镜像摘要、评估依赖安全更新、校验真实 PostgreSQL 结果、确认 HTTPS、日志留存、备份恢复及实际账号权限。源码当前固定应用依赖版本，Docker 基础镜像使用大版本标签，尚未锁定镜像 digest。

## 环境变量

| 变量 | 含义 |
|---|---|
| SPRING_PROFILES_ACTIVE | `prod` / `dev` |
| DB_URL / DB_USER / DB_PASSWORD | 生产数据库连接 |
| ADMIN_PASSWORD | 空用户表时管理员初始密码 |
| IMPORT_SOURCE | 是否执行指定历史导入包 |
| SOURCE_SEED | 服务器内导入包路径 |
| SERVER_ADDRESS | 默认本机 127.0.0.1，prod 为 0.0.0.0 |
| API_TARGET | 本机 Vite 代理目标，默认 http://127.0.0.1:8080 |
| TEST_DB_URL / TEST_DB_USER / TEST_DB_PASSWORD | 集成测试连接；不应指向业务库 |

前端不保存 API 密钥。云智冷连接、统一登录、短信等外部能力尚未接入。
