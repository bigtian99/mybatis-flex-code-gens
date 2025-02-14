package club.bigtian.mf.plugin.core.internal;

import club.bigtian.mf.plugin.core.constant.MybatisFlexConstant;
import club.bigtian.mf.plugin.core.constant.QualifiedNameConstant;
import club.bigtian.mf.plugin.core.icons.Icons;
import club.bigtian.mf.plugin.core.util.PluginUtil;
import club.bigtian.mf.plugin.core.util.PsiJavaFileUtil;
import cn.hutool.core.util.ObjectUtil;
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
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.StringJoiner;


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

        // 检查是否是Controller类
        PsiClass containingClass = psiMethod.getContainingClass();
        if (containingClass == null) {
            return false;
        }

        // 检查是否有@Controller或@RestController注解
        PsiAnnotation controllerAnnotation = containingClass.getAnnotation(QualifiedNameConstant.SPRING_CONTROLLER);
        PsiAnnotation restControllerAnnotation = containingClass
                .getAnnotation(QualifiedNameConstant.SPRING_REST_CONTROLLER);
        if (controllerAnnotation == null && restControllerAnnotation == null) {
            return false;
        }

        // 获取URL路径
        String urlPath = getRequestMappingPath(psiMethod);
        if (urlPath == null) {
            return false;
        }

        // 获取URL中的所有路径变量
        java.util.Set<String> pathVariables = new java.util.HashSet<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\{([^}]+)}");
        java.util.regex.Matcher matcher = pattern.matcher(urlPath);
        while (matcher.find()) {
            pathVariables.add(matcher.group(1));
        }

        // 如果URL中没有路径变量，检查是否有参数需要添加@RequestParam
        if (pathVariables.isEmpty()) {
            PsiParameter[] parameters = psiMethod.getParameterList().getParameters();
            if (parameters.length == 0) {
                return false;
            }
            // 检查是否有参数没有注解
            for (PsiParameter parameter : parameters) {
                PsiAnnotation pathVarAnnotation = parameter.getAnnotation(QualifiedNameConstant.SPRING_PATH_VARIABLE);
                PsiAnnotation requestParamAnnotation = parameter
                        .getAnnotation(QualifiedNameConstant.SPRING_REQUEST_PARAM);
                if (pathVarAnnotation == null && requestParamAnnotation == null) {
                    return true;
                }
            }
            return false;
        }

        // 检查URL中的路径变量是否都有对应的参数
        PsiParameter[] parameters = psiMethod.getParameterList().getParameters();
        for (String pathVar : pathVariables) {
            boolean hasMatchingParam = false;
            for (PsiParameter parameter : parameters) {
                if (parameter.getName().equals(pathVar)) {
                    // 如果找到同名参数，检查是否有@PathVariable注解
                    PsiAnnotation pathVarAnnotation = parameter
                            .getAnnotation(QualifiedNameConstant.SPRING_PATH_VARIABLE);
                    if (pathVarAnnotation == null) {
                        return true; // 参数存在但没有@PathVariable注解
                    }
                    hasMatchingParam = true;
                    break;
                }
            }
            if (!hasMatchingParam) {
                return true; // URL中有路径变量，但没有对应的参数
            }
        }

        // 检查是否有其他参数需要添加@RequestParam
        for (PsiParameter parameter : parameters) {
            // 如果参数名不在路径变量中，且没有任何注解
            if (!pathVariables.contains(parameter.getName())) {
                PsiAnnotation pathVarAnnotation = parameter.getAnnotation(QualifiedNameConstant.SPRING_PATH_VARIABLE);
                PsiAnnotation requestParamAnnotation = parameter
                        .getAnnotation(QualifiedNameConstant.SPRING_REQUEST_PARAM);
                if (pathVarAnnotation == null && requestParamAnnotation == null) {
                    return true;
                }
            }
        }

        return false;
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

            // 为现有参数添加注解
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

            // 检查URL中的路径变量是否都有对应的参数
            if (urlPath != null) {
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\{([^}]+)}");
                java.util.regex.Matcher matcher = pattern.matcher(urlPath);

                while (matcher.find()) {
                    String pathVarName = matcher.group(1);
                    boolean parameterExists = false;

                    // 检查这个路径变量是否已经有对应的参数
                    for (PsiParameter parameter : parameters) {
                        if (parameter.getName().equals(pathVarName)) {
                            parameterExists = true;
                            break;
                        }
                    }

                    // 如果没有对应的参数，添加一个新参数
                    if (!parameterExists) {
                        // 创建新的参数
                        PsiAnnotation pathVarAnnotation = psiElementFactory.createAnnotationFromText(
                                "@PathVariable(\"" + pathVarName + "\")", null);

                        // 默认使用String类型，你也可以根据需要修改
                        String parameterText = "String " + pathVarName;
                        PsiParameter newParameter = psiElementFactory.createParameterFromText(parameterText, psiMethod);

                        // 添加注解到新参数
                        newParameter.getModifierList().addBefore(pathVarAnnotation, null);

                        // 添加新参数到方法
                        psiMethod.getParameterList().add(newParameter);
                    }
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

        // 先处理现有参数
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

        // 检查URL中的路径变量，添加缺失的参数
        if (urlPath != null) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\{([^}]+)}");
            java.util.regex.Matcher matcher = pattern.matcher(urlPath);

            while (matcher.find()) {
                String pathVarName = matcher.group(1);
                boolean parameterExists = false;

                // 检查这个路径变量是否已经有对应的参数
                for (PsiParameter parameter : parameters) {
                    if (parameter.getName().equals(pathVarName)) {
                        parameterExists = true;
                        break;
                    }
                }

                // 如果没有对应的参数，添加到预览中
                if (!parameterExists) {
                    StringBuilder builder = new StringBuilder();
                    builder.append("@PathVariable(\"")
                            .append(pathVarName)
                            .append("\") String ")
                            .append(pathVarName);
                    previewText.add(builder.toString());
                }
            }
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
