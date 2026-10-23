# DeepSeek智能客服：运行与面试指南

## 真实客服已接通（2026-10-13）

已使用本地配置的真实DeepSeek密钥验证客服接口：模型选择get_faq工具、读取业务说明生成中文回答，第二轮追问能够使用会话上下文。密钥仅保存在`config/application.properties`，该文件已被Git忽略；仓库提供无密钥的example文件。

当前新版Java后端在**8083**后台运行，Nginx的8080入口已转发到它。浏览器打开 http://localhost:8080/customer-service.html ，使用原项目账号登录后即可提问。登录、商户等请求也会由新版后端处理。原8081进程未停止。

本次增加DeepSeek认证失败、余额不足、限流、网络超时等安全错误提示，不返回上游错误正文。真实调用回归测试默认不运行，需要显式设置`runRealDeepSeek=true`，会产生API费用。

以后修改代码或重启电脑，先打包，再启动8083并安装转发配置：

```powershell
mvn -DskipTests package
./scripts/Start-CustomerBackend.ps1 -Port 8083
./scripts/Install-LocalNginxConfig.ps1 -BackendPort 8083
```

启动脚本遇到端口占用会停止，不杀现有进程。后台进程PID/端口记录在`tmp/customer-backend-process.json`，标准日志在`tmp/customer-backend.log`。若需要由IDE运行8081，先在任务管理器停止这个脚本启动的Java进程，在IDE启动后执行`./scripts/Install-LocalNginxConfig.ps1 -BackendPort 8081`。Nginx实际转发端口应与运行的后端一致。

不要在聊天、前端JS、请求参数或Git中填写API密钥。修改本地密钥配置后需要重启后端才能生效。

> 2026-10-13更新：已增加前端客服入口，并补齐讲义业务。当前运行方式和完整变更见[功能完善工作说明](黑马点评功能完善工作说明.md)。文末较早排障记录中“端口未监听”和“未自动修改Nginx”是当时状态，本次已安装并重载Nginx；后端仍需重启加载新代码。

## 功能与边界

POST /customer-agent/chat支持中文、多轮会话、商户名称查询、本人订单查询和平台FAQ，沿用原项目authorization Token。没有前端聊天窗口、SSE、支付退款动作、人工工单和向量RAG。FAQ是代码内静态说明，不是正式售后政策。

这是一个受约束的工具调用Agent：模型决定是否查数据，Java校验并执行，再把结果交给模型生成回答。模型不能直接访问数据库，也不能执行任意SQL。

## 启动步骤

1. 准备JDK、Maven、MySQL、Redis，导入src/main/resources/db/hmdp.sql。
2. 修改application.yaml数据源与Redis。RedissonConfig现在读取同一Spring Redis配置，避免原先硬编码导致两个客户端连接不同实例。可用SPRING_REDIS_HOST、SPRING_REDIS_PORT、SPRING_REDIS_PASSWORD环境变量覆盖。
3. 在DeepSeek控制台创建有效密钥、确认额度。密钥只放环境变量，不提交Git。

```powershell
$env:DEEPSEEK_API_KEY = '替换为密钥'
$env:DEEPSEEK_MODEL = 'deepseek-flash'
mvn spring-boot:run
```

默认根地址https://api.deepseek.com，客户端追加/chat/completions。DEEPSEEK_BASE_URL不要填写完整接口路径。默认模型依据检查时官方文档，可按账号可用模型覆盖；请求显式关闭thinking以简化工具消息协议。

4. POST /user/code?phone=手机号，从开发日志取验证码；POST /user/login，JSON传phone、code，获取data中的token。原项目没有接短信服务。
5. 调客服：

