# NeoLife · 黑马点评与 DeepSeek 智能客服

Java 8 / Spring Boot 2.3.12 / MyBatis-Plus / MySQL / Redis / Redisson 学习项目。

- [黑马点评知识总结与完成度对照](docs/黑马点评面试总结.md)
- [智能客服运行、设计与面试指南](docs/智能客服README.md)

新增 POST /customer-agent/chat，只读查询商户、本人订单和平台 FAQ。配置 DEEPSEEK_API_KEY 后运行 mvn spring-boot:run，携带现有登录 token 调用。数据库初始化脚本：src/main/resources/db/hmdp.sql。

2026-10-13已补齐讲义最终业务代码：Stream异步秒杀、点赞排行、共同关注、Feed、GEO、签到与UV，并增加签到和订单前端入口。

- [功能完善工作说明与运行验收](docs/黑马点评功能完善工作说明.md)

没有完整支付退款流程；真实DeepSeek调用仍需有效密钥、额度和可用网络。已有数据库不要重导包含DROP TABLE的初始化SQL，使用工作说明中的增量升级脚本。

2026-10-13：DeepSeek真实客服已接通并通过两轮问答、工具调用和Nginx真实入口验证。当前新版后端运行于8083，网页入口仍为http://localhost:8080/customer-service.html。密钥配置文件config/application.properties已被Git忽略；详见客服README开头的运行说明。
