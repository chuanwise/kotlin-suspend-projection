# Kotlin Suspend Projection

Kotlin Suspend Projection 是一个面向 JVM 的 Kotlin 编译器插件。它以 Kotlin `suspend` API 为语义真源，在编译期生成适合 Java 和其他 JVM 语言调用、实现的普通方法。

项目的目标很直接：

> Kotlin 开发者只需要思考 `suspend`，Java 开发者只需要使用普通 Java API。

项目当前处于 `0.1.0-SNAPSHOT` 开发阶段，尚未发布到公共 Maven 仓库。包名、Gradle 插件 ID 和 Maven group 均为 `cn.chuanwise.kotlinsuspendprojection`。

## Kotlin suspend function 是什么

`suspend fun` 是 Kotlin 对可挂起函数的语言级表达。函数可以在等待异步结果时挂起，并在结果就绪后恢复，而不要求业务 API 暴露回调或 `Future`。

```kotlin
suspend fun findUser(id: String): User
```

对 Kotlin 调用方，这就是一个自然的顺序式 API：

```kotlin
val user = service.findUser("42")
```

但在 JVM 字节码中，suspend function 会使用 `Continuation` 等 coroutine ABI。Java 源码既不适合直接调用，也很难正确实现这种底层签名。Kotlin Suspend Projection 在 canonical suspend API 与平台原生 API 之间生成双向桥接：

```text
Java caller
    -> generated projection
    -> Kotlin suspend implementation

Java implementation
    -> generated projection
    -> Kotlin suspend caller
```

## 快速开始

### 1. 应用 Gradle 插件

```kotlin
plugins {
    kotlin("jvm") version "2.4.20"
    id("cn.chuanwise.kotlinsuspendprojection") version "0.1.0-SNAPSHOT"
}
```

插件会自动配置 annotations、runtime 和产物验证。默认只处理带 `@JvmSuspendProjection` 的声明，只生成 Blocking projection，并把 Blocking 设为 primary。

### 2. 声明 canonical suspend API

```kotlin
import cn.chuanwise.kotlinsuspendprojection.annotations.JvmSuspendProjection

data class User(val id: String, val name: String)

interface UserService {
    @JvmSuspendProjection
    suspend fun findUser(id: String): User?
}
```

Kotlin 实现仍然只实现 suspend function：

```kotlin
class KotlinUserService : UserService {
    override suspend fun findUser(id: String): User? =
        User(id, "Kotlin")
}
```

### 3. Java 调用 Kotlin suspend 实现

Blocking 是默认 primary，所以 Java 使用同名普通方法，不需要构造 `Continuation`，也不需要安装编译器插件：

```java
UserService service = new KotlinUserService();

User user = service.findUser("42");
```

### 4. Java 实现 Kotlin suspend 接口

默认 strict direct implementation 允许 Java 直接实现 canonical 接口。漏掉 `findUser` 会在 Java 编译期报错：

```java
public final class JavaUserService implements UserService {

    @Override
    public User findUser(String id) {
        return new User(id, "Java");
    }
}
```

Kotlin 调用方仍然面对原始 suspend API：

```kotlin
val service: UserService = JavaUserService()
val user = service.findUser("42")
```

这也是本项目与单向 blocking bridge 的主要区别：投影既支持 **Java caller**，也支持 **Java implementer**。已经编译的 library 可以由普通 Java/Kotlin 工程零插件消费；Gradle 和 IDE 插件用于生成、验证及改善开发体验。

生成的 `UserService.Projections.ViaBlocking` 也始终保留，供希望明确锁定 Blocking contract 的实现方使用。选择异步 projection 后，还可以实现非阻塞的 `ViaCompletionStage` contract：

```java
public final class StageUserService
        implements UserService.Projections.ViaCompletionStage {

    @Override
    public CompletionStage<User> findUserCompletionStage(String id) {
        return CompletableFuture.completedFuture(new User(id, "Java Stage"));
    }

    @Override
    public <T extends CharSequence> CompletionStage<T> echoCompletionStage(T value) {
        return CompletableFuture.completedFuture(value);
    }

}
```

