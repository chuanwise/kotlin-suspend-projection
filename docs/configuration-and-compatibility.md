# 配置与兼容性

## 完整 Gradle DSL

所有配置都是普通 Kotlin 属性，使用 `property = value`：

```kotlin
import cn.chuanwise.kotlinsuspendprojection.gradle.*

suspendProjection {
    enabled = true

    selection { mode = SelectionMode.ALL }
    generatedTypes {
        layout = GeneratedTypeLayout.NESTED_NAMESPACE
        namespace = "Projections"
    }

    jvm {
        rawSuspendAbi = RawSuspendAbi.HIDDEN
        sameNameCaller = JvmProjection.NONE

        directImplementation {
            projection = JvmProjection.NONE
            enforcement = DirectImplementationEnforcement.GUARDED_DEFAULT
            uninstrumentedKotlin = UninstrumentedKotlin.WARNING
        }
        blocking {
            exports {
                enabled = true
                emitNamedCaller = true
            }
            imports {
                enabled = true
                execution = BlockingExecution.DIRECT
                interruption = BlockingInterruption.THROW_CHECKED
            }
        }
        runtimeGuards {
            compatibility = true
            invalidPath = true
        }
    }

    dependencies { automatic = true }
    verification { enabled = true }
}
```

## 配置分组

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `enabled` | `true` | 是否对 JVM compilation 应用编译器插件 |
| `selection.mode` | `ALL` | 处理全部合格成员，或只处理 `@SuspendProjection` 声明 |
| `generatedTypes.layout` | `NESTED_NAMESPACE` | 生成类型布局 |
| `generatedTypes.namespace` | `Projections` | 嵌套 namespace 类型名 |
| `jvm.rawSuspendAbi` | `HIDDEN` | 是否用 `@JvmSynthetic` 对 Java 源码隐藏 lowered suspend ABI |
| `jvm.sameNameCaller` | `NONE` | 是否生成同名 Java caller |
| `jvm.directImplementation.projection` | `NONE` | Java 是否可直接实现 canonical 接口的同步投影 |
| `jvm.directImplementation.enforcement` | `GUARDED_DEFAULT` | 使用运行时守卫默认桥或 strict 抽象契约 |
| `jvm.directImplementation.uninstrumentedKotlin` | `WARNING` | 无插件 Kotlin 子类的 opt-in 级别 |
| `jvm.blocking.exports.enabled` | `true` | suspend 实现是否导出 Blocking caller |
| `jvm.blocking.exports.emitNamedCaller` | `true` | 是否生成 `fooBlocking` caller |
| `jvm.blocking.imports.enabled` | `true` | 是否生成 `ViaBlocking` 实现契约 |
| `jvm.runtimeGuards.compatibility` | `true` | 是否写入生成代码版本检查 |
| `jvm.runtimeGuards.invalidPath` | `true` | 是否写入无效递归路径检查 |
| `dependencies.automatic` | `true` | 是否自动加入 annotations/runtime 依赖 |
| `verification.enabled` | `true` | `verifySuspendProjections` 是否实际运行 |

## 注解选择

`SelectionMode.ANNOTATED` 支持类级和函数级选择：

```kotlin
@SuspendProjection
interface EntireApi {
    suspend fun first()
    suspend fun second()
}

interface PartialApi {
    @SuspendProjection
    suspend fun projected()

    suspend fun untouched()
}
```

注解的完整名称为 `cn.chuanwise.kotlinsuspendprojection.annotations.SuspendProjection`，保留级别为 `BINARY`。

## 主投影预设

```kotlin
suspendProjection {
    primary(JvmProjection.BLOCKING)
}
```

等价于启用 Blocking 同名 caller、Blocking direct implementation 和 Blocking imports，并关闭额外的命名 caller。其他细节仍可在预设之后覆盖。

## 当前实现边界

当前版本完整实现：

- `GeneratedTypeLayout.NESTED_NAMESPACE`；
- `JvmProjection.NONE` 与 `JvmProjection.BLOCKING` 策略；
- `BlockingExecution.DIRECT`；
- `BlockingInterruption.THROW_CHECKED`。

其他枚举值是后续 ABI 的保留名称。选择尚未实现的值会在 Gradle 配置阶段报错，避免配置看似生效但产物没有变化。

## 自动依赖与验证

关闭 `dependencies.automatic` 时，项目必须自行提供版本一致的：

```text
cn.chuanwise.kotlinsuspendprojection:annotations
cn.chuanwise.kotlinsuspendprojection:runtime-core
cn.chuanwise.kotlinsuspendprojection:runtime-jvm
```

插件注册 `verifySuspendProjections` 并接入 `check`。任务检查 `@SuspendProjectionMeta` schema、generated-code version 和 strict 实现契约。仅关闭 runtime guard 不会关闭产物验证：

```kotlin
suspendProjection {
    verification { enabled = false }
}
```

## Runtime 系统属性

以下属性值为 `false`（忽略大小写）时关闭对应检查：

| JVM 属性 | 作用 |
| --- | --- |
| `kotlin.suspend.projection.runtime.guards` | 关闭所有 runtime guard |
| `kotlin.suspend.projection.runtime.compatibility` | 仅关闭版本兼容性检查 |
| `kotlin.suspend.projection.runtime.invalidPath` | 仅关闭无效路径检查 |

编译配置决定生成代码是否包含检查；系统属性用于部署时临时关闭已经生成的检查。

## 兼容性边界

| 项目 | 当前状态 |
| --- | --- |
| Kotlin | `2.4.20`，K2 |
| JVM target | Java 8 |
| JVM default mode | `NO_COMPATIBILITY` |
| generated-code version | `1` |
| metadata schema | `1` |

runtime 通过 `SuspendProjectionGeneratedCode.MIN_SUPPORTED_VERSION` 与 `MAX_SUPPORTED_VERSION` 声明可执行的生成代码范围，不要求 generator 与 runtime 的发布字符串完全相同。`@SuspendProjectionMeta` 保留更多可观察信息，但运行时语义不依赖反射读取它。
