# 开发指南

## 环境

- JDK 8 或更高版本；
- 可运行 Kotlin `2.4.20` 插件的 Gradle；
- Git。

仓库当前没有提交 Gradle Wrapper，因此命令示例使用系统 Gradle。

## 模块构建

```shell
gradle clean test
```

只运行 Gradle 插件消费者测试：

```shell
gradle :gradle-plugin:test --tests "*SuspendProjectionGradlePluginTest"
```

功能测试会在 `.gradle-test-kit/` 创建隔离缓存。该目录与所有模块的 `build/`、`.gradle/`、`.kotlin/` 都已忽略。

## 验证重点

修改 FIR/IR 生成逻辑时，至少应验证：

- Kotlin 实现可以从 Java 通过同名 blocking 方法调用；
- 默认命名 caller 与 `primary(BLOCKING)` 同名 caller 均符合配置；
- 默认只生成带 `@SuspendProjection` 的声明，`SelectionMode.ALL` 可显式扩大范围；
- Java 可以实现 `Foo.Projections.ViaBlocking`；
- `primary(BLOCKING)` 下 Java 漏实现 strict 方法会在编译期失败；
- `@SuspendProjection` 类级和函数级选择不会泄漏到未选择声明；
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
