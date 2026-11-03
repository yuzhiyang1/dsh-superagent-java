# 管理台真实浏览器验收

需要 Java 21、Maven、Node.js 22 和 Playwright Chromium。

在仓库根目录先运行 `mvn verify`，然后在此目录运行 `npm install`、`npx playwright install chromium` 和 `npm test`。

测试启动真实 Java `PanelFixture`，使用临时 SQLite 数据和真实 Engine/管理 API，不伪造 fetch 响应，不使用账号密钥或外部消息服务。覆盖六个页面、中英文、导航、过滤搜索、暂停、设置版本冲突与历史、提示词及技能编辑/预览/恢复、人工答复和停止后的重复操作。每项测试保留截图，失败保留 trace 和视频。

人工答复测试只确认输入落盘。完整模型恢复、真实来源账户、PR 发布和执行工具验收需要另行集成测试，不能据此宣称已覆盖。内置提示词跨发行版本的默认内容变化仍需版本迁移测试。

GitHub Actions 的 `Java 21 and browser acceptance` 工作流自动运行，并保留七天验收产物。测试失败不能作为发布完成的依据。
