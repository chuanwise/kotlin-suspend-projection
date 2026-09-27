# 开发指南

## 环境

- JDK 8 或更高版本；
- 可运行 Kotlin `2.4.20` 插件的 Gradle；
- Git。

根构建当前没有提交 Gradle Wrapper，因此根模块命令示例使用系统 Gradle。`idea-plugin/` 是独立构建，带有自己的 Wrapper。

## 模块构建

```shell
gradle clean test
```

只运行 Gradle 插件消费者测试：

```shell
gradle :gradle-plugin:test --tests "*SuspendProjectionGradlePluginTest"
```

功能测试会在 `.gradle-test-kit/` 创建隔离缓存。该目录与所有模块的 `build/`、`.gradle/`、`.kotlin/` 都已忽略。

## IDEA 插件

IDEA 插件以独立 Gradle 构建维护，要求 JDK 21：

```shell
cd idea-plugin
./gradlew test buildPlugin
```

Windows 可使用：

```powershell
cd idea-plugin
.\gradlew.bat test buildPlugin
```

插件目标版本、IntelliJ Platform Gradle Plugin 和测试依赖统一声明在 `idea-plugin/gradle/libs.versions.toml`。插件测试覆盖 Java 预生成投影视图、Java direct implementation 诊断、Kotlin 补全隐藏、注解覆盖、泛型、extension receiver 和 value class JVM 类型。

## 验证重点

修改 FIR/IR 生成逻辑时，至少应验证：

- Kotlin 实现可以从 Java 通过同名 blocking 方法调用；
- 默认只生成 Blocking primary 的同名 caller；
- 默认只生成带 `@JvmSuspendProjection` 的声明，`SelectionMode.ALL` 可显式扩大范围；
- Java 可以实现 `Foo.Projections.ViaBlocking`；
- Java 可以实现 `ViaCompletionStage`、`ViaCompletableFuture` 和 `ViaFuture`；
- CompletionStage/CompletableFuture imports 不阻塞，Future imports 明确使用 `get()`；
- Blocking 与非 Blocking primary 都能生成 strict Kotlin bridge；
- `primary = BLOCKING` 下 Java 漏实现 strict 方法会在编译期失败；
- `@JvmSuspendProjection` 文件、类、函数级的投影继承和 `enable` 覆盖不会泄漏；
- 自定义 generated namespace 会同步作用于 FIR、IR 和 Java ABI；
- canonical suspend 调用可以到达 Java blocking 实现；
- owner 和 function 泛型上界保持正确；
- context/extension receiver 参数完整且顺序正确；
- value class 的 Java 方法名与描述符合法；
- 非 public owner/member 不泄漏生成 ABI；
- `GUARDED_DEFAULT` 和 `STRICT` 的验证行为符合配置；
- metadata schema 与 generated-code version 可被 verifier 接受。

## 版本同步

依赖与插件版本统一声明在 `gradle/libs.versions.toml`。生成代码和发布坐标仍有源码常量，发布前必须同步检查：

- Version Catalog 的 `versions.project` 与 `versions.kotlin`；
- Gradle 插件依赖坐标；
- `SuspendProjectionMeta.generatorVersion`；
- generated-code version 与 runtime 支持范围。

在引入自动版本注入前，不要只修改其中一个位置。

## 内部资料

设计讨论、研究记录、PoC 构建结果和参考仓库位于 `docs/.agents/`。该目录用于本地研发上下文，明确不进入 Git。
