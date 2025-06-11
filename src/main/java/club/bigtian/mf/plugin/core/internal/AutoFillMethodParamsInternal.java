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
 * Auto fill method parameters
 * Fill the parameters from outer method to inner method call
 */
public class AutoFillMethodParamsInternal implements IntentionAction, Iconable {

    @Override
    public @IntentionName @NotNull String getText() {
        return "Auto Fill Method Parameters";
    }

    @Override
    public @NotNull @IntentionFamilyName String getFamilyName() {
        return "Auto Fill Method Parameters";
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

        // Check if cursor is at a method call or possible method call location
        PsiMethodCallExpression methodCall = PsiTreeUtil.getParentOfType(elementAt, PsiMethodCallExpression.class);
        if (methodCall == null) {
            // May be at an incomplete method call
            PsiReferenceExpression refExpr = PsiTreeUtil.getParentOfType(elementAt, PsiReferenceExpression.class);
            if (refExpr == null) {
                return false;
            }
        }

        // Check if inside a method
        PsiMethod outerMethod = PsiTreeUtil.getParentOfType(elementAt, PsiMethod.class);
        if (outerMethod == null) {
            return false;
        }

        // Check if outer method has parameters
        PsiParameter[] outerParameters = outerMethod.getParameterList().getParameters();
        if (outerParameters.length == 0) {
            return false;
        }

        // If we have a method call, check if parameters count is different
        if (methodCall != null) {
            PsiExpressionList argumentList = methodCall.getArgumentList();
            if (argumentList == null) {
                return false;
            }

            PsiExpression[] arguments = argumentList.getExpressions();

            // Only show intention if parameter counts are different
            // If they're the same, no need for auto-filling
            return arguments.length != outerParameters.length;
        }

        return true;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
        SelectionModel selectionModel = editor.getSelectionModel();
        int offset = selectionModel.getSelectionStart();
        PsiElement elementAt = file.findElementAt(offset);

        // Get method call expression
        PsiMethodCallExpression methodCall = PsiTreeUtil.getParentOfType(elementAt, PsiMethodCallExpression.class);
        if (methodCall == null) {
            return; // If not a complete method call, we might need to handle reference expressions,
                    // but skipping for now
        }

        // Get outer method
        PsiMethod outerMethod = PsiTreeUtil.getParentOfType(methodCall, PsiMethod.class);
        if (outerMethod == null) {
            return;
        }

        // Get outer method parameters
        PsiParameter[] outerParameters = outerMethod.getParameterList().getParameters();

        // Get method call parameter list
        PsiExpressionList argumentList = methodCall.getArgumentList();
        PsiExpression[] existingArgs = argumentList.getExpressions();

        WriteCommandAction.runWriteCommandAction(project, () -> {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

            // Create parameter fill list
            List<String> argTexts = new ArrayList<>();

            // Keep existing parameters
            for (PsiExpression existingArg : existingArgs) {
                argTexts.add(existingArg.getText());
            }

            // If existing args count is less than outer method params, add more
            for (int i = existingArgs.length; i < outerParameters.length; i++) {
                argTexts.add(outerParameters[i].getName());
            }

            // Build new parameter list text
            StringBuilder argsBuilder = new StringBuilder();
            for (int i = 0; i < argTexts.size(); i++) {
                argsBuilder.append(argTexts.get(i));
                if (i < argTexts.size() - 1) {
                    argsBuilder.append(", ");
                }
            }

            // Create new expression string
            String methodCallText = methodCall.getMethodExpression().getText() + "(" + argsBuilder.toString() + ")";

            // Replace original method call
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
        String description = "Auto Fill Method Parameters";
        SelectionModel selectionModel = editor.getSelectionModel();
        int offset = selectionModel.getSelectionStart();
        PsiElement elementAt = file.findElementAt(offset);

        // Get method call expression
        PsiMethodCallExpression methodCall = PsiTreeUtil.getParentOfType(elementAt, PsiMethodCallExpression.class);
        if (methodCall == null) {
            return IntentionPreviewInfo.EMPTY;
        }

        // Get outer method
        PsiMethod outerMethod = PsiTreeUtil.getParentOfType(methodCall, PsiMethod.class);
        if (outerMethod == null) {
            return IntentionPreviewInfo.EMPTY;
        }

        // Get outer method parameters
        PsiParameter[] outerParameters = outerMethod.getParameterList().getParameters();

        // Get method call parameter list
        PsiExpressionList argumentList = methodCall.getArgumentList();
        PsiExpression[] existingArgs = argumentList.getExpressions();

        // Build preview text
        StringJoiner previewText = new StringJoiner(", ");

        // Keep existing parameters
        for (PsiExpression existingArg : existingArgs) {
            previewText.add(existingArg.getText());
        }

        // If existing args count is less than outer method params, add more
        for (int i = existingArgs.length; i < outerParameters.length; i++) {
            previewText.add(outerParameters[i].getName());
        }

        // Build method call preview
        StringBuilder methodCallPreview = new StringBuilder();
        methodCallPreview.append(methodCall.getMethodExpression().getText())
                .append("(")
                .append(previewText.toString())
                .append(")");

        // Build complete code snippet
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