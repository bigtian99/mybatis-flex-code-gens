package club.bigtian.mf.plugin.core.render;

import club.bigtian.mf.plugin.action.flex.SQLPreviewAction;
import club.bigtian.mf.plugin.core.icons.Icons;
import club.bigtian.mf.plugin.core.util.NotificationUtils;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.psi.PsiJavaFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Map;

public class SqlPreviewIconRenderer extends GutterIconRenderer {
    private int lineNumber;
    private PsiJavaFile psiFile;
    Map<Integer, String> iconMap;

    public SqlPreviewIconRenderer(int lineNumber, PsiJavaFile psiFile, Map<Integer, String> iconMap) {
        this.lineNumber = lineNumber;
        this.psiFile = psiFile;
        this.iconMap = iconMap;
    }

    @Override
    public boolean equals(Object obj) {
        return false;
    }

    @Override
    public @Nullable AnAction getClickAction() {
        return new AnAction() {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                // 弹窗是模态的，重复点击天然被挡住，不需要"去重标志"。
                // 之前那个标志一旦没复位就会让后续点击全部静默失效，这里彻底去掉。
                String selectedText = iconMap.get(lineNumber);
                try {
                    new SQLPreviewAction().preview(selectedText, psiFile, e, () -> {
                    });
                } catch (Throwable t) {
                    try {
                        NotificationUtils.notifyError("SQL 预览启动失败：" + t, "SQL Preview",
                                psiFile.getProject());
                    } catch (Throwable ignored) {
                    }
                }
            }
        };

    }

    @Override
    public boolean isNavigateAction() {
        return true;
    }


    @Override
    public @Nullable String getTooltipText() {
        return "预览 SQL";
    }

    @Override
    public int hashCode() {
        return 0;
    }

    @Override
    public @NotNull Icon getIcon() {
        return Icons.SQL_PREVIEW;
    }
}
