package club.bigtian.mf.plugin.core.util;

import com.intellij.openapi.application.ApplicationInfo;

/**
 * 版本工具
 *
 * @author bigtian
 * @date 2023/07/31
 */
public class VersionUtil {
    private static final ApplicationInfo APPLICATIONINFO = ApplicationInfo.getInstance();

    /**
     * 获取 IDEA 版本
     *
     * @return int
     */
    public static Long getIdeVersion() {
        return Long.parseLong(APPLICATIONINFO.getBuild().asStringWithoutProductCode().replaceAll("\\.", ""));
    }
}
