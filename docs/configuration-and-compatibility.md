# 配置与兼容性

## 完整 Gradle DSL

```kotlin
import cn.chuanwise.kotlinsuspendprojection.gradle.*

suspendProjection {
    enable = true
    projections = setOf(JvmProjection.BLOCKING)
    primary = JvmProjection.BLOCKING

    selection { mode = SelectionMode.ANNOTATED }
    generatedTypes {
        layout = GeneratedTypeLayout.NESTED_NAMESPACE
        namespace = "Projections"
    }

    jvm {
        rawSuspendAbi = RawSuspendAbi.HIDDEN
        sameNameCaller = JvmProjection.BLOCKING

        directImplementation {
            projection = JvmProjection.BLOCKING
            enforcement = DirectImplementationEnforcement.STRICT
            uninstrumentedKotlin = UninstrumentedKotlin.ERROR
        }
        blocking {
            exports {
                enable = true
                emitNamedCaller = false
            }
            imports {
                enable = true
                execution = BlockingExecution.DIRECT
                interruption = BlockingInterruption.THROW_CHECKED
            }
        }
        completionStage {
            exports { enable = false; emitNamedCaller = true }
            imports { enable = false }
        }
        completableFuture {
            exports { enable = false; emitNamedCaller = true }
            imports { enable = false }
        }
        future {
            exports { enable = false; emitNamedCaller = true }
            imports { enable = false }
        }
        runtimeGuards {
            compatibility = true
            invalidPath = true
        }
    }

    dependencies { automatic = true }
    verification { enable = true }
}
```

## 配置分组

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `enable` | `true` | 是否对 JVM compilation 应用编译器插件 |
| `projections` | `setOf(BLOCKING)` | 注解未显式指定类型时继承的项目投影集合 |
| `primary` | `BLOCKING` | 同名 caller 与 direct implementation 使用的主要投影 |
| `selection.mode` | `ANNOTATED` | 默认只处理 `@JvmSuspendProjection` 声明，可改为全部合格成员 |
| `generatedTypes.layout` | `NESTED_NAMESPACE` | 生成类型布局 |
| `generatedTypes.namespace` | `Projections` | 嵌套 namespace 类型名 |
| `jvm.rawSuspendAbi` | `HIDDEN` | 是否用 `@JvmSynthetic` 对 Java 源码隐藏 lowered suspend ABI |
| `jvm.sameNameCaller` | `BLOCKING` | 生成同名 Java caller 的投影；通常由 `primary` 设置 |
| `jvm.directImplementation.projection` | `BLOCKING` | Java 直接实现 canonical 接口使用的投影 |
| `jvm.directImplementation.enforcement` | `STRICT` | 使用运行时守卫默认桥或 strict 抽象契约 |
| `jvm.directImplementation.uninstrumentedKotlin` | `ERROR` | 无插件 Kotlin 子类的 opt-in 级别 |
| `jvm.blocking.exports.enable` | `true` | suspend 实现是否导出 Blocking caller |
| `jvm.blocking.exports.emitNamedCaller` | `false` | 是否额外生成 `fooBlocking`；primary 已使用同名 caller |
| `jvm.blocking.imports.enable` | `true` | 是否生成 `ViaBlocking` 实现契约 |
| `jvm.completionStage.exports.enable` | `false` | 是否把 CompletionStage 作为项目默认 export |
| `jvm.completionStage.exports.emitNamedCaller` | `true` | 是否保留 `fooCompletionStage` caller |
| `jvm.completionStage.imports.enable` | `false` | 是否把 CompletionStage 作为项目默认 import |
| `jvm.completableFuture.exports.enable` | `false` | 是否把 CompletableFuture 作为项目默认 export |
| `jvm.completableFuture.exports.emitNamedCaller` | `true` | 是否保留 `fooCompletableFuture` caller |
| `jvm.completableFuture.imports.enable` | `false` | 是否把 CompletableFuture 作为项目默认 import |
| `jvm.future.exports.enable` | `false` | 是否把 Future 作为项目默认 export |
| `jvm.future.exports.emitNamedCaller` | `true` | 是否保留 `fooFuture` caller |
| `jvm.future.imports.enable` | `false` | 是否把 Future 作为项目默认 import |
| `jvm.runtimeGuards.compatibility` | `true` | 是否写入生成代码版本检查 |
| `jvm.runtimeGuards.invalidPath` | `true` | 是否写入无效递归路径检查 |
| `dependencies.automatic` | `true` | 是否自动加入 annotations/runtime 依赖 |
| `verification.enable` | `true` | `verifySuspendProjections` 是否实际运行 |

