# 配置与兼容性

## Gradle 扩展

```kotlin
suspendProjection {
    enabled.set(true)
    directImplementationEnforcement.set(
        DirectImplementationEnforcement.GUARDED_DEFAULT,
    )
    addDependencies.set(true)
    verifyArtifacts.set(true)
    emitCompatibilityGuard.set(true)
    emitInvalidPathGuard.set(true)
}
```

| 属性 | 默认值 | 含义 |
| --- | --- | --- |
| `enabled` | `true` | 是否对 JVM compilation 应用编译器插件 |
| `directImplementationEnforcement` | `GUARDED_DEFAULT` | 直接实现使用 guarded defaults 或 strict contract |
| `addDependencies` | `true` | 是否自动加入 annotations 和 runtime 依赖 |
| `verifyArtifacts` | `true` | `verifySuspendProjections` 是否实际运行 |
| `emitCompatibilityGuard` | `true` | 是否把 runtime/generated-code 版本检查写入生成桥 |
| `emitInvalidPathGuard` | `true` | 是否把递归无效路径检查写入生成桥 |

关闭 `addDependencies` 时，项目必须自行提供版本完全一致的：

```text
dev.suspendprojection:annotations
dev.suspendprojection:runtime-core
dev.suspendprojection:runtime-jvm
```

编译器插件在缺失 `SuspendProjectionMeta` 或 `awaitSuspendProjection` 时会直接报错，而不是生成不完整桥。

## 验证任务

插件注册 `verifySuspendProjections`，并让 `check` 依赖它。任务读取项目产出的 JAR，检查：

- `@SuspendProjectionMeta` schema 是否受支持；
- generated-code version 是否位于 runtime 支持范围；
- `STRICT` 模式下具体实现是否提供要求的投影方法。

仅关闭 runtime guard 不会关闭 artifact verification。要跳过后者必须显式设置：

```kotlin
suspendProjection {
    verifyArtifacts.set(false)
}
```

## Runtime 属性

以下系统属性只在属性值等于 `false`（忽略大小写）时关闭检查：

| JVM 属性 | 作用 |
| --- | --- |
| `kotlin.suspend.projection.runtime.guards` | 关闭所有 runtime guard |
| `kotlin.suspend.projection.runtime.compatibility` | 仅关闭版本兼容性检查 |
| `kotlin.suspend.projection.runtime.invalidPath` | 仅关闭无效路径检查 |

这些开关适合诊断或紧急绕过，不应替代发布前验证。

## 当前兼容性边界

| 项目 | 当前状态 |
| --- | --- |
| Kotlin | `2.4.20`，K2 |
| JVM target | Java 8 |
| JVM default mode | `NO_COMPATIBILITY` |
| Gradle 插件目标 | Kotlin JVM compilation |
| generated-code version | `1` |
| metadata schema | `1` |

编译器插件使用 Kotlin compiler internal API，因此 Kotlin minor 版本升级必须经过独立适配和完整矩阵测试。当前实现不承诺直接兼容其他 Kotlin minor 版本。

`SuspendProjectionGeneratedCode.MIN_SUPPORTED_VERSION` 与 `MAX_SUPPORTED_VERSION` 定义 runtime 能执行的生成代码范围。兼容性判断基于范围，而不是要求 generator 与 runtime 的发布版本字符串完全相等。

## ABI 注意事项

以下变化应按公开 ABI 变化处理：

- 切换 `GUARDED_DEFAULT` 与 `STRICT`；
- 修改生成方法命名或参数顺序；
- 修改 value class 的底层类型；
- 修改 owner/function 泛型上界；
- 关闭或改变某类公开投影。

`@SuspendProjectionMeta` 是可观察 metadata，但运行时不依赖其反射可见性。
