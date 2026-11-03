# 添加一个 Java Source

对应上游 `docs/008-cookbook.md`。正式服务不再把可安装的来源限制为 GitHub/飞书。

## 两个接口分别负责什么

- `SourceKind` 是安装的种类，拥有配置默认值与验证、字段描述、策略验证/接收行、接收范围、环境变量、健康项、标签/名单同步，以及实例工厂。
- `SourceInstance` 是当前配置运行出的实例，负责真实接收/轮询、读取上下文、发送、启动/关闭。热更新会关闭旧实例；未变更的实例保留。
- `SourceKinds` 注册种类；`SourceRegistry` 路由运行实例。稳定的 kind ID 同时用于 `Trigger.source`、Thread 前缀和 `source:<id>` 设置块。

`SourceKindsTest.QueueKind` 和 `QueueInstance` 是完整可运行的小例子：读取真实 JSONL 文件，转换 Trigger，和 SQLite cursor 同事务提交；走正常 Eval→Ticket；回复实际写入另一文件。这是测试用本地传输，不是第三方队列服务的假实现。替换其文件边界即可接真实协议。

## 安装

显式注入适合应用内扩展和测试：

```java
SourceKinds kinds = SourceKinds.builtins().register(new MyQueueKind());
var service = new OrchestratorService(options, Clock.systemUTC(), kinds);
service.start();
```

也可编写公共无参 `SourceKind` 实现，在插件 JAR 的
`META-INF/services/io.github.dsh.sources.SourceKind` 文件写入实现类全名，然后将 JAR 放进部署 classpath。
`SourceKinds.installed()` 用标准 Java `ServiceLoader` 加载已经安装的类，不下载或执行远程插件。插件代码属于受信任的部署代码，不能由模型或收到的消息安装。

## 配置与管理表单

`defaultConfig()` 必须返回新对象；默认配置应不接收任何对话。`validateConfig()` 返回验证后的独立副本，禁止网络请求和其它副作用。`defaultEnabled()` 决定未保存实例的默认启用状态。

`fields()` 使用 text/number/switch/select/rows 字段，包含 `key` 和中英 `label`。select 用 `options`，rows 用 `columns`；支持 advanced、pattern、min/max 等字段。管理页按描述符生成表单，新增来源无需给面板增加 kind 分支。配置仍由插件验证，浏览器校验不能代替服务端验证。

`panelOnlyKeys()` 阻止主模型改账号、凭据路径、发送权限等部署属性；`restartKeys()` 声明需要重新建立实例的字段。敏感凭据只能从部署环境读取，描述符/保存配置只存环境变量名，不返回密钥。

`environmentValues(config, setup, trigger)` 生成当前工单的真实环境值；`syncReady(config, policy)` 判断当前策略是否已配置同步。

`policyFields()`、`policyRows(config)`、`explicit()` 生成该来源的策略表；`validatePolicy()` 负责来源特有的名单格式。`caseInsensitiveActors()` 用于 GitHub 式忽略账号大小写。可选 `policySync()` 描述同步入口，`synchronizePolicy()` 只读取完整结果；设置服务负责乐观锁、提交和历史。

卸载种类后，历史配置仍可只读查看；不丢失旧值、不继续运行旧实例、不允许编辑未安装来源。重新注册后重新使用保存值。实际注册表变化后调用服务 `reloadSources()`。

## 接收与授权

稳定 Trigger ID 让重读/断线补拉幂等。将消息正文、发送者、原始地址保留在 DTO；来源身份必须来自已认证协议，不能由消息正文声称。

`SourceKind.scope(trigger, config)` 返回 listened/observed/row/reason：

- listened 决定是否在接收范围；配置变更也会重新验证已有 Mandate。
- observed 决定接收未明确提及的消息，仍须进入 Policy admission 表，不能直接启动工作。
- row 是 admission 表行；reason 解释过滤。

服务把 scope 回调安装到 Engine；持久化 `SourceScope` 是额外上限。回调失败时拒绝授权，配置替换后重新评估。`installScope(context)` 应同步安装可信范围；不要等异步 start 才收紧范围。范围外的事件应交给 Engine 记录过滤原因，而不是在读协议阶段静默消失。

## 游标、读取、回信与生命周期

在事务外完整读取一批，验证完再在一个 `store.atomic` 中 `engine.receive` 和保存 cursor。分页中断不能提前推进。新来源使用 `read`/`readContext` 暴露原文，`find` 识别来源专用 URL/ID；工作会话只允许读取实际到达它的 Trigger/Thread。

`SourceKind.channelAllowed(address, config)` 同步检查目的地是否仍在范围，`channelLink(address, config)` 提供来源自己的链接和标签；界面与发送端不必识别具体来源。

`send` 返回 sent/gone/waiting。来源关闭应 waiting；原会话永久删除或不再监听应 gone；临时错误抛出，由持久化 Outbox 统一退避。外部幂等 key 必须稳定，不能声称远端请求和 SQLite 是同一事务。

start/close 管理长连接或子进程；poll 返回收到数量、下一次间隔和错误。服务为每个实例隔离轮询，替换后旧实例不能覆盖新实例的健康。

`health()` 声明自己拥有的条目。`context.ready(name)`/`context.error(name, detail)` 只允许更新声明项；运行时、其他来源和保留名字不能占用。长期问题才报告 error，短暂失败由来源退避。关闭后的旧回调被忽略。

## 验证

`SourceKindsTest` 验证第三种来源从注册、动态设置/策略、真实接收、cursor、工单、真实交付、环境变量，到卸载保存值和健康隔离。`SourceProtocolsTest` 和 `ServiceSourcesTest` 验证内置来源仍通过同一服务边界运行。
