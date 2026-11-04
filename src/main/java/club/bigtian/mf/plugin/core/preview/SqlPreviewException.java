package club.bigtian.mf.plugin.core.preview;

/**
 * SQL 预览快速通道不可用时抛出，调用方据此回退到"生成源文件 + IDE 编译 + 独立进程执行"的旧流程。
 */
public class SqlPreviewException extends RuntimeException {

    public SqlPreviewException(String message) {
        super(message);
    }

    public SqlPreviewException(String message, Throwable cause) {
        super(message, cause);
    }
}
