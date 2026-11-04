package club.bigtian.mf.plugin.core.preview;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 内存编译器 + 字节码补丁的往返测试（不依赖 IDEA 运行时）。
 */
public class SqlPreviewCompilerTest {

    private static final String CLASS_NAME = "preview.TestPreview";

    @Test
    public void shouldCompileInMemoryWithoutWritingFiles() {
        String source = "package preview;\n"
                + "public class TestPreview {\n"
                + "    public static String preview() { return \"SELECT 1\"; }\n"
                + "}\n";
        SqlPreviewCompiler.CompiledClasses compiled =
                SqlPreviewCompiler.compile(CLASS_NAME, source, Collections.emptyList());
        assertNotNull(compiled.getMainBytes());
        assertTrue(compiled.getMainBytes().length > 0);
    }

    @Test
    public void shouldCacheCompiledBytecode() {
        String source = "package preview;\n"
                + "public class TestPreview {\n"
                + "    public static String preview() { return \"cached\"; }\n"
                + "}\n";
        SqlPreviewCompiler.CompiledClasses first =
                SqlPreviewCompiler.compile(CLASS_NAME, source, Collections.emptyList());
        SqlPreviewCompiler.CompiledClasses second =
                SqlPreviewCompiler.compile(CLASS_NAME, source, Collections.emptyList());
        assertSame("同一份源码应当命中字节码缓存", first, second);
    }

    @Test
    public void shouldReportCompileError() {
        String source = "package preview;\n"
                + "public class TestPreview {\n"
                + "    public static String preview() { return undefinedSymbol; }\n"
                + "}\n";
        try {
            SqlPreviewCompiler.compile(CLASS_NAME, source, Collections.emptyList());
            fail("编译失败时应当抛出 SqlPreviewException");
        } catch (SqlPreviewException e) {
            assertTrue(e.getMessage().contains("编译失败"));
        }
    }

    @Test
    public void entityPatchShouldAddNoArgConstructor() throws Exception {
        String source = "package preview;\n"
                + "public class TestPreview {\n"
                + "    private final int value;\n"
                + "    public TestPreview(int value) { this.value = value; }\n"
                + "}\n";
        SqlPreviewCompiler.CompiledClasses compiled =
                SqlPreviewCompiler.compile(CLASS_NAME, source, Collections.emptyList());
        byte[] original = compiled.getMainBytes();
        byte[] patched = EntityConstructorPatcher.ensureNoArgConstructor(original);
        assertTrue("缺少无参构造器时应当补齐", patched != original);
        assertSame("重复补齐应当幂等", patched, EntityConstructorPatcher.ensureNoArgConstructor(patched));

        Class<?> clazz = new ByteArrayClassLoader().define(CLASS_NAME, patched);
        Constructor<?> constructor = clazz.getDeclaredConstructor();
        assertNotNull(constructor);
        assertNotNull(constructor.newInstance());
    }

    @Test
    public void entityPatchShouldKeepExistingConstructor() {
        String source = "package preview;\n"
                + "public class TestPreview {\n"
                + "    public TestPreview() { }\n"
                + "}\n";
        byte[] original = SqlPreviewCompiler.compile(CLASS_NAME, source, Collections.emptyList()).getMainBytes();
        assertSame(original, EntityConstructorPatcher.ensureNoArgConstructor(original));
    }

    @Test
    public void entityPatchShouldHandleNull() {
        assertEquals(null, EntityConstructorPatcher.ensureNoArgConstructor(null));
    }

    private static final class ByteArrayClassLoader extends ClassLoader {
        ByteArrayClassLoader() {
            super(ClassLoader.getPlatformClassLoader());
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
