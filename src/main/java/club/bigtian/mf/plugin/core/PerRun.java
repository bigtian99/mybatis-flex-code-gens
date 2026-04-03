package club.bigtian.mf.plugin.core;

import club.bigtian.mf.plugin.core.config.MybatisFlexConfig;
import club.bigtian.mf.plugin.core.util.PsiJavaFileUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.impl.ConsoleViewImpl;
import com.intellij.execution.runners.JavaProgramPatcher;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;

import java.util.Map;

/**
 * 这个类继承了JavaProgramPatcher，用于修改RunProfile的JavaParameters。
 * 它专门用于向JVM参数中添加Java代理。
 */
public class PerRun extends JavaProgramPatcher {
    private static final String SPRING_BOOT_RUN_CONFIGURATION = "com.intellij.spring.boot.run.SpringBootApplicationRunConfiguration";

    /**
     * 这个方法被调用来修改RunProfile的JavaParameters。
     * 它向JVM参数中添加一个Java代理。
     *
     * @param executor       将运行配置文件的执行器。
     * @param runProfile     要运行的配置文件。
     * @param javaParameters 要修改的Java参数。
     */
    @Override
    public void patchJavaParameters(Executor executor, RunProfile runProfile, JavaParameters javaParameters) {
        MybatisFlexConfig config = Template.getMybatisFlexConfig();
        if (ObjectUtil.defaultIfNull(config.getEnableDebug(), true)) {
            setLogLevel(runProfile, javaParameters);
        }
        if (isSpringBootRunConfiguration(runProfile)) {
            Project project = extractProject(runProfile);
            if (project == null) {
                return;
            }
            ConsoleView consoleView = new ConsoleViewImpl(project, true);
            consoleView.addMessageFilter(new MybatisFlexAgentFilter());
        }

    }

    /**
     * 设置日志级别(只有在spring boot项目生效)
     *
     * @param runProfile
     * @param javaParameters
     */

    private static void setLogLevel(RunProfile runProfile, JavaParameters javaParameters) {
        if (isSpringBootRunConfiguration(runProfile)) {
            PsiClass mainClass = extractMainClass(runProfile);
            if (mainClass == null) {
                return;
            }
            Map<String, String> env = javaParameters.getEnv();
            String packageName = PsiJavaFileUtil.getPackageName(mainClass);
            env.put(StrUtil.format("logging.level.{}", packageName), "debug");
        }

    }

    private static boolean isSpringBootRunConfiguration(RunProfile runProfile) {
        return ObjectUtil.isNotNull(runProfile) && SPRING_BOOT_RUN_CONFIGURATION.equals(runProfile.getClass().getName());
    }

    private static Project extractProject(RunProfile runProfile) {
        try {
            return (Project) runProfile.getClass().getMethod("getProject").invoke(runProfile);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static PsiClass extractMainClass(RunProfile runProfile) {
        try {
            return (PsiClass) runProfile.getClass().getMethod("getMainClass").invoke(runProfile);
        } catch (Exception ignored) {
            return null;
        }
    }
}
