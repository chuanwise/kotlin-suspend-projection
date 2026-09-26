# Java 互操作

## Java 调用 Kotlin 实现

```kotlin
interface UserService {
    suspend fun load(id: UserId): User
}
```

插件默认在接口上生成带 `Blocking` 后缀的 Java 方法。Kotlin 实现只需实现 canonical suspend 方法：

```kotlin
class DefaultUserService : UserService {
    override suspend fun load(id: UserId): User = TODO()
}
```

Java 调用：

```java
User user = service.loadBlocking(idValue);
```

启用 `primary(JvmProjection.BLOCKING)` 后，Java caller 改为同名 `load(...)`，同时允许 Java 直接 `implements UserService`。同名投影对 Kotlin 源码隐藏，避免 Kotlin 调用时在 suspend 与 blocking 入口之间产生歧义。

## Java 实现 Kotlin 接口

Java 实现应选择明确的投影契约：

```java
public final class JdbcUserService
        implements UserService.Projections.ViaBlocking {
    @Override
    public User loadBlocking(String id) {
        return query(id);
    }
}
```

这里假设 `UserId` 是以 `String` 为底层表示的 value class。Java 签名使用 Kotlin/JVM lowering 后的实际类型。

不建议手写 `Continuation` 来实现 lowered suspend ABI。需要底层控制时仍可在字节码层访问 canonical 方法，但 `ViaBlocking` 是项目支持和验证的 Java 实现入口。

`generatedTypes.namespace` 可以修改中间类型名。例如设置为 `Interop` 后，契约名称变为 `UserService.Interop.ViaBlocking`。

## Java 直接实现

```kotlin
suspendProjection {
    primary(JvmProjection.BLOCKING)
}
```

此时 Java 可以直接实现 canonical 接口，并提供同名同步方法。主投影默认使用 `STRICT`，同步方法是抽象契约，漏实现会由 Java 编译器报告。

接口上的 `@SubclassOptInRequired` 默认以 ERROR 阻止未安装插件的 Kotlin 实现方。提醒级别可通过 `uninstrumentedKotlin` 调整；Java 不受 Kotlin opt-in 机制影响。

direct implementation 会增加接口 ABI，并可能与 Java 多接口继承中的同名方法冲突。Kotlin 实现方需要插件生成 strict bridge；手工 opt-in 不会代替代码生成。需要零插件 Kotlin 实现时，优先使用 `ViaBlocking`。

## 泛型 owner

owner 类型参数和多重上界会复制到 `ViaBlocking`：

```kotlin
interface Store<T> where T : CharSequence, T : Comparable<T> {
    suspend fun echo(value: T): T
    suspend fun <R : T> map(value: R): R
}
```

对应 Java 实现：

```java
public final class TextStore
        implements Store.Projections.ViaBlocking<TextStore.Text> {
    @Override
    public Text echoBlocking(Text value) {
        return value;
    }

    @Override
    public <R extends Text> R mapBlocking(R value) {
        return value;
    }
}
```

## Receiver 参数

Kotlin receiver 在 Java 投影中成为显式参数：

```kotlin
class RequestContext

interface Formatter<T> {
    context(context: RequestContext)
    suspend fun T.decorate(suffix: String): T
}
```

概念上的 Java blocking 方法为：

```java
T decorateBlocking(RequestContext context, T receiver, String suffix);
```

参数顺序遵循 Kotlin/JVM ABI：context receiver、extension receiver、普通 value parameter。

## Value class

插件识别返回值和参数树中的 value class，并为 Java 投影写入稳定 `@JvmName`，避免 Kotlin 默认 mangling 产生 Java 源码无法声明的连字符方法名。

Java 看到的是 lowering 后的底层表示。例如：

| Kotlin 类型 | 常见 Java 投影类型 |
| --- | --- |
| `@JvmInline value class UserId(val value: String)` | `String` |
| `UserId?`，底层为引用类型 | `String` |
| `@JvmInline value class Token(val value: Int)` | `int`，nullable 时通常装箱 |

最终签名应以编译产物或 IDE 展示为准，不要仅根据源代码类型名手写猜测。

## 可见性

当前只处理：

- public interface；
- 其中直接声明的 public suspend 方法。

private/internal owner、private/protected/internal suspend 成员以及 class owner 不会生成新的投影。父接口已经生成的契约可通过正常接口继承关系继续存在。

## Blocking 与中断

基础实现使用 JDK 同步原语等待 suspend 计算完成，不依赖 `kotlinx.coroutines`。

等待线程被中断时会得到 `InterruptedException`，但底层 suspend 计算没有 `Job` 可供取消。业务若要求强取消语义，应等待后续 coroutine-aware runtime，而不是把当前 blocking 投影视为完整协程取消桥。
