package club.bigtian.mf.plugin.core.util;

import com.intellij.openapi.compiler.CompileStatusNotification;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.File;
import java.util.concurrent.TimeUnit;

public class CompilerManagerUtil {
    private static final Logger LOG = Logger.getInstance(CompilerManagerUtil.class);

    /**
     * 编译
     *
     * @param files        文件
     * @param notification 通知
     */
    public static void compile(VirtualFile[] files, CompileStatusNotification notification) {
        CompilerManager compilerManager = getCompilerManager();
        compilerManager.compile(files, notification);
    }

    /**
     * 重新编译
     */
    public static void rebuild() {
        CompilerManager compilerManager = getCompilerManager();
        compilerManager.rebuild(null);
        LOG.info("编译完成");
    }

    private static CompilerManager getCompilerManager() {
        CompilerManager compilerManager = CompilerManager.getInstance(ProjectUtils.getCurrentProject());
        return compilerManager;
    }

    /**
     * 单独编译某个模块
     *
     * @param module 模块
     */
    public static void make(Module module) {
        CompilerManager compilerManager = CompilerManager.getInstance(ProjectUtils.getCurrentProject());
        compilerManager.make(module, null);
    }

    public static void make() {
        new Thread(() -> {
            try {
                TimeUnit.SECONDS.sleep(5);
                ProcessBuilder processBuilder = new ProcessBuilder("/Users/daijunxiong/app/gradle-8.1.1/bin/gradle", "build");
                processBuilder.directory(new File(ProjectUtils.getCurrentProject().getBasePath()));

                // 如果需要处理命令行输出，可以将其重定向到标准输出和标准错误流
                processBuilder.redirectErrorStream(true);
                processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);

                // 启动进程执行编译任务
                Process process = processBuilder.start();
                process.waitFor();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).start();

    }


}
