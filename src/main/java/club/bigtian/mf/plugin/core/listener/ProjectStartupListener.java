package club.bigtian.mf.plugin.core.listener;

import club.bigtian.mf.plugin.core.contributor.ServiceCompletionContributor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 项目启动监听器，用于预加载QueryColumn方法缓存
 * Author: BigTian
 */
public class ProjectStartupListener implements ProjectActivity {

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        // 异步预加载QueryColumn方法缓存
        preloadQueryColumnMethods(project);
        return Unit.INSTANCE;
    }

    /**
     * 预加载QueryColumn方法缓存
     */
    private void preloadQueryColumnMethods(Project project) {
        // 使用IDEA原生的应用程序管理器在后台执行任务
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                ServiceCompletionContributor.preloadQueryColumnMethods(project);
            } catch (Exception e) {
                // 静默处理异常，不影响项目启动
                e.printStackTrace();
            }
        });
    }
}