## 注解选择

`@JvmSuspendProjection` 可标注文件、类或函数，并一次指定多种 projection：

```kotlin
@file:cn.chuanwise.kotlinsuspendprojection.annotations.JvmSuspendProjection(
    cn.chuanwise.kotlinsuspendprojection.annotations.JvmProjectionType.FUTURE,
)

package example
```

```kotlin
import cn.chuanwise.kotlinsuspendprojection.annotations.JvmProjectionType
import cn.chuanwise.kotlinsuspendprojection.annotations.JvmSuspendProjection

@JvmSuspendProjection(
    JvmProjectionType.COMPLETION_STAGE,
    JvmProjectionType.COMPLETABLE_FUTURE,
)
interface EntireApi {
    suspend fun first()

    @JvmSuspendProjection(JvmProjectionType.BLOCKING)
    suspend fun second()
}
```

投影类型优先级为：函数显式列表、类显式列表、文件显式列表、项目 `projections`。空列表只选择声明，类型继续向外继承。

`enable` 默认为 `true`，选择状态按函数、类、文件的最近注解决定：

```kotlin
@JvmSuspendProjection(enable = false)
interface DisabledApi {
    suspend fun skipped()

    @JvmSuspendProjection(enable = true)
    suspend fun restored()
}
```

这允许函数在被禁用的类或文件中重新启用。注解完整名称为 `cn.chuanwise.kotlinsuspendprojection.annotations.JvmSuspendProjection`，保留级别为 `BINARY`。

需要为所有符合条件的声明生成投影时：

```kotlin
suspendProjection {
    selection { mode = SelectionMode.ALL }
}
```

ALL 模式会选择全部合格声明，但 `@JvmSuspendProjection(enable = false)` 仍可排除局部范围。

## 主投影预设

```kotlin
suspendProjection {
    primary = JvmProjection.BLOCKING
}
```

`COMPLETION_STAGE`、`COMPLETABLE_FUTURE` 和 `FUTURE` 也可以作为 primary projection。

`primary = X` 会确保 X 加入 `projections`，启用 X 的同名 caller、direct implementation 和 imports，并关闭 X 的额外命名 caller。例如 Blocking primary 还会设置：

```kotlin
directImplementation {
    projection = JvmProjection.BLOCKING
    enforcement = DirectImplementationEnforcement.STRICT
    uninstrumentedKotlin = UninstrumentedKotlin.ERROR
}
```

其他细节仍可在预设之后显式覆盖。

## Direct implementation 的权衡

`STRICT` 让 Java 漏实现投影方法时直接编译失败，是所有 primary projection 的默认策略。它也带来以下约束：

- canonical 接口新增同名同步抽象方法，属于公开 JVM ABI；
- Java 的多接口继承、已有同名方法和泛型擦除可能产生冲突；
- 受插件处理的 Kotlin 实现会自动 opt-in 并生成同步桥；
- 无插件 Kotlin 实现默认被 ERROR 级 `@SubclassOptInRequired` 阻止；
- 手工 opt-in 只是显式承担风险，不会替代 bridge 生成；
- 零插件实现方应实现明确的 `Foo.Projections.ViaXxx`，或避免 direct implementation。

需要保留双向默认桥时，可以在设置 `primary` 后覆盖为 `GUARDED_DEFAULT`。此模式不能在 Java 编译期发现漏实现，只能在 canonical suspend 默认桥与 projected 默认桥互相递归时抛出 `InvalidSuspendProjectionPathException`。

## 当前实现边界

当前版本完整实现：

- `GeneratedTypeLayout.NESTED_NAMESPACE`；
- Blocking、CompletionStage、CompletableFuture 和 Future 的 exports/imports；
- 上述四种 projection 的 same-name caller、direct implementation 与 primary preset；
- `BlockingExecution.DIRECT`；
- `BlockingInterruption.THROW_CHECKED`。

`CONTINUATION` 和其他 layout 枚举值是后续 ABI 的保留名称。选择尚未实现的值会在 Gradle 配置阶段报错，避免配置看似生效但产物没有变化。

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
    verification { enable = false }
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