```powershell
$loginToken = '替换为登录token'
$headers = @{ authorization = $loginToken }
$body = @{ message = '帮我找名字里有火锅的店' } | ConvertTo-Json
$result = Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/customer-agent/chat' -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
$result | ConvertTo-Json -Depth 8
$body = @{ sessionId = $result.data.sessionId; message = '这些店几点营业？' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/customer-agent/chat' -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

新会话不传sessionId；继续会话传服务器返回UUID。成功返回Result包装，data有sessionId、reply、toolsUsed（逗号分隔工具名）。业务错误沿用success=false；未登录由原拦截器返回401。

## 代码职责

| 类 | 职责 |
|---|---|
| CustomerAgentController | 获取服务端用户身份、接收请求、安全错误提示 |
| AgentProperties | 密钥、模型、根地址配置 |
| DeepSeekClient | HTTP、Bearer认证、JSON模型协议、连接5秒/读取30秒超时 |
| CustomerTools | 白名单工具、JSON schema、参数校验、业务数据查询 |
| CustomerAgent | 工具循环、上下文、Redis会话、用户并发锁 |
| CustomerAgentTest | 不依赖外部服务的回归测试 |

流程：鉴权 → 校验消息 → 用户锁 → Redis历史 → system+历史+问题 → 模型回答或tool_calls → Java执行工具 → assistant调用消息+tool结果消息 → 再问模型 → 保存最终回答。

tool_call_id关联工具结果与模型发起的调用。最多3轮工具，第4次请求强制tool_choice=none；每轮最多4个工具，最多4次模型请求和12次工具执行。输出max_tokens=1000。各HTTP请求有超时，但没有全局deadline，多轮延迟会累积。没有自动重试，避免不受控费用。

会话Key：agent:history:{userId}:{sessionId}，TTL30分钟，保留6个完整问答轮次，只存用户和最终回答。失败不保存半轮；不长期保存工具数据。后续实时事实仍要查工具，历史回答不是实时库存。不同用户即使知道同一个UUID也不会共享历史。

锁Key：agent:lock:{userId}。Redisson tryLock无显式leaseTime，watchdog续期；同用户跨会话只能同时一个请求，finally释放锁。这是并发限制，不是每分钟限流。正式部署还需要用户速率、日预算、会话数量上限、全局并发和熔断。

## 工具与权限

search_shops：非空店名关键字，最多50字符；参数化LIKE查数据库，只返回id、名称、地址、营业时间、均价，最多5条。不是语义搜索，不支持距离排序。直接查DB也避开现有商户逻辑过期缓存未预热的问题。

get_my_order：订单号用字符串，避免JavaScript大整数精度丢失；SQL始终附加服务端userId，不相信模型传的身份。只返回订单号、券号、状态、创建时间。查不到与越权同样返回“订单不存在或无权查看”。

get_faq：解释登录、秒杀限制与订单状态，明确当前支付、核销、退款业务未实现，不能承诺退款。

未知工具不执行；参数需格式与长度校验。用户输入和工具内容是不可信数据，系统提示降低注入风险，但提示词不能证明安全；权限边界在后端，且没有资金操作工具。上游错误正文不返回或记录，避免密钥/隐私泄露。当前泛化错误便于保护信息，生产可增加脱敏错误类别、traceId、耗时指标。

## 为什么没有引入Spring AI

项目使用Java8、Spring Boot2.3，直接复用RestTemplate、Jackson避免为接入Agent升级整个工程。框架可以减少协议代码，不能替代业务授权、幂等、成本管理和可靠性设计。Agent不必须依赖某个框架。

## 从零理解Agent：面试问答

**LLM**：根据上下文生成Token的模型，语言能力强但不是实时数据库，会产生幻觉。低温度不保证事实正确。

**Tool Calling**：模型返回工具名和JSON参数，应用执行并回传结果。schema是工具说明，不是执行权限。模型不能凭一个函数定义自己连接MySQL。

**Agent与工作流**：工作流步骤由代码固定；Agent把部分下一步选择交给模型。本项目模型选择三个只读工具，权限、循环和停止条件由Java控制。

**Memory**：多轮上下文。本项目是Redis短期会话，不是训练模型或永久记忆，过期后不再携带旧对话。

**RAG**：先检索相关资料再生成回答，常见流程为文档切分、Embedding、向量召回、重排、带来源回答。当前没有Embedding/向量库，静态FAQ和SQL工具不能包装为向量RAG。

**Token和成本**：输入包括历史、系统提示、工具schema与结果，每轮都会增加成本；限制历史、输出与轮数有助于预算，但字符限制不是精确Token预算。价格看官方实时文档。

**幻觉处理**：事实要求工具查询；没数据说明不知道；限制工具权限；评测事实一致性而非只看语言流畅。模型可能不遵守提示词，仍需要评测。

**为何不直接退款**：原业务没有退款闭环。未来需要明确确认、权限、状态机、幂等键、审计后再接受控写工具。

**怎么评测**：准备店名歧义、商户不存在、本人订单、他人订单、伪造身份、恶意工具文本、非法参数、429、超时、连续调用工具等问题集。指标为工具选对率、参数准确率、越权泄露、事实一致性、成功率、P95延迟、Token成本。不能只展示一张成功截图。

## 验证与排障

隔离测试：mvn -Dtest=CustomerAgentTest test。不启动Spring，不依赖MySQL、Redis、真实DeepSeek。覆盖工具执行与身份、查询owner条件、非法输入、异常释放锁且不保存半轮。原有HmDianPingApplicationTests依赖外部服务并运行较多并发任务，应准备环境后单独跑。

真实联调：未登录401；查商户与DB一致；本人订单可查；他人订单不可查；无效UUID失败；同会话多轮理解上文；无密钥、额度不足、超时返回客服不可用；Redis失败不能绕过鉴权。

启动失败先查MySQL/Redis；客服失败查密钥、额度、网络、模型权限、数据库；同用户同时请求返回稍后重试。真实模型、真实数据、多实例并发、故障注入还需环境联调，Mock测试不等于这些全部通过。

## 官方资料

- [DeepSeek首次调用](https://api-docs.deepseek.com/)
- [Chat Completions及工具协议](https://api-docs.deepseek.com/api/create-chat-completion/)
- [工具调用指南](https://api-docs.deepseek.com/guides/tool_calls)
- [模型与价格](https://api-docs.deepseek.com/quick_start/pricing/)

核验日期2026-10-12，模型和价格可能变化。

本次验证记录：新增客服代码编译通过；CustomerAgentTest共6项测试通过，0失败、0错误，额外覆盖工具循环上限和不同用户同sessionId的历史Key隔离。git diff --check通过。没有运行原有外部服务集成测试，没有真实DeepSeek密钥联调。

## 前端入口（2026-10-13补充）

新增frontend/hmdp/customer-service.html、首页入口及底栏客服按钮。复用原Vue/Axios与sessionStorage token，纯文本渲染回答，防止模型HTML被执行；客服单独180秒请求超时，普通接口保持原超时。修复common.js无response时错误处理崩溃。前端副本来自本机Nginx目录，安装脚本scripts/Install-CustomerFrontend.ps1会备份后复制4个文件到该目录；只有静态文件变化不需要重启Nginx。浏览器Ctrl+F5刷新。

入口：http://localhost:8080/customer-service.html。后端必须启动8081；原Nginx upstream同时包含8081、8082，两者当前均未监听，首页分类会加载失败。如仅运行单实例，建议移除未使用的8082并在校验配置后reload。当前未自动修改Nginx或启动后端。

“我的”跳登录是原common.js遇401的行为。token在sessionStorage中，通常关闭标签页后丢失；过期或失效也需重新登录，不能绕过鉴权。

真实链路验收记录：RealCustomerAgentTest通过（1项、0失败、0错误），调用地址为http://localhost:8080/api/customer-agent/chat，覆盖两轮真实模型请求、get_faq工具执行和Redis历史持久化；测试临时登录态与历史已清理。另有DeepSeekClientTest三项测试通过，验证协议、未配置密钥和上游错误脱敏。
