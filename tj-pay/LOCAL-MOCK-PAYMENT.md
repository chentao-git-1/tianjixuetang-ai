# 天机学堂 AI 版：支付启动修复与本地 Mock 支付报告

## 1. 报错原因

原 `WxConfiguration.certificatesManager()` 调用微信 SDK 的 `putMerchant()` 注册商户时会下载平台证书。教程商户号、私钥和证书序列号不可用，微信返回 `401 / SIGN_ERROR`，导致证书管理器创建失败，再经 `NotifyServiceImpl → NotifyController` 使整个 ApplicationContext 启动失败。

工作区已有的条件装配使用 `pay.wx.enabled`，而实际配置和属性类使用 `tj.pay.wx`。此外，只关闭配置类仍不能解决其他 Bean 的强制注入。

## 2. 扫描发现的依赖和调用链

- 微信：`WxConfiguration` 创建 `CertificatesManager`、`CloseableHttpClient`；`WxPayClient` 强制注入 HTTP 客户端；`WxPayService` 强制注入客户端；`NotifyServiceImpl` 强制注入证书管理器和微信属性。
- 属性注册：原微信属性依赖被禁用的配置类上的 `EnableConfigurationProperties`，禁用时属性本身也不会注册。现在微信、支付宝属性由独立 `PayConfiguration` 始终注册。
- 支付宝：`AliPayConfiguration` 原先无条件调用静态 `Factory.setOptions()`；`AliPayService` 无条件存在。现在 SDK 配置、渠道实现均受开关控制，服务显式依赖 SDK 配置，保证初始化顺序。
- 通知：真实通知保留微信 SDK 验签/解密和支付宝 `verifyNotify()`。禁用渠道会拒绝请求，不会把模拟请求当作真实平台回调。
- 支付/退款：原 `Map<String, IPayService>` 使用强制 `Resource` 注入。现在允许零个渠道启动，渠道不存在时给出业务错误；查询任务不会因暂时禁用渠道而关闭历史支付/退款单。
- 下单：交易服务查询课程业务订单，使用 `order.realAmount` 和 `order.userId` 构造 `PayApplyDTO`，通过 `PayClient` 调用 `POST /pay-orders`。支付服务保存 `pay_order`，以 `biz_order_no` 关联业务订单，以 `pay_order_no` 标识渠道交易。
- 支付成功：原 `checkNotifyData()` 中的独立业务校验已提取至公共 `PaymentSuccessService`。真实回调、状态查询补偿和 Mock 确认共用金额校验、渠道校验、条件更新及 MQ 通知。原 `markPayOrderSuccess()` 接口保留，并委托同一状态更新逻辑。
- 退款：经 `refund_order`、现有渠道 SDK 和微信退款回调/查询任务处理。Mock 会明确拒绝退款，不创建或发送虚假的退款成功结果。
- Mapper：三个 Mapper 均继承 MyBatis-Plus `BaseMapper`，对应 XML 无自定义 SQL。本次沿用现有实体字段和枚举，不增加表或支付状态。
- 消息：成功消息沿用 `PayResultDTO`、Exchange `pay.topic`、Routing Key `pay.success`，由 `trade.pay.success.queue` 的 `PayMessageHandler` 消费。
- 消费端：交易服务将订单更新为 `OrderStatus.PAYED=2`，更新条目后继续发送 `order.topic / order.pay` 报名消息。现在仅允许 `NO_PAY=1` 条件转为已支付，重复消息或查询补偿不会重复处理条目和报名通知。
- 状态：支付状态以代码枚举为准：`0` 未提交、`1` 待支付、`2` 已关闭、`3` 支付成功；业务订单的已支付状态则为 `2`。现有实体部分注释与枚举不一致，勿据旧注释判断 SQL 状态。
- 基础设施：Nacos 提供配置和发现；MySQL 保存支付/退款单；Redis/Redisson 提供现有申请和通知锁；RabbitMQ 通知交易服务；XXL-Job 调度支付/退款查询。支付服务本身没有新增对课程/交易服务的反向调用。
- 当前工作区未包含前端源码和数据库初始化 SQL。渠道编码校验接受 `mockPay`；后端显示枚举已补充；Mock 渠道通过现有渠道 DTO 动态返回，无需修改数据库渠道字典。

## 3. 修改文件及原因

