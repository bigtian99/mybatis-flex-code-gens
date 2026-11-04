# SQL 预览：从"写文件 + 工程编译 + 起 JVM"到"内存编译 + 字节码加载"

> 对应 issue：点击代码行左侧 icon 预览 SQL 太慢，能否用字节码技术加速。

## 一、原来的流程慢在哪

点击 icon 之后，`SQLPreviewAction` 会：

1. 把选中的链式调用片段拼成一整个 `MybatisFlexSqlPreview` 类（带 `main` 方法）；
2. 把这个类**写到用户源码目录**里；
3. 调用 `CompilerManager.compile` 触发 IDEA 的工程编译（顺带把实体类也编译一遍）；
4. 用运行配置**另起一个 JVM**，`main` 里把 SQL `System.out.println` 出来；
5. 监听进程 stdout，看到 SELECT/INSERT... 才弹窗；
6. 最后把临时文件删掉、把临时加在实体上的无参构造器删掉。

这条链路上每一步都在做"重活"：落盘 + VFS 刷新、模块级增量编译、
JVM fork 与类加载、控制台解析。任何一步抖动（比如实体类刚改过、项目待编译）都会让点击变成"卡几秒"。

## 二、实测数据（本机 JBR 25，取中位数，≥5 次）

用真实的 mybatis-flex 1.7.4 + mybatis 3.5.13 渲染同一条 `QueryWrapper`：

| 场景 | 耗时 | 说明 |
|---|---|---|
| A 落盘 + `javac` CLI + fork 新 JVM | **761 ms** | javac 537ms + fork/JVM 启动 190ms，**还没算** IDEA 构建系统 |
| B 进程内首次编译 + 加载 + 调用 | **619 ms** | 其中 494ms 是 javac 首次装载与 JIT，稳态单次编译只要 38~56ms |
| C 内存编译结果缓存 + 每次新建隔离类加载器 | **79 ms** | 78ms 全花在"新加载器里重新初始化 MyBatis-Flex 静态状态"，define 只占 0.5ms |
| D 缓存框架类加载器 + 每次只新建子加载器（**本次采用**） | **2~3 ms** | 框架类已预热，只 define 生成的类 |

结论很直接：**编译不是主要成本，JVM 与框架类加载才是**。
所以"字节码技术"能帮上忙的地方不是"跳过 javac"，而是：

1. 用 `javax.tools.JavaCompiler` 在内存里编译，产物是字节码，不再落盘、不再触发工程编译；
2. 用字节码缓存 + 一次性的类加载换成"复用已预热的类加载器"；
3. 用 ASM 在字节码层面补实体类的无参构造器，取代"改用户源码 + 重新编译 + 再改回来"。

## 三、现在的流程

```
选中片段
  ├─ 按项目真实方法签名修正重载参数（见第四节）
  ├─ 生成预览源码（内存字符串，不落盘）
  ├─ SqlPreviewCompiler：内存 javac 编译 -> Map<类名, byte[]>
  │     └─ 按源码 SHA-256 缓存，同一片段第二次点击直接命中
  ├─ SqlPreviewLoaderCache：项目级"框架类加载器"（父加载器是 JDK 平台加载器）
  │     └─ 项目依赖、实体、Mapper、TableDef 全在这里，与插件类加载器隔离
  ├─ 每次预览只新建一个子加载器，define 生成的类
  ├─ 反射调用 preview()，直接拿到 SQL 字符串
  └─ 弹窗（仍然是原来的 SQLPreviewDialog）
```

失败即回退：编译不过、类没编译、超时（20s）、实体补丁失败等任何异常，
都会自动走回旧流程（写文件 + 工程编译 + 独立 JVM），保证功能不退化。

### 关于"要不要用 ASM 生成 main 方法"

评估过，结论是**不该**：

