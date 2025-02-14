package club.bigtian.mf.plugin.core.internal;

import club.bigtian.mf.plugin.core.constant.MybatisFlexConstant;
import club.bigtian.mf.plugin.core.constant.QualifiedNameConstant;
import club.bigtian.mf.plugin.core.icons.Icons;
import club.bigtian.mf.plugin.core.util.PluginUtil;
import club.bigtian.mf.plugin.core.util.PsiJavaFileUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.util.IntentionFamilyName;
import com.intellij.codeInspection.util.IntentionName;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Iconable;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.Map;
import java.util.StringJoiner;

import static club.bigtian.mf.plugin.core.util.PsiJavaFileUtil.methodHasAnnotation;
import static club.bigtian.mf.plugin.core.util.VirtualFileUtils.getAllResourceFiles;

/**
 * 添加 @Params 注解
 */
public class RequestParamInternal implements IntentionAction, Iconable {

    @Override
    public @IntentionName @NotNull String getText() {
        return "Auto Identify Add @RequestParam Or @PathVariable";
    }

    @Override
    public @NotNull @IntentionFamilyName String getFamilyName() {
        return "RequestParamInternal";
    }


    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        if (!(file instanceof PsiJavaFile)
                || PluginUtil.isConflictPluginInstalled(MybatisFlexConstant.MYBATIS_PLUGIN_ID)) {
            return false;
        }
        SelectionModel selectionModel = editor.getSelectionModel();
        int offset = selectionModel.getSelectionStart();
        PsiElement elementAt = file.findElementAt(offset);
        PsiMethod psiMethod = PsiTreeUtil.getParentOfType(elementAt, PsiMethod.class);

        if (ObjectUtil.isNull(psiMethod)) {
            return false;
        }

        // 获取方法的所有参数
        PsiParameter[] parameters = psiMethod.getParameterList().getParameters();
        if (parameters.length == 0) {
            return false;
        }

        // 检查是否至少有一个参数没有 @PathVariable 或 @RequestParam 注解
        boolean hasParameterWithoutAnnotation = false;
        for (PsiParameter parameter : parameters) {
            PsiAnnotation pathVarAnnotation = parameter.getAnnotation(QualifiedNameConstant.SPRING_PATH_VARIABLE);
            PsiAnnotation requestParamAnnotation = parameter.getAnnotation(QualifiedNameConstant.SPRING_REQUEST_PARAM);
            if (pathVarAnnotation == null && requestParamAnnotation == null) {
                hasParameterWithoutAnnotation = true;
                break;
            }
        }

        return hasParameterWithoutAnnotation;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
        SelectionModel selectionModel = editor.getSelectionModel();
        int offset = selectionModel.getSelectionStart();
        PsiElement elementAt = file.findElementAt(offset);
        PsiMethod psiMethod = PsiTreeUtil.getParentOfType(elementAt, PsiMethod.class);
        PsiJavaFile javaFile = (PsiJavaFile) file;
        PsiParameter[] parameters = psiMethod.getParameterList().getParameters();
        PsiElementFactory psiElementFactory = JavaPsiFacade.getElementFactory(project);

        // 获取方法上的 RequestMapping 注解的 URL 路径
        String urlPath = getRequestMappingPath(psiMethod);

