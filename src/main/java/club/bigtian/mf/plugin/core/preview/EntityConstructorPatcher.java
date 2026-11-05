package club.bigtian.mf.plugin.core.preview;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 实体类无参构造器补齐器。
 * <p>
 * MyBatis 的 {@code DefaultObjectFactory} 在把结果集映射成实体时要求实体存在无参构造器，
 * 旧流程是直接用 PSI 往用户的 Entity 源码里插一个 {@code public X(){}}，编译完再删掉，
 * 这不仅会改动用户代码，还会让 IDE 重新编译实体文件。
 * <p>
 * 这里改成在字节码层面补齐：只在内存里给实体类加一个默认构造器，
 * 用户源码一个字节都不动。
 */
public final class EntityConstructorPatcher {

    private static final String CONSTRUCTOR_NAME = "<init>";
    private static final String DEFAULT_CONSTRUCTOR_DESC = "()V";

    private EntityConstructorPatcher() {
    }

    /**
     * 如果类里没有 {@code ()V} 构造器，就补一个
     *
     * @param classBytes 原始字节码
     * @return 处理后的字节码，无需处理时返回原数组
     */
    public static byte[] ensureNoArgConstructor(byte[] classBytes) {
        if (classBytes == null || classBytes.length == 0) {
            return classBytes;
        }
        try {
            ClassReader reader = new ClassReader(classBytes);
            if (hasNoArgConstructor(reader)) {
                return classBytes;
            }
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                private String superName = "java/lang/Object";

                @Override
                public void visit(int version, int access, String name, String signature, String superName,
                                  String[] interfaces) {
                    if (superName != null) {
                        this.superName = superName;
                    }
                    super.visit(version, access, name, signature, superName, interfaces);
                }

                @Override
                public void visitEnd() {
                    MethodVisitor methodVisitor = super.visitMethod(Opcodes.ACC_PUBLIC, CONSTRUCTOR_NAME,
                            DEFAULT_CONSTRUCTOR_DESC, null, null);
                    methodVisitor.visitCode();
                    methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                    methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, CONSTRUCTOR_NAME,
                            DEFAULT_CONSTRUCTOR_DESC, false);
                    methodVisitor.visitInsn(Opcodes.RETURN);
                    methodVisitor.visitMaxs(0, 0);
                    methodVisitor.visitEnd();
                    super.visitEnd();
                }
            }, 0);
            return writer.toByteArray();
        } catch (Throwable e) {
            // 字节码处理失败时保持原样，交给运行时暴露真实问题
            return classBytes;
        }
    }

    /**
     * 判断类里是否已经有 {@code ()V} 构造器
     */
    private static boolean hasNoArgConstructor(ClassReader reader) {
        boolean[] found = new boolean[1];
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                if (CONSTRUCTOR_NAME.equals(name) && DEFAULT_CONSTRUCTOR_DESC.equals(descriptor)) {
                    found[0] = true;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }
}
