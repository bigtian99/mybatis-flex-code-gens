package club.bigtian.mf.plugin.core.contributor;

import club.bigtian.mf.plugin.core.config.CustomConfig;
import club.bigtian.mf.plugin.core.icons.Icons;
import club.bigtian.mf.plugin.core.util.Modules;
import club.bigtian.mf.plugin.core.util.TableDefUtils;
import club.bigtian.mf.plugin.core.util.VirtualFileUtils;
import cn.hutool.core.util.StrUtil;
import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service层代码补全
 * Author: BigTian
 */
public class ServiceCompletionContributor extends CompletionContributor {

    private static final String ISERVICE_CLASS = "com.mybatisflex.core.service.IService";

    @Override
    public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
        try {
            PsiElement position = parameters.getPosition();
            Project project = position.getProject();
            PsiFile psiFile = position.getContainingFile();

            // 安全地获取VirtualFile，处理可能为null的情况
            VirtualFile virtualFile = psiFile.getVirtualFile();
            if (virtualFile == null) {
                // 如果VirtualFile为null，尝试从Document获取
                Document document = parameters.getEditor().getDocument();
                virtualFile = VirtualFileUtils.getVirtualFile(document);
                if (virtualFile == null) {
                    // 如果仍然为null，直接返回，无法获取模块信息
                    return;
                }
            }

            Module moduleForFile = ModuleUtil.findModuleForFile(virtualFile, project);
            CustomConfig config = Modules.moduleConfig(moduleForFile);
            // 确保是Java文件
            if (!(psiFile instanceof PsiJavaFile)) {
                return;
            }

            PsiJavaFile javaFile = (PsiJavaFile) psiFile;
            PsiClass[] classes = javaFile.getClasses();

            // 如果没有类定义，直接返回
            if (classes.length == 0) {
                return;
            }

            PsiClass psiClass = classes[0];
            String entityQualifiedName = null;

            // 判断是接口还是类
            if (psiClass.isInterface()) {
                // 处理接口，查找IService<Entity>
                entityQualifiedName = getEntityClassFromInterface(psiClass, project);
            } else {
                // 处理实现类，查找ServiceImpl<Mapper, Entity>
                entityQualifiedName = getEntityClassFromImpl(psiClass, project);
            }

            // 如果获取到了实体类的全限定名，添加补全
            if (entityQualifiedName != null) {
                // 在类名前面增加"table"字符

                // 获取TableDef的全限定名
                String aptClass = TableDefUtils.getTableDefQualifiedName( addTablePrefixToClassName(entityQualifiedName), virtualFile);



                // 提取简单类名用于显示

                addCompletionItems(result, entityQualifiedName, project, aptClass);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 从接口中提取泛型参数，返回实体类的全限定名
     */
    private String getEntityClassFromInterface(PsiClass psiClass, Project project) {
        PsiReferenceList extendsList = psiClass.getExtendsList();
        if (extendsList == null) {
            return null;
        }

        for (PsiJavaCodeReferenceElement referenceElement : extendsList.getReferenceElements()) {
            PsiElement resolved = referenceElement.resolve();
            if (resolved instanceof PsiClass) {
                PsiClass superClass = (PsiClass) resolved;
                String qualifiedName = superClass.getQualifiedName();

                if (ISERVICE_CLASS.equals(qualifiedName)) {
                    // 获取泛型参数
                    PsiTypeElement typeElement = referenceElement.getParameterList().getTypeParameterElements()[0];
                    if (typeElement != null) {
                        PsiType entityType = typeElement.getType();
                        // 尝试获取全限定名
                        String fullQualifiedName = getFullQualifiedNameFromType(entityType, project);
                        return fullQualifiedName != null ? fullQualifiedName : entityType.getPresentableText();
                    }
                }
            }
        }

        // 检查实现的接口
        for (PsiClassType interfaceType : psiClass.getImplementsList().getReferencedTypes()) {
            if (interfaceType.getPresentableText().startsWith("IService<")) {
                PsiType[] parameters = interfaceType.getParameters();
                if (parameters.length > 0) {
                    PsiType entityType = parameters[0];
                    // 尝试获取全限定名
                    String fullQualifiedName = getFullQualifiedNameFromType(entityType, project);
                    return fullQualifiedName != null ? fullQualifiedName : entityType.getPresentableText();
                }
            }
        }

        return null;
    }

    /**
     * 从实现类中提取泛型参数，返回实体类的全限定名
     */
    private String getEntityClassFromImpl(PsiClass psiClass, Project project) {
        PsiReferenceList extendsList = psiClass.getExtendsList();
        if (extendsList == null) {
            return null;
        }

        for (PsiClassType superType : psiClass.getSuperTypes()) {
            String superTypeName = superType.getPresentableText();
            if (superTypeName.startsWith("ServiceImpl<")) {
                PsiType[] parameters = superType.getParameters();
                if (parameters.length >= 2) {
                    // 第二个参数是实体类
                    PsiType entityType = parameters[1];
                    // 尝试获取全限定名
                    String fullQualifiedName = getFullQualifiedNameFromType(entityType, project);
                    return fullQualifiedName != null ? fullQualifiedName : entityType.getPresentableText();
                }
            }
        }

        return null;
    }

    /**
     * 从PsiType获取全限定类名（优化版本）
     *
     * @param psiType PsiType对象
     * @param project 项目对象
     * @return 全限定类名，如果无法获取则返回null
     */
    private String getFullQualifiedNameFromType(PsiType psiType, Project project) {
        if (psiType == null) {
            return null;
        }

        // 方式1：优先使用canonical text（最高效）
        String canonicalText = psiType.getCanonicalText();
        if (canonicalText != null && canonicalText.contains(".") && !canonicalText.contains("<")) {
            // 如果是简单的全限定名（不包含泛型），直接返回
            return canonicalText;
        }

        // 方式2：如果是PsiClassType，通过resolve获取（次优）
        if (psiType instanceof PsiClassType) {
            PsiClassType classType = (PsiClassType) psiType;
            PsiClass psiClass = classType.resolve();
            if (psiClass != null) {
                String qualifiedName = psiClass.getQualifiedName();
                if (qualifiedName != null) {
                    return qualifiedName;
                }
            }
        }

        // 方式3：处理复杂情况（如泛型）
        if (canonicalText != null && canonicalText.contains(".")) {
            // 提取泛型中的主类名
            if (canonicalText.contains("<")) {
                return canonicalText.substring(0, canonicalText.indexOf("<"));
            }
            return canonicalText;
        }

        return null;
    }

    /**
     * 在类名前面增加"table"前缀
     *
     * @param qualifiedName 全限定类名，例如：com.example.entity.table.UserTableDef
     * @return 修改后的全限定类名，例如：com.example.entity.table.tableUserTableDef
     */
    private String addTablePrefixToClassName(String qualifiedName) {
        if (StrUtil.isEmpty(qualifiedName)) {
            return qualifiedName;
        }

        // 分离包名和类名
        int lastDotIndex = qualifiedName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            // 没有包名，直接在类名前加table
            return "table" + qualifiedName;
        }

        String packageName = qualifiedName.substring(0, lastDotIndex);
        String className = qualifiedName.substring(lastDotIndex + 1);

        // 在类名前加table前缀
        return packageName + ".table." + className;
    }

    /**
     * 添加代码补全项
     */
    private void addCompletionItems(CompletionResultSet result, String entityClassName, Project project, String aptClass) {
        // 获取忽略大小写的结果集
        CompletionResultSet completionResultSet = result.caseInsensitive();
        String prefix = completionResultSet.getPrefixMatcher().getPrefix();

        if (StrUtil.isEmpty(prefix)) {
            return;
        }

        // 获取@Column注解value到TableDef字段的映射
        Map<String, String> columnToTableDefMapping = getColumnToTableDefMapping(entityClassName, aptClass, project);

        // 从全限定名中提取简单类名用于显示
        entityClassName = StrUtil.subAfter(entityClassName, ".", true);

        // 根据实体类名添加基本的CRUD方法补全
        List<String> methods = new ArrayList<>();
        methods.add("save(" + entityClassName + " entity)");
        methods.add("saveOrUpdate(" + entityClassName + " entity)");
        methods.add("removeById(Serializable id)");
        methods.add("getById(Serializable id)");
        methods.add("list()");
        methods.add("page(Page<" + entityClassName + "> page)");

        for (String method : methods) {
            LookupElement lookupElement = LookupElementBuilder.create(method)
                    .withPresentableText(method)
                    .withTypeText(entityClassName)
                    .withIcon(Icons.FLEX);

            completionResultSet.addElement(lookupElement);
        }
    }

    /**
     * 获取@Column注解value到TableDef字段的映射关系
     *
     * @param entityQualifiedName 实体类的全限定名
     * @param aptClass TableDef类名
     * @param project 项目对象
     * @return @Column注解value到TableDef字段名的映射
     */
    private Map<String, String> getColumnToTableDefMapping(String entityQualifiedName, String aptClass, Project project) {
        Map<String, String> columnToTableDefMapping = new HashMap<>();

        try {
            JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
            GlobalSearchScope scope = GlobalSearchScope.allScope(project);

            // 查找实体类（使用全限定名）
            PsiClass entityClass = findEntityClass(entityQualifiedName, psiFacade, scope);
            // 查找TableDef类
            PsiClass tableDefClass = findTableDefClass(aptClass, psiFacade, scope);

            if (entityClass != null && tableDefClass != null) {
                // 获取实体类字段的@Column注解值
                Map<String, String> entityFieldToColumn = getEntityFieldToColumnMap(entityClass);
                // 获取TableDef类的字段
                Map<String, String> tableDefFields = getTableDefFieldsMap(tableDefClass);

                // 建立@Column value到TableDef字段的映射
                for (Map.Entry<String, String> entityEntry : entityFieldToColumn.entrySet()) {
                    String entityFieldName = entityEntry.getKey();
                    String columnValue = entityEntry.getValue();

                    // 在TableDef中查找对应的字段（通常是大写形式）
                    String tableDefFieldName = findMatchingTableDefField(entityFieldName, tableDefFields);
                    if (tableDefFieldName != null) {
                        columnToTableDefMapping.put(columnValue, tableDefFieldName);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return columnToTableDefMapping;
    }

    /**
     * 根据全限定名查找实体类
     *
     * @param entityQualifiedName 实体类的全限定名
     * @param psiFacade JavaPsiFacade
     * @param scope 搜索范围
     * @return 找到的PsiClass，如果找不到返回null
     */
    private PsiClass findEntityClass(String entityQualifiedName, JavaPsiFacade psiFacade, GlobalSearchScope scope) {
        if (StrUtil.isEmpty(entityQualifiedName)) {
            return null;
        }

        return ApplicationManager.getApplication().runReadAction((Computable<PsiClass>) () -> {
            try {
                // 直接通过全限定名查找
                PsiClass entityClass = psiFacade.findClass(entityQualifiedName, scope);
                if (entityClass != null) {
                    return entityClass;
                }

                // 如果传入的不是全限定名，而是简单类名，则尝试在常见包中查找
                if (!entityQualifiedName.contains(".")) {
                    String[] commonEntityPackages = {
                        "entity", "model", "domain", "pojo", "bean"
                    };

                    for (String packageName : commonEntityPackages) {
                        String fullClassName = packageName + "." + entityQualifiedName;
                        entityClass = psiFacade.findClass(fullClassName, scope);
                        if (entityClass != null) {
                            return entityClass;
                        }
                    }

                    // 通过类名搜索所有匹配的类
                    PsiClass[] classes = psiFacade.findClasses(entityQualifiedName, scope);
                    if (classes.length > 0) {
                        return classes[0];
                    }
                }

                return null;
            } catch (Exception e) {
                // 记录异常但不中断流程
                return null;
            }
        });
    }

    /**
     * 查找TableDef类
     *
     * @param aptClass TableDef类名
     * @param psiFacade JavaPsiFacade
     * @param scope 搜索范围
     * @return 找到的TableDef PsiClass
     */
    private PsiClass findTableDefClass(String aptClass, JavaPsiFacade psiFacade, GlobalSearchScope scope) {
        return ApplicationManager.getApplication().runReadAction((Computable<PsiClass>) () -> {
            try {
                // 尝试直接查找
                PsiClass tableDefClass = psiFacade.findClass(aptClass, scope);
                if (tableDefClass != null) {
                    return tableDefClass;
                }

                // 在常见的table包中查找
                String[] commonTablePackages = {
                    "table", "entity.table", "model.table", "domain.table"
                };

                for (String packageName : commonTablePackages) {
                    String fullClassName = packageName + "." + aptClass;
                    tableDefClass = psiFacade.findClass(fullClassName, scope);
                    if (tableDefClass != null) {
                        return tableDefClass;
                    }
                }

                return null;
            } catch (Exception e) {
                // 记录异常但不中断流程
                return null;
            }
        });
    }

    /**
     * 获取实体类字段到@Column注解value的映射
     *
     * @param entityClass 实体类
     * @return 字段名到@Column注解value的映射
     */
    private Map<String, String> getEntityFieldToColumnMap(PsiClass entityClass) {
        Map<String, String> fieldToColumnMap = new HashMap<>();

        PsiField[] fields = entityClass.getAllFields();
        for (PsiField field : fields) {
            String fieldName = field.getName();
            String columnValue = getColumnAnnotationValue(field);

            // 如果没有@Column注解，使用字段名
            if (StrUtil.isEmpty(columnValue)) {
                columnValue = fieldName;
            }

            fieldToColumnMap.put(fieldName, columnValue);
        }

        return fieldToColumnMap;
    }

    /**
     * 获取TableDef类的字段映射
     *
     * @param tableDefClass TableDef类
     * @return 字段名映射（小写到原始字段名）
     */
    private Map<String, String> getTableDefFieldsMap(PsiClass tableDefClass) {
        Map<String, String> tableDefFieldsMap = new HashMap<>();

        PsiField[] fields = tableDefClass.getAllFields();
        for (PsiField field : fields) {
            String fieldName = field.getName();
            // 建立小写字段名到原始字段名的映射，便于查找
            tableDefFieldsMap.put(fieldName.toLowerCase(), fieldName);
        }

        return tableDefFieldsMap;
    }

    /**
     * 在TableDef字段中查找匹配的字段
     *
     * @param entityFieldName 实体类字段名
     * @param tableDefFields TableDef字段映射
     * @return 匹配的TableDef字段名
     */
    private String findMatchingTableDefField(String entityFieldName, Map<String, String> tableDefFields) {
        // 方式1：直接匹配（小写）
        String lowerFieldName = entityFieldName.toLowerCase();
        if (tableDefFields.containsKey(lowerFieldName)) {
            return tableDefFields.get(lowerFieldName);
        }

        // 方式2：转换为大写下划线格式匹配
        String upperUnderscoreFieldName = toUpperUnderscoreCase(entityFieldName).toLowerCase();
        if (tableDefFields.containsKey(upperUnderscoreFieldName)) {
            return tableDefFields.get(upperUnderscoreFieldName);
        }

        // 方式3：模糊匹配
        for (Map.Entry<String, String> entry : tableDefFields.entrySet()) {
            String tableDefFieldLower = entry.getKey();
            String originalFieldName = entry.getValue();

            // 如果TableDef字段名包含实体字段名
            if (tableDefFieldLower.contains(lowerFieldName) ||
                lowerFieldName.contains(tableDefFieldLower)) {
                return originalFieldName;
            }
        }

        return null;
    }

    /**
     * 将驼峰命名转换为大写下划线格式
     *
     * @param camelCase 驼峰命名字符串
     * @return 大写下划线格式字符串
     */
    private String toUpperUnderscoreCase(String camelCase) {
        if (StrUtil.isEmpty(camelCase)) {
            return camelCase;
        }

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                result.append('_');
            }
            result.append(Character.toUpperCase(c));
        }
        return result.toString();
    }

    /**
     * 获取字段的@Column注解的value值
     *
     * @param field PsiField对象
     * @return @Column注解的value值，如果没有注解或value为空则返回null
     */
    private String getColumnAnnotationValue(PsiField field) {
        PsiAnnotation columnAnnotation = field.getAnnotation("com.mybatisflex.annotation.Column");
        if (columnAnnotation == null) {
            return null;
        }

        // 获取value属性
        PsiAnnotationMemberValue valueAttribute = columnAnnotation.findAttributeValue("value");
        if (valueAttribute != null) {
            String value = valueAttribute.getText();
            // 移除引号
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            return StrUtil.isNotEmpty(value) ? value : null;
        }

        return null;
    }
}