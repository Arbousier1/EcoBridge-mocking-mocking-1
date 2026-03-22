# EcoBridge

EcoBridge 是一个面向 Minecraft 服务器的经济内核插件，用于接管/增强 UltimateShop 的定价与风控逻辑。  
项目采用 **Java 25 + Rust Native Core** 混合架构：

- Java 侧负责 Bukkit 事件接入、配置、策略编排。
- Rust 侧负责核心数学计算（价格、供需核、环境因子、风控与税收）。

## 核心目标

- 用可解释的数学模型替代静态价格。
- 在“卖多买少”的真实服务器环境下，避免价格长期塌陷。
- 将宏观调控做成平滑控制，而不是生硬跳变。

## 经济算法总览

EcoBridge 的价格计算由四层组成：

1. 供需核 `n_eff`（按商品/商店键）
2. 环境因子 `epsilon`
3. 行为价格公式（Rust）
4. 恢复护栏（Java）

最终买入价由宏观快照给出；卖出价在此基础上再乘卖出比率与玩家侧策略因子。

---

## 1) 供需核 `n_eff`

### 方向定义

- 玩家 **买入**：记为负量（`-amount`，消耗供给）
- 玩家 **卖出**：记为正量（`+amount`，增加供给）

这意味着：

- `n_eff > 0`：卖压更强，价格倾向下行
- `n_eff < 0`：买压更强，价格倾向上行

### 时间衰减聚合

每个成交量会按指数衰减进入有效供需量：

```text
n_eff(t) = Σ amount_i * exp(-(t - t_i) / tau)
```

- `tau` 越大，历史影响越久，价格更稳但更“迟钝”
- `tau` 越小，价格反应更快但更敏感

实现位置：`ecobridge-rust/src/economy/summation.rs`

---

## 2) 环境因子 `epsilon`

`epsilon` 用于模拟非纯供需因素，包含：

- 季节/周期波动（day/week/month wave）
- 周末因子
- 新手保护（随在线时长衰减）
- 通胀反馈因子

最终通过对数加权几何合成：

```text
epsilon = exp(
  w_seasonal * ln(f_seasonal) +
  w_weekend  * ln(f_weekend)  +
  w_newbie   * ln(f_newbie)   +
  w_inflation* ln(f_inflation)
)
```

实现位置：`ecobridge-rust/src/economy/environment.rs`

---

## 3) 行为价格公式（Rust 核）

核心价格模型（简化）：

```text
P = base * epsilon * exp( clamp_tanh( -adj_lambda * (n_eff + trade_amount) ) )
```

其中：

- `adj_lambda` 为行为敏感度
- 卖出时 `adj_lambda = lambda * 0.6`，下行更粘（防止被瞬间砸穿）
- 买入时 `adj_lambda = lambda`

实现位置：`ecobridge-rust/src/economy/pricing.rs`

---

## 4) 恢复护栏（防止长期塌陷）

即使卖盘长期偏强，系统也会启用恢复护栏：

- 价格低于 `history_avg * floor_ratio` 时硬托底
- 当价格低于激活阈值，按步进向 `history_avg * target_ratio` 拉回
- 卖压越强，回升越慢（阻尼），但不会无限下坠

关键参数：

- `economy.recovery.floor-ratio-to-history`
- `economy.recovery.activation-ratio-to-history`
- `economy.recovery.target-ratio-to-history`
- `economy.recovery.strength`
- `economy.recovery.max-step-per-cycle`

实现位置：`ecobridge-java/src/main/java/top/ellan/ecobridge/domain/algorithm/PriceComputeEngine.java`

---

## 宏观控制（已替代 PID）

宏观控制器采用 **Predictive + Fuzzy + Sink/Faucet**：

- 预测未来货币量：`predictedM1 = M1 + (faucetRate - sinkRate) * horizon`
- 基于模糊规则输出 `lambdaMultiplier`
- 通过调节 `lambda` 实现“温和干预”，而不是直接硬改价格

实现位置：

- `ecobridge-java/src/main/java/top/ellan/ecobridge/application/service/MacroEngine.java`
- `ecobridge-java/src/main/java/top/ellan/ecobridge/application/control/PredictiveFuzzyFluidController.java`

---

## 玩家侧卖出策略（个体行为层）

卖出价格会在全局卖价基础上再乘个人因子：

- 玩家额度池（quota，支持按在线人数份额化）
- 玩家-商品衰减（短期频繁卖出会递减）
- 周末/节日/特殊商品系数

实现位置：`ecobridge-java/src/main/java/top/ellan/ecobridge/application/service/PlayerMarketPolicyService.java`

---

## 风控与税收（Rust）

转账/交易审计在 Rust 侧执行：

- 动态限额（与在线时长相关）
- 行为速率审计（velocity/活跃度）
- 高风险预警与拦截码
- 自适应税：基础税 + 通胀 + 行为惩罚 + 奢侈税 + 贫富调节税（上限 80%）

实现位置：`ecobridge-rust/src/security/regulator.rs`

---

## 数据流（从成交到价格）

1. UltimateShop 交易事件进入 Java Hook  
2. 成交按 `MARKET_TRADE:<shop.product>` 写入异步日志  
3. Rust 热存储按 market key 聚合更新  
4. 宏观调度器定时计算商品快照价  
5. UltimateShop 价格桥接读取快照价并回写交易结果

---

## 关键配置建议（起步）

- 如果你的服“卖多买少”：优先调高恢复相关参数（`recovery.*`），其次降低 `default-lambda`。
- 如果价格反应太慢：降低 `tau` 或提升 `lambda`。
- 如果价格波动太激烈：增大 `tau`，并降低 `control.lambda.max-multiplier`。

---

## 兼容说明

- 宏观层已切换到 Predictive/Fuzzy 控制，不再依赖旧 PID 决策。
- 项目内仍保留部分 PID 兼容占位接口，主要用于历史兼容与外部占位符读取，不参与当前主控制路径。
