package club.bigtian.mf.plugin.core.preview;

import club.bigtian.mf.plugin.core.Template;
import club.bigtian.mf.plugin.core.constant.MybatisFlexConstant;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiJavaFile;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * SQL 预览快速执行器。
 * <p>
 * 流程：内存编译 -&gt; 复用项目级框架类加载器 -&gt; 为生成类单独建子加载器 -&gt; 进程内反射调用。
 * 相比旧流程省掉了：写源码文件、IDEA 工程编译、启动独立 JVM、解析控制台输出。
 */
public final class SqlPreviewRunner {

    /**
     * 预览代码内部捕获异常时返回的前缀
     */
    public static final String ERROR_MARKER = "__SQL_PREVIEW_ERROR__";

    private static final Logger LOG = Logger.getInstance(SqlPreviewRunner.class);

    private SqlPreviewRunner() {
    }

    /**
     * 进程内执行预览代码，返回 SQL
     *
     * @param project     当前项目
     * @param contextFile 触发预览的源文件
     * @param className   生成的类限定名
     * @param source      生成的源码
     * @param entityClass 需要补齐无参构造器的实体类限定名，可为 null
     * @return SQL 文本
     */
    public static String preview(Project project, PsiJavaFile contextFile, String className, String source,
                                 String entityClass) {
        long start = System.currentTimeMillis();
        List<String> classpath = resolveClasspath(contextFile);
        if (classpath.isEmpty()) {
            throw new SqlPreviewException("项目运行 classpath 为空，请检查模块 SDK 与依赖配置");
        }
        SqlPreviewCompiler.CompiledClasses compiled = SqlPreviewCompiler.compile(className, source, classpath);
        long compiledAt = System.currentTimeMillis();
        byte[] mainBytes = compiled.getMainBytes();
        if (mainBytes == null) {
            throw new SqlPreviewException("编译产物中缺少主类 " + className);
        }
        String dialect = Template.getConfigData(MybatisFlexConstant.SQL_DIALECT, "mysql");
        SqlPreviewClassLoader frameworkLoader = SqlPreviewLoaderCache.getInstance(project).get(classpath, dialect);
        frameworkLoader.patchEntityIfNeeded(entityClass);
        SqlPreviewGeneratedLoader generatedLoader = new SqlPreviewGeneratedLoader(frameworkLoader);
        try {
            Class<?> previewClass = generatedLoader.define(className, mainBytes);
            Method method = previewClass.getMethod("preview");
            Object result = method.invoke(null);
            String sql = result == null ? null : String.valueOf(result);
            if (sql != null && sql.startsWith(ERROR_MARKER)) {
                throw new SqlPreviewException("预览代码执行失败：" + sql.substring(ERROR_MARKER.length()));
            }
            LOG.debug("SQL preview finished in " + (System.currentTimeMillis() - start) + "ms (compile "
                    + (compiledAt - start) + "ms, run " + (System.currentTimeMillis() - compiledAt) + "ms)");
            return sql;
        } catch (SqlPreviewException e) {
            throw e;
        } catch (InvocationTargetException e) {
            Throwable target = e.getTargetException();
            throw new SqlPreviewException("预览代码执行失败：" + describe(target), target);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            throw new SqlPreviewException("项目依赖未编译或缺少类：" + e.getMessage(), e);
        } catch (Exception e) {
            throw new SqlPreviewException("预览代码执行失败：" + describe(e), e);
        }
    }

    private static String describe(Throwable throwable) {
        if (throwable == null) {
            return "未知错误";
        }
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName() + (StrUtil.isBlank(message) ? "" : ": " + message);
    }

    /**
     * 解析某个源文件所属模块的运行 classpath
     */
    public static List<String> resolveClasspath(PsiJavaFile contextFile) {
        Module module = ReadAction.compute(() -> ModuleUtilCore.findModuleForPsiElement(contextFile));
        if (ObjectUtil.isNull(module)) {
            throw new SqlPreviewException("无法定位当前文件所属模块");
        }
        return resolveClasspath(module);
    }

    /**
     * 解析模块的运行 classpath（模块输出目录 + 依赖库 + SDK）
     */
    private static List<String> resolveClasspath(Module module) {
        try {
            JavaParameters parameters = new JavaParameters();
            parameters.configureByModule(module, JavaParameters.JDK_AND_CLASSES_AND_TESTS);
            Set<String> paths = new LinkedHashSet<>(parameters.getClassPath().getPathList());
            return new ArrayList<>(paths);
        } catch (Exception e) {
            LOG.debug("resolve classpath failed: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 每次预览专用：只承载生成的类，父加载器是项目级框架类加载器（已预热）。
     */
    private static final class SqlPreviewGeneratedLoader extends ClassLoader {

        static {
            registerAsParallelCapable();
        }

        private SqlPreviewGeneratedLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(String className, byte[] bytes) {
            return defineClass(className, bytes, 0, bytes.length);
        }
    }
}
