# IDEA 插件

Kotlin Suspend Projection 的 IDEA 插件负责补齐“源代码已经声明 projection，但编译器插件尚未产出字节码”这段开发体验。它不是运行时依赖，也不改变编译器插件生成的 ABI。

## 提供的能力

### Java 预生成投影视图

对于 `@JvmSuspendProjection` 选中的 public interface suspend 成员，插件会在 Java PSI 中提供对应的虚拟方法：

```kotlin
interface UserService {
    @JvmSuspendProjection(
        JvmProjectionType.BLOCKING,
        JvmProjectionType.COMPLETION_STAGE,
    )
    suspend fun findUser(id: String): User
}
```

Java 编辑器可以在编译前解析：

```java
service.findUser("42");
service.findUserCompletionStage("42");
```

这些虚拟方法沿用 Kotlin light method 的 JVM 参数类型，因此 owner 泛型、函数泛型、extension receiver 和 value class 的签名与 Java 实际看到的签名一致。

### Kotlin 隐藏 Java projection

虚拟方法只加入 Java PSI，不加入 Kotlin 名称解析。Kotlin 补全和调用仍以 canonical suspend function 为准：

```kotlin
val user = service.findUser("42")
```

编译器最终生成的 Java projection 仍带有 `@JvmSynthetic`，因此已编译依赖中的这些方法也不会污染 Kotlin API。

### Java direct implementation

默认 Blocking primary 允许 Java 直接实现 canonical 接口：

```java
final class JavaUserService implements UserService {
    @Override
    public User findUser(String id) {
        return new User(id, "Java");
    }
}
```

编译器插件会为原始 suspend JVM 签名生成 default bridge。IDEA 插件会识别这个约定，移除编辑器中“还需要实现 `findUser(..., Continuation)`”的伪错误。

过滤是保守的：只有缺失项全部是已由 Blocking direct projection 覆盖的 suspend bridge 时才会移除诊断。接口中另有普通抽象方法未实现时，IDEA 仍会正常报错。

## 注解解析

当前 IDEA 插件支持与编译器一致的声明级规则：

- `@JvmSuspendProjection` 可放在文件、类或函数上；
- 函数显式 projection 优先于类，类优先于文件；
- 空 projection 列表继续继承外层设置，最终默认 Blocking；
- `enable = false` 关闭对应声明或作用域；
- 支持 annotation import alias。

插件当前不会解析 Gradle DSL。因此仅由 `selection.mode = ALL` 选中、仅由项目级非默认 projection 选中，或依赖自定义 generated namespace 的声明，要以实际编译结果为准。显式注解的代码可获得完整的编译前体验。

## 本地构建与安装

插件构建要求 JDK 21：

```powershell
cd idea-plugin
.\gradlew.bat test buildPlugin
```

构建产物位于 `idea-plugin/build/distributions/`。在 IDEA 中打开 **Settings | Plugins**，选择 **Install Plugin from Disk**，再选择生成的 ZIP。

插件本身只改善编辑体验。使用已经编译完成的 Kotlin Suspend Projection library 时，Java 和 Kotlin 消费方都不要求安装 IDEA 插件或 Gradle 插件。
