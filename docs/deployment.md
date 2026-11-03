# 运行、停止、备份与回滚

## 启动真实服务

需要 JDK 21、Maven 3.9+；Git/worktree 功能还需要 Git，配置 gh/lark 包装器时需要对应官方 CLI。令牌仅通过环境变量提供，不写进设置或会话。先执行 `mvn verify`，再运行：

```sh
export OPENAI_BASE_URL=https://your-provider.example/v1/
export OPENAI_MODEL=your-tool-calling-model
# 在当前 shell 的安全环境中设置 OPENAI_API_KEY，不提交到仓库。
scripts/dsh-superagent start --state-dir state --port 8080
scripts/dsh-superagent status --state-dir state --port 8080
```

浏览器打开 http://127.0.0.1:8080/orchestrator/ 。完成模型、来源账号、准入策略和执行权限设置。第一次未配置好时服务仍保留管理入口，状态退出码为 3，不能当作已就绪。控制程序退出码：0 就绪，1 进程/启动异常，2 运行中健康异常，3 配置待完成。`--respect-stop` 尊重人工停止标记，不会被自动恢复命令悄悄拉起。

```sh
scripts/dsh-superagent stop --state-dir state --port 8080
scripts/dsh-superagent restart --state-dir state --port 8080
```

也可以直接 `java -jar target/dsh-superagent-java-1.0.0-SNAPSHOT.jar --state-dir state --port 8080` 前台运行。管理端仅绑定回环地址；远程查看使用受信任 SSH 隧道，不要把未加认证的端口转发给公众。日志在状态目录下轮转，最多 20 份、每份 5 MiB。状态目录包含来源内容、模型会话、工作目录和授权记录，应限制操作系统访问权限。

## 安全备份

与原项目的部署要求一样，升级前由操作人保存备份。本项目不会自行上传备份。

1. 用 `stop` 停止服务，确认没有另一个该状态目录的进程。不要在 SQLite 写入过程中仅复制主数据库文件，WAL 中可能还有已提交数据。
2. 服务关闭后，将整个状态目录复制到受保护的备份目录，包含数据库以及可能存在的 `-wal`、`-shm` 文件、会话和工作目录。另存当前 JAR、配置环境变量的名称及版本；不要把实际密钥写入备份说明。
3. 验证备份能读取，记录版本与备份时间，再替换 JAR。数据库只允许已知版本迁移；遇到高于程序支持的 schema 会拒绝启动，而不会覆盖数据。

回滚时先停止新服务，再恢复整套匹配的旧 JAR 和旧状态目录。不要把旧程序直接指向已升级的数据库，也不要混用不同时间的 SQLite、会话和 worktree。恢复后使用 `status` 检查，再在面板确认来源、待发回复和任务状态。忙碌派工会按恢复流程创建新 Assignment，保留稳定会话、路径和分支；先检查现有文件/Git/远端事实，避免重做已发生的副作用。

## 多模型供应商

默认供应商使用 `OPENAI_BASE_URL`、`OPENAI_API_KEY`、`OPENAI_MODEL`。可通过 `DSH_MODEL_PROVIDERS` 配置 JSON 数组，每项有 `id`、`name`、`baseUrl`、`apiKeyEnv`，可选 `authRequired`。`apiKeyEnv` 是环境变量名称而非令牌。管理页面从每个供应商的真实 `/models` 发现目录，失败会单独显示。角色选择包括 provider/model/reasoningEffort；新会话采用角色设置，已存在会话保持原选择，提示词仍在每次请求重新组装。

## 验证边界

测试运行真实本地 SQLite、HTTP 服务、模型协议工具循环、Git worktree、shell job、子代理、来源协议和控制程序；网络账户由本地协议 fixture 代替。真实 GitHub/飞书账号权限和具体模型能力必须由使用者用自己的测试账号验证。协议测试通过不等于替用户验证了线上账户。
