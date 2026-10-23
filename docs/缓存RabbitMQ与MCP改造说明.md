# 缓存、RabbitMQ 与 MCP 改造说明

2026-10-23开始，按三个功能分别提交和推送。真实运行结果与代码实现分开记录；不将目标1500 QPS写成已达到，也不将单实例称为高可用集群。

## 1. 商户缓存

ShopBloomFilter使用Redisson RBloomFilter（预期100万条、误判率1%），启动分页导入现有商户ID，完成后才标记ready。查询在原来的互斥锁/逻辑过期缓存前做过滤；新增商户补入。过滤器未准备、被删除或查询异常时放行，避免初始化过程误拦截；空值缓存继续处理误判。布隆过滤器有误判，不会精确判断存在，也不删除单个成员。外部直接导入DB后必须重启重建，否则新数据可能被拒绝。新增保存到补入Redis之间仍有很短窗口，生产应使用持久化变更事件和双缓冲重建。

原CacheClient负责冷缓存互斥加载与热缓存逻辑过期。布隆过滤器不替代Redis故障降级，也没有因此证明高可用。

## 验证记录

各阶段测试与同步结果将在后续提交中追加。

## 2. RabbitMQ 异步下单与预热

启动SeckillWarmup预热所有秒杀券；也保留首次请求初始化。开启rabbit profile后，链路为：Lua资格预扣+Stream持久投递记录 → RabbitOrderTransport持久消息+Publisher Confirm → 确认后ACK/删除Stream记录 → Rabbit消费者 → OrderWriter事务条件扣库存并幂等写订单 → 手动ACK。Stream作为outbox保留，避免Redis预扣后进程退出丢失待发送订单；Broker故障会保留Pending继续发送，确认后进程退出可能重复投递，由数据库幂等处理。

消费者2至4个并发，prefetch20。消费失败NACK且不立即重排，进入hmdp.orders.dead死信队列；预扣和处理中状态保留，需要管理员查原因后安全重放，不能盲目退款/补库存。Redis和Rabbit均需配置持久化与备份，当前不是跨机房容灾。保留Stream-only模式以兼容现有环境。

运行：设置RABBIT_HOST、RABBIT_PORT、RABBIT_USER、RABBIT_PASSWORD、RABBIT_VHOST，执行`mvn spring-boot:run -Dspring-boot.run.profiles=rabbit`。不把真实密码提交Git。RabbitMQ需单独安装，推荐官方镜像rabbitmq:3-management，映射5672和15672；这是部署示例，不表示本机已安装。

