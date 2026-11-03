package club.bigtian.mf.plugin.core.util;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import org.jetbrains.kotlin.j2k.ConverterSettings;
import org.jetbrains.kotlin.psi.KtFile;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class KtFileUtil {

    /**
     * 2026.3 起 Kotlin 插件把 java 转 kotlin 的实现迁移到了 j2k 模块的 {@code JavaToKotlinService}，
     * 原来的 {@code JavaToKotlinActionHandler} 已被移除，这里两套实现都通过反射调用，保证新旧版本都能使用。
     */
    private static final String JAVA_TO_KOTLIN_SERVICE = "org.jetbrains.kotlin.j2k.JavaToKotlinService";
    private static final String JAVA_TO_KOTLIN_ACTION_HANDLER = "org.jetbrains.kotlin.idea.actions.JavaToKotlinActionHandler";
    private static final String CONVERSION_TYPE = "org.jetbrains.kotlin.idea.statistics.ConversionType";
    private static final String PROGRESS_REPORTER = "com.intellij.platform.util.progress.RawProgressReporter";
    private static final String KOTLIN_FILE_EXTENSION = ".kt";

    public static Set<String> getImportSet(KtFile ktFile) {
        return Objects.requireNonNull(ktFile.getImportList()).getImports().stream()
                .map(el -> Objects.requireNonNull(el.getImportedFqName()).asString())
                .collect(Collectors.toSet());
    }

    /**
     * 转换kt文件
     *
     * @param psiJavaFiles PSI Java文件
     * @return {@link List}<{@link KtFile}>
     */
    public static List<KtFile> convertKtFile(List<PsiJavaFile> psiJavaFiles) {
        List<KtFile> ktFiles = new ArrayList<>();
        for (PsiJavaFile psiFile : psiJavaFiles) {
            Project project = Objects.requireNonNull(ProjectUtils.getCurrentProject());
            Module module = Modules.getModuleFromDirectory(psiFile.getContainingDirectory());
            if (module == null) {
                continue;
            }
            ktFiles.addAll(convertFiles(Collections.singletonList(psiFile), project, module));
        }
        return ktFiles;
    }

    private static List<KtFile> convertFiles(List<PsiJavaFile> psiJavaFiles, Project project, Module module) {
        if (psiJavaFiles.isEmpty()) {
            return Collections.emptyList();
        }
        List<KtFile> converted = convertWithKotlinService(psiJavaFiles, project, module);
        if (converted == null) {
            converted = convertWithActionHandler(psiJavaFiles, project, module);
        }
        if (converted == null || converted.isEmpty()) {
            converted = findConvertedKtFiles(psiJavaFiles, project);
        }
        return converted;
    }

    /**
     * 2026.3 及以上的转换入口：{@code JavaToKotlinService#convert}
     */
    private static List<KtFile> convertWithKotlinService(List<PsiJavaFile> psiJavaFiles, Project project, Module module) {
        JavaToKotlinServiceConverter converter = JavaToKotlinServiceConverter.create(project);
        if (converter == null) {
            return null;
        }
        try {
            Object conversionResult = BuildersKt.runBlocking(
                    (CoroutineContext) EmptyCoroutineContext.INSTANCE,
                    (Function2<CoroutineScope, Continuation<? super Unit>, Object>) (coroutineScope, continuation) -> {
                        try {
                            return converter.convert(psiJavaFiles, module, continuation);
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException(e);
                        }
                    }
            );
            return extractConvertedKtFiles(conversionResult, project);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 2026.1 / 2026.2 的转换入口：{@code JavaToKotlinActionHandler#convertFiles}
     */
    private static List<KtFile> convertWithActionHandler(List<PsiJavaFile> psiJavaFiles, Project project, Module module) {
        Object actionHandler;
        Method convertFiles;
        try {
            Class<?> handlerClass = Class.forName(JAVA_TO_KOTLIN_ACTION_HANDLER);
            convertFiles = handlerClass.getMethod("convertFiles", List.class, Project.class, Module.class, boolean.class,
                    boolean.class, Function1.class, ConverterSettings.class, List.class, List.class, Continuation.class);
            actionHandler = handlerClass.getField("INSTANCE").get(null);
        } catch (ReflectiveOperationException | IllegalArgumentException | LinkageError e) {
            return null;
        }
        try {
            BuildersKt.runBlocking(
                    (CoroutineContext) EmptyCoroutineContext.INSTANCE,
                    (Function2<CoroutineScope, Continuation<? super Unit>, Object>) (coroutineScope, continuation) -> {
                        try {
                            return convertFiles.invoke(actionHandler, psiJavaFiles, project, module, false, false,
                                    (Function1<PsiElement, Boolean>) psiElement -> true,
                                    ConverterSettings.Companion.getDefaultSettings(),
                                    Collections.emptyList(), Collections.emptyList(), continuation);
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException(e);
                        }
                    }
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        } catch (RuntimeException e) {
            return null;
        }
        return findConvertedKtFiles(psiJavaFiles, project);
    }

    private static List<KtFile> extractConvertedKtFiles(Object conversionResult, Project project) {
        List<KtFile> ktFiles = new ArrayList<>();
        if (conversionResult == null) {
            return ktFiles;
        }
        try {
            Object convertedFiles = conversionResult.getClass().getMethod("getConverted").invoke(conversionResult);
            if (!(convertedFiles instanceof List)) {
                return ktFiles;
            }
            PsiManager psiManager = PsiManager.getInstance(project);
            for (Object convertedFile : (List<?>) convertedFiles) {
                Object kotlinFile = convertedFile.getClass().getMethod("getKotlinFile").invoke(convertedFile);
                if (!(kotlinFile instanceof VirtualFile)) {
                    continue;
                }
                PsiFile psiFile = psiManager.findFile((VirtualFile) kotlinFile);
                if (psiFile instanceof KtFile) {
                    ktFiles.add((KtFile) psiFile);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Collections.emptyList();
        }
        return ktFiles;
    }

    /**
     * 兜底方案：转换完成后按 java 文件所在目录查找同名 kt 文件
     */
    private static List<KtFile> findConvertedKtFiles(List<PsiJavaFile> psiJavaFiles, Project project) {
        PsiManager psiManager = PsiManager.getInstance(project);
        List<KtFile> ktFiles = new ArrayList<>();
        for (PsiJavaFile psiJavaFile : psiJavaFiles) {
            PsiDirectory directory = psiJavaFile.getContainingDirectory();
            VirtualFile javaFile = psiJavaFile.getVirtualFile();
            if (directory == null || javaFile == null) {
                continue;
            }
            VirtualFile kotlinFile = LocalFileSystem.getInstance().findFileByPath(
                    directory.getVirtualFile().getPath() + "/" + javaFile.getNameWithoutExtension() + KOTLIN_FILE_EXTENSION);
            PsiFile psiFile = kotlinFile == null ? null : psiManager.findFile(kotlinFile);
            if (psiFile instanceof KtFile) {
                ktFiles.add((KtFile) psiFile);
            }
        }
        return ktFiles;
    }

    /**
     * 2026.3 提供的 java 转 kotlin 服务
     */
    private static final class JavaToKotlinServiceConverter {

        private final Object service;

        private final Method convert;

        private final Object conversionType;

        private JavaToKotlinServiceConverter(Object service, Method convert, Object conversionType) {
            this.service = service;
            this.convert = convert;
            this.conversionType = conversionType;
        }

        private static JavaToKotlinServiceConverter create(Project project) {
            try {
                Class<?> serviceClass = Class.forName(JAVA_TO_KOTLIN_SERVICE);
                Class<?> conversionTypeClass = Class.forName(CONVERSION_TYPE);
                Method convert = serviceClass.getMethod("convert", List.class, Module.class, ConverterSettings.class,
                        boolean.class, conversionTypeClass, Class.forName(PROGRESS_REPORTER), Continuation.class);
                Object conversionType = Enum.valueOf(asEnumClass(conversionTypeClass), "FILES");
                Object service = serviceClass.getConstructor(Project.class).newInstance(project);
                return new JavaToKotlinServiceConverter(service, convert, conversionType);
            } catch (ReflectiveOperationException | IllegalArgumentException | LinkageError e) {
                return null;
            }
        }

        private Object convert(List<PsiJavaFile> psiJavaFiles, Module module, Continuation<? super Unit> continuation)
                throws ReflectiveOperationException {
            return convert.invoke(service, psiJavaFiles, module, ConverterSettings.Companion.getDefaultSettings(),
                    false, conversionType, null, continuation);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static Class<? extends Enum> asEnumClass(Class<?> type) {
            return (Class<? extends Enum>) type;
        }
    }
}
