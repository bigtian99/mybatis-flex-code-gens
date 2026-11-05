package club.bigtian.mf.plugin.core.preview;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.compiler.CompilationStatusListener;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompilerTopics;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.messages.MessageBusConnection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 项目级"框架类加载器"缓存。
 * <p>
 * 单次预览里 MyBatis-Flex 的静态初始化占了绝大部分时间（约 78ms / 80ms），把加载器缓存下来
 * 之后每次预览只需要给生成的类单独建一个子加载器，耗时降到 1~3ms。
 * <p>
 * 失效时机：
 * <ul>
 *     <li>classpath 变化（依赖增删、模块切换）</li>
 *     <li>项目里发生了一次编译（用户重新编译后，实体 / Mapper / TableDef 的字节码可能已经更新）</li>
 *     <li>项目关闭</li>
 * </ul>
 */
public final class SqlPreviewLoaderCache implements Disposable {

    private static final Logger LOG = Logger.getInstance(SqlPreviewLoaderCache.class);

    /**
     * 按项目缓存，项目关闭时随 Disposer 一起释放
     */
    private static final Map<Project, SqlPreviewLoaderCache> INSTANCES = new ConcurrentHashMap<>();

    private final Project project;

    private String fingerprint;

    private SqlPreviewClassLoader loader;

    private volatile boolean compilationHookInstalled;

    private SqlPreviewLoaderCache(Project project) {
        this.project = project;
    }

    public static SqlPreviewLoaderCache getInstance(Project project) {
        return INSTANCES.computeIfAbsent(project, key -> {
            SqlPreviewLoaderCache cache = new SqlPreviewLoaderCache(key);
            Disposer.register(key, cache);
            return cache;
        });
    }

    /**
     * 获取（或创建）框架类加载器
     *
     * @param classpath 项目运行 classpath
     * @param salt      额外影响预览结果的配置（例如方言），变化时重建加载器
     */
    public synchronized SqlPreviewClassLoader get(List<String> classpath, String salt) {
        installCompilationHook();
        String current = fingerprint(classpath, salt);
        if (loader != null && current.equals(fingerprint)) {
            return loader;
        }
        closeQuietly();
        loader = SqlPreviewClassLoader.of(classpath);
        fingerprint = current;
        LOG.debug("sql preview classloader created, entries=" + classpath.size());
        return loader;
    }

    /**
     * 主动失效（例如项目编译完成后）
     */
    public synchronized void invalidate() {
        closeQuietly();
        fingerprint = null;
    }

    @Override
    public void dispose() {
        invalidate();
        INSTANCES.remove(project, this);
    }

    private void installCompilationHook() {
        if (compilationHookInstalled) {
            return;
        }
        compilationHookInstalled = true;
        try {
            MessageBusConnection connection = project.getMessageBus().connect(this);
            connection.subscribe(CompilerTopics.COMPILATION_STATUS, new CompilationStatusListener() {
                @Override
                public void compilationFinished(boolean aborted, int errors, int warnings, CompileContext compileContext) {
                    // 用户刚编译过，实体 / Mapper 的字节码可能变了，扔掉旧的加载器
                    invalidate();
                    SqlPreviewCompiler.clearCache();
                }
            });
        } catch (Throwable e) {
            LOG.debug("install compilation hook failed: " + e.getMessage());
        }
    }

    private void closeQuietly() {
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (Exception ignored) {
        }
        loader = null;
    }

    private static String fingerprint(List<String> classpath, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(String.valueOf(salt).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            for (String path : classpath) {
                digest.update(String.valueOf(path).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            byte[] bytes = digest.digest();
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception e) {
            return String.valueOf(classpath.hashCode()) + "|" + salt;
        }
    }
}