数据库仍用`stock > 0`条件更新（乐观并发控制），不是新增version字段。Redis锁负责初始化和Stream出站批次协调，DB唯一索引兜底一人一券。Broker确认与消费者确认不是一回事，参考[官方确认文档](https://www.rabbitmq.com/docs/confirms)。当前逐条发布确认优先保证正确性，不能宣称1500QPS。压测要同时记录受理RPS、最终落库吞吐、P95/P99、错误率、队列积压与库存对账，重复拒绝请求不能计为成功下单吞吐。

第一项已推送：b76ec32，分支codex/cache-rabbitmq-mcp。未修改main。

第二项已推送：c1bddcd。编译与消费失败NACK/不ACK单元测试通过。真实RabbitMQ服务当前未安装，因此未声称已经完成Broker联调。

## 3. MCP客服

McpToolGateway提供JSON-RPC分发、initialize、ping、tools/list、tools/call。CustomerTools保留业务实现，新增get_shop_vouchers；订单身份始终取服务端登录用户，而不是模型参数。客服主循环通过McpToolGateway把MCP工具schema转换成DeepSeek函数schema，并把模型工具请求转为MCP tools/call。多轮记忆和循环上限不变。

scripts/mcp_server.py是标准stdio适配进程：逐行读写UTF-8 JSON-RPC，普通日志不输出到stdout；用本机HTTP桥访问受登录保护的/mcp。设置HMDP_USER_TOKEN为本项目登录返回的token（不是DeepSeek密钥），HMDP_MCP_URL默认http://127.0.0.1:8080/api/mcp。示例MCP客户端配置：

```json
{"mcpServers":{"hmdp":{"command":"python","args":["D:/Osborn/redis/code/hm-dianping/scripts/mcp_server.py"],"env":{"HMDP_USER_TOKEN":"在本地填登录token"}}}}
```

登录Token过期后需重新登录更新，不能把你的Token配置提交Git。订单工具不能以客户端传入userId切换用户。HTTP桥拒绝Origin头，stdio只允许连接loopback；未实现完整OAuth远端授权。当前对外支持stdio传输，/mcp只是其私有HTTP桥，不能宣称为完整Streamable HTTP Server。无SSE推送、任务恢复或向量RAG。Agent使用进程内JSON-RPC分发；外部MCP客户端通过stdio访问相同分发器，两者共享业务逻辑。

协议参照[官方MCP 2025-03-26传输](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports)和[工具规范](https://modelcontextprotocol.io/specification/2025-03-26/server/tools)。本实现是受限的只读工具子集，不声称经过官方兼容性认证。多实例可共享Redis会话，但真正高可用还需要负载均衡、Redis/MySQL/Rabbit故障切换、超时熔断、故障演练，目前未实现这些部署条件。

## 不用Docker怎么安装RabbitMQ

Docker不是RabbitMQ的前置条件。优先考虑在你现有Redis虚拟机里安装。先执行`cat /etc/os-release`确认系统，按[Debian/Ubuntu官方安装说明](https://www.rabbitmq.com/docs/install-debian)或对应发行版指南安装受支持的RabbitMQ和Erlang。不要直接照抄其他发行版命令；官方不建议使用Debian/Ubuntu默认仓库中过旧版本。

安装完成后在虚拟机启用服务和管理插件，建立专用用户/虚拟主机；示例中密码在虚拟机终端本地输入，不要提交或发聊天：

```sh
sudo systemctl enable --now rabbitmq-server
sudo rabbitmq-plugins enable rabbitmq_management
sudo rabbitmqctl add_vhost hmdp
sudo rabbitmqctl add_user hmdp
sudo rabbitmqctl set_permissions -p hmdp hmdp '.*' '.*' '.*'
sudo rabbitmq-diagnostics ping
```

较老版本add_user若要求密码参数，请按对应版本CLI帮助在本地设置。放行5672给Windows应用访问；15672只给需要访问管理台的主机。不要以guest做跨机器连接。Windows本机也可原生安装：先安装匹配的64位Erlang，再按[官方Windows说明](https://www.rabbitmq.com/docs/install-windows)安装RabbitMQ服务。当前没有虚拟机登录凭据或系统版本，未擅自安装系统服务。

本地配置可以在已被Git忽略的config/application.properties添加：

```properties
hmdp.rabbit.enabled=true
spring.rabbitmq.host=你的虚拟机IP
spring.rabbitmq.port=5672
spring.rabbitmq.username=hmdp
spring.rabbitmq.password=仅在本地填写
spring.rabbitmq.virtual-host=hmdp
```

启用前先备份并确认没有旧版Stream-only消费者仍在运行，避免其抢走outbox直接落库；使用新版所有节点统一配置。服务仍需重新打包/重启，不会自动更新之前运行在8083的jar。

## 验证与简历表述

测试命令：`mvn -Dtest=ShopBloomFilterTest,RabbitOrderTransportTest,CustomerAgentTest,DeepSeekClientTest,McpToolGatewayTest test`。覆盖Bloom未就绪放行/不存在拦截、消费失败死信、工具循环、MCP协议转换与权限身份传递、上游错误脱敏。当前无真实Broker端到端、MCP外部客户端、HA故障切换或1500QPS结果。

可如实写：实现Bloom与逻辑过期缓存防护；实现Redis Lua预扣、Stream可靠出站记录、RabbitMQ确认投递/事务幂等消费；实现MCP stdio工具服务及客服适配。不要写“已实现高可用”或“QPS提升至1500”，直到完成部署和可复现测量。性能验收需预热、独立压测机、多用户有效Token、足够活动库存，并与DB最终成功订单数和剩余库存对账。

## Docker部署（用户选择）

已提供compose.yaml。Docker Desktop安装完成并启动Linux容器引擎后，复制.env.example为.env，设置独立密码，再执行：

```powershell
docker compose up -d
docker compose ps
docker compose logs --tail=50 rabbitmq
```

管理台http://localhost:15672，用户名hmdp，密码来自本地.env。数据卷rabbit-data保留队列数据；不要用down -v删除业务消息。配置只绑定本机端口，不开放公网。Java本地配置需设置hmdp.rabbit.enabled=true、spring.rabbitmq.host=127.0.0.1、port=5672、username=hmdp、password与.env一致、virtual-host=hmdp。这是Windows应用连接本机容器的地址；若后端也容器化，主机名应改为Compose服务名rabbitmq。

Docker Desktop安装可能需要管理员授权、启用WSL2/虚拟化并重启，参见[官方Windows安装说明](https://docs.docker.com/desktop/setup/install/windows-install/)。安装器完成与引擎可用是两个不同状态，只有docker info成功才可启动容器。

本轮代码验证：5个测试类共12项单元测试通过，0失败0错误；Python stdio适配器语法检查、git diff --check通过。尚未产生1500QPS或高可用验证结果。