        WriteCommandAction.runWriteCommandAction(project, () -> {
            // 检查是否需要导入注解
            if (!hasAnnotation(javaFile, QualifiedNameConstant.SPRING_PATH_VARIABLE)) {
                javaFile.getImportList().add(PsiJavaFileUtil.createImportStatement(
                        PsiJavaFileUtil.getPsiClass(QualifiedNameConstant.SPRING_PATH_VARIABLE)));
            }
            if (!hasAnnotation(javaFile, QualifiedNameConstant.SPRING_REQUEST_PARAM)) {
                javaFile.getImportList().add(PsiJavaFileUtil.createImportStatement(
                        PsiJavaFileUtil.getPsiClass(QualifiedNameConstant.SPRING_REQUEST_PARAM)));
            }

            for (PsiParameter parameter : parameters) {
                // 检查参数是否已经有注解
                PsiAnnotation pathVarAnnotation = parameter.getAnnotation(QualifiedNameConstant.SPRING_PATH_VARIABLE);
                PsiAnnotation requestParamAnnotation = parameter
                        .getAnnotation(QualifiedNameConstant.SPRING_REQUEST_PARAM);

                if (pathVarAnnotation == null && requestParamAnnotation == null) {
                    String paramName = parameter.getName();
                    PsiAnnotation annotation;

                    // 检查参数名是否在 URL 路径的 {} 中
                    if (urlPath != null && urlPath.contains("{" + paramName + "}")) {
                        // 使用 @PathVariable
                        annotation = psiElementFactory.createAnnotationFromText("@PathVariable(\"" + paramName + "\")",
                                null);
                    } else {
                        // 使用 @RequestParam
                        annotation = psiElementFactory.createAnnotationFromText("@RequestParam(\"" + paramName + "\")",
                                null);
                    }

                    parameter.getModifierList().addBefore(annotation, null);
                }
            }
        });
    }

    private boolean hasAnnotation(PsiJavaFile file, String qualifiedName) {
        return PsiJavaFileUtil.getQualifiedNameImportSet(file).contains(qualifiedName);
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }

    @Override

    public Icon getIcon(int flags) {
        return Icons.FLEX;
    }
    private String getRequestMappingPath(PsiMethod method) {
        // 检查各种 Spring MVC 的 Mapping 注解
        String[] mappingAnnotations = {
                QualifiedNameConstant.SPRING_REQUEST_MAPPING,
                QualifiedNameConstant.SPRING_GET_MAPPING,
                QualifiedNameConstant.SPRING_POST_MAPPING,
                QualifiedNameConstant.SPRING_PUT_MAPPING,
                QualifiedNameConstant.SPRING_DELETE_MAPPING,
                QualifiedNameConstant.SPRING_PATCH_MAPPING
        };

        for (String annotationName : mappingAnnotations) {
            PsiAnnotation annotation = method.getAnnotation(annotationName);
            if (annotation != null) {
                // 尝试获取 value 或 path 属性
                PsiAnnotationMemberValue value = annotation.findAttributeValue("value");
                if (value == null) {
                    value = annotation.findAttributeValue("path");
                }
                if (value != null) {
                    return value.getText().replace("\"", "");
                }
            }
        }
        return null;
    }



    @Override
    public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull Editor editor,
                                                         @NotNull PsiFile file) {
        String description = "自动为方法添加@PathVariable或@RequestParam注解";
        SelectionModel selectionModel = editor.getSelectionModel();
        int offset = selectionModel.getSelectionStart();
        PsiElement elementAt = file.findElementAt(offset);
        PsiMethod psiMethod = PsiTreeUtil.getParentOfType(elementAt, PsiMethod.class);
        if (psiMethod == null) {
            return IntentionPreviewInfo.EMPTY;
        }

        // 获取方法的URL路径
        String urlPath = getRequestMappingPath(psiMethod);

        // 构建修改后的参数列表
        PsiParameter[] parameters = psiMethod.getParameterList().getParameters();
        StringJoiner previewText = new StringJoiner(",\n    ");

        for (PsiParameter parameter : parameters) {
            // 检查参数是否已经有注解
            PsiAnnotation pathVarAnnotation = parameter.getAnnotation(QualifiedNameConstant.SPRING_PATH_VARIABLE);
            PsiAnnotation requestParamAnnotation = parameter.getAnnotation(QualifiedNameConstant.SPRING_REQUEST_PARAM);

            if (pathVarAnnotation != null || requestParamAnnotation != null) {
                // 如果已经有注解，保持原样
                previewText.add(parameter.getText());
                continue;
            }

            String paramName = parameter.getName();
            StringBuilder builder = new StringBuilder();

            // 根据URL路径决定使用哪种注解
            if (urlPath != null && urlPath.contains("{" + paramName + "}")) {
                builder.append("@PathVariable(\"").append(paramName).append("\") ");
            } else {
                builder.append("@RequestParam(\"").append(paramName).append("\") ");
            }

            builder.append(parameter.getType().getPresentableText())
                    .append(" ")
                    .append(paramName);

            previewText.add(builder.toString());
        }

        // 构建完整的方法预览
        StringBuilder methodPreview = new StringBuilder();

        // 添加方法的注解，按照类型分组
        PsiAnnotation[] methodAnnotations = psiMethod.getModifierList().getAnnotations();
        // 先添加Mapping相关注解
        for (PsiAnnotation annotation : methodAnnotations) {
            String annotationText = annotation.getText();
            if (annotationText.contains("Mapping")) {
                methodPreview.append(annotationText).append("\n");
            }
        }
        // 再添加其他注解
        for (PsiAnnotation annotation : methodAnnotations) {
            String annotationText = annotation.getText();
            if (!annotationText.contains("Mapping")) {
                methodPreview.append(annotationText).append("\n");
            }
        }

        // 添加方法签名，只获取修饰符
        PsiModifierList modifierList = psiMethod.getModifierList();
        StringBuilder modifiers = new StringBuilder();
        for (String modifier : PsiModifier.MODIFIERS) {
            if (modifierList.hasModifierProperty(modifier)) {
                modifiers.append(modifier).append(" ");
            }
        }

        methodPreview.append(modifiers.toString())
                .append(psiMethod.getReturnType().getPresentableText())
                .append(" ")
                .append(psiMethod.getName())
                .append("(\n    ")
                .append(previewText.toString())
                .append("\n) {");

        return new IntentionPreviewInfo.CustomDiff(
                JavaFileType.INSTANCE,
                description,
                methodPreview.toString());
    }

}