显式启用后也会生成 `ViaCompletableFuture` 和 `ViaFuture`。`CompletionStage`/`CompletableFuture` imports 通过 completion callback 恢复 coroutine；普通 `Future` 没有标准 completion callback，因此 imports 会使用 `Future.get()` 等待。

## 常用高级功能

### 配置项目默认投影和 primary

默认配置等价于：

```kotlin
import cn.chuanwise.kotlinsuspendprojection.gradle.JvmProjection

suspendProjection {
    projections = setOf(JvmProjection.BLOCKING)
    primary = JvmProjection.BLOCKING
}
```

项目可以选择其他默认投影：

```kotlin
suspendProjection {
    projections = setOf(
        JvmProjection.COMPLETION_STAGE,
        JvmProjection.COMPLETABLE_FUTURE,
    )
    primary = JvmProjection.COMPLETION_STAGE
}
```

primary projection 使用同名 Java caller 和 strict direct implementation。Java 漏实现同步方法会在编译期失败；受插件处理的 Kotlin 实现会自动生成 bridge；未安装插件的 Kotlin 实现方默认会收到 ERROR 级 `@SubclassOptInRequired` 提醒。

Direct implementation 会扩大 canonical 接口的公开 JVM ABI，也可能与 Java 多接口继承中的同名方法发生冲突。详细权衡见[配置与兼容性](docs/configuration-and-compatibility.md#direct-implementation-的权衡)。

### 为声明选择多种 projection

`@JvmSuspendProjection` 可以同时指定多个 `JvmProjectionType`。显式列表会替换继承到的默认列表，因此需要 Blocking 时也应明确写入：

```kotlin
import cn.chuanwise.kotlinsuspendprojection.annotations.JvmProjectionType
import cn.chuanwise.kotlinsuspendprojection.annotations.JvmSuspendProjection

@JvmSuspendProjection(
    JvmProjectionType.BLOCKING,
    JvmProjectionType.COMPLETION_STAGE,
    JvmProjectionType.COMPLETABLE_FUTURE,
    JvmProjectionType.FUTURE,
)
suspend fun findUser(id: String): User?
```

这会生成同名 Blocking caller，以及 `findUserCompletionStage`、`findUserCompletableFuture`、`findUserFuture` 和对应的 `ViaXxx` 实现契约。

### 文件、类和函数级策略

注解可用于文件、类和函数。投影类型按以下顺序解析：

```text
函数显式 projections > 类显式 projections > 文件显式 projections > 项目 projections
```

空参数注解只选择声明，投影类型继续向外继承：

```kotlin
@file:cn.chuanwise.kotlinsuspendprojection.annotations.JvmSuspendProjection(
    cn.chuanwise.kotlinsuspendprojection.annotations.JvmProjectionType.COMPLETION_STAGE,
)

package example
```

```kotlin
import cn.chuanwise.kotlinsuspendprojection.annotations.JvmProjectionType
import cn.chuanwise.kotlinsuspendprojection.annotations.JvmSuspendProjection

@JvmSuspendProjection(JvmProjectionType.COMPLETABLE_FUTURE)
interface EntireApi {
    suspend fun first()

    @JvmSuspendProjection(JvmProjectionType.BLOCKING)
    suspend fun second()
}
```

`enable` 默认为 `true`。`enable = false` 会关闭对应函数或作用域，更具体的注解可以重新启用：

```kotlin
@JvmSuspendProjection(enable = false)
interface DisabledApi {
    suspend fun skipped()

    @JvmSuspendProjection(enable = true)
    suspend fun restored()
}
```

默认 `SelectionMode.ANNOTATED` 只处理被注解选择的声明。显式处理模块内全部合格声明：

```kotlin
import cn.chuanwise.kotlinsuspendprojection.gradle.SelectionMode

suspendProjection {
    selection {
        mode = SelectionMode.ALL
    }
}
```

`@JvmSuspendProjection(enable = false)` 仍可在 ALL 模式中排除局部声明。

### 修改生成类型的 namespace

默认实现接口名为 `Foo.Projections.ViaBlocking`。中间 namespace 可以修改：

```kotlin
suspendProjection {
    generatedTypes {
        namespace = "Interop"
    }
}
```

生成名称随之变为 `Foo.Interop.ViaBlocking`。

### 运行时保护与产物验证

生成代码默认携带 `@SuspendProjectionMeta`，记录生成代码 schema、版本和能力信息。runtime 会检查生成代码兼容性，Gradle 的 `verifySuspendProjections` 任务会检查 metadata、strict contract 和生成桥是否完整。

这些 metadata 用于描述和验证，实际调用不会通过反射发现 projection。

## 当前能力

当前版本实现四种 JVM projection。默认只选择 Blocking；其他 projection 可由项目配置或注解显式选择：

| 方向 | 生成 API | 用途 |
| --- | --- | --- |
| suspend exports | `fooBlocking(...)` | Java 调用 Kotlin suspend 实现 |
| suspend exports | `fooCompletionStage(...)` | Java 以非阻塞 CompletionStage 调用 |
| suspend exports | `fooCompletableFuture(...)` | Java 获得可组合的 CompletableFuture；取消不会自动传播到底层 coroutine |
| suspend exports | `fooFuture(...)` | Java 以 Future 接口调用 |
| Blocking imports | `Foo.Projections.ViaBlocking` | Java/其他 JVM 语言实现 Kotlin suspend 接口 |
| CompletionStage imports | `Foo.Projections.ViaCompletionStage` | completion callback 恢复 coroutine |
| CompletableFuture imports | `Foo.Projections.ViaCompletableFuture` | CompletableFuture completion 恢复 coroutine |
| Future imports | `Foo.Projections.ViaFuture` | 通过 `Future.get()` 导入结果 |
| same-name caller | `foo(...)` | 所选 projection 作为主要 Java caller API |
| direct implementation | `implements Foo` | Java 直接实现 canonical 接口 |

签名矩阵覆盖：

- owner 泛型、函数泛型和多重上界；
- extension receiver、context receiver 和普通参数；
- nullable 类型；
- 引用底层与 primitive 底层 value class；
- public interface 中直接声明的 public suspend 成员；
- Java 8 字节码目标、Kotlin 2.4.20 和 K2。

四种 projection 都支持 exports、imports、same-name caller、direct implementation 和 `primary = ...`。原始 `Continuation` 仍只作为未来的显式 escape hatch 预留，当前不能被选为 policy projection。

后续 projection 仍会遵循同一模型：

```text
Projection exports/imports <-> canonical suspend semantics
```

不会把 Blocking、Future、Callback 等适配器两两连接成难以维护的转换网络。

## 文档

- [架构与生成模型](docs/architecture.md)
- [Java 互操作](docs/java-interop.md)
- [配置与兼容性](docs/configuration-and-compatibility.md)
- [开发与测试](docs/development.md)

## 构建

仓库未提交 Gradle Wrapper，需要使用本机 Gradle：

```shell
gradle clean test
```

## Also see

本项目参考并受益于以下开源项目：

- [Kotlin JVM Blocking Bridge (KJBB)](https://github.com/him188/kotlin-jvm-blocking-bridge)：为 Kotlin suspend function 生成 Java 友好的 blocking bridge，并提供 Gradle 与 IDE 集成。
- [Kotlin Suspend Transform Compiler Plugin](https://github.com/ForteScarlet/kotlin-suspend-transform-compiler-plugin)：将 suspend function 转换为平台兼容 API，并探索 Blocking、异步 API 与可扩展 Transformer。

它们解决了 Kotlin suspend API 跨语言使用中的重要问题，也为 Kotlin Suspend Projection 的编译器实现、命名和开发体验提供了有价值的参考。
