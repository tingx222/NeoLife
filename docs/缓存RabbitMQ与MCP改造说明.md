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
