package club.bigtian.mf.plugin.action.flex;

import club.bigtian.mf.plugin.core.Template;
import club.bigtian.mf.plugin.core.constant.MybatisFlexConstant;
import club.bigtian.mf.plugin.core.function.ManyFunction;
import club.bigtian.mf.plugin.core.function.SimpleFunction;
import club.bigtian.mf.plugin.core.log.MyBatisLogExecutor;
import club.bigtian.mf.plugin.core.preview.MybatisFlexMethods;
import club.bigtian.mf.plugin.core.preview.SqlPreviewChainFixer;
import club.bigtian.mf.plugin.core.preview.SqlPreviewException;
import club.bigtian.mf.plugin.core.preview.SqlPreviewLoaderCache;
import club.bigtian.mf.plugin.core.preview.SqlPreviewRunner;
import club.bigtian.mf.plugin.core.util.*;
import club.bigtian.mf.plugin.windows.MybatisFlexSettingDialog;
import club.bigtian.mf.plugin.windows.SQLPreviewDialog;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.intellij.execution.*;
import com.intellij.execution.configurations.JavaCommandLineState;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class SQLPreviewAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(SQLPreviewAction.class);

    /**
     * 进程内预览的类名（同一个类加载器里多次预览不会冲突，因为每次预览都是新加载器，这里只是保持可读性）
     */
    private static final String FAST_CLASS_NAME = "MybatisFlexSqlPreview";

    /**
     * 进程内预览超时时间（毫秒），避免用户代码卡死预览流程
     */
    private static final long PREVIEW_TIMEOUT_MS = 20_000L;

    /**
     * 预览专用线程池：和超时等待的线程分开，避免在 IDE 的公共线程池里互相等待
     */
    private static final java.util.concurrent.atomic.AtomicBoolean FALLBACK_NOTIFIED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static final ExecutorService PREVIEW_EXECUTOR = java.util.concurrent.Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "SqlPreview-Worker");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private static final List<String> IMPORT_LIST = Arrays.asList("com.mybatisflex.core.FlexGlobalConfig",
            "com.mybatisflex.core.mybatis.FlexConfiguration",
            "org.springframework.jdbc.datasource.DelegatingDataSource",
            "org.apache.ibatis.mapping.Environment",
            "com.mybatisflex.core.dialect.DbType",
            "org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory");

    private static final String COMMON_CODE =
            "    Environment environment = new Environment(\"mybatisFlex\", new JdbcTransactionFactory(), new DelegatingDataSource());\n" +
                    "        FlexConfiguration configuration = new FlexConfiguration(environment);\n" +
                    "        FlexGlobalConfig globalConfig = FlexGlobalConfig.getDefaultConfig();\n" +
                    "        globalConfig.setConfiguration(configuration);\n" +
                    "        FlexGlobalConfig.setConfig(\"mybatisFlex\", globalConfig, true);\n" +
                    "        configuration.addMapper({}.class);\n"+
                    "           globalConfig.setDbType(DbType.{});\n";
    private static final String TEMPLATE =
            "        {} baseMapper = Mappers.ofMapperClass({}.class);\n" +
            "        Field field = ReflectUtil.getField(ServiceImpl.class, \"mapper\");\n" +
            "        ReflectUtil.setFieldValue({}, field, baseMapper);";

    private static final List<String> SERVICE_IMPORT_LIST = Arrays.asList("cn.hutool.core.util.ReflectUtil",
            "com.mybatisflex.core.mybatis.Mappers",
            "java.lang.reflect.Field", "com.mybatisflex.spring.service.impl.ServiceImpl");
    private static final String SYSTEM_OUT_PRINTLN_TO_SQL = "\nSystem.out.println({}.toSQL());";
    /**
     * 进程内预览用的语句模板：把 SQL 赋值给局部变量，由生成的 preview() 方法返回，
     * 这样就不需要重定向 System.out，也不会污染 IDE 控制台
     */
    private static final String SQL_RESULT_ASSIGN = "\n__preview_sql = String.valueOf({}.toSQL());";
    private static final String CLASS_TEMPLATE = "public class MybatisFlexSqlPreview {\n    public static void main(String[] args) { {}\n}\n}";
    private static final String FAST_CLASS_TEMPLATE =
            "public class {} {\n" +
                    "    public static String preview() {\n" +
                    "        String __preview_sql = null;\n" +
                    "        try { {}\n" +
                    "        } catch (Throwable __preview_error) {\n" +
                    "            __preview_error.printStackTrace();\n" +
                    "            return \"" + SqlPreviewRunner.ERROR_MARKER + "\" + __preview_error.getClass().getName()\n" +
                    "                    + \": \" + __preview_error.getMessage();\n" +
                    "        }\n" +
                    "        return __preview_sql;\n" +
                    "    }\n" +
                    "\n" +
                    "    public static void main(String[] args) {\n" +
                    "        String __preview_sql = preview();\n" +
                    "        if (__preview_sql != null) {\n" +
                    "            System.out.println(__preview_sql);\n" +
                    "        }\n" +
                    "    }\n" +
                    "}\n";

    private PsiClass entityClass;
    PsiMethod constructorMethod;
    List<String> list;
    PsiElementFactory instance = PsiElementFactory.getInstance(ProjectUtils.getCurrentProject());

    /**
     * 排除 import 导入包
     */
    private final List<String> excludeImportList = Arrays.asList("org.mapstruct.factory.Mappers");
    AnActionEvent event;

    /**
     * 是否生成"返回 SQL 字符串"的语句（进程内预览），而不是 System.out.println（旧流程）
     */
    private boolean returnSqlResult = false;

    /**
     * 是否允许修改用户源码（旧流程需要给实体补无参构造器）
     */
    private boolean mutateSource = true;

    /**
     * 预览 SQL
     * <p>
     * 优先走"内存编译 + 进程内执行"的快速通道，失败时自动回退到旧流程
     * （生成源文件 -&gt; IDEA 工程编译 -&gt; 独立 JVM 执行），保证功能不退化。
     */
    public void preview(String selectedText, PsiJavaFile psiFile, AnActionEvent actionEvent, SimpleFunction function) {
        this.event = actionEvent;
        this.list = null;
        this.entityClass = null;
        Project project = ProjectUtils.getCurrentProject();
        LOG.info("[SQLPreview] click, snippet=" + abbreviate(selectedText));
        if (ObjectUtil.isNull(project) || ObjectUtil.isNull(psiFile)) {
            notifyError("SQL 预览失败：拿不到当前项目/文件", project);
            function.apply();
            return;
        }
        // 关键点一：PSI 只能在 read action 里访问，而且要在 EDT 上先把 PSI 榨干成字符串；
        // 后台线程只碰纯字符串，避免 "Read access is allowed from inside read-action only"。
        FastPreviewRequest request;
        try {
            request = ReadAction.compute((ThrowableComputable<FastPreviewRequest, RuntimeException>)
                    () -> buildFastPreviewRequest(selectedText, psiFile));
        } catch (Throwable e) {
            LOG.warn("[SQLPreview] build preview request failed, use legacy", e);
            notifyError("SQL 预览失败：" + describeThrowable(e), project);
            try {
                legacyPreview(selectedText, psiFile, function);
            } finally {
                function.apply();
            }
            return;
        }
        if (request == null) {
            try {
                legacyPreview(selectedText, psiFile, function);
            } finally {
                function.apply();
            }
            return;
        }
        // 关键点二：解析 classpath、扫 jar、编译、执行都可能很慢，全部离开 EDT。
        try {
            AppExecutorUtil.getAppExecutorService().submit(() -> doPreview(project, psiFile, request, function));
        } catch (Throwable e) {
            LOG.warn("[SQLPreview] submit task failed", e);
            notifyError("SQL 预览失败：" + describeThrowable(e), project);
            try {
                legacyPreview(request.selectedText, psiFile, function);
            } finally {
                function.apply();
            }
        }
    }

    /**
     * 后台执行：只用字符串，不碰 PSI。任何异常都要让用户看见。
     */
    private void doPreview(Project project, PsiJavaFile psiFile, FastPreviewRequest origin, SimpleFunction function) {
        boolean handedToLegacy = false;
        try {
            FastPreviewRequest request = origin;
            String fixedSource = fixOverload(origin.source, psiFile);
            if (!fixedSource.equals(origin.source)) {
                request = new FastPreviewRequest(fixedSource, origin.entityClassName, origin.selectedText, origin.className);
            }
            String sql;
            long start = System.currentTimeMillis();
            try {
                sql = runFastPreview(project, psiFile, request);
            } catch (Throwable e) {
                LOG.warn("[SQLPreview] fast path failed, fallback", e);
                notifyFallbackOnce(project, describeThrowable(e));
                handedToLegacy = true;
                ApplicationManager.getApplication().invokeLater(
                        () -> legacyPreview(origin.selectedText, psiFile, function),
                        ModalityState.defaultModalityState());
                return;
            }
            long cost = System.currentTimeMillis() - start;
            LOG.info("[SQLPreview] fast path ok in " + cost + "ms");
            showResult(project, sql, "⚡ fast " + cost + "ms");
            function.apply();
        } catch (Throwable e) {
            LOG.warn("[SQLPreview] preview failed", e);
            notifyError("SQL 预览失败：" + describeThrowable(e), project);
            if (!handedToLegacy) {
                try {
                    ApplicationManager.getApplication().invokeLater(
                            () -> legacyPreview(origin.selectedText, psiFile, function),
                            ModalityState.defaultModalityState());
                    handedToLegacy = true;
                } catch (Throwable ignored) {
                }
            }
            function.apply();
        } finally {
            if (handedToLegacy) {
                LOG.info("[SQLPreview] handed to legacy path");
            }
        }
    }

    private static String describeThrowable(Throwable e) {
        if (e == null) {
            return "未知错误";
        }
        Throwable root = e;
        int guard = 0;
        while (root.getCause() != null && root.getCause() != root && guard++ < 5) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (StrUtil.isBlank(message) ? "" : ": " + message);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    /**
     * 快速通道失败时提示一次，避免用户完全无感地一直在走旧流程
     */
    /**
     * 按项目里真实的 mybatis-flex 方法签名修正重载参数（在后台线程调用）。
     */
    private String fixOverload(String text, PsiJavaFile psiFile) {
        if (StrUtil.isBlank(text) || (!text.contains("->") && !text.contains(".set("))) {
            return text;
        }
        try {
            Project project = ProjectUtils.getCurrentProject();
            List<String> classpath = SqlPreviewRunner.resolveClasspath(psiFile);
            if (classpath.isEmpty()) {
                return text;
            }
            String dialect = dialectDbType();
            ClassLoader loader = SqlPreviewLoaderCache.getInstance(project).get(classpath, dialect);
            return SqlPreviewChainFixer.fix(text, MybatisFlexMethods.of(loader));
        } catch (Throwable e) {
            LOG.debug("[SQLPreview] fix overload failed: " + e.getMessage());
            return text;
        }
    }

    /**
     * 构建进程内预览请求（只读 PSI，不改动任何源码）
     */
    private FastPreviewRequest buildFastPreviewRequest(String selectedText, PsiJavaFile psiFile) {
        // 调用方已包在 ReadAction 里
        String body = buildBody(selectedText, psiFile, true);
        if (StrUtil.isBlank(body)) {
            return null;
        }
        String source = buildFastSource(psiFile, body);
        String entityClassName = ObjectUtil.isNull(entityClass) ? null : entityClass.getQualifiedName();
        String packageName = psiFile.getPackageName();
        String className = StrUtil.isBlank(packageName) ? FAST_CLASS_NAME : packageName + "." + FAST_CLASS_NAME;
        return new FastPreviewRequest(source, entityClassName, selectedText, className);
    }

    private void notifyError(String message, Project project) {
        try {
            NotificationUtils.notifyError(message, "SQL Preview", project);
        } catch (Throwable ignored) {
        }
        LOG.warn("[SQLPreview] " + message);
    }

    private void notifyFallbackOnce(Project project, String reason) {
        if (!FALLBACK_NOTIFIED.compareAndSet(false, true)) {
            return;
        }
        NotificationUtils.notifyWarning("SQL 快速预览不可用，已回退旧流程。原因：" + reason
                + "（后续不再提示，详见 idea.log 中的 SqlPreviewRunner）", "SQL Preview", project);
    }

    /**
     * 带超时地执行预览代码
     */
    private String runFastPreview(Project project, PsiJavaFile psiFile, FastPreviewRequest request) {
        Future<String> future = PREVIEW_EXECUTOR.submit(
                () -> SqlPreviewRunner.preview(project, psiFile, request.className, request.source, request.entityClassName));
        try {
            return future.get(PREVIEW_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new SqlPreviewException("SQL 预览超时（" + PREVIEW_TIMEOUT_MS + "ms）");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SqlPreviewException) {
                throw (SqlPreviewException) cause;
            }
            throw new SqlPreviewException(cause == null ? e.getMessage() : cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SqlPreviewException("SQL 预览被中断");
        }
    }

    private void showResult(Project project, String sql, String meta) {
        if (StrUtil.isBlank(sql)) {
            notifyError("未解析到 SQL，请检查预览语句（生成代码见 idea.log）", project);
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                new SQLPreviewDialog(sql, event, meta).setVisible(true);
            } catch (Throwable e) {
                LOG.warn("[SQLPreview] show dialog failed", e);
                notifyError("SQL 预览弹窗失败：" + describeThrowable(e), project);
            }
        }, ModalityState.defaultModalityState());
    }

    /**
     * 旧流程：生成源文件 -> 工程编译 -> 独立 JVM 执行
     */
    private void legacyPreview(String selectedText, PsiJavaFile psiFile, SimpleFunction function) {
        this.list = null;
        this.entityClass = null;
        this.mutateSource = true;
        this.returnSqlResult = false;
        try {
            String body = buildBody(selectedText, psiFile, false);
            createFile(psiFile, StrUtil.format(CLASS_TEMPLATE, body), psiFile.getPackageName());
        } catch (Exception e) {
            LOG.warn("legacy sql preview failed", e);
        } finally {
            function.apply();
        }
    }

    /**
     * 组装预览方法体
     *
     * @param selectedText   选中的代码片段
     * @param psiFile        当前文件
     * @param returnSqlResult true：生成返回 SQL 的语句（进程内预览）；false：生成 System.out.println 语句（旧流程）
     */
    private String buildBody(String selectedText, PsiJavaFile psiFile, boolean returnSqlResult) {
        this.returnSqlResult = returnSqlResult;
        this.mutateSource = !returnSqlResult;
        // 防御：片段若还带着 .update()/.remove()，先截断，否则 toSQL() 会挂在 int 上
        selectedText = stripTerminalCall(selectedText);
        // 支持 return getOne(QueryWrapper.create()...) / getList(QueryWrapper...) 这种"外面包了一层"的写法
        selectedText = unwrapWrappedChain(selectedText);
        if (selectedText.startsWith("QueryWrapper")) {
            return StrUtil.format(statementTemplate(), selectedText);
        }
        // 处理链式调用
        return chain(selectedText, psiFile);
    }

    private String statementTemplate() {
        return returnSqlResult ? SQL_RESULT_ASSIGN : SYSTEM_OUT_PRINTLN_TO_SQL;
    }

    /**
     * 组装进程内预览的完整源码：包名 + 原文件导入 + 预览类
     */
    private String buildFastSource(PsiJavaFile psiFile, String body) {
        StringBuilder builder = new StringBuilder();
        String packageName = psiFile.getPackageName();
        if (StrUtil.isNotBlank(packageName)) {
            builder.append("package ").append(packageName).append(";\n\n");
        }
        Set<String> imports = new LinkedHashSet<>();
        PsiImportList importList = psiFile.getImportList();
        if (ObjectUtil.isNotNull(importList)) {
            for (PsiImportStatementBase statement : importList.getAllImportStatements()) {
                String importText = statement.getText();
                String qualified = StrUtil.subBetween(importText, "import", ";");
                if (ObjectUtil.isNull(qualified)) {
                    continue;
                }
                qualified = qualified.trim();
                if (excludeImportList.contains(qualified) || excludeImportList.contains("import " + qualified)) {
                    continue;
                }
                imports.add(qualified);
            }
        }
        if (CollUtil.isNotEmpty(list)) {
            imports.addAll(list);
        }
        for (String qualified : imports) {
            builder.append("import ").append(qualified).append(";\n");
        }
        builder.append('\n');
        builder.append(StrUtil.format(FAST_CLASS_TEMPLATE, FAST_CLASS_NAME, body));
        return builder.toString();
    }

    /**
     * 一次进程内预览请求
     */
    private static final class FastPreviewRequest {
        private final String source;
        private final String entityClassName;
        /**
         * 生成类的全限定名（含 package），必须和源码里的 package 一致，否则编译产物按 FQN 存放，
         * 用简单类名去取会取不到，报"编译产物中缺少主类"。
         */
        private final String className;
        /**
         * 回退时使用：重新走旧流程需要的原始片段
         */
        private final String selectedText;

        private FastPreviewRequest(String source, String entityClassName, String selectedText, String className) {
            this.className = className;
            this.source = source;
            this.entityClassName = entityClassName;
            this.selectedText = selectedText;
        }
    }

    /**
     * 创建文件
     *
     * @param psiFile
     * @param text
     * @param packageName
     */
    private void createFile(PsiJavaFile psiFile, String text, String packageName) {
        Project project = ProjectUtils.getCurrentProject();
        PsiJavaFile psiJavaFile = (PsiJavaFile) PsiFileFactory.getInstance(project).createFileFromText("MybatisFlexSqlPreview.java", JavaFileType.INSTANCE, text);
        psiJavaFile.setPackageName(psiFile.getPackageName());
        PsiImportList importList = psiFile.getImportList();
        PsiDirectory containingDirectory = psiFile.getContainingDirectory();
        WriteCommandAction.runWriteCommandAction(project, () -> {
            PsiImportList psiJavaFileImportList = psiJavaFile.getImportList();
            if (ObjectUtil.isNotNull(importList)) {
                Arrays.stream(importList.getAllImportStatements())
                        .filter(el -> {
                            String anImport = StrUtil.subBetween(el.getText(), "import ", ";").trim();
                            return !excludeImportList.contains(anImport);
                        })
                        .forEach(el -> psiJavaFileImportList.add(el));
            }
            if (CollUtil.isNotEmpty(list)) {
                for (String impor : list) {
                    PsiClass importClass = PsiJavaFileUtil.getPsiClass(impor);
                    if (importClass != null) {
                        psiJavaFileImportList.add(instance.createImportStatement(importClass));
                    }
                }
            }
            try {
                PsiFile file = containingDirectory.findFile(psiJavaFile.getName());
                if (ObjectUtil.isNotNull(file)) {
                    file.delete();
                }

                PsiElement element = containingDirectory.add(CodeReformat.reformat(psiJavaFile));
                VirtualFile virtualFile = element.getContainingFile().getVirtualFile();
                showSql(project, packageName, virtualFile);
                // compileJavaCode(psiJavaFile.getText());
                // showSql(project, packageName, null);

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * 处理链式调用
     * 约定：默认最后一个方法是执行数据库操作的方法
     *
     * @param text
     * @return
     */
    public String chain(String text, PsiJavaFile psiJavaFile) {
        if (text.contains("=")) {
            text = StrUtil.subAfter(text, "=", false).trim();
        }
        Map<String, String> qualifiedNameImportMap = PsiJavaFileUtil.getQualifiedNameImportMap(psiJavaFile);

        String val = null;
        if (text.startsWith("this.")) {
            text = StrUtil.subAfter(text, "this.", false);
        }
        // 处理service里面的链式调用
        String print = StrUtil.format(statementTemplate(), StrUtil.startWithAny(text, "queryChain()", "query()", "updateChain()") ? "service." + text : text);
        boolean flag = StrUtil.containsAny(text, "queryChain()", "query()", "updateChain()");
        AtomicReference<String> variableReference = new AtomicReference<>();
        String ofValue = null;
        if (flag) {
            AtomicReference<String> atomicReference = new AtomicReference<>();
            text = "\n" + getImplText(psiJavaFile, text, qualifiedNameImportMap, (entityClass, variableTem) -> {
                atomicReference.set(entityClass);
                variableReference.set(variableTem);
            });
            val = atomicReference.get();
        } else {
            ofValue = getOfValue(text, qualifiedNameImportMap, psiJavaFile);
            if (ObjectUtil.isNotNull(ofValue)) {
                if (!ofValue.contains(":")) {
                    val = ofValue;
                }
            } else {
                val = null;
            }
            // UpdateChain.of(getMapper()) / QueryChain.of(mapperField) 这类参数不是类名。
            // 直接当类名用会把 "getMapper(" 塞进 addMapper(...) 生成非法代码（line 43 语法错误）。
            // 这里从 ServiceImpl<Mapper, Entity> 泛型推出 Mapper，改成 Mappers.ofMapperClass(X.class)。
            String candidate = StrUtil.isNotBlank(val) ? val : ofValue;
            if (StrUtil.isNotBlank(candidate) && StrUtil.isBlank(qualifiedNameImportMap.get(candidate))) {
                String mapper = serviceImplMapper(psiJavaFile);
                if (StrUtil.isNotBlank(mapper)) {
                    String mapperFqn = qualifiedNameImportMap.get(mapper);
                    if (StrUtil.isBlank(mapperFqn)) {
                        String pkg = psiJavaFile.getPackageName();
                        mapperFqn = StrUtil.isBlank(pkg) ? mapper : pkg + "." + mapper;
                    }
                    print = print.replaceAll("of\\(\\s*[^()]*(\\(\\))?\\s*\\)",
                            "of(Mappers.ofMapperClass(" + mapper + ".class))");
                    val = mapper;
                    ofValue = mapper + ":" + mapperFqn;
                }
            }
            text = "";
        }
        Collection<PsiClass> implementors =
                PsiJavaFileUtil.getSonPsiClass(MybatisFlexConstant.MYBATISFLEX_CORE_BASE_MAPPER,
                        GlobalSearchScope.allScope(ProjectUtils.getCurrentProject()));
        Iterator<PsiClass> iterator = implementors.iterator();
        String qualifiedName = "";
        // 判断是不是对应的mapper
        if (StrUtil.isNotBlank(val)) {
            while (iterator.hasNext()) {
                PsiClass next = iterator.next();
                for (PsiClassType superType : next.getSuperTypes()) {
                    for (PsiType parameter : superType.getParameters()) {
                        if (parameter.getCanonicalText().endsWith(val)) {
                            val = next.getName();
                            qualifiedName = next.getQualifiedName();
                            break;
                        }
                    }
                }
            }
        }
        list = new ArrayList<>(IMPORT_LIST);
        if (ObjectUtil.isNotNull(ofValue) && ofValue.contains(":")) {
            String[] valueArr = ofValue.split(":");
            qualifiedName = valueArr[1];
            val = valueArr[0];
            print = print.replace("(this)", StrUtil.format("(Mappers.ofMapperClass({}.class))", val));
            list.add("com.mybatisflex.core.mybatis.Mappers");
        }
        if (StrUtil.isNotBlank(qualifiedName)) {
            list.add(qualifiedName);
        }
        if (flag) {
            text += StrUtil.format(TEMPLATE, val, val, variableReference.get());
            // 导入新的
            list.addAll(SERVICE_IMPORT_LIST);
        }
        if (mutateSource) {
            // 旧流程需要实体存在无参构造器；进程内预览改成字节码补齐，不动用户源码
            WriteCommandAction.runWriteCommandAction(ProjectUtils.getCurrentProject(), () -> {
                if (ObjectUtil.isNotNull(entityClass)) {
                    boolean hashConstructor = isHasConstructor(entityClass);
                    if (!hashConstructor) {
                        constructorMethod = instance.createMethodFromText(StrUtil.format("public {}(){}", entityClass.getName(), "{}"), null);
                        entityClass.add(constructorMethod);
                    }
                }
            });
        }

        text = StrUtil.format(COMMON_CODE, ObjectUtil.defaultIfNull(val,
                "Object"), dialectDbType()) + text + print;

        return text;
    }

    /**
     * 生成代码里用的是 {@code DbType.XXX} 枚举常量，必须大写（MYSQL / ORACLE ...）。
     * 配置里若为空或是小写（历史默认值 "mysql"），直接拼进去会变成 {@code DbType.mysql}
     * 这种不存在的枚举，编译报 cannot find symbol: variable mysql。
     */
    private static String dialectDbType() {
        String value = null;
        try {
            value = Template.getConfigData(MybatisFlexConstant.SQL_DIALECT, "MYSQL");
        } catch (Throwable ignored) {
        }
        if (StrUtil.isBlank(value)) {
            return "MYSQL";
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
        return StrUtil.isBlank(normalized) ? "MYSQL" : normalized;
    }

    /**
     * 剥掉包在外面的调用，例如 {@code return getOne(QueryWrapper.create()...)} 取出里面的 QueryWrapper 链。
     * 否则生成代码会把 "return getOne(" 一起塞进 String.valueOf(...) 里，直接语法错误。
     */
    private static String unwrapWrappedChain(String text) {
        if (StrUtil.isBlank(text)) {
            return text;
        }
        if (text.startsWith("return ")) {
            text = text.substring("return ".length()).trim();
        }
        if (text.startsWith("QueryWrapper")) {
            return text;
        }
        if (!StrUtil.contains(text, "QueryWrapper")) {
            return text;
        }
        int open = text.indexOf('(');
        int close = text.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return text;
        }
        String inner = text.substring(open + 1, close).trim();
        if (inner.startsWith("QueryWrapper")) {
            return inner;
        }
        return text;
    }

    /**
     * 去掉链式调用末尾的执行方法（.update()/.remove()），保留到最后一个 SQL 构建方法
     */
    private static String stripTerminalCall(String text) {
        if (StrUtil.isBlank(text)) {
            return text;
        }
        if (StrUtil.containsAny(text, ".update()", ".remove()")) {
            return StrUtil.subBefore(text, ".", true);
        }
        return text;
    }

    /**
     * 从当前文件的 ServiceImpl&lt;Mapper, Entity&gt; 泛型里取 Mapper 简单类名
     */
    private static String serviceImplMapper(PsiJavaFile psiJavaFile) {
        try {
            String mapper = StrUtil.subBetween(psiJavaFile.getText(), "ServiceImpl<", ",");
            if (StrUtil.isNotBlank(mapper)) {
                return mapper.trim();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String getOfValue(String text, Map<String, String> qualifiedNameImportMap, PsiJavaFile psiJavaFile) {
        String val = "";
        if (text.contains("of(")) {
            val = StrUtil.subBetween(text, "of(", ")");
        } else if (text.contains("create(")) {
            if (text.contains("create()")) {
                return null;
            }
            val = StrUtil.subBetween(text, "create(", ")");
        } else {
            return null;
//        //获取类
        }
        String entityName = null;

        if (val.startsWith("Mappers")) {
            val = StrUtil.subBetween(val, "(", ".class");

        } else if ("this".equals(val)) {
            PsiClass psiClass = psiJavaFile.getClasses()[0];
            entityName = StrUtil.subBetween(psiClass.getText(), "BaseMapper<", ">");
            val = psiClass.getName() + ":" + psiClass.getQualifiedName();
        } else {
            val = StrUtil.subBefore(val, ".", false);
        }

        entityClass = PsiJavaFileUtil.getPsiClass(qualifiedNameImportMap.get(ObjectUtil.defaultIfNull(entityName, val)));

        return val;
    }

    private boolean isHasConstructor(PsiClass entityClass) {
        boolean hashConstructor = false;
        PsiMethod[] constructors = entityClass.getConstructors();
        for (PsiMethod constructor : constructors) {
            if (constructor.getText().startsWith("public") && constructor.getParameters().length == 0) {
                hashConstructor = true;
                break;
            }
        }
        return hashConstructor;
    }

    private void removeNoArgsConstructor(PsiClass entityClass) {
        PsiMethod[] constructors = entityClass.getConstructors();
        for (PsiMethod constructor : constructors) {
            if (constructor.getText().startsWith("public") && constructor.getParameters().length == 0) {
                constructor.delete();
                break;
            }
        }
    }


    public String getImplText(PsiJavaFile psiJavaFile, String selectedText, Map<String, String> qualifiedNameImportMap, ManyFunction<String> consumer) {
        String temVal = "";
        Project project = psiJavaFile.getProject();
        PsiClass[] classes = psiJavaFile.getClasses();
        if (StrUtil.startWithAny(selectedText, "query()", "queryChain()", "updateChain()")) {
            String name = StrUtil.subBefore(psiJavaFile.getName(), ".", true);
            temVal = StrUtil.format("{} service=new {}();\n", name, name);
            String text = psiJavaFile.getText();
            String mapper = StrUtil.subBetween(text, "ServiceImpl<", ",").trim();
            consumer.applay(qualifiedNameImportMap.get(mapper), "service");
            return temVal;
        }
        for (PsiClass psiClass : classes) {
            PsiField[] allFields = psiClass.getAllFields();
            for (PsiField field : allFields) {
                if (selectedText.contains(field.getName())) {
                    String text = field.getText();
                    if (text.contains("=")) {
                        text = StrUtil.subBefore(text, "=", true);
                    }
                    if (text.contains("\n")) {
                        String[] split = text.split("\n");
                        text = split[split.length - 1];
                    }
                    text = text.replace(";", "").trim();
                    while (!Character.isUpperCase(text.charAt(0))) {
                        text = StrUtil.subAfter(text, " ", false);
                    }
                    String className = StrUtil.subBefore(text, " ", true);
                    String qualifiedName = qualifiedNameImportMap.get(className);
                    if (StrUtil.isBlank(qualifiedName)) {
                        // 片段里引用的类没有 import 映射（比如手写变量、同包类），跳过，不能拿 null 去查 PSI
                        LOG.debug("[SQLPreview] no import mapping for " + className + ", skip");
                        continue;
                    }
                    // 获取全局搜索范围
                    Collection<PsiClass> implementors = PsiJavaFileUtil.getSonPsiClass(qualifiedName, GlobalSearchScope.allScope(project));
                    if (CollUtil.isEmpty(implementors)) {
                        // 如果没有找到子类，就默认自身是实现类
                        implementors.add(PsiJavaFileUtil.getPsiClass(qualifiedName));
                    }
                    PsiClass sonPsiClass = implementors.iterator().next();
                    String genericity = PsiJavaFileUtil.getGenericity(sonPsiClass);
                    String name = sonPsiClass.getName();
                    consumer.applay(genericity, StrUtil.subAfter(text, " ", true));
                    if (text.contains(field.getName())) {
                        temVal = StrUtil.format("{}=new {}();\n", text, name);
                    }
                }
            }
        }
        return temVal;
    }


    private void showSql(Project project, String packageName, VirtualFile virtualFile) {
        ArrayList<VirtualFile> virtualFiles = new ArrayList<VirtualFile>();
        if (ObjectUtil.isNotNull(entityClass)) {
            virtualFiles.add(entityClass.getContainingFile().getVirtualFile());
        }
        virtualFiles.add(virtualFile);
        CompilerManagerUtil.compile(virtualFiles.toArray(new VirtualFile[0]), (b, i, i1, compileContext) -> {
        try {
                ApplicationManager.getApplication().invokeLater(() -> {
                    WriteCommandAction.runWriteCommandAction(project, () -> {
                        try {
                            if (!MybatisFlexSettingDialog.insideSchemaFlag) {
                                virtualFile.delete(this);
                            }
                            if (ObjectUtil.isNotNull(entityClass)) {
                                removeNoArgsConstructor(entityClass);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                }, ModalityState.defaultModalityState());
            // 执行配置
            ProgramRunner runner = ProgramRunner.PROGRAM_RUNNER_EP.getExtensions()[0];
            Executor instance = MyBatisLogExecutor.getInstance();
            RunManagerEx runManager = (RunManagerEx) RunManager.getInstance(project);
            RunnerAndConfigurationSettings defaultSettings = runManager.getSelectedConfiguration();
            ExecutionEnvironment environment = new ExecutionEnvironment(instance, runner, defaultSettings, project);
            // 创建 Java 执行配置
            JavaCommandLineState commandLineState = new JavaCommandLineState(environment) {
                @Override
                protected JavaParameters createJavaParameters() throws ExecutionException {
                    JavaParameters params = new JavaParameters();
                    Sdk projectSdk = ProjectRootManager.getInstance(project).getProjectSdk();
                    params.configureByProject(project, JavaParameters.JDK_AND_CLASSES_AND_TESTS, projectSdk);
                    // params.setMainClass(packageName + ".MybatisFlexSqlPreview");
                    params.setMainClass(packageName + ".MybatisFlexSqlPreview");
                    return params;
                }
            };
            ExecutionResult executionResult = commandLineState.execute(instance, runner);
            if (ObjectUtil.isNotNull(executionResult)) {
                ProcessHandler processHandler = executionResult.getProcessHandler();
                processHandler.addProcessListener(new ProcessAdapter() {
                    @Override
                    public void onTextAvailable(ProcessEvent event1, Key outputType) {
                        if (ProcessOutputTypes.STDOUT.equals(outputType)) {
                            if (StrUtil.startWithAnyIgnoreCase(event1.getText(), "SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "DROP", "ALTER", "TRUNCATE","WITH")) {
                                new SQLPreviewDialog(event1.getText(), event, "legacy").setVisible(true);
                            }
                        } else if (ProcessOutputTypes.STDERR.equals(outputType)) {
                            String text = event1.getText();
                            System.out.println(text);
                            if (text.startsWith("Exception in")) {
                                NotificationUtils.notifyError((StrUtil.subAfter(text, ":", true)), "Mybatis-Flex system tips");
                            }
                        }
                    }
                });
                processHandler.startNotify();
            }
        } catch (
                Exception e) {
            Messages.showErrorDialog("Error executing code:\n" + e.getMessage(), "Code Execution Error");
        }
        });
    }


    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        // 如果是从项目视图中右键点击的进来的则创建新的类
        String selectedText = e.getData(CommonDataKeys.EDITOR).getSelectionModel().getSelectedText();
        if (StrUtil.isNotBlank(selectedText)) {
            PsiJavaFile psiFile = (PsiJavaFile) e.getData(CommonDataKeys.PSI_FILE);
            preview(selectedText, psiFile,e, () -> {
            });
            return;
        }

    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(AnActionEvent e) {
        e.getPresentation().setEnabled(PsiJavaFileUtil.isFlexProject());
    }
}
