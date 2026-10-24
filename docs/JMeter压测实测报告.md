# JMeter 本地并发基线实测报告

测试日期：2026-10-24。运行编号：`bench_42ae9bec3f6a4d97`。

## 测了什么

使用本机已安装的Apache JMeter 5.6.3，以CLI非GUI模式请求`http://127.0.0.1:8080/api/voucher-order/seckill/{voucherId}`，经过Nginx、当前运行后端、Redis、RabbitMQ和MySQL。每次请求使用不同测试用户的Token，断言响应JSON的`success=true`，不能只以HTTP200作为成功。

这是同机、短时、闭环并发突发基线，不是独立压测机的持续稳态测试，也没有证明1500QPS。没有修改下单业务代码来迎合结果。测试期间后端继续使用用户现有运行配置，未固定CPU、隔离其他进程或统一日志级别，这些因素会影响结果。

## 测试数据

- 在本地DB创建1000个有独立ID的测试用户，昵称带本轮唯一标记，手机号使用非真实手机号的000开头测试值。
- 为这1000个用户生成匹配真实登录态结构的临时Redis Token，有效期1小时。这是测试夹具，**没有经过验证码登录流程**，所以不包含登录/短信吞吐测量。未给已有真实用户伪造会话。
- 创建4张新测试券（当时ID13、14、15、16），分别分配10、100、500、1000库存，共1610份。不同轮复用用户，但每轮使用不同券，不触发一人一券拒绝。
- 在压测前为新券写入与DB一致的Redis活动快照，排除首次懒加载耗时；这不是本轮对应用启动预热功能的测试。
- Token CSV、原始JTL、JMeter日志放在被Git忽略的`tmp/bench_42ae9bec3f6a4d97`。JMX和脱敏汇总可提交，登录Token不提交。

## 实测结果

| 请求数 | 配置/观测最大线程数 | 受理成功 | 受理RPS | P95 | P99 | 最终DB订单数 | DB/Redis剩余库存 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 10（预检） | 2 / 2 | 10 | 6.99 | 101ms | 101ms | 10 | 0 / 0 |
| 100 | 10 / 10 | 100 | 63.17 | 69ms | 69ms | 100 | 0 / 0 |
| 500 | 50 / 50 | 500 | 219.68 | 125ms | 135ms | 500 | 0 / 0 |
| 1000 | 100 / 100 | 1000 | 328.73 | 185ms | 221ms | 1000 | 0 / 0 |

四轮全部业务受理成功，最终成功落库1610单。每轮`COUNT(DISTINCT user_id)`等于订单数，无重复买家，数据库和Redis库存一致；本轮券对应的处理中标记排空。失败数0不代表其他并发规模也不会失败。

RPS计算采用JTL第一条采样开始到最后一条采样结束的窗口，1000请求档窗口为3.042秒。P95/P99来自该轮HTTP样本，不是数据库提交延迟。不同轮启动新的JMeter进程，存在连接建立、Groovy断言编译和服务端热身差异，因此不能简单把各轮差异都归因于线程数。

最高档从启动JMeter到检测到全部订单落库共33.71秒，**包含JMeter启动时间和完成状态轮询误差**，不能当作精确DB平均TPS。接口约329次/秒受理，不代表数据库每秒提交329单；后台出站每批10条、完成后等待200ms且逐条等待Rabbit确认，当前单实例存在明显持续吞吐限制。

Rabbit管理API计数按周期采样，各轮前后快照不严格对齐：例如500请求档可能含上一轮尚未刷新的计数，1000请求档可能少计尾部。因此JSON中的`rabbit_counter_delta_unaligned`仅供参考，不作为某一轮精确消息数证明。最终订单和库存以DB/Redis对账为准。

脱敏机器可读汇总：[JSON结果](JMeter-result-bench_42ae9bec3f6a4d97.json)。

## 清理结果

成功排空本轮任务后，脚本仅删除本轮测试订单、测试券、库存行、测试账号、测试Token及本轮活动缓存。旧券1/11/12和用户此前订单未修改，未清空共享Redis、Rabbit队列或数据卷。

清理后的独立只读核验：本轮用户、订单、券、库存行残留均为0。Token CSV已删除，私有manifest中的Token列表已清空。因此直接打开JMX再次运行之前，需要重新准备CSV和活动，不能复用已经清理的13至16号券。

## 查看与复现

可在JMeter GUI打开`D:/Osborn/redis/code/hm-dianping/scripts/jmeter-orders.jmx`查看计划。正式发送压力时使用CLI；无需在GUI开启结果树。

本地1000请求HTML报告：`D:/Osborn/redis/code/hm-dianping/tmp/bench_42ae9bec3f6a4d97/html-1000/index.html`。原始JTL与HTML保留本机，未提交GitHub。

重新执行同样的数据准备、四档测试、对账及成功后清理：

```powershell
Set-Location D:\Osborn\redis\code\hm-dianping
python scripts/jmeter_benchmark.py --execute
```

依赖本机mysql命令、Python/PyYAML和JMeter；脚本默认查找本机JMeter，也可传`--jmeter`指定jmeter.bat。MySQL/Redis配置来自application.yaml，Rabbit验证使用config内本地凭据；如果改变数据源位置，需要先核对脚本设置。脚本会真实创建测试数据，必须仅在本地测试环境执行。

如果中途失败，脚本停止升压并保留测试夹具用于调查，目录中的private-manifest.json记录本轮ID；不要为了重跑清空整个库、队列或Redis。成功时自动清理，因此适合重复基线验证。

## 能写进面试材料的结论

“使用JMeter进行本地100并发、1000个独立用户请求的突发测试，接口受理约329次/秒、P95 185ms，1000笔订单最终全部落库，库存一致、无重复买家。识别出异步出站批量和同步确认对持续吞吐的限制。”

不能写“峰值1500QPS已验证”“持续吞吐329TPS”或“高可用已验证”。下一步应使用独立压测机、更多独立用户、固定时长及重复轮次，增加主机资源和队列积压采样；优化后在相同条件复测。

JMeter配置与CLI原则参考[官方组件说明](https://jmeter.apache.org/usermanual/component_reference.html)和[官方最佳实践](https://jmeter.apache.org/usermanual/best-practices.html)。
