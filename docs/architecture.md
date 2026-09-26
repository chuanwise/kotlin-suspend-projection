# 架构与生成模型

## 核心约束

Kotlin Suspend Projection 遵循以下约束：

1. 原始 Kotlin `suspend` 声明是唯一语义来源。
2. 生成投影必须直接导出或导入 canonical suspend 语义。
3. 已编译库的 Kotlin/Java 消费方不依赖编译器插件。
4. Java 调用入口对 Java 可见，但通过 Kotlin metadata 与 `@JvmSynthetic` 对 Kotlin 源码隐藏。
5. `@SuspendProjectionMeta` 用于描述和验证，不参与运行时语义发现。
6. 不支持的声明不会静默生成近似 ABI。

## 模块

| 模块 | 职责 |
| --- | --- |
| `annotations` | 生成声明 metadata，以及直接实现策略所需的 opt-in 标记 |
| `runtime-core` | 生成代码版本范围、兼容性判断和能力常量 |
| `runtime-jvm` | blocking 等待实现及 JVM runtime guard |
| `compiler-plugin` | K2 FIR/IR 声明生成、桥接方法与 metadata 注入 |
| `verification-jvm` | 使用 ASM 校验生成 metadata 和 strict 实现契约 |
| `gradle-plugin` | 编译器插件接入、依赖配置和验证任务 |

## 生成结构

给定：

```kotlin
interface Repository<T : Any> {
    suspend fun find(id: String): T?
}
```

概念上的生成结构为：

```text
Repository<T>
├── suspend find(String): T?
├── find(String): T?                    Java 同名 blocking 入口
└── Projections
    └── ViaBlocking<T> : Repository<T>
        ├── findBlocking(String): T?     Java 实现契约
        └── suspend find(String): T?     默认导入桥
```

实际 JVM 描述符会遵循 Kotlin 的类型擦除、value class lowering 和 context/extension receiver lowering 规则。

## 两条桥接方向

### Kotlin 实现导出到 Java

同名非 suspend 方法调用 `awaitSuspendProjection`，后者启动 canonical suspend 方法并阻塞等待结果。原始 lowered suspend 方法会标记为 `@JvmSynthetic`，普通 Java 源码只看到适合调用的投影。

基础 runtime 不依赖 `kotlinx.coroutines`。线程中断会终止等待并抛出 `InterruptedException`，但不会取消已经启动的底层协程，因此 capability 记录为 `waiter-interruption-only`。

### Java blocking 实现导入到 Kotlin

Java 类实现 `Foo.Projections.ViaBlocking` 的 `xxxBlocking` 方法。生成的默认 suspend 方法把 canonical 调用转发给 blocking 实现，因此 Kotlin 调用方仍只依赖 `Foo`。

## 直接实现策略

### `GUARDED_DEFAULT`

默认策略。接口上的 canonical suspend 方法和同名 blocking 方法都有互相桥接能力，因此 Java 可以直接实现任意一侧。

如果实现类两侧都没有实现，默认桥会形成递归调用。生成的 path guard 会在同一路径重入时抛出 `InvalidSuspendProjectionPathException`。

### `STRICT`

同名 blocking 投影为抽象契约。编译器为受插件处理的 Kotlin 实现生成桥，artifact verifier 检查具体类是否满足 strict 投影。

`STRICT` 能更早暴露缺失实现，但它改变了公开接口 ABI。库发布后切换策略应视为兼容性变更。

## Metadata

生成方法携带 runtime-visible `@SuspendProjectionMeta`，当前记录：

- metadata schema version；
- generated-code version；
- generator version；
- 稳定的 origin/projection/direction/lineage；
- 策略 flags；
- cancellation capability。

运行时行为不通过反射读取这些注解。所需版本、开关和路径 ID 都作为常量直接传入 runtime API。
