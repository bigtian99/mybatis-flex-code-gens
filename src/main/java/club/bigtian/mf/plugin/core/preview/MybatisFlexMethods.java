package club.bigtian.mf.plugin.core.preview;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 项目里 MyBatis-Flex 方法重载的索引。
 * <p>
 * 预览代码是"按方法名 + 启发式参数"拼出来的，而 MyBatis-Flex 里同名方法大量重载
 * （例如 {@code eq(Object)} / {@code eq(Object, boolean)}、{@code isNull(boolean)}），
 * 只按名字改写很容易生成一个"没有对应重载"的调用，导致预览直接编译失败。
 * <p>
 * 这里不再硬编码方法表，而是从项目真实的 mybatis-flex jar 里扫描
 * {@code com.mybatisflex.core.query} / {@code com.mybatisflex.core.update} 下的类，
 * 建立起"方法名 -&gt; 各重载参数类型"的索引，供 {@link SqlPreviewChainFixer} 判断某个位置到底
 * 该写 {@code true} 还是写 lambda。
 * <p>
 * 索引按类加载器缓存，随框架类加载器失效一起失效。
 */
public final class MybatisFlexMethods {

    /**
     * MyBatis-Flex 里参与链式调用的包
     */
    private static final String[] SCAN_PACKAGES = {
            "com/mybatisflex/core/query/",
            "com/mybatisflex/core/update/",
            "com/mybatisflex/core/table/"
    };

    private static final Map<ClassLoader, MybatisFlexMethods> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 方法名 -&gt; 所有重载的参数类型
     */
    private final Map<String, List<Class<?>[]>> overloads = new HashMap<>();

    private MybatisFlexMethods() {
    }

    /**
     * 获取（并缓存）某个类加载器对应的重载索引
     */
    public static MybatisFlexMethods of(ClassLoader loader) {
        if (loader == null) {
            return EMPTY;
        }
        return CACHE.computeIfAbsent(loader, MybatisFlexMethods::scan);
    }

    private static final MybatisFlexMethods EMPTY = new MybatisFlexMethods();

    /**
     * 供单元测试构造一个只包含指定重载的索引
     *
     * @param table 方法名 -&gt; 各重载的参数类型
     */
    static MybatisFlexMethods forTesting(Map<String, List<Class<?>[]>> table) {
        MybatisFlexMethods index = new MybatisFlexMethods();
        index.overloads.putAll(table);
        return index;
    }

    private static MybatisFlexMethods scan(ClassLoader loader) {
        MybatisFlexMethods index = new MybatisFlexMethods();
        Set<String> classNames = new LinkedHashSet<>();
        try {
            for (URL url : ((java.net.URLClassLoader) loader).getURLs()) {
                collectClassNames(url, classNames);
            }
        } catch (Throwable e) {
            return index;
        }
        for (String className : classNames) {
            try {
                Class<?> clazz = Class.forName(className, false, loader);
                if (clazz.isInterface() && !Modifier.isPublic(clazz.getModifiers())) {
                    continue;
                }
                for (Method method : clazz.getMethods()) {
                    if (method.getDeclaringClass() == Object.class) {
                        continue;
                    }
                    String name = method.getName();
                    if ("<init>".equals(name) || name.startsWith("lambda$")) {
                        continue;
                    }
                    index.overloads.computeIfAbsent(name, key -> new ArrayList<>())
                            .add(method.getParameterTypes());
                }
            } catch (Throwable ignored) {
                // 某些类可能因为缺少可选依赖加载不了，跳过即可
            }
        }
        return index;
    }

    private static void collectClassNames(URL url, Set<String> classNames) {
        try {
            File file = new File(url.toURI());
            if (file.isDirectory()) {
                File root = new File(file, "com/mybatisflex");
                collectFromDirectory(root, root, classNames);
                return;
            }
            if (!file.getName().endsWith(".jar")) {
                return;
            }
            try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(file)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    java.util.zip.ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.endsWith(".class") && isScanned(name) && !name.contains("$")) {
                        classNames.add(name.substring(0, name.length() - 6).replace('/', '.'));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void collectFromDirectory(File root, File current, Set<String> classNames) {
        if (current == null || !current.exists()) {
            return;
        }
        File[] files = current.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                collectFromDirectory(root, file, classNames);
            } else if (file.getName().endsWith(".class") && !file.getName().contains("$")) {
                String path = file.getAbsolutePath().substring(root.getAbsolutePath().length() - "com/mybatisflex".length())
                        .replace(File.separatorChar, '/');
                if (isScanned(path)) {
                    classNames.add(path.substring(0, path.length() - 6).replace('/', '.'));
                }
            }
        }
    }

    private static boolean isScanned(String entryName) {
        for (String prefix : SCAN_PACKAGES) {
            if (entryName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否存在该名字的方法
     */
    public boolean hasMethod(String name) {
        return overloads.containsKey(name);
    }

    /**
     * 某个重载在所有重载里是否在该位置声明了 boolean / Boolean 参数。
     * <p>
     * 预览代码里的 {@code el -> true} 是我们自己加的"开关"参数占位符，
     * 只有当同名方法确实存在 boolean 参数的重载时，才能安全地换成 {@code true}。
     *
     * @param name  方法名
     * @param index 参数下标
     */
    public boolean acceptsBooleanAt(String name, int index) {
        List<Class<?>[]> candidates = overloads.get(name);
        if (candidates == null) {
            return false;
        }
        for (Class<?>[] parameters : candidates) {
            if (index >= parameters.length) {
                continue;
            }
            Class<?> parameter = parameters[index];
            if (parameter == boolean.class || parameter == Boolean.class) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否存在参数个数为 {@code count} 的重载
     */
    public boolean hasArity(String name, int count) {
        List<Class<?>[]> candidates = overloads.get(name);
        if (candidates == null) {
            return false;
        }
        for (Class<?>[] parameters : candidates) {
            if (parameters.length == count) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否存在可变参数重载
     */
    public boolean hasVarargs(String name) {
        List<Class<?>[]> candidates = overloads.get(name);
        if (candidates == null) {
            return false;
        }
        return !candidates.isEmpty() && candidates.stream().anyMatch(parameters -> parameters.length > 0
                && parameters[parameters.length - 1].isArray());
    }
}
