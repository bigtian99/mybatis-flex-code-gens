package club.bigtian.mf.plugin.core.preview;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 预览用的"框架类加载器"。
 * <p>
 * 父加载器是 JDK 平台类加载器，因此项目依赖（mybatis-flex、mybatis、实体类、Mapper、TableDef）
 * 全部由它从项目 classpath 加载，与插件自身的类加载器完全隔离。
 * <p>
 * 这个加载器按项目缓存复用（见 {@link SqlPreviewLoaderCache}）：MyBatis-Flex 的静态初始化
 * 大约占单次预览 80ms 中的 78ms，复用之后每次预览只剩 1~3ms。
 */
public final class SqlPreviewClassLoader extends URLClassLoader {

    /**
     * 已经补过无参构造器的实体类，保证补丁幂等
     */
    private final Set<String> patchedEntities = Collections.synchronizedSet(new LinkedHashSet<>());

    static {
        registerAsParallelCapable();
    }

    private SqlPreviewClassLoader(URL[] urls) {
        super(urls, ClassLoader.getPlatformClassLoader());
    }

    /**
     * 根据项目 classpath 创建类加载器
     *
     * @param classpath 项目 classpath（目录或 jar）
     */
    public static SqlPreviewClassLoader of(List<String> classpath) {
        List<URL> urls = new ArrayList<>(classpath.size());
        for (String path : classpath) {
            if (path == null || path.isEmpty()) {
                continue;
            }
            try {
                File file = new File(path);
                if (!file.exists()) {
                    continue;
                }
                urls.add(file.toURI().toURL());
            } catch (MalformedURLException ignored) {
            }
        }
        if (urls.isEmpty()) {
            throw new SqlPreviewException("项目运行 classpath 为空，请先确认模块 SDK 与依赖");
        }
        return new SqlPreviewClassLoader(urls.toArray(new URL[0]));
    }

    /**
     * 实体类缺少无参构造器时，在字节码层面补一个。
     * <p>
     * 旧流程是往用户源码里插一个构造器、编译、再删掉；这里用户源码一个字节都不动。
     * 注意补丁必须打在本加载器（框架类所在命名空间）里，否则 TableDef / Mapper 解析到的
     * 仍然是父命名空间里的旧实体类。
     *
     * @param entityClassName 实体类限定名
     */
    public void patchEntityIfNeeded(String entityClassName) {
        if (entityClassName == null || entityClassName.isEmpty() || !patchedEntities.add(entityClassName)) {
            return;
        }
        try {
            byte[] bytes = readClassBytes(entityClassName);
            if (bytes == null) {
                // 实体还没编译，交给后续类加载报错并回退到旧流程
                return;
            }
            byte[] patched = EntityConstructorPatcher.ensureNoArgConstructor(bytes);
            if (patched != bytes) {
                defineInner(entityClassName, patched);
            }
        } catch (Throwable e) {
            // 补构造器失败不影响后续流程，运行时若真的需要会抛出明确异常并回退
        }
    }

    /**
     * 读取某个类在项目 classpath 中的原始字节码
     *
     * @param className 限定类名
     * @return 字节码，找不到时返回 null
     */
    public byte[] readClassBytes(String className) {
        String resource = className.replace('.', '/') + ".class";
        URL url = findResource(resource);
        if (url == null) {
            return null;
        }
        try (InputStream in = url.openStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 定义实体类补丁
     */
    private Class<?> defineInner(String className, byte[] bytes) {
        return defineClass(className, bytes, 0, bytes.length);
    }

    /**
     * 定义编译产物（同一个类名只能定义一次，重复预览时由子加载器负责定义）
     */
    Class<?> define(String className, byte[] bytes) {
        return defineClass(className, bytes, 0, bytes.length);
    }

    /**
     * 编译产物中除主类之外的内部类
     */
    void defineAll(Map<String, byte[]> classes, String skipClassName) {
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            if (entry.getKey().equals(skipClassName)) {
                continue;
            }
            define(entry.getKey(), entry.getValue());
        }
    }
}
