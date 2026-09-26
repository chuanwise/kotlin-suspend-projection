# Kotlin Suspend Projection

Kotlin Suspend Projection 是一个面向 JVM 的 Kotlin 编译器插件。它以 Kotlin `suspend` API 为唯一语义来源，在编译期生成 Java 友好的同步投影，让 Kotlin 与 Java 围绕同一套接口协作。

项目当前处于 `0.1.0-SNAPSHOT` 开发阶段，尚未发布到公共 Maven 仓库。公开包名、Gradle 插件 ID 和 Maven group 均为 `cn.chuanwise.kotlinsuspendprojection`。

## 默认生成模型

```kotlin
interface Greeter {
    suspend fun greet(name: String): String
}
```

默认配置会生成：

- `Greeter.greetBlocking(...)`：Java 调用 Kotlin suspend 实现；
- `Greeter.Projections.ViaBlocking`：Java 用 blocking 方法实现 Kotlin suspend 接口；
- `@SuspendProjectionMeta`：记录生成器、schema、投影方向和能力信息；
- runtime compatibility guard 与 invalid-path guard。

```java
Greeter greeter = new KotlinGreeter();
String message = greeter.greetBlocking("Java");

public final class JavaGreeter implements Greeter.Projections.ViaBlocking {
    @Override
    public String greetBlocking(String name) {
        return "Hello, " + name;
    }
}
```

消费已经编译的库时，Kotlin 与 Java 用户都可以零插件使用；安装 Gradle/IDE 插件会获得生成、验证和诊断能力。

## Gradle 接入

项目使用 Version Catalog 管理自身依赖版本，见 [`gradle/libs.versions.toml`](gradle/libs.versions.toml)。消费者应用插件：

```kotlin
plugins {
    kotlin("jvm") version "2.4.20"
    id("cn.chuanwise.kotlinsuspendprojection") version "0.1.0-SNAPSHOT"
}
```

```kotlin
import cn.chuanwise.kotlinsuspendprojection.gradle.*

suspendProjection {
    enabled = true

    selection {
        mode = SelectionMode.ANNOTATED
    }
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

## 使用注解选择 API

默认只处理带 `@SuspendProjection` 的接口或函数。需要处理所有符合条件的 public interface suspend 成员时，显式配置 `SelectionMode.ALL`。

可以标注整个接口：

```kotlin
import cn.chuanwise.kotlinsuspendprojection.annotations.SuspendProjection

@SuspendProjection
interface UserService {
    suspend fun load(id: String): User
}
```

也可以只标注单个函数：

```kotlin
interface UserService {
    @SuspendProjection
    suspend fun load(id: String): User

    suspend fun internalRefresh()
}
```

此时只为 `load` 生成投影。

## Java 直接实现 canonical 接口

希望 Java 写 `implements Foo`，并实现同名同步方法时，可以启用 Blocking 主投影预设：

```kotlin
suspendProjection {
    primary(JvmProjection.BLOCKING)
}
```

该预设启用同名 caller、Blocking direct implementation 和 Blocking imports，并关闭额外的 `fooBlocking` caller。它同时选择 `STRICT` enforcement 和 `ERROR` 级无插件 Kotlin 提醒。Java 可直接实现：

```java
public final class JavaGreeter implements Greeter {
    @Override
    public String greet(String name) {
        return "Hello, " + name;
    }
}
```

`STRICT` 把同步方法生成为抽象契约，因此 Java 漏写 `override` 会在编译期失败。接口还会携带 `@SubclassOptInRequired`；未安装编译器插件的 Kotlin 实现方默认得到 ERROR，安装 Gradle 插件的编译会自动 opt-in，并生成 Kotlin 实现所需的同步桥。

### Direct implementation 的限制

- 同名同步方法会扩大 canonical 接口的公开 JVM ABI，启用、关闭或更换投影都属于兼容性变更；
- Java 多接口继承中可能出现同名同参数但返回类型不兼容的冲突；
- Kotlin direct implementer 需要编译器插件生成 strict bridge。手工 opt-in 只关闭诊断，不会生成 bridge；
- 不适合 direct implementation 的消费者可以继续使用 `Foo.Projections.ViaBlocking`；
- `GUARDED_DEFAULT` 仍可显式配置，但漏实现只能在运行时由 `InvalidSuspendProjectionPathException` 检出。

## 当前支持范围

- Kotlin `2.4.20`、K2、JVM/Java 8；
- public interface 中直接声明的 public suspend 成员；
- owner 泛型、函数泛型及多重上界；
- context receiver、extension receiver、普通参数；
- 普通、nullable、引用底层和 primitive 底层 value class；
- Blocking exports/imports、同名主投影、guarded/strict direct implementation；
- 运行时版本检查、递归无效路径检查和产物字节码验证。

当前只实现 `NESTED_NAMESPACE` 布局和 Blocking 投影。DSL 中为后续版本预留的其他枚举值会在配置校验时明确失败，不会被静默忽略。

## 构建

仓库未提交 Gradle Wrapper，使用本机 Gradle：

```shell
gradle clean test
```

更多文档：

- [架构与生成模型](docs/architecture.md)
- [Java 互操作](docs/java-interop.md)
- [配置与兼容性](docs/configuration-and-compatibility.md)
- [开发指南](docs/development.md)
