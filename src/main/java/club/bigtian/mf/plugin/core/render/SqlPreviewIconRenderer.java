package club.bigtian.mf.plugin.core.render;

import club.bigtian.mf.plugin.action.flex.SQLPreviewAction;
import club.bigtian.mf.plugin.core.icons.Icons;
import club.bigtian.mf.plugin.core.util.NotificationUtils;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.psi.PsiJavaFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class SqlPreviewIconRenderer extends GutterIconRenderer {
    private int lineNumber;
    private PsiJavaFile psiFile;
    // 每个图标各自持有一个标志，防止同一个图标被连续点击多次（原来是 static，点一个图标会把所有图标都禁掉）
    private volatile boolean previewing = false;
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
                if (previewing) {
                    return;
                }
                previewing = true;
                // 看门狗：无论后续发生什么，90 秒后一定解锁，避免"点一次失败后再也点不动"
                AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> previewing = false, 90, TimeUnit.SECONDS);
                String selectedText = iconMap.get(lineNumber);
                try {
                    new SQLPreviewAction().preview(selectedText, psiFile, e, () -> previewing = false);
                } catch (Throwable t) {
                    previewing = false;
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
