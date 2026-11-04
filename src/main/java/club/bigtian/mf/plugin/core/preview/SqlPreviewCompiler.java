package club.bigtian.mf.plugin.core.preview;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 预览代码的内存编译器。
 * <p>
 * 与旧流程（写入用户源码目录 -> 触发 IDEA 工程编译）的区别：
 * <ul>
 *     <li>不落盘、不改动用户源码树，不产生 VCS 变更</li>
 *     <li>不走工程构建系统，不触发整个模块的增量编译</li>
 *     <li>编译产物是字节码，按源码哈希缓存，重复预览同一片段时直接命中缓存</li>
 * </ul>
 */
public final class SqlPreviewCompiler {

    /**
     * 缓存版本，编译流程变化时递增即可让旧缓存失效
     */
    private static final String CACHE_VERSION = "v1";

    private static final int MAX_CACHE_SIZE = 64;

    private static final Map<String, CompiledClasses> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CompiledClasses> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    private SqlPreviewCompiler() {
    }

    /**
     * 编译预览代码
     *
     * @param className 主类限定名
     * @param source    完整源码
     * @param classpath 项目运行 classpath
     * @return 类名 -&gt; class 字节码
     */
    public static CompiledClasses compile(String className, String source, List<String> classpath) {
        String cacheKey = cacheKey(className, source);
        synchronized (CACHE) {
            CompiledClasses cached = CACHE.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        CompiledClasses compiled = doCompile(className, source, classpath);
        synchronized (CACHE) {
            CACHE.put(cacheKey, compiled);
        }
        return compiled;
    }

    /**
     * 清空字节码缓存
     */
    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    private static CompiledClasses doCompile(String className, String source, List<String> classpath) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new SqlPreviewException("当前运行环境没有 javac（jdk.compiler 模块缺失）");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager standardManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
        MemoryFileManager fileManager = new MemoryFileManager(standardManager);
        List<String> options = new ArrayList<>();
        // 预览代码是纯语法糖，不需要注解处理器（Lombok / APT 都已在项目里编译完成）
        options.add("-proc:none");
        options.add("-nowarn");
        options.add("-Xlint:none");
        options.add("-encoding");
        options.add("UTF-8");
        if (!classpath.isEmpty()) {
            options.add("-classpath");
            options.add(String.join(java.io.File.pathSeparator, classpath));
        }
        try {
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null,
                    Collections.singletonList(new StringSource(className, source)));
            Boolean success = task.call();
            if (!Boolean.TRUE.equals(success)) {
                throw new SqlPreviewException("预览代码编译失败：" + describe(diagnostics));
            }
            return new CompiledClasses(className, fileManager.getClasses());
        } catch (SqlPreviewException e) {
            throw e;
        } catch (Exception e) {
            throw new SqlPreviewException("预览代码编译失败：" + e.getMessage(), e);
        } finally {
            try {
                standardManager.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String describe(DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() != Diagnostic.Kind.ERROR) {
                continue;
            }
            if (count++ > 0) {
                builder.append("; ");
            }
            builder.append("line ").append(diagnostic.getLineNumber()).append(": ")
                    .append(diagnostic.getMessage(Locale.ROOT));
            if (count >= 3) {
                builder.append(" ...");
                break;
            }
        }
        return builder.length() == 0 ? "未知错误" : builder.toString();
    }

    private static String cacheKey(String className, String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(CACHE_VERSION.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(className.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(source.getBytes(StandardCharsets.UTF_8));
            byte[] bytes = digest.digest();
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception e) {
            return className + ":" + source.hashCode();
        }
    }

    /**
     * 编译产物
     */
    public static final class CompiledClasses {
        private final String mainClassName;
        private final Map<String, byte[]> classes;

        CompiledClasses(String mainClassName, Map<String, byte[]> classes) {
            this.mainClassName = mainClassName;
            this.classes = classes;
        }

        public String getMainClassName() {
            return mainClassName;
        }

        public Map<String, byte[]> getClasses() {
            return classes;
        }

        public byte[] getMainBytes() {
            return classes.get(mainClassName);
        }
    }

    private static final class StringSource extends SimpleJavaFileObject {
        private final String code;

        StringSource(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
                    JavaFileObject.Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    private static final class MemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, byte[]> classes = new HashMap<>();
        private final Map<String, ByteArrayOutputStream> outputs = new HashMap<>();

        MemoryFileManager(StandardJavaFileManager fileManager) {
            super(fileManager);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                                                   FileObject sibling) {
            return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
                @Override
                public OutputStream openOutputStream() {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    outputs.put(className, out);
                    return out;
                }
            };
        }

        Map<String, byte[]> getClasses() {
            for (Map.Entry<String, ByteArrayOutputStream> entry : outputs.entrySet()) {
                classes.put(entry.getKey(), entry.getValue().toByteArray());
            }
            return classes;
        }
    }

}
