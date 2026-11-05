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
        return compile(className, source, classpath, null);
    }

    /**
     * 编译预览代码
     *
     * @param className 主类限定名
     * @param source    完整源码
     * @param classpath 项目运行 classpath
     * @param javacPath 进程内编译器不可用时，用这个 javac 可执行文件兜底（可为 null）
     * @return 类名 -&gt; class 字节码
     */
    public static CompiledClasses compile(String className, String source, List<String> classpath, String javacPath) {
        String cacheKey = cacheKey(className, source);
        synchronized (CACHE) {
            CompiledClasses cached = CACHE.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        CompiledClasses compiled = doCompile(className, source, classpath, javacPath);
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

    private static CompiledClasses doCompile(String className, String source, List<String> classpath, String javacPath) {
        JavaCompiler compiler = createCompiler();
        if (compiler == null) {
            // 进程内拿不到 javac（IntelliJ 的类加载环境、以及部分 JBR 运行时都会这样），
            // 用项目 SDK 的 javac 兜底，依然不碰 IDEA 的工程编译
            if (javacPath != null && !javacPath.isEmpty()) {
                return compileWithJavacProcess(className, source, classpath, javacPath);
            }
            throw new SqlPreviewException("当前运行环境没有可用的 javac");
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

    /**
     * 用外部 javac 编译（进程内编译器不可用时的兜底）。
     * <p>
     * 源码写到系统临时目录、产物读回内存后立刻删除，不落进用户工程。
     */
    private static CompiledClasses compileWithJavacProcess(String className, String source, List<String> classpath,
                                                           String javacPath) {
        java.nio.file.Path workDir = null;
        try {
            workDir = java.nio.file.Files.createTempDirectory("mf-sqlpreview-");
            String simpleName = className.substring(className.lastIndexOf('.') + 1);
            java.nio.file.Path sourceFile = workDir.resolve(simpleName + ".java");
            java.nio.file.Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));
            java.nio.file.Path outDir = workDir.resolve("out");
            java.nio.file.Files.createDirectories(outDir);

            List<String> command = new ArrayList<>();
            command.add(javacPath);
            command.add("-proc:none");
            command.add("-nowarn");
            command.add("-encoding");
            command.add("UTF-8");
            command.add("-d");
            command.add(outDir.toString());
            if (!classpath.isEmpty()) {
                command.add("-classpath");
                command.add(String.join(java.io.File.pathSeparator, classpath));
            }
            command.add(sourceFile.toString());

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            byte[] outputBytes = readAll(process.getInputStream());
            if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new SqlPreviewException("预览代码编译超时");
            }
            if (process.exitValue() != 0) {
                throw new SqlPreviewException("预览代码编译失败：" + shorten(new String(outputBytes, StandardCharsets.UTF_8)));
            }
            Map<String, byte[]> classes = new HashMap<>();
            try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(outDir)) {
                for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) stream::iterator) {
                    if (!path.toString().endsWith(".class")) {
                        continue;
                    }
                    String relative = outDir.relativize(path).toString();
                    String name = relative.substring(0, relative.length() - ".class".length())
                            .replace(java.io.File.separatorChar, '.');
                    classes.put(name, java.nio.file.Files.readAllBytes(path));
                }
            }
            return new CompiledClasses(className, classes);
        } catch (SqlPreviewException e) {
            throw e;
        } catch (Exception e) {
            throw new SqlPreviewException("预览代码编译失败：" + e.getMessage(), e);
        } finally {
            deleteRecursively(workDir);
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void deleteRecursively(java.nio.file.Path dir) {
        if (dir == null) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    java.nio.file.Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= 800 ? trimmed : trimmed.substring(0, 800) + " ...";
    }

    /**
     * 创建编译器。
     * <p>
     * 不能只用 {@link ToolProvider#getSystemJavaCompiler()}：IntelliJ 运行时通过
     * {@code -Djava.system.class.loader=com.intellij.util.lang.PathClassLoader} 换掉了系统类加载器，
     * 而 JDK 的 ToolProvider 是用系统类加载器去找 {@code com.sun.tools.javac.api.JavacTool} 的，
     * 结果直接返回 null（插件在 IDE 里跑、在 IntelliJ 的测试沙箱里跑都是这个情况）。
     * 兜底方案是用自己的类加载器反射 jdk.compiler 里的 JavacTool。
     */
    private static JavaCompiler createCompiler() {
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler != null) {
                return compiler;
            }
        } catch (Throwable ignored) {
            // 继续走反射兜底
        }
        try {
            Class<?> toolClass = Class.forName("com.sun.tools.javac.api.JavacTool");
            Object tool = toolClass.getMethod("create").invoke(null);
            if (tool instanceof JavaCompiler) {
                return (JavaCompiler) tool;
            }
        } catch (Throwable ignored) {
        }
        return null;
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

        /**
         * 编译产物里主类的真实名字。源码的 package 与调用方传入的不一致时，按简单类名兜底匹配，
         * 因为 defineClass 必须用 class 文件里的真实名字，否则会报 wrong name。
         */
        public String resolveMainClassName() {
            if (classes.containsKey(mainClassName)) {
                return mainClassName;
            }
            String simpleName = mainClassName.substring(mainClassName.lastIndexOf('.') + 1);
            for (String name : classes.keySet()) {
                if (name.equals(simpleName) || name.endsWith("." + simpleName)) {
                    return name;
                }
            }
            return mainClassName;
        }

        public byte[] getMainBytes() {
            return classes.get(resolveMainClassName());
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
