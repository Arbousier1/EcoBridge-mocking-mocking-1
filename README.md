# EcoBridge

EcoBridge 是一个用于 Minecraft 服务器经济系统的插件，目标是接管并增强 UltimateShop 的定价与风控流程。

## 技术架构

- Java 25：平台集成、事件监听、服务编排
- Rust Native Core：核心数学计算与风控执行
- UltimateShop 兼容层：读取商品交易上下文并应用动态价格

## 主要能力

- 动态价格计算（供需 + 环境因子 + 行为因子）
- 宏观调控（Predictive + Fuzzy + Sink/Faucet）
- 玩家侧配额与卖出衰减策略
- 风险审计与降级保护

## i18n 与编码

项目已统一为 UTF-8（无 BOM），并引入国际化资源包：

- `ecobridge-java/src/main/resources/i18n/messages.properties`（英文）
- `ecobridge-java/src/main/resources/i18n/messages_zh_CN.properties`（中文）

配置语言：

```yaml
i18n:
  locale: "zh-CN"   # 可选: zh-CN / en-US
```

运行时会根据该配置加载文案，`/ecoadmin reload` 后会重新读取语言设置。

## 构建

```bash
cd ecobridge-java
./gradlew build
```

## 开发说明

- 入口主类：`top.ellan.ecobridge.EcoBridge`
- 启动分层：`application/bootstrap/*Lifecycle`
- 平台适配：`integration/platform/**`
- 原生桥接：`infrastructure/ffi/**`
