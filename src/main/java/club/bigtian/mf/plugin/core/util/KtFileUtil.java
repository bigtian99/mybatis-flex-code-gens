package club.bigtian.mf.plugin.core.util;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiFile;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import org.jetbrains.kotlin.idea.actions.JavaToKotlinActionHandler;
import org.jetbrains.kotlin.j2k.ConverterSettings;
import org.jetbrains.kotlin.j2k.J2kPostprocessorExtension;
import org.jetbrains.kotlin.j2k.J2kPreprocessorExtension;
import org.jetbrains.kotlin.psi.KtFile;

import java.util.*;
import java.util.stream.Collectors;

public class KtFileUtil {

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

        List<Map.Entry<String, String>> expectedKtFiles = new ArrayList<>();
        for (PsiJavaFile psiJavaFile : psiJavaFiles) {
            if (psiJavaFile.getContainingDirectory() == null || psiJavaFile.getVirtualFile() == null) {
                continue;
            }
            expectedKtFiles.add(new AbstractMap.SimpleEntry<>(
                    psiJavaFile.getContainingDirectory().getVirtualFile().getPath(),
                    psiJavaFile.getVirtualFile().getNameWithoutExtension() + ".kt"
            ));
        }

        try {
            BuildersKt.runBlocking(
                    (CoroutineContext) EmptyCoroutineContext.INSTANCE,
                    (Function2<CoroutineScope, Continuation<? super Unit>, Object>) (coroutineScope, continuation) -> JavaToKotlinActionHandler.INSTANCE.convertFiles(
                            psiJavaFiles,
                            project,
                            module,
                            false,
                            false,
                            psiElement -> true,
                            ConverterSettings.Companion.getDefaultSettings(),
                            Collections.emptyList(),
                            Collections.<J2kPostprocessorExtension>emptyList(),
                            continuation
                    )
            );
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        PsiManager psiManager = PsiManager.getInstance(project);
        List<KtFile> result = new ArrayList<>();
        for (Map.Entry<String, String> expectedKtFile : expectedKtFiles) {
            PsiFile psiFile = Optional.ofNullable(psiManager.findDirectory(
                            Objects.requireNonNull(com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                                    .findFileByPath(expectedKtFile.getKey()))))
                    .map(directory -> directory.findFile(expectedKtFile.getValue()))
                    .orElse(null);
            if (psiFile instanceof KtFile) {
                result.add((KtFile) psiFile);
            }
        }
        return result;
    }
}