下表路径均相对于工作区根目录。

| 文件 | 原因 |
| --- | --- |
| `tj-pay/tj-pay-service/src/main/java/com/tianji/pay/config/PayConfiguration.java` | 独立注册真实渠道属性，解决禁用配置后属性 Bean 缺失 |
| `tj-pay/tj-pay-service/src/main/java/com/tianji/pay/third/wx/config/WxConfiguration.java` | 修正前缀，条件创建证书/HTTP 客户端，缺凭证时先失败，Mock 时不初始化 |
| `tj-pay/tj-pay-service/src/main/java/com/tianji/pay/third/wx/config/WxPayProperties.java` | 增加微信启用属性 |
| `tj-pay/tj-pay-service/src/main/java/com/tianji/pay/third/wx/WxPayClient.java` | 与微信配置使用相同装配条件 |
| `tj-pay/tj-pay-service/src/main/java/com/tianji/pay/third/wx/WxPayService.java` | 禁用时不注册微信支付渠道 |
| `tj-pay/tj-pay-service/src/main/java/com/tianji/pay/third/ali/config/AliPayConfiguration.java` | 条件初始化支付宝 SDK，检查必要凭证 |
| `tj-pay/tj-pay-service/src/main/java/com/tianji/pay/third/ali/config/AliPayProperties.java` | 增加支付宝启用属性 |
| `tj-pay/tj-pay-service/src/main/java/com/tianji/pay/third/ali/AliPayService.java` | 条件注册渠道并显式依赖已初始化 SDK 配置 |
| `tj-pay/tj-pay-service/src/main/java/com/tianji/pay/service/impl/NotifyServiceImpl.java` | 使用可选 SDK Provider，拒绝禁用渠道回调，复用公共成功处理，保留真实验签 |
| `tj-pay/tj-pay-service/src/main/java/com/tianji/pay/service/PaymentSuccessService.java` | 提取公共业务校验、条件更新和通知；公共方法使通知锁可经 Spring AOP 生效；同步 MQ 异常回滚数据库事务 |
| `tj-pay/tj-pay-service/src/main/java/com/tianji/pay/service/impl/PayOrderServiceImpl.java` | 支持空渠道集合，校验申请金额/用户一致性，条件切换渠道和关闭支付单，避免覆盖成功状态；成功查询补齐 DTO 状态和业务订单号 |
| `tj-pay/tj-pay-service/src/main/java/com/tianji/pay/service/impl/RefundOrderServiceImpl.java` | 支持空渠道集合，拒绝 Mock 退款，禁用真实渠道时保留历史退款单 |
| `tj-pay/tj-pay-service/src/main/java/com/tianji/pay/controller/PayChannelController.java` | 仅返回可用渠道，在 local 进程动态添加 Mock 渠道，不写共享字典 |
| `tj-pay/tj-pay-service/src/main/java/com/tianji/pay/controller/PayOrderController.java` | 启用已有 DTO 的参数校验 |
| `tj-pay/tj-pay-service/src/main/java/com/tianji/pay/controller/MockPayController.java` | 增加仅接收已有 Mock 支付单号的模拟确认入口 |
| `tj-pay/tj-pay-service/src/main/java/com/tianji/pay/third/mock/LocalMockPayment.java` | 统一 Mock 组件条件装配注解 |
| `tj-pay/tj-pay-service/src/main/java/com/tianji/pay/third/mock/LocalMockPaymentCondition.java` | 只有单独启用 local 才注册 Mock；混合 Profile 也拒绝注册 |
| `tj-pay/tj-pay-service/src/main/java/com/tianji/pay/third/mock/MockPayConfiguration.java` | 注册最先执行的访问过滤器，拒绝真实支付和 Mock 同时启用 |
| `tj-pay/tj-pay-service/src/main/java/com/tianji/pay/third/mock/MockPayProperties.java` | 绑定 Mock 开关和开发令牌，令牌不参与 toString 输出 |
| `tj-pay/tj-pay-service/src/main/java/com/tianji/pay/third/mock/MockPayAccessFilter.java` | 检查真实连接回环地址、拒绝转发头、常量时间比较至少 32 字符令牌 |
| `tj-pay/tj-pay-service/src/main/java/com/tianji/pay/third/mock/MockPayService.java` | 实现现有支付抽象；生成本地支付 URI、从数据库查询状态，不调用第三方网络 |
| `tj-pay/tj-pay-domain/src/main/java/com/tianji/pay/sdk/constants/PayConstants.java` | 增加 `mockPay` 渠道常量 |
| `tj-pay/tj-pay-domain/src/main/java/com/tianji/pay/sdk/constants/PayChannel.java` | 增加 Mock 渠道的后端显示名称 |
| `tj-pay/tj-pay-domain/src/main/java/com/tianji/pay/sdk/dto/PayApplyDTO.java` | 支付金额增加非空校验 |
| `tj-pay/tj-pay-service/src/main/resources/application.yml` | 默认关闭真实渠道；移除应用配置内的教程密钥，改为环境变量占位符 |
| `tj-pay/tj-pay-service/src/main/resources/application-local.yml` | 将本地支付配置作为最后一个导入 |
| `tj-pay/tj-pay-service/src/main/resources/pay-local.yml` | 本地默认 Mock、真实渠道关闭、绑定回环地址和匹配的发现 IP |
| `tj-trade/src/main/java/com/tianji/trade/service/impl/OrderServiceImpl.java` | 成功消费校验及订单条件更新，避免重复改业务订单和发送报名消息 |
| `tj-trade/src/main/java/com/tianji/trade/service/impl/PayServiceImpl.java` | 未支付查询结果重新投递后返回，修复原先继续落入成功处理的缺陷 |
| `tj-pay/tj-pay-service/pom.xml` | 仅测试范围增加 H2，验证真实 Mapper 与事务，不替换运行时 MySQL |
| `tj-pay/tj-pay-service/src/test/java/com/tianji/pay/PaymentFlowTest.java` | 隔离数据库验证下单、确认、重复/并发、金额/渠道/状态和发送异常回滚 |
| `tj-pay/tj-pay-service/src/test/java/com/tianji/pay/PaymentWiringTest.java` | 验证 SDK 开关、属性注册、零渠道启动、环境隔离及配置导入优先级 |
| `tj-pay/tj-pay-service/src/test/java/com/tianji/pay/MockPayAccessTest.java` | 验证回环、令牌、转发头访问限制 |
| `tj-pay/tj-pay-service/src/test/java/com/tianji/pay/AliPayTest.java` | 真实 SDK 示例测试改为显式开启才执行，避免默认测试访问真实支付接口 |
| `tj-pay/tj-pay-service/src/test/java/com/tianji/pay/WxPayTest.java` | 同上 |
| `tj-pay/tj-pay-service/src/test/resources/mock-config-test.yml` | 配置优先级测试入口 |
| `tj-pay/tj-pay-service/src/test/resources/mock-remote-test.yml` | 模拟远端开关配置的隔离夹具 |
| `tj-trade/src/test/java/com/tianji/trade/PaySuccessIdempotencyTest.java` | 验证重复消费和未支付查询不会进入成功处理 |
| `tj-pay/LOCAL-MOCK-PAYMENT.md` | 本报告和可复现的本地操作说明 |

