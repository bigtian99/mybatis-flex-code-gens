package club.bigtian.mf.plugin.core.internal;

import club.bigtian.mf.plugin.core.constant.MybatisFlexConstant;
import club.bigtian.mf.plugin.core.icons.Icons;
import club.bigtian.mf.plugin.core.util.PluginUtil;
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
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * 自动填充方法参数
 * 将外层方法的参数按顺序填充到内层方法调用中
 */
public class AutoFillMethodParamsInternal implements IntentionAction, Iconable {

    @Override
    public @IntentionName @NotNull String getText() {
        return "自动填充方法参数";
    }

    @Override
    public @NotNull @IntentionFamilyName String getFamilyName() {
        return "自动填充方法参数";
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

        // 检查是否在方法调用处或可能的方法调用位置
        PsiMethodCallExpression methodCall = PsiTreeUtil.getParentOfType(elementAt, PsiMethodCallExpression.class);
        if (methodCall == null) {
            // 可能是在尚未完成的方法调用处
            PsiReferenceExpression refExpr = PsiTreeUtil.getParentOfType(elementAt, PsiReferenceExpression.class);
            if (refExpr == null) {
                return false;
            }
        }

        // 检查是否在一个方法内部
        PsiMethod outerMethod = PsiTreeUtil.getParentOfType(elementAt, PsiMethod.class);
        if (outerMethod == null) {
            return false;
        }

        // 检查外部方法是否有参数
        PsiParameter[] outerParameters = outerMethod.getParameterList().getParameters();
        return outerParameters.length > 0;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
        SelectionModel selectionModel = editor.getSelectionModel();
        int offset = selectionModel.getSelectionStart();
        PsiElement elementAt = file.findElementAt(offset);

        // 获取方法调用表达式
        PsiMethodCallExpression methodCall = PsiTreeUtil.getParentOfType(elementAt, PsiMethodCallExpression.class);
        if (methodCall == null) {
            return; // 如果不是完整的方法调用，可能我们需要处理引用表达式，目前暂不处理
        }

        // 获取外部方法
        PsiMethod outerMethod = PsiTreeUtil.getParentOfType(methodCall, PsiMethod.class);
        if (outerMethod == null) {
            return;
        }

        // 获取外部方法的参数
        PsiParameter[] outerParameters = outerMethod.getParameterList().getParameters();

        // 获取方法调用的参数列表
        PsiExpressionList argumentList = methodCall.getArgumentList();
        PsiExpression[] existingArgs = argumentList.getExpressions();

        WriteCommandAction.runWriteCommandAction(project, () -> {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

            // 创建参数填充列表
            List<String> argTexts = new ArrayList<>();

            // 保留现有的参数
            for (PsiExpression existingArg : existingArgs) {
                argTexts.add(existingArg.getText());
            }

            // 如果已有参数数量小于外部方法参数数量，则继续添加
            for (int i = existingArgs.length; i < outerParameters.length; i++) {
                argTexts.add(outerParameters[i].getName());
            }

            // 构建新的参数列表文本
            StringBuilder argsBuilder = new StringBuilder();
            for (int i = 0; i < argTexts.size(); i++) {
                argsBuilder.append(argTexts.get(i));
                if (i < argTexts.size() - 1) {
                    argsBuilder.append(", ");
                }
            }

            // 创建新的表达式字符串
            String methodCallText = methodCall.getMethodExpression().getText() + "(" + argsBuilder.toString() + ")";

            // 替换原有的方法调用
            PsiExpression newMethodCall = factory.createExpressionFromText(methodCallText, methodCall);
            methodCall.replace(newMethodCall);
        });
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }

    @Override
    public Icon getIcon(int flags) {
        return Icons.FLEX;
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull Editor editor,
            @NotNull PsiFile file) {
        String description = "自动填充方法参数";
        SelectionModel selectionModel = editor.getSelectionModel();
        int offset = selectionModel.getSelectionStart();
        PsiElement elementAt = file.findElementAt(offset);

        // 获取方法调用表达式
        PsiMethodCallExpression methodCall = PsiTreeUtil.getParentOfType(elementAt, PsiMethodCallExpression.class);
        if (methodCall == null) {
            return IntentionPreviewInfo.EMPTY;
        }

        // 获取外部方法
        PsiMethod outerMethod = PsiTreeUtil.getParentOfType(methodCall, PsiMethod.class);
        if (outerMethod == null) {
            return IntentionPreviewInfo.EMPTY;
        }

        // 获取外部方法的参数
        PsiParameter[] outerParameters = outerMethod.getParameterList().getParameters();

        // 获取方法调用的参数列表
        PsiExpressionList argumentList = methodCall.getArgumentList();
        PsiExpression[] existingArgs = argumentList.getExpressions();

        // 构建预览文本
        StringJoiner previewText = new StringJoiner(", ");

        // 保留现有的参数
        for (PsiExpression existingArg : existingArgs) {
            previewText.add(existingArg.getText());
        }

        // 如果已有参数数量小于外部方法参数数量，则继续添加
        for (int i = existingArgs.length; i < outerParameters.length; i++) {
            previewText.add(outerParameters[i].getName());
        }

        // 构建方法调用预览
        StringBuilder methodCallPreview = new StringBuilder();
        methodCallPreview.append(methodCall.getMethodExpression().getText())
                .append("(")
                .append(previewText.toString())
                .append(")");

        // 构建完整的代码片段
        PsiStatement statement = PsiTreeUtil.getParentOfType(methodCall, PsiStatement.class);
        if (statement == null) {
            return IntentionPreviewInfo.EMPTY;
        }

        String statementText = statement.getText();
        String originalMethodCallText = methodCall.getText();
        String newStatementText = statementText.replace(originalMethodCallText, methodCallPreview.toString());

        return new IntentionPreviewInfo.CustomDiff(
                JavaFileType.INSTANCE,
                description,
                newStatementText);
    }
}