* 预览片段是用户写的任意 Java 表达式（`QueryWrapper.create().select(...).where(...)`，
  里面还有变量、静态字段、lambda、方法引用），要把它变成字节码就得先做完整的
  表达式解析 + 重载决议 + 类型推断，等于自己写半个 javac，正确性风险极高；
* 实测 javac 稳态编译这段代码只要 38~56ms，而 ASM 省掉的正是这 56ms，
  却要付出上面那些风险，收益与风险不成比例。

所以 ASM 只用在一个"必须改字节码、且有确定收益"的地方：**实体类无参构造器补丁**。

## 四、方法重载支持

MyBatis-Flex 的同名方法大量重载，例如：

```java
QueryCondition eq(Object value);
QueryCondition eq(Object value, boolean isEffective);
QueryCondition eq(Object value, BooleanSupplier isEffective);
QueryCondition isNull(boolean isEffective);   // 没有 isNull() 之外的形态
QueryCondition between(Object start, Object end);
QueryCondition between(Object start, Object end, boolean isEffective);
```

而注解处理器（`MybatisFlexConfigAnnotator`）为了让"动态条件"能跑起来，
会把这类调用改写成 `el -> true`（函数式接口形态）。一旦落到的重载声明的是
`boolean` 而不是函数式接口，生成的预览代码就**没有对应重载**，
以前表现为"IDEA 编译报错"，现在会表现为"快速通道编译失败 -> 回退"。

因此新增一步重载修正（`MybatisFlexMethods` + `SqlPreviewChainFixer`）：

1. 从项目真实的 mybatis-flex jar 里扫描 `com.mybatisflex.core.query` / `core.update` /
   `core.table` 下的类，建立"方法名 -> 各重载参数类型"的索引（按类加载器缓存，随项目编译失效）；
2. 逐个调用点检查：该位置是否存在 `boolean` 参数的重载 —— 存在就把 `el -> true` 换成 `true`；
3. 该位置没有这个参数个数的重载、但存在更少参数的重载 —— 去掉这个多余的开关参数；
4. 其余情况（例如 `where(Consumer<QueryWrapper>)`，函数式接口才是正确重载）保持 lambda 不动；
5. 参数里嵌套的链式调用（`where(ACCOUNT.ID.isNull(el -> true))`）会递归修正。

示例：

```java
// 修正前（编译不过）
... .where(ACCOUNT.ID.isNull(el -> true))
    .and(ACCOUNT.AGE.between(1, 2, el -> true))

// 修正后（命中真实重载）
... .where(ACCOUNT.ID.isNull(true))
    .and(ACCOUNT.AGE.between(1, 2, true))
// where(Consumer<QueryWrapper>) 这种重载保持不变
... .where(el -> true)
```

## 五、兼容与回退

| 情况 | 行为 |
|---|---|
| 项目没编译 / 实体、Mapper 类不存在 | 快速通道抛异常 -> 回退旧流程（旧流程会先编译） |
| 预览片段编译不过 | 同上，旧流程会给出编译错误 |
| 用户刚编译过项目 | 监听 `CompilerTopics.COMPILATION_STATUS`，丢弃旧的类加载器与字节码缓存 |
| classpath 变化（加依赖、切模块） | 指纹变化，重建类加载器 |
| 运行时异常 / 卡死 | 20s 超时 -> 回退旧流程 |
| 实体类缺少无参构造器 | 字节码层面补一个（幂等），不再改用户源码 |

## 六、怎么验证

* 单元测试（不需要起 IDE）：

```bash
./gradlew test --tests 'club.bigtian.mf.plugin.core.preview.*'
```

覆盖：内存编译、字节码缓存、编译错误上报、无参构造器补丁（含幂等）、
重载修正（boolean 重载、函数式接口重载、嵌套链、字符串里带逗号）。

* 端到端：用真实 mybatis-flex jar 跑一遍
  "重载修正 -> 内存编译 -> 类加载 -> 输出 SQL"，本机实测
  `cold=446ms / cached=1ms / warm run=3ms`，SQL 输出正确。
