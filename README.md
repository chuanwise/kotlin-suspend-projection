# Kotlin Suspend Projection

Kotlin Suspend Projection 是一个面向 JVM 的 Kotlin 编译器插件。它以 Kotlin `suspend` API 为唯一语义来源，在编译期生成 Java 友好的同步投影，让 Kotlin 与 Java 可以围绕同一套接口协作。

项目当前处于 `0.1.0-SNAPSHOT` 开发阶段，适合验证 API 设计和集成方案，尚未发布到公共 Maven 仓库。

## 解决什么问题

Kotlin 的 `suspend fun` 在 JVM 上会降级为带 `Continuation` 参数的方法。这个 ABI 对 Kotlin 编译器透明，但不适合作为普通 Java API，也不方便 Java 类直接实现。

本项目为公开接口生成两种 blocking 投影：

- 同名 Java 调用入口，例如 `String greet(String name)`；
- 显式 Java 实现契约，例如 `Greeter.Projections.ViaBlocking` 中的 `greetBlocking`。

Kotlin 调用方和实现方继续使用原始 `suspend` 接口。消费已编译产物时不要求安装编译器插件或 IDE 插件。

## 示例

声明 Kotlin 接口：

```kotlin
interface Greeter {
    suspend fun greet(name: String): String
}

class KotlinGreeter : Greeter {
    override suspend fun greet(name: String): String = "Hello, $name"
}
```

编译器会生成 Java 可见的 blocking 调用入口。Java 调用方可以直接调用：

```java
Greeter greeter = new KotlinGreeter();
String message = greeter.greet("Java");
```

Java 实现方使用显式契约：

```java
public final class JavaGreeter implements Greeter.Projections.ViaBlocking {
    @Override
    public String greetBlocking(String name) {
        return "Hello, " + name;
    }
}
```

`ViaBlocking` 提供从 blocking 实现回到 canonical suspend 语义的默认桥。Kotlin 代码仍然通过 `Greeter.greet` 调用 `JavaGreeter`。

## Gradle 配置

插件 ID：

```kotlin
plugins {
    kotlin("jvm") version "2.4.20"
    id("dev.suspendprojection") version "0.1.0-SNAPSHOT"
}
```

插件默认会：

- 添加 `annotations`、`runtime-core` 和 `runtime-jvm` 依赖；
- 将 Kotlin 与 Java 字节码目标设置为 Java 8；
- 使用 `JvmDefaultMode.NO_COMPATIBILITY`；
- 启用生成代码兼容性检查和无效适配路径检查；
- 将 `verifySuspendProjections` 接入 `check`。

可用配置：

```kotlin
import dev.suspendprojection.gradle.DirectImplementationEnforcement

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

详细说明见 [配置与兼容性](docs/configuration-and-compatibility.md)。

## 当前支持范围

- Kotlin `2.4.20`、K2 编译器；
- JVM 与 Java 8 字节码；
- public interface 中声明的 public suspend 成员；
- owner 泛型、函数泛型及多重上界；
- context receiver、extension receiver 和普通 value parameter；
- 普通、nullable、引用类型底层和 primitive 底层 value class；
- Java 调用 Kotlin 实现，以及 Java 通过 `ViaBlocking` 实现 Kotlin suspend 接口；
- 运行时生成代码版本检查、递归无效路径检查和产物字节码验证。

当前不生成 abstract/open/final class 的投影，也未支持 JS、Native、reactive、Flow 或 `CompletionStage` 投影。

## Runtime Guard

默认生成两类检查：

1. 生成代码版本是否位于 runtime 支持范围内；
2. guarded-default 桥是否进入了递归的无效路径。

第二类检查用于识别“实现类既没有提供 canonical suspend 实现，也没有提供 blocking 实现，两个默认桥互相调用”的错误。检测到后抛出 `InvalidSuspendProjectionPathException`，避免最终表现为无限递归或栈溢出。

除了 Gradle 编译配置，还可以通过 JVM 参数临时关闭检查：

```text
-Dkotlin.suspend.projection.runtime.guards=false
-Dkotlin.suspend.projection.runtime.compatibility=false
-Dkotlin.suspend.projection.runtime.invalidPath=false
```

## 构建与验证

仓库未提交 Gradle Wrapper。使用本机 Gradle 执行：

```shell
gradle clean test
```

Gradle 插件的功能测试会创建真实消费者工程，编译 Kotlin API 和 Java 实现，并检查生成 class 的名称、签名、可见性和 metadata。

更多文档：

- [架构与生成模型](docs/architecture.md)
- [Java 互操作](docs/java-interop.md)
- [配置与兼容性](docs/configuration-and-compatibility.md)
- [开发指南](docs/development.md)
