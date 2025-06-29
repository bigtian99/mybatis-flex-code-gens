package club.bigtian.mf.plugin.core.util;

import club.bigtian.mf.plugin.core.MybatisFlexDocumentChangeHandler;
import club.bigtian.mf.plugin.core.config.CustomConfig;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.google.common.collect.Maps;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClassOwner;
import com.intellij.psi.PsiManager;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TableDefUtils {

    private static Map<String, String> tableDefMappingMap = new HashMap<>();

    public static Map<String, String> getTableDefMappingMap() {
        return tableDefMappingMap;
    }

    public static Map<String, Map<String, String>> getDependenciesTableDefKeyTable(VirtualFile currentFile) {
        return getDependenciesTableDef(currentFile).values()
                .stream()
                .collect(Collectors.toMap(el -> {
                    String body = el.getClasses()[0].getConstructors()[0].getBody().getText().replace(" ", "");
                    return StrUtil.subBetween(body, ",\"", "\"");
                }, el -> {
                    return Arrays.stream(el.getClasses()[0].getFields())
                            .collect(Collectors.toMap(field -> StrUtil.subBetween(field.getInitializer().getText().replace(" ", ""), ",\"", "\""),
                                    field -> field.getNameIdentifier().getText(), (k1, k2) -> k1));
                }, (k1, k2) -> k1));
    }

    /**
     * 获取模块依赖关系的所有 tableDef文件
     *
     * @param currentFile 当前文件
     */
    public static Map<String, PsiClassOwner> getDependenciesTableDef(VirtualFile currentFile) {
        Map<String, PsiClassOwner> tableDefMap = Maps.newHashMap();
        Module module = ModuleUtil.findModuleForFile(currentFile, ProjectUtils.getCurrentProject());
        if (ObjectUtil.isNull(module)) {
            return tableDefMap;
        }
        // 获取当前模块的依赖模块
        List<Module> moduleList = Arrays.stream(ModuleRootManager.getInstance(module).getDependencies())
                .collect(Collectors.toList());
        moduleList.add(module);
        CustomConfig config = new CustomConfig();
        // 获取当前模块以及所依赖的模块的TableDef文件
        for (Module dependency : moduleList) {
            VirtualFile[] contentRoots = ModuleRootManager.getInstance(dependency).getContentRoots();
            VirtualFile virtualFile = null;
            for (VirtualFile contentRoot : contentRoots) {
                config = Modules.moduleConfig(dependency);
                virtualFile = VirtualFileUtils.getVirtualFile(contentRoot, config);
                if (ObjectUtil.isNotNull(virtualFile)) {
                    break;
                }
            }
            if (ObjectUtil.isNull(virtualFile)) {
                return tableDefMap;
            }
            getTableDef(virtualFile, tableDefMap, config);
        }
        return tableDefMap;
    }


    /**
     * 获取相关的TableDef文件
     *
     * @param file
     * @param tableDefMap
     */
    public static void getTableDef(VirtualFile file, Map<String, PsiClassOwner> tableDefMap, CustomConfig config) {
        try {
            VirtualFile[] children = file.getChildren();
            for (VirtualFile child : children) {
                boolean directory = child.isDirectory();
                if (directory) {
                    getTableDef(child, tableDefMap, config);
                } else {
                    String name = child.getName();
                    String tableDefConf = ObjectUtil.defaultIfNull(config.getTableDefClassSuffix(), "TableDef");
                    if (name.contains(tableDefConf)) {
                        PsiClassOwner psiJavaFile = (PsiClassOwner) PsiManager.getInstance(ProjectUtils.getCurrentProject()).findFile(child);
                        assert psiJavaFile != null;
                        String packageName = psiJavaFile.getPackageName().replace("table", "");

                        String path = StrUtil.subBefore(child.getPath(), ".", true);
                        String tableDef = StrUtil.subAfter(path, "/", true);
                        tableDefMap.put(packageName + tableDef.replace(tableDefConf, ""), psiJavaFile);

                        String tableName = MybatisFlexDocumentChangeHandler.getDefInstanceName(config, StrUtil.subBefore(tableDef, tableDefConf, false), true);
                        tableDefMappingMap.put(tableName,  psiJavaFile.getPackageName()+"."+ tableDef);
                    }
                }
            }
        } catch (Exception e) {

        }
    }

    /**
     * 根据实体类的全限定名查找对应的TableDef文件
     *
     * @param entityQualifiedName 实体类的全限定名，例如：com.example.entity.User
     * @param currentFile 当前文件（用于确定模块上下文）
     * @return 对应的TableDef文件，如果找不到则返回null
     */
    public static PsiClassOwner findTableDefByEntityQualifiedName(String entityQualifiedName, VirtualFile currentFile) {
        if (StrUtil.isEmpty(entityQualifiedName) || currentFile == null) {
            return null;
        }

        // 获取所有TableDef文件
        Map<String, PsiClassOwner> tableDefMap = getDependenciesTableDef(currentFile);

        // 直接通过全限定名查找
        PsiClassOwner tableDefFile = tableDefMap.get(entityQualifiedName);
        if (tableDefFile != null) {
            return tableDefFile;
        }

        // 如果直接查找失败，尝试通过类名查找
        String className = StrUtil.subAfter(entityQualifiedName, ".", true);
        for (Map.Entry<String, PsiClassOwner> entry : tableDefMap.entrySet()) {
            String key = entry.getKey();
            if (key.endsWith("." + className) || key.endsWith(className)) {
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * 根据实体类的全限定名获取TableDef类的全限定名
     *
     * @param entityQualifiedName 实体类的全限定名
     * @param currentFile 当前文件
     * @return TableDef类的全限定名，如果找不到则返回null
     */
    public static String getTableDefQualifiedName(String entityQualifiedName, VirtualFile currentFile) {
        PsiClassOwner tableDefFile = findTableDefByEntityQualifiedName(entityQualifiedName, currentFile);
        if (tableDefFile == null) {
            return null;
        }

        // 获取配置
        Module module = ModuleUtil.findModuleForFile(currentFile, ProjectUtils.getCurrentProject());
        CustomConfig config = Modules.moduleConfig(module);

        // 构建TableDef类名
        String className = StrUtil.subAfter(entityQualifiedName, ".", true);
        String tableDefClassName = getTableDefClassName(className, config);

        return tableDefFile.getPackageName() + "." + tableDefClassName;
    }

    /**
     * 根据实体类名生成TableDef类名
     *
     * @param entityClassName 实体类名
     * @param config 配置
     * @return TableDef类名
     */
    private static String getTableDefClassName(String entityClassName, CustomConfig config) {
        // 处理实体类后缀过滤
        String ignoreEntitySuffixes = config.getTableDefIgnoreEntitySuffixes();
        if (StrUtil.isNotEmpty(ignoreEntitySuffixes)) {
            String[] suffixes = ignoreEntitySuffixes.split(",");
            for (String suffix : suffixes) {
                suffix = suffix.trim();
                if (entityClassName.endsWith(suffix)) {
                    entityClassName = entityClassName.replace(suffix, "");
                    break;
                }
            }
        }

        // 添加TableDef后缀
        String tableDefSuffix = ObjectUtil.defaultIfNull(config.getTableDefClassSuffix(), "TableDef");
        return entityClassName + tableDefSuffix;
    }
}