没有执行 Git commit、push、部署，也没有修改 Nacos 配置。

## 4. 配置项与配置来源

最终复用前缀 `tj.pay`，不要再使用 `pay.wx.enabled`。

```yaml
tj:
  pay:
    wx:
      enabled: false
    ali:
      enabled: false
    mock:
      enabled: true
      token: ${TJ_PAY_MOCK_TOKEN:}
```

真实渠道和 Mock 的全局默认均为 false；local 最后导入的 `pay-local.yml` 将 Mock 设为 true、真实渠道设为 false。Mock 开启但令牌为空或不足 32 字符时服务仍可启动，模拟接口返回 403。

local 默认 `server.address=127.0.0.1`、`server.forward-headers-strategy=none`、`spring.cloud.nacos.discovery.ip=127.0.0.1`。因此 local 的交易服务、网关和支付服务应在同一台机器。VM/容器调用方不能通过自己的 127.0.0.1 访问宿主支付服务；需要按实际网络调整正常服务地址，模拟确认入口仍要求本机直连和令牌。

Nacos 信息：

- 服务 Data ID：`pay-service.yaml`，Group：`DEFAULT_GROUP`。
- 共享 Data ID：`shared-spring.yaml`、`shared-redis.yaml`、`shared-mybatis.yaml`、`shared-logs.yaml`、`shared-feign.yaml`、`shared-mq.yaml`、`shared-xxljob.yaml`。
- 当前代码仅为 discovery 指定 namespace `f923fb34-cb0a-4c06-8fca-ad61ea61a3f0`；没有设置 `spring.cloud.nacos.config.namespace`，配置读取使用 Nacos 默认命名空间。不要将 discovery namespace 当成配置 namespace。
- 本次只读检查到 `pay-service.yaml` 内已有 `tj.pay.wx.enabled=false`，没有覆盖远端配置。
- 配置导入优先级：导入值高于声明该导入的文件，同一导入列表中后面的来源优先；本地支付配置最后导入，覆盖同一列表前面的 Nacos 开关。命令行、系统属性、环境变量仍可覆盖文件值。参见 [Spring Boot 3.3 配置说明](https://docs.spring.io/spring-boot/3.3/reference/features/external-config.html)。本次测试验证了最后本地导入覆盖远端支付开关。

恢复真实支付时：设置 `tj.pay.mock.enabled=false`，按需将 `tj.pay.wx.enabled` / `tj.pay.ali.enabled` 设置为 true，并提供真实商户凭证和 `tj.pay.notifyHost`。使用 local 时应通过命令行/环境变量覆盖最后导入的本地开关；dev/test 可在专用命名空间的 `pay-service.yaml` 配置。不要改共享环境或共享 Data ID。

微信参数仍为 `appId`、`mchId`、`mchSerialNo`、`privateKey`、`apiV3Key`；支付宝参数仍为 `appId`、`merchantPrivateKey`、`publicKey`，保留协议、网关、签名算法等现有属性。应用默认配置提供 `TJ_PAY_WX_APP_ID`、`TJ_PAY_WX_MCH_ID`、`TJ_PAY_WX_MCH_SERIAL_NO`、`TJ_PAY_WX_PRIVATE_KEY`、`TJ_PAY_WX_API_V3_KEY`、`TJ_PAY_ALI_APP_ID`、`TJ_PAY_ALI_MERCHANT_PRIVATE_KEY`、`TJ_PAY_ALI_PUBLIC_KEY`、`TJ_PAY_NOTIFY_HOST` 占位符。

## 5. Mock 完整流程

1. 用户通过现有课程购买流程创建业务订单。交易服务从数据库读取金额和用户，构造现有支付申请 DTO。
2. 选择 `mockPay`。支付服务走原申请逻辑保存 `pay_order`，生成真实 `pay_order_no`，返回 `mockpay://pay-orders/{payOrderNo}`。该 URI 用于保持原 String/二维码协议，不是浏览器确认链接，也不包含开发令牌。
3. 开发者从本机向模拟确认入口 POST，仅传支付单号及开发令牌。接口不接受成功金额、业务订单号或成功时间输入。
4. 服务查找该 Mock 支付单，从数据库读取金额；公共成功服务再次校验单号、正数金额、渠道、待支付状态及超时。不存在、关闭、未就绪、超时或其他渠道的单据不能成功。
5. 使用原 `pay:notify:payOrderNo:*` 锁以及数据库条件更新，转为 `TRADE_SUCCESS=3`，记录成功时间和原通知状态。已成功的重复请求只返回已保存结果，不再次发送消息。
6. 使用现有 MQ helper 发送 `PayResultDTO(status=3, bizOrderId=bizOrderNo, payOrderNo, payChannel=mockPay, successTime)` 至 `pay.topic / pay.success`。
7. 交易服务现有监听器消费；订单从 `NO_PAY=1` 条件转为 `PAYED=2`，更新条目，继续发送现有报名消息。MQ 重投递和支付查询补偿受同一业务订单条件更新保护。

Mock 退款明确不支持。现有退款实现缺少累计已退款金额校验，并存在私有方法上的锁和退款查询结果问题；在这些问题修复之前，自动模拟退款成功会掩盖超额退款风险。

## 6. 本地启动步骤

使用 JDK 17。当前机器可用的路径：

- JDK：`C:\Users\ct666\.jdks\ms-17.0.19`
- Maven：`C:\D\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd`

在工作区根目录 PowerShell 执行：

```powershell
$env:JAVA_HOME = 'C:/Users/ct666/.jdks/ms-17.0.19'
$env:TJ_PAY_MOCK_TOKEN = [Guid]::NewGuid().ToString('N')
& 'C:/D/IntelliJ IDEA 2026.1.3/plugins/maven/lib/maven3/bin/mvn.cmd' -B -ntp -pl tj-pay/tj-pay-service,tj-trade -am -DskipTests package
& "$env:JAVA_HOME/bin/java.exe" -jar tj-pay/tj-pay-service/target/tj-pay-service.jar --spring.profiles.active=local
```

IDEA 启动 `com.tianji.PayApplication` 时设置 Java 17、Profile `local`，并在运行配置环境变量中设置 `TJ_PAY_MOCK_TOKEN`。令牌不要提交到仓库；Postman 中使用相同值。

启动前保证当前 Nacos 导入可读取，MySQL `tj_pay` 库和原有表已存在，Redis/Redisson、RabbitMQ 的连接参数和认证正确。完整课程流程还需要交易、课程及现有认证/网关等相关服务，并保证交易延迟交换机所需的 RabbitMQ delayed-message 插件可用。

交易服务必须重新启动以加载本次消费者幂等和查询补偿修复；仅重启支付服务不会更新已经运行的交易服务代码。

## 7. Postman / Knife4j 使用方法

1. 按原项目认证流程登录，在课程业务 API 创建测试订单。现有交易服务接口为 `GET /orders/prePlaceOrder?courseIds=...` 和 `POST /orders/placeOrder`；请求体使用原 `PlaceOrderDTO`，包含生成的 `orderId`、`courseIds` 和可选 `couponIds`。经网关时使用对应交易路由前缀 `/ts`。
2. 调用交易服务 `GET /pay/channels`，本地应看到 `mockPay`。支付服务内部渠道列表仍为 `GET /pay-channels/list`。
3. 调用交易服务 `POST /pay/order`（经网关为 `/ts/pay/order`），使用原请求结构：

   ```json
   {"orderId": "已有测试课程订单号", "payChannelCode": "mockPay"}
   ```

   不要让普通客户端直接调用支付服务内部 `POST /pay-orders` 来决定业务金额。金额由交易服务读取业务订单后生成。

4. 从返回的 `mockpay://pay-orders/210...` 中取得支付单号。开发者在本机 Postman 调用：

   ```http
   POST http://127.0.0.1:8087/mock-pay/{payOrderNo}/success
   X-Mock-Pay-Token: 与启动配置相同的至少32字符令牌
   ```

   无需请求体，不接受自定义成功金额/业务订单号。不要经过网关或代理调用，不添加 `Forwarded`、`X-Forwarded-For`、`X-Real-IP`。

5. Knife4j 地址通常为 `http://127.0.0.1:8087/doc.html`；选择“本机模拟支付（仅local）”，填写支付单号和 `X-Mock-Pay-Token`。若共享配置更改了文档路径/开启状态，以实际 Swagger 配置为准。
6. 使用原查询接口确认结果：支付服务 `GET /pay-orders/{bizOrderId}/status`；交易服务 `GET /orders/{id}/status`。支付成功状态为 3，业务订单已支付状态为 2。
7. 重复确认同一支付单会返回已有结果；无令牌、错误令牌、非回环或代理请求返回 403；非 local 环境没有此路由。

需要手工核对时只读查询原表：

```sql
SELECT biz_order_no, pay_order_no, amount, pay_channel_code, status, pay_success_time
FROM tj_pay.pay_order WHERE pay_order_no = /* 本次支付单号 */ 0;
```

RabbitMQ 核对 `pay.topic → pay.success → trade.pay.success.queue`。业务订单表对应 `com.tianji.trade.domain.po.Order`；请按实际交易库配置查询该订单，勿使用支付单号代替业务订单号。

## 8. 编译和验证结果

- 使用 JDK `17.0.19` 和 Maven `3.9.11`，`tj-pay-service`、`tj-trade` 及所需依赖完成编译和可执行 JAR 打包，`BUILD SUCCESS`。
- 已执行的隔离验证共 14 项，全部通过：支付流程 5 项、Bean/配置装配 6 项、访问控制 1 项、交易端幂等/延迟查询 2 项。
- H2 验证执行了真实 MyBatis-Plus Mapper 和数据库事务，RabbitMQ helper 使用测试替身。证明了支付单状态变化、条件更新、并发只发送一次、同步 MQ 异常回滚以及生产者消息字段兼容；不是实际 MySQL/RabbitMQ 端到端验证。
- Bean 测试确认关闭真实渠道时没有 `CertificatesManager`、微信 HTTP 客户端、微信服务或支付宝 SDK 配置；缺少凭证时真实渠道在调用网络前失败。
- 验证 local 开启 Mock、其他/混合 Profile 不注册 Mock、真实和模拟开关冲突时拒绝启动、本地最后导入覆盖远端开关。
- 已只读确认当前开发环境 `192.168.150.101` 的 Nacos 8848、MySQL 3306、Redis 6379、RabbitMQ 5672 端口可达，并确认 Nacos 配置可读取。这不能证明所有数据库表和消息消费链路均可用。
- 完整 `PayApplication` 已于本次验证中使用 local 成功启动：日志确认 `Tomcat started on port 8087` 和 `Started PayApplication in 7.587 seconds`。连接现有开发 Nacos 配置、Redis 等依赖，未出现微信证书初始化失败。此次临时启动通过参数关闭 Nacos 服务注册和 XXL-Job 管理端注册，检查结束后已停止，不影响现有服务发现。MySQL 连接及 RabbitMQ 实际业务投递未通过真实订单请求验证。
- 用户后续明确表示不需要真实订单测试，因此没有修改任何真实课程业务订单或执行实际支付成功联调。

复现已执行的隔离验证：

```powershell
& 'C:/D/IntelliJ IDEA 2026.1.3/plugins/maven/lib/maven3/bin/mvn.cmd' -B -ntp -pl tj-pay/tj-pay-service,tj-trade -am '-Dtest=PaymentFlowTest,PaymentWiringTest,MockPayAccessTest,PaySuccessIdempotencyTest' '-Dsurefire.failIfNoSpecifiedTests=false' package
```

构建记录在 `target/mock-build.log`；启动记录在 `target/pay-startup.log`；测试报告在各模块 `target/surefire-reports`。

## 9. 未完成事项和风险

- 未进行真实商户 SDK 调用或有效真实回调联调：没有商户资质。原 SDK、验签与解密路径均保留，没有添加跳过验签的逻辑。
- 未按用户最新指示进行真实课程订单端到端联调；MySQL 实际业务数据变更、真实 RabbitMQ 消费、后续课程报名链路需要用户按上述步骤执行。
- Mock 不支持退款，不伪造退款成功。
- 数据库和 RabbitMQ 仍是两个独立系统。公共成功服务的事务会在同步发送异常时回滚，但现有 `RabbitMqHelper.send()` 不等待 broker confirm，也没有持久化 outbox：异步投递失败、数据库提交失败或进程崩溃时不能承诺消息绝不丢失或严格 exactly-once。支付/交易条件更新解决正常重复请求、并发以及重复消费，不等价于跨系统原子提交。可靠通知的进一步改造应单独设计。
- 原退款流程仍存在累计金额校验缺失、私有 `@Lock` 自调用不经过 AOP、支付宝退款查询成功判断可疑，以及退款查询任务无论实际结果均构造成功 DTO 等设计缺陷。本次只隔离禁用渠道和拒绝 Mock 退款，未做无关退款重构。
- 申请支付的原 Redis 锁仍是固定 3 秒租期且不自动解锁；数据库是否有 `biz_order_no`/`pay_order_no` 唯一索引需在实际库确认。长时间真实 SDK 调用的首次创建并发问题仍受原设计限制。
- 现有交易支付申请未在该方法内校验当前登录用户与订单归属，支付内部 API 的信任边界仍依赖网关和服务网络；本次模拟成功端点有额外回环与开发令牌保护。业务权限与内部 API 认证的全面治理未扩展到本次修复。
- 历史真实 SDK 示例测试中仍有原教程配置；默认已禁用，只有 `-DrunRealPayTests=true` 才运行。实际使用前应替换为个人真实商户配置，勿把教程凭证当作可用凭证。
- 前端源码未在本工作区，动态渠道 DTO 和扫码返回类型保持兼容，但前端若硬编码只有微信/支付宝，需要在相应前端仓库补充 Mock 展示。
