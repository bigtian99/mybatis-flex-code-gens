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
        selectedText = fixOverload(selectedText, psiFile);
        try {
            FastPreviewRequest request = buildFastPreviewRequest(selectedText, psiFile);
            if (ObjectUtil.isNotNull(request)) {
                submitFastPreview(project, psiFile, request, function);
                return;
            }
        } catch (Throwable e) {
            LOG.debug("build fast sql preview failed, fallback to legacy", e);
        }
        legacyPreview(selectedText, psiFile, function);
    }

    /**
     * 按项目里真实的 mybatis-flex 方法签名修正重载参数。
     * <p>
     * MyBatis-Flex 的同名方法大量重载（{@code isNull(boolean)}、{@code eq(Object, boolean)}、
     * {@code between(Object, Object, boolean)} 等），预览片段里的 {@code el -> true}
     * 只有落在函数式接口参数上才成立，落不到时必须改成 {@code true}，否则生成的代码没有对应重载。
     *
     * @param text    预览片段
     * @param psiFile 当前文件
     * @return 修正后的片段，解析失败时原样返回
     */
    private String fixOverload(String text, PsiJavaFile psiFile) {
        if (StrUtil.isBlank(text) || !text.contains("->")) {
            return text;
        }
        try {
            Project project = ProjectUtils.getCurrentProject();
            List<String> classpath = SqlPreviewRunner.resolveClasspath(psiFile);
            if (classpath.isEmpty()) {
                return text;
            }
            ClassLoader loader = SqlPreviewLoaderCache.getInstance(project).get(classpath);
            return SqlPreviewChainFixer.fix(text, MybatisFlexMethods.of(loader));
        } catch (Throwable e) {
            LOG.debug("fix overload failed: " + e.getMessage());
            return text;
        }
    }

    /**
     * 构建进程内预览请求（只读 PSI，不改动任何源码）
     *
     * @return 无法构建时返回 null，交由旧流程处理
     */
    private FastPreviewRequest buildFastPreviewRequest(String selectedText, PsiJavaFile psiFile) {
        String body = ReadAction.compute((ThrowableComputable<String, RuntimeException>) () -> buildBody(selectedText, psiFile, true));
        if (StrUtil.isBlank(body)) {
            return null;
        }
        String source = buildFastSource(psiFile, body);
        String entityClassName = ObjectUtil.isNull(entityClass) ? null : entityClass.getQualifiedName();
        return new FastPreviewRequest(source, entityClassName, selectedText);
    }

    /**
     * 后台线程执行进程内预览，成功则弹窗，失败回退旧流程
     */
    private void submitFastPreview(Project project, PsiJavaFile psiFile, FastPreviewRequest request,
                                   SimpleFunction function) {
        AppExecutorUtil.getAppExecutorService().submit(() -> {
            boolean fallback = true;
            try {
                String sql = runFastPreview(project, psiFile, request);
                showResult(project, sql);
                fallback = false;
            } catch (SqlPreviewException e) {
                LOG.debug("fast sql preview failed, fallback to legacy: " + e.getMessage());
            } catch (Throwable e) {
                LOG.warn("fast sql preview error", e);
            } finally {
                if (fallback) {
                    ApplicationManager.getApplication().invokeLater(
                            () -> legacyPreview(request.selectedText, psiFile, function),
                            ModalityState.defaultModalityState());
                } else {
                    function.apply();
                }
            }
        });
    }

    /**
     * 带超时地执行预览代码
     */
    private String runFastPreview(Project project, PsiJavaFile psiFile, FastPreviewRequest request) {
        Future<String> future = PREVIEW_EXECUTOR.submit(
                () -> SqlPreviewRunner.preview(project, psiFile, FAST_CLASS_NAME, request.source, request.entityClassName));
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

    private void showResult(Project project, String sql) {
        if (StrUtil.isBlank(sql)) {
            NotificationUtils.notifyWarning("未解析到 SQL，请检查预览语句", "SQL Preview", project);
            return;
        }
        ApplicationManager.getApplication().invokeLater(
                () -> new SQLPreviewDialog(sql, event).setVisible(true),
                ModalityState.defaultModalityState());
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
         * 回退时使用：重新走旧流程需要的原始片段
         */
        private final String selectedText;

        private FastPreviewRequest(String source, String entityClassName, String selectedText) {
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
                    psiJavaFileImportList.add(instance.createImportStatement(PsiJavaFileUtil.getPsiClass(impor)));
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
                "Object"), Template.getConfigData(MybatisFlexConstant.SQL_DIALECT,"mysql")) + text + print;

        return text;
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
                                new SQLPreviewDialog(event1.getText(),event).setVisible(true);
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
