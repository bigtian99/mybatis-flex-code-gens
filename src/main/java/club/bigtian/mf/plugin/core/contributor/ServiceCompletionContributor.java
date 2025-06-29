package club.bigtian.mf.plugin.core.contributor;

import club.bigtian.mf.plugin.core.config.CustomConfig;
import club.bigtian.mf.plugin.core.icons.Icons;
import club.bigtian.mf.plugin.core.util.Modules;
import club.bigtian.mf.plugin.core.util.TableDefUtils;
import club.bigtian.mf.plugin.core.util.VirtualFileUtils;
import club.bigtian.mf.plugin.entity.AptInfo;
import cn.hutool.core.util.StrUtil;
import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import java.util.Set;
import java.util.HashSet;
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
 * Service层代码补全 - 增强APT字段补全功能
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
                // 获取TableDef的全限定名
                String aptClass = TableDefUtils.getTableDefQualifiedName(entityQualifiedName, virtualFile);

                // 检查是否是方法名补全（根据输入的前缀判断）
                String prefix = result.getPrefixMatcher().getPrefix();
                if (isMethodNameCompletion(prefix)) {
                    // 根据方法名生成查询方法
                    addMethodNameBasedCompletion(result, entityQualifiedName, project, aptClass, prefix, parameters);
                } else {
                    // 提供基本的CRUD方法补全
                    addCompletionItems(result, entityQualifiedName, project, aptClass);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 判断当前位置是否应该提供APT字段补全
     *
     * @param position 当前光标位置的PSI元素
     * @param parameters 补全参数
     * @return 如果应该提供APT字段补全则返回true
     */
    private boolean shouldProvideAptCompletion(PsiElement position, CompletionParameters parameters) {
        // 检查是否在QueryWrapper或类似的查询构建器上下文中
        PsiElement parent = position.getParent();
        while (parent != null) {
            if (parent instanceof PsiMethodCallExpression) {
                PsiMethodCallExpression methodCall = (PsiMethodCallExpression) parent;
                PsiExpression qualifierExpression = methodCall.getMethodExpression().getQualifierExpression();
                if (qualifierExpression != null) {
                    PsiType type = qualifierExpression.getType();
                    if (type != null) {
                        String typeName = type.getCanonicalText();
                        // 检查是否是QueryWrapper或相关的查询类
                        if (typeName.contains("QueryWrapper") || typeName.contains("QueryCondition") ||
                            typeName.contains("QueryColumn")) {
                            return true;
                        }
                    }
                }

                // 检查方法名是否是查询相关的方法
                String methodName = methodCall.getMethodExpression().getReferenceName();
                if (methodName != null && (methodName.contains("where") || methodName.contains("and") ||
                    methodName.contains("or") || methodName.contains("select") || methodName.contains("orderBy"))) {
                    return true;
                }
            }
            parent = parent.getParent();
        }

        // 检查是否在访问TableDef字段的上下文中
        String text = parameters.getEditor().getDocument().getText();
        int offset = parameters.getOffset();
        if (offset > 10) {
            String beforeCursor = text.substring(Math.max(0, offset - 50), offset);
            if (beforeCursor.contains("TableDef") || beforeCursor.contains(".table.") ||
                beforeCursor.contains("TABLE_DEF") || beforeCursor.contains("QueryWrapper") ||
                beforeCursor.contains(".eq(") || beforeCursor.contains(".ne(") ||
                beforeCursor.contains(".like(") || beforeCursor.contains(".in(")) {
                return true;
            }
        }

        return false;
    }

    /**
     * 判断是否是方法名补全
     */
    private boolean isMethodNameCompletion(String prefix) {
        if (StrUtil.isEmpty(prefix)) {
            return false;
        }

        // 检查是否以查询方法前缀开头（不区分大小写）
        String lowerPrefix = prefix.toLowerCase();
        String[] queryPrefixes = {"listby", "findby", "getby", "countby", "existsby", "removeby", "deleteby",
                                 "list", "find", "get", "count", "exists", "remove", "delete"};
        for (String queryPrefix : queryPrefixes) {
            if (lowerPrefix.startsWith(queryPrefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 根据方法名生成查询方法补全
     */
    private void addMethodNameBasedCompletion(CompletionResultSet result, String entityQualifiedName,
                                            Project project, String aptClass, String prefix, CompletionParameters parameters) {
        try {
            JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
            GlobalSearchScope scope = GlobalSearchScope.allScope(project);

            // 查找实体类
            PsiClass entityClass = findEntityClass(entityQualifiedName, psiFacade, scope);
            if (entityClass == null) {
                return;
            }

            // 获取当前Service类，用于检查方法是否已存在
            PsiClass currentServiceClass = getCurrentServiceClass(parameters);

            // 获取实体类字段信息
            Map<String, FieldInfo> entityFields = getEntityFieldsInfo(entityClass);

            // 获取TableDef字段映射
            Map<String, String> fieldToTableDefMapping = getFieldToTableDefMapping(entityQualifiedName, aptClass, project);

            // 解析方法名前缀
            String methodPrefix = extractMethodPrefix(prefix);
            if (StrUtil.isEmpty(methodPrefix)) {
                // 如果没有完整的方法前缀，提供方法前缀补全
                addMethodPrefixCompletions(result, prefix, entityFields, fieldToTableDefMapping, entityQualifiedName, currentServiceClass);
                return;
            }

            String fieldsPart = prefix.substring(methodPrefix.length());

            // 生成可能的方法补全
            generateMethodCompletions(result, methodPrefix, fieldsPart, entityFields, fieldToTableDefMapping, entityQualifiedName, currentServiceClass);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }



    /**
     * 获取当前Service类
     */
    private PsiClass getCurrentServiceClass(CompletionParameters parameters) {
        try {
            PsiElement position = parameters.getPosition();
            PsiElement current = position;

            // 向上查找包含的类
            while (current != null) {
                if (current instanceof PsiClass) {
                    return (PsiClass) current;
                }
                current = current.getParent();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 添加方法前缀补全
     */
    private void addMethodPrefixCompletions(CompletionResultSet result, String prefix,
                                          Map<String, FieldInfo> entityFields, Map<String, String> fieldToTableDefMapping,
                                          String entityQualifiedName, PsiClass currentServiceClass) {

        String simpleEntityName = StrUtil.subAfter(entityQualifiedName, ".", true);
        String[] queryPrefixes = {"listBy", "findBy", "getBy", "countBy", "existsBy", "removeBy", "deleteBy"};
        String lowerPrefix = prefix.toLowerCase();

        for (String queryPrefix : queryPrefixes) {
            if (queryPrefix.toLowerCase().startsWith(lowerPrefix)) {

                // 添加整个实体的方法补全（如listBy + 实体名）
                if (queryPrefix.equals("listBy") || queryPrefix.equals("findBy")) {
                    String entityMethodName = queryPrefix + simpleEntityName;
                    if (currentServiceClass == null || !methodExists(currentServiceClass, entityMethodName)) {
                        String entityMethodSignature = entityMethodName + "()";
                        String entityMethodBody = generateEntityMethodBody(queryPrefix, entityFields, fieldToTableDefMapping, simpleEntityName);
                        String returnType = getReturnTypeForMethodPrefix(queryPrefix, simpleEntityName);

                        LookupElement entityMethodElement = LookupElementBuilder.create(entityMethodSignature)
                                .withPresentableText(entityMethodSignature)
                                .withTypeText(returnType)
                                .withTailText(" { " + entityMethodBody + " }", true)
                                .withIcon(Icons.FLEX)
                                .withInsertHandler((context, item) -> {
                                    // 为整个实体方法创建特殊的字段映射
                                    Map<String, FieldInfo> entityMethodFields = new HashMap<>();
                                    entityMethodFields.put("entity", new FieldInfo("entity", simpleEntityName, "entity"));
                                    insertMethodImplementationWithCursor(context, entityMethodName, entityMethodBody, returnType, "Entity", entityMethodFields, entityQualifiedName, fieldToTableDefMapping);
                                });

                        result.addElement(entityMethodElement);
                    }
                }

                // 为每个字段生成方法补全
                for (FieldInfo fieldInfo : entityFields.values()) {
                    String capitalizedFieldName = StrUtil.upperFirst(fieldInfo.fieldName);
                    String methodName = queryPrefix + capitalizedFieldName;

                    // 检查方法是否已存在
                    if (currentServiceClass != null && methodExists(currentServiceClass, methodName)) {
                        continue; // 跳过已存在的方法
                    }

                    String methodSignature = methodName + "()";
                    String methodBody = generateSingleFieldMethodBody(queryPrefix, fieldInfo, fieldToTableDefMapping, simpleEntityName);
                    String returnType = getReturnTypeForMethodPrefix(queryPrefix, simpleEntityName);

                    // 创建单字段的字段信息映射
                    Map<String, FieldInfo> singleFieldMap = new HashMap<>();
                    singleFieldMap.put(fieldInfo.fieldName, fieldInfo);

                    LookupElement methodElement = LookupElementBuilder.create(methodSignature)
                            .withPresentableText(methodSignature)
                            .withTypeText(returnType)
                            .withTailText(" { " + methodBody + " }", true)
                            .withIcon(Icons.FLEX)
                            .withInsertHandler((context, item) -> {
                                insertMethodImplementationWithCursor(context, methodName, methodBody, returnType, capitalizedFieldName, singleFieldMap, entityQualifiedName, fieldToTableDefMapping);
                            });

                    result.addElement(methodElement);
                }
            }
        }
    }

    /**
     * 检查方法是否已存在
     */
    private boolean methodExists(PsiClass serviceClass, String methodName) {
        if (serviceClass == null || StrUtil.isEmpty(methodName)) {
            return false;
        }

        // 检查当前类中是否已有同名方法
        PsiMethod[] methods = serviceClass.getAllMethods();
        for (PsiMethod method : methods) {
            if (methodName.equals(method.getName())) {
                return true;
            }
        }

        return false;
    }

    /**
     * 实体字段信息类
     */
    private static class FieldInfo {
        String fieldName;
        String fieldType;
        String columnName;

        FieldInfo(String fieldName, String fieldType, String columnName) {
            this.fieldName = fieldName;
            this.fieldType = fieldType;
            this.columnName = columnName;
        }
    }

    /**
     * 获取实体类字段信息
     */
    private Map<String, FieldInfo> getEntityFieldsInfo(PsiClass entityClass) {
        Map<String, FieldInfo> fieldsInfo = new HashMap<>();

        PsiField[] fields = entityClass.getAllFields();
        for (PsiField field : fields) {
            // 跳过静态字段和常量
            if (field.hasModifierProperty(PsiModifier.STATIC) ||
                field.hasModifierProperty(PsiModifier.FINAL)) {
                continue;
            }

            String fieldName = field.getName();
            String fieldType = field.getType().getPresentableText();
            String columnName = getColumnAnnotationValue(field);
            if (StrUtil.isEmpty(columnName)) {
                columnName = toUnderscoreCase(fieldName);
            }

            fieldsInfo.put(fieldName, new FieldInfo(fieldName, fieldType, columnName));
        }

        return fieldsInfo;
    }

    /**
     * 提取方法前缀（不区分大小写）
     */
    private String extractMethodPrefix(String prefix) {
        String lowerPrefix = prefix.toLowerCase();
        String[] queryPrefixes = {"listby", "findby", "getby", "countby", "existsby", "removeby", "deleteby"};
        String[] originalPrefixes = {"listBy", "findBy", "getBy", "countBy", "existsBy", "removeBy", "deleteBy"};

        for (int i = 0; i < queryPrefixes.length; i++) {
            String queryPrefix = queryPrefixes[i];
            if (lowerPrefix.startsWith(queryPrefix) && prefix.length() > queryPrefix.length()) {
                return originalPrefixes[i]; // 返回标准格式的前缀
            }
        }
        return "";
    }

    /**
     * 生成方法补全
     */
    private void generateMethodCompletions(CompletionResultSet result, String methodPrefix, String fieldsPart,
                                         Map<String, FieldInfo> entityFields, Map<String, String> fieldToTableDefMapping,
                                         String entityQualifiedName, PsiClass currentServiceClass) {

        String simpleEntityName = StrUtil.subAfter(entityQualifiedName, ".", true);

        // 如果字段部分为空，提供所有可能的字段组合
        if (StrUtil.isEmpty(fieldsPart)) {
            generateSingleFieldMethods(result, methodPrefix, entityFields, fieldToTableDefMapping, simpleEntityName, currentServiceClass, entityQualifiedName);
            return;
        }

        // 解析已输入的字段部分
        List<String> possibleCompletions = parseFieldsAndGenerateCompletions(fieldsPart, entityFields, fieldToTableDefMapping);

        for (String completion : possibleCompletions) {
            String fullMethodName = methodPrefix + completion;

            // 检查方法是否已存在
            if (currentServiceClass != null && methodExists(currentServiceClass, fullMethodName)) {
                continue; // 跳过已存在的方法
            }

            // 生成空的方法签名，不包含参数
            String methodSignature = fullMethodName + "()";
            String methodBody = generateMethodBody(methodPrefix, completion, fieldToTableDefMapping, simpleEntityName);
            String returnType = getReturnTypeForMethodPrefix(methodPrefix, simpleEntityName);

            LookupElement methodElement = LookupElementBuilder.create(methodSignature)
                    .withPresentableText(methodSignature)
                    .withTypeText(returnType)
                    .withTailText(" { " + methodBody + " }", true)
                    .withIcon(Icons.FLEX)
                    .withInsertHandler((context, item) -> {
                        // 插入方法体，光标聚焦到括号内
                        insertMethodImplementationWithCursor(context, fullMethodName, methodBody, returnType, completion, entityFields, entityQualifiedName, fieldToTableDefMapping);
                    });

            result.addElement(methodElement);
        }
    }

    /**
     * 生成单字段方法
     */
    private void generateSingleFieldMethods(CompletionResultSet result, String methodPrefix,
                                          Map<String, FieldInfo> entityFields, Map<String, String> fieldToTableDefMapping,
                                          String simpleEntityName, PsiClass currentServiceClass, String entityQualifiedName) {

        for (FieldInfo fieldInfo : entityFields.values()) {
            String capitalizedFieldName = StrUtil.upperFirst(fieldInfo.fieldName);
            String methodName = methodPrefix + capitalizedFieldName;

            // 检查方法是否已存在
            if (currentServiceClass != null && methodExists(currentServiceClass, methodName)) {
                continue; // 跳过已存在的方法
            }

            String methodSignature = methodName + "()";
            String methodBody = generateSingleFieldMethodBody(methodPrefix, fieldInfo, fieldToTableDefMapping, simpleEntityName);
            String returnType = getReturnTypeForMethodPrefix(methodPrefix, simpleEntityName);

            // 创建单字段的字段信息映射
            Map<String, FieldInfo> singleFieldMap = new HashMap<>();
            singleFieldMap.put(fieldInfo.fieldName, fieldInfo);

            LookupElement methodElement = LookupElementBuilder.create(methodSignature)
                    .withPresentableText(methodSignature)
                    .withTypeText(returnType)
                    .withTailText(" { " + methodBody + " }", true)
                    .withIcon(Icons.FLEX)
                    .withInsertHandler((context, item) -> {
                        insertMethodImplementationWithCursor(context, methodName, methodBody, returnType, capitalizedFieldName, singleFieldMap, entityQualifiedName, fieldToTableDefMapping);
                    });

            result.addElement(methodElement);
        }
    }

    /**
     * 解析字段并生成补全
     */
    private List<String> parseFieldsAndGenerateCompletions(String fieldsPart, Map<String, FieldInfo> entityFields,
                                                          Map<String, String> fieldToTableDefMapping) {
        List<String> completions = new ArrayList<>();

        // 获取已使用的字段列表，避免重复
        Set<String> usedFields = getUsedFieldsFromInput(fieldsPart);

        // 检查是否以单个字母结尾，可能是要输入And/Or
        if (fieldsPart.length() > 0) {
            char lastChar = fieldsPart.charAt(fieldsPart.length() - 1);
            if (lastChar == 'a' || lastChar == 'A') {
                // 检查前面是否是完整的字段名
                String prefix = fieldsPart.substring(0, fieldsPart.length() - 1);
                if (endsWithValidField(prefix, entityFields)) {
                    completions.add(prefix + "And");
                    return completions; // 只返回And选项，保持连贯性
                }
            } else if (lastChar == 'o' || lastChar == 'O') {
                String prefix = fieldsPart.substring(0, fieldsPart.length() - 1);
                if (endsWithValidField(prefix, entityFields)) {
                    completions.add(prefix + "Or");
                    return completions; // 只返回Or选项，保持连贯性
                }
            }
        }

        // 检查是否以And/Or结尾，需要补全字段名
        if (fieldsPart.endsWith("And") || fieldsPart.endsWith("Or")) {
            for (String fieldName : entityFields.keySet()) {
                String capitalizedFieldName = StrUtil.upperFirst(fieldName);
                // 避免重复字段
                if (!usedFields.contains(fieldName)) {
                    completions.add(fieldsPart + capitalizedFieldName);
                }
            }
            return completions;
        }

        // 查找可能的字段匹配
        for (String fieldName : entityFields.keySet()) {
            String capitalizedFieldName = StrUtil.upperFirst(fieldName);

            // 检查是否匹配当前输入
            if (capitalizedFieldName.startsWith(fieldsPart)) {
                // 避免重复字段
                if (!usedFields.contains(fieldName)) {
                    // 单字段
                    completions.add(capitalizedFieldName);
                }
            }

            // 检查And/Or组合中的字段匹配
            if (fieldsPart.contains("And") || fieldsPart.contains("Or")) {
                String[] parts = fieldsPart.split("(?=And|Or)");
                if (parts.length > 0) {
                    String lastPart = parts[parts.length - 1];
                    if (lastPart.startsWith("And") || lastPart.startsWith("Or")) {
                        String connector = lastPart.startsWith("And") ? "And" : "Or";
                        String remainingPart = lastPart.substring(connector.length());

                        if (capitalizedFieldName.startsWith(remainingPart)) {
                            // 避免重复字段
                            if (!usedFields.contains(fieldName)) {
                                String prefix = fieldsPart.substring(0, fieldsPart.length() - remainingPart.length());
                                String completion = prefix + capitalizedFieldName;
                                completions.add(completion);
                            }
                        }
                    }
                }
            }
        }

        return completions;
    }

    /**
     * 获取已使用的字段列表
     */
    private Set<String> getUsedFieldsFromInput(String input) {
        Set<String> usedFields = new HashSet<>();

        // 分割And/Or，提取字段名
        String[] parts = input.split("(?=And|Or)");
        for (String part : parts) {
            String fieldPart = part;
            if (fieldPart.startsWith("And") || fieldPart.startsWith("Or")) {
                fieldPart = fieldPart.substring(fieldPart.startsWith("And") ? 3 : 2);
            }

            if (StrUtil.isNotEmpty(fieldPart)) {
                // 转换为小写驼峰形式
                String fieldName = StrUtil.lowerFirst(fieldPart);
                usedFields.add(fieldName);
            }
        }

        return usedFields;
    }

    /**
     * 检查输入是否以有效字段结尾
     */
    private boolean endsWithValidField(String input, Map<String, FieldInfo> entityFields) {
        for (String fieldName : entityFields.keySet()) {
            String capitalizedFieldName = StrUtil.upperFirst(fieldName);
            if (input.endsWith(capitalizedFieldName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查是否是有效的字段前缀
     */
    private boolean isValidFieldPrefix(String prefix, Map<String, FieldInfo> entityFields) {
        for (String fieldName : entityFields.keySet()) {
            String capitalizedFieldName = StrUtil.upperFirst(fieldName);
            if (prefix.endsWith(capitalizedFieldName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 生成方法体
     */
    private String generateMethodBody(String methodPrefix, String fieldsCompletion,
                                    Map<String, String> fieldToTableDefMapping, String simpleEntityName) {

        // 解析字段组合
        List<String> fields = parseFieldsFromCompletion(fieldsCompletion);
        List<String> connectors = parseConnectorsFromCompletion(fieldsCompletion);

        // 获取TableDef静态变量名
        String tableDefInstanceName = getTableDefInstanceName(simpleEntityName);

        StringBuilder methodBody = new StringBuilder();
        methodBody.append("QueryWrapper queryWrapper = QueryWrapper.create()");

        for (int i = 0; i < fields.size(); i++) {
            String field = fields.get(i);
            String tableDefField = fieldToTableDefMapping.get(field);
            if (tableDefField == null) {
                tableDefField = field.toUpperCase();
            }

            String fullFieldReference = tableDefInstanceName + "." + tableDefField;

            if (i == 0) {
                methodBody.append("\n        .where(").append(fullFieldReference).append(".eq(").append(field).append("))");
            } else {
                String connector = i - 1 < connectors.size() ? connectors.get(i - 1) : "and";
                if ("or".equalsIgnoreCase(connector)) {
                    methodBody.append("\n        .or(").append(fullFieldReference).append(".eq(").append(field).append("))");
                } else {
                    methodBody.append("\n        .and(").append(fullFieldReference).append(".eq(").append(field).append("))");
                }
            }
        }

        methodBody.append(";\n");
        methodBody.append(generateReturnStatement(methodPrefix, "queryWrapper", simpleEntityName));

        return methodBody.toString();
    }

    /**
     * 生成单字段方法体
     */
    private String generateSingleFieldMethodBody(String methodPrefix, FieldInfo fieldInfo,
                                                Map<String, String> fieldToTableDefMapping, String simpleEntityName) {

        String tableDefField = fieldToTableDefMapping.get(fieldInfo.fieldName);
        if (tableDefField == null) {
            tableDefField = fieldInfo.fieldName.toUpperCase();
        }

        // 获取TableDef静态变量名
        String tableDefInstanceName = getTableDefInstanceName(simpleEntityName);
        String fullFieldReference = tableDefInstanceName + "." + tableDefField;

        StringBuilder methodBody = new StringBuilder();
        methodBody.append("QueryWrapper queryWrapper = QueryWrapper.create()\n");
        methodBody.append("        .where(").append(fullFieldReference).append(".eq(").append(fieldInfo.fieldName).append("));\n");
        methodBody.append(generateReturnStatement(methodPrefix, "queryWrapper", simpleEntityName));

        return methodBody.toString();
    }

    /**
     * 生成整个实体的方法体（使用Condition条件判断）
     */
    private String generateEntityMethodBody(String methodPrefix, Map<String, FieldInfo> entityFields,
                                          Map<String, String> fieldToTableDefMapping, String simpleEntityName) {

        // 获取TableDef静态变量名
        String tableDefInstanceName = getTableDefInstanceName(simpleEntityName);

        StringBuilder methodBody = new StringBuilder();
        methodBody.append("QueryWrapper queryWrapper = QueryWrapper.create()");

        boolean isFirst = true;
        for (FieldInfo fieldInfo : entityFields.values()) {
            String tableDefField = fieldToTableDefMapping.get(fieldInfo.fieldName);
            // 只处理在APT中确实存在的字段
            if (tableDefField == null) {
                continue; // 跳过APT中不存在的字段
            }

            String fullFieldReference = tableDefInstanceName + "." + tableDefField;
            String entityFieldAccess = StrUtil.lowerFirst(simpleEntityName) + ".get" + StrUtil.upperFirst(fieldInfo.fieldName) + "()";

            if (isFirst) {
                methodBody.append("\n        .where(").append(fullFieldReference).append(".eq(").append(entityFieldAccess).append(", ObjectUtil.isNotNull(").append(entityFieldAccess).append(")))");
                isFirst = false;
            } else {
                methodBody.append("\n        .and(").append(fullFieldReference).append(".eq(").append(entityFieldAccess).append(", ObjectUtil.isNotNull(").append(entityFieldAccess).append(")))");
            }
        }

        methodBody.append(";\n");
        methodBody.append(generateReturnStatement(methodPrefix, "queryWrapper", simpleEntityName));

        return methodBody.toString();
    }

    /**
     * 从补全中解析字段
     */
    private List<String> parseFieldsFromCompletion(String completion) {
        List<String> fields = new ArrayList<>();
        String[] parts = completion.split("(?=And|Or)");

        for (String part : parts) {
            if (part.startsWith("And") || part.startsWith("Or")) {
                String fieldName = part.substring(part.startsWith("And") ? 3 : 2);
                if (StrUtil.isNotEmpty(fieldName)) {
                    fields.add(StrUtil.lowerFirst(fieldName));
                }
            } else {
                if (StrUtil.isNotEmpty(part)) {
                    fields.add(StrUtil.lowerFirst(part));
                }
            }
        }

        return fields;
    }

    /**
     * 从补全中解析连接符
     */
    private List<String> parseConnectorsFromCompletion(String completion) {
        List<String> connectors = new ArrayList<>();
        String[] parts = completion.split("(?=And|Or)");

        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.startsWith("And")) {
                connectors.add("and");
            } else if (part.startsWith("Or")) {
                connectors.add("or");
            }
        }

        return connectors;
    }

    /**
     * 生成返回语句（不区分大小写）
     */
    private String generateReturnStatement(String methodPrefix, String queryWrapper, String simpleEntityName) {
        String lowerMethodPrefix = methodPrefix.toLowerCase();
        switch (lowerMethodPrefix) {
            case "listby":
            case "findby":
                return "return list(" + queryWrapper + ");";
            case "getby":
                return "return getOne(" + queryWrapper + ");";
            case "countby":
                return "return count(" + queryWrapper + ");";
            case "existsby":
                return "return count(" + queryWrapper + ") > 0;";
            case "removeby":
            case "deleteby":
                return "return remove(" + queryWrapper + ");";
            default:
                return "return list(" + queryWrapper + ");";
        }
    }

    /**
     * 根据方法前缀获取返回类型（不区分大小写）
     */
    private String getReturnTypeForMethodPrefix(String methodPrefix, String simpleEntityName) {
        String lowerMethodPrefix = methodPrefix.toLowerCase();
        switch (lowerMethodPrefix) {
            case "listby":
            case "findby":
                return "List<" + simpleEntityName + ">";
            case "getby":
                return simpleEntityName;
            case "countby":
                return "long";
            case "existsby":
            case "removeby":
            case "deleteby":
                return "boolean";
            default:
                return "List<" + simpleEntityName + ">";
        }
    }

    /**
     * 插入方法实现
     */
    private void insertMethodImplementation(com.intellij.codeInsight.completion.InsertionContext context,
                                          String methodSignature, String methodBody, String returnType) {

        ApplicationManager.getApplication().runWriteAction(() -> {
            try {
                // 获取当前文档和编辑器
                com.intellij.openapi.editor.Document document = context.getDocument();
                com.intellij.openapi.editor.Editor editor = context.getEditor();

                // 获取当前光标位置，但不删除已输入的内容
                int startOffset = context.getStartOffset();

                // 找到当前方法或类的结束位置
                PsiFile psiFile = context.getFile();
                PsiElement elementAtCaret = psiFile.findElementAt(startOffset);

                // 找到合适的插入位置（当前方法后面或类的结束前）
                int insertOffset = findInsertPosition(elementAtCaret, document);

                // 构建完整的方法代码
                StringBuilder methodCode = new StringBuilder();
                methodCode.append("\n\n    ");
                methodCode.append("public ").append(returnType).append(" ").append(methodSignature).append(" {\n");
                methodCode.append("        ").append(methodBody).append("\n");
                methodCode.append("    }");

                // 插入完整的方法
                document.insertString(insertOffset, methodCode.toString());

                // 添加必要的导入
                addRequiredImports(context, methodBody);

                // 不移动光标，保持在原位置

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * 插入方法实现，光标聚焦到用户输入行的括号内
     */
    private void insertMethodImplementationWithCursor(com.intellij.codeInsight.completion.InsertionContext context,
                                                    String methodName, String methodBody, String returnType,
                                                    String fieldsCompletion, Map<String, FieldInfo> entityFields,
                                                    String entityQualifiedName, Map<String, String> fieldToTableDefMapping) {

        ApplicationManager.getApplication().runWriteAction(() -> {
            try {
                // 获取当前文档和编辑器
                com.intellij.openapi.editor.Document document = context.getDocument();
                com.intellij.openapi.editor.Editor editor = context.getEditor();

                // 获取当前光标位置
                int startOffset = context.getStartOffset();
                int tailOffset = context.getTailOffset();

                // 解析字段并生成参数列表
                StringBuilder paramBuilder = new StringBuilder();

                // 检查是否是整个实体方法（通过fieldsCompletion判断）
                if ("Entity".equals(fieldsCompletion)) {
                    // 整个实体方法，需要实体参数
                    String simpleEntityName = StrUtil.subAfter(entityQualifiedName, ".", true);
                    paramBuilder.append(simpleEntityName).append(" ").append(StrUtil.lowerFirst(simpleEntityName));
                } else {
                    // 普通字段方法，解析字段生成参数
                    List<String> fields = parseFieldsFromCompletion(fieldsCompletion);
                    boolean firstParam = true;
                    for (String field : fields) {
                        FieldInfo fieldInfo = entityFields.get(field);
                        // 只生成同时存在于实体类和APT中的字段参数
                        if (fieldInfo != null && fieldToTableDefMapping.containsKey(field)) {
                            if (!firstParam) paramBuilder.append(", ");
                            paramBuilder.append(fieldInfo.fieldType).append(" ").append(fieldInfo.fieldName);
                            firstParam = false;
                        }
                    }
                }

                // 找到合适的插入位置（在替换用户输入之前计算）
                PsiFile psiFile = context.getFile();
                PsiElement elementAtCaret = psiFile.findElementAt(startOffset);
                int insertOffset = findInsertPosition(elementAtCaret, document);

                // 替换用户输入的内容为方法名和空括号
                String userInputReplacement = methodName + "()";
                document.replaceString(startOffset, tailOffset, userInputReplacement);

                // 计算光标在括号内的位置（在替换后）
                int cursorPosition = startOffset + methodName.length() + 1; // +1 for opening parenthesis

                // 构建完整的方法代码（在其他位置插入）
                StringBuilder methodCode = new StringBuilder();
                methodCode.append("\n\n    ");
                methodCode.append("public ").append(returnType).append(" ").append(methodName).append("(");
                methodCode.append(paramBuilder.toString()).append(") {\n");

                // 直接使用方法体，保持原有缩进
                methodCode.append("        ").append(methodBody.replace("\n", "\n        ")).append("\n");

                methodCode.append("    }");

                // 插入完整的方法到合适位置
                document.insertString(insertOffset, methodCode.toString());

                // 添加必要的导入
                addRequiredImports(context, methodBody);

                // 将光标定位到用户输入行的括号内
                editor.getCaretModel().moveToOffset(cursorPosition);

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * 获取当前行的文本
     */
    private String getCurrentLineText(com.intellij.openapi.editor.Document document, int offset) {
        int lineNumber = document.getLineNumber(offset);
        int lineStartOffset = document.getLineStartOffset(lineNumber);
        int lineEndOffset = document.getLineEndOffset(lineNumber);
        return document.getText().substring(lineStartOffset, lineEndOffset);
    }

    /**
     * 获取当前行的开始偏移量
     */
    private int getLineStartOffset(com.intellij.openapi.editor.Document document, int offset) {
        int lineNumber = document.getLineNumber(offset);
        return document.getLineStartOffset(lineNumber);
    }

    /**
     * 找到合适的插入位置
     */
    private int findInsertPosition(PsiElement elementAtCaret, com.intellij.openapi.editor.Document document) {
        // 向上查找包含的方法或类
        PsiElement current = elementAtCaret;
        PsiMethod containingMethod = null;
        PsiClass containingClass = null;

        while (current != null) {
            if (current instanceof PsiMethod && containingMethod == null) {
                containingMethod = (PsiMethod) current;
            } else if (current instanceof PsiClass && containingClass == null) {
                containingClass = (PsiClass) current;
                break;
            }
            current = current.getParent();
        }

        if (containingClass != null) {
            // 如果在方法内，确保插入到当前方法外面
            if (containingMethod != null) {
                // 在当前方法后面插入
                return containingMethod.getTextRange().getEndOffset();
            } else {
                // 如果不在方法内，插入到类的最后一个方法后面
                PsiMethod[] methods = containingClass.getMethods();
                if (methods.length > 0) {
                    // 找到最后一个方法的结束位置
                    PsiMethod lastMethod = methods[methods.length - 1];
                    return lastMethod.getTextRange().getEndOffset();
                } else {
                    // 如果没有方法，插入到类的结束大括号前
                    String classText = containingClass.getText();
                    int classStart = containingClass.getTextRange().getStartOffset();
                    int lastBraceIndex = classText.lastIndexOf('}');
                    if (lastBraceIndex > 0) {
                        return classStart + lastBraceIndex;
                    }
                    return containingClass.getTextRange().getEndOffset() - 1;
                }
            }
        }

        // 如果找不到合适位置，使用当前位置
        return elementAtCaret.getTextRange().getEndOffset();
    }

    /**
     * 添加必要的导入
     */
    private void addRequiredImports(com.intellij.codeInsight.completion.InsertionContext context, String methodBody) {
        try {
            PsiFile psiFile = context.getFile();
            if (!(psiFile instanceof PsiJavaFile)) {
                return;
            }

            PsiJavaFile javaFile = (PsiJavaFile) psiFile;
            Project project = context.getProject();

            // 需要导入的类
            Set<String> requiredImports = new HashSet<>();
            Set<String> requiredStaticImports = new HashSet<>();

            // 检查方法体中使用的类
            if (methodBody.contains("QueryWrapper")) {
                requiredImports.add("com.mybatisflex.core.query.QueryWrapper");
            }

            // 检查是否使用了ObjectUtil
            if (methodBody.contains("ObjectUtil")) {
                requiredImports.add("cn.hutool.core.util.ObjectUtil");
            }

            // 查找TableDef类的静态导入
            String tableDefStaticImport = findTableDefStaticImport(context, methodBody);
            if (StrUtil.isNotEmpty(tableDefStaticImport)) {
                requiredStaticImports.add(tableDefStaticImport);
            }

            // 添加普通导入
            for (String importClass : requiredImports) {
                addImportIfNotExists(javaFile, importClass, project);
            }

            // 添加静态导入
            for (String staticImportClass : requiredStaticImports) {
                addStaticImportIfNotExists(javaFile, staticImportClass, project);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 查找TableDef类的静态导入
     */
    private String findTableDefStaticImport(com.intellij.codeInsight.completion.InsertionContext context, String methodBody) {
        try {
            // 从方法体中提取TableDef实例名
            String[] parts = methodBody.split("\\.");
            for (String part : parts) {
                if (part.matches("^[A-Z][A-Z0-9_]*$") && !part.equals("QueryWrapper") && !part.contains("(")) {
                    // 这可能是TableDef实例名，尝试找到对应的TableDef类
                    PsiElement position = context.getFile().findElementAt(context.getStartOffset());
                    PsiClass serviceClass = getCurrentServiceClassFromElement(position);
                    if (serviceClass != null) {
                        String entityQualifiedName = getEntityQualifiedNameFromServiceClass(serviceClass, context.getProject());
                        if (StrUtil.isNotEmpty(entityQualifiedName)) {
                            String tableDefClass = TableDefUtils.getTableDefQualifiedName(entityQualifiedName, context.getFile().getVirtualFile());
                            if (StrUtil.isNotEmpty(tableDefClass)) {
                                // 返回静态导入格式：com.example.table.UserTableDef.USER
                                return tableDefClass + "." + part;
                            }
                        }
                    }
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 从元素获取当前Service类
     */
    private PsiClass getCurrentServiceClassFromElement(PsiElement element) {
        PsiElement current = element;
        while (current != null) {
            if (current instanceof PsiClass) {
                return (PsiClass) current;
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * 从Service类获取实体类全限定名
     */
    private String getEntityQualifiedNameFromServiceClass(PsiClass serviceClass, Project project) {
        try {
            // 检查是否实现了IService接口
            String entityFromInterface = getEntityClassFromInterface(serviceClass, project);
            if (StrUtil.isNotEmpty(entityFromInterface)) {
                return entityFromInterface;
            }

            // 检查是否继承了ServiceImpl类
            String entityFromImpl = getEntityClassFromImpl(serviceClass, project);
            if (StrUtil.isNotEmpty(entityFromImpl)) {
                return entityFromImpl;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 添加导入（如果不存在）
     */
    private void addImportIfNotExists(PsiJavaFile javaFile, String qualifiedName, Project project) {
        try {
            // 检查是否已经导入
            PsiImportList importList = javaFile.getImportList();
            if (importList != null) {
                PsiImportStatement[] importStatements = importList.getImportStatements();
                for (PsiImportStatement importStatement : importStatements) {
                    if (qualifiedName.equals(importStatement.getQualifiedName())) {
                        return; // 已经导入
                    }
                }
            }

            // 添加导入
            JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
            PsiElementFactory factory = psiFacade.getElementFactory();

            // 尝试查找类并创建导入语句
            PsiClass psiClass = psiFacade.findClass(qualifiedName, GlobalSearchScope.allScope(project));
            if (psiClass != null) {
                PsiImportStatement importStatement = factory.createImportStatement(psiClass);
                if (importList != null && importStatement != null) {
                    importList.add(importStatement);
                }
            } else {
                // 对于特定的常用类，强制添加导入
                if ("cn.hutool.core.util.ObjectUtil".equals(qualifiedName)) {
                    try {
                        // 使用最简单的方法：直接在文档中添加导入文本
                        addImportDirectly(javaFile, qualifiedName);
                    } catch (Exception e) {
                        System.out.println("Failed to add ObjectUtil import: " + e.getMessage());
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 直接添加导入语句到文件中
     */
    private void addImportDirectly(PsiJavaFile javaFile, String qualifiedName) {
        try {
            PsiImportList importList = javaFile.getImportList();
            if (importList != null) {
                // 获取文档
                com.intellij.openapi.editor.Document document = com.intellij.psi.PsiDocumentManager.getInstance(javaFile.getProject()).getDocument(javaFile);
                if (document != null) {
                    // 找到package语句的结束位置
                    PsiPackageStatement packageStatement = javaFile.getPackageStatement();
                    int insertOffset;
                    if (packageStatement != null) {
                        insertOffset = packageStatement.getTextRange().getEndOffset();
                        // 在package语句后添加导入
                        String importText = "\nimport " + qualifiedName + ";";
                        document.insertString(insertOffset, importText);
                    } else {
                        // 如果没有package语句，在文件开头添加
                        String importText = "import " + qualifiedName + ";\n";
                        document.insertString(0, importText);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 添加静态导入（如果不存在）
     */
    private void addStaticImportIfNotExists(PsiJavaFile javaFile, String qualifiedName, Project project) {
        try {
            // 检查是否已经有静态导入
            PsiImportList importList = javaFile.getImportList();
            if (importList != null) {
                PsiImportStaticStatement[] staticImportStatements = importList.getImportStaticStatements();
                for (PsiImportStaticStatement staticImportStatement : staticImportStatements) {
                    if (qualifiedName.equals(staticImportStatement.getImportReference().getQualifiedName())) {
                        return; // 已经导入
                    }
                }
            }

            // 添加静态导入
            JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
            PsiElementFactory factory = psiFacade.getElementFactory();

            // 解析类名和字段名
            int lastDotIndex = qualifiedName.lastIndexOf('.');
            String className = qualifiedName.substring(0, lastDotIndex);
            String fieldName = qualifiedName.substring(lastDotIndex + 1);

            // 查找类
            PsiClass psiClass = psiFacade.findClass(className, GlobalSearchScope.allScope(project));
            if (psiClass != null) {
                // 创建静态导入语句
                PsiImportStaticStatement staticImportStatement = factory.createImportStaticStatement(psiClass, fieldName);

                if (importList != null && staticImportStatement != null) {
                    importList.add(staticImportStatement);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 获取字段到TableDef字段的映射
     */
    private Map<String, String> getFieldToTableDefMapping(String entityQualifiedName, String aptClass, Project project) {
        Map<String, String> mapping = new HashMap<>();

        try {
            JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
            GlobalSearchScope scope = GlobalSearchScope.allScope(project);

            // 查找实体类
            PsiClass entityClass = findEntityClass(entityQualifiedName, psiFacade, scope);
            // 查找TableDef类
            PsiClass tableDefClass = findTableDefClass(aptClass, psiFacade, scope);

            if (entityClass != null && tableDefClass != null) {
                // 获取实体类字段的@Column注解值
                Map<String, String> entityFieldToColumn = getEntityFieldToColumnMap(entityClass);
                // 获取TableDef类的字段
                Map<String, String> tableDefFields = getTableDefFieldsMap(tableDefClass);

                // 建立实体字段到TableDef字段的映射
                for (Map.Entry<String, String> entityEntry : entityFieldToColumn.entrySet()) {
                    String entityFieldName = entityEntry.getKey();
                    String columnValue = entityEntry.getValue();

                    // 在TableDef中查找对应的字段
                    String tableDefFieldName = findMatchingTableDefField(entityFieldName, tableDefFields);
                    if (tableDefFieldName != null) {
                        mapping.put(entityFieldName, tableDefFieldName);
                    }
                    // 如果在TableDef中找不到对应字段，则不添加到映射中，这样就不会生成查询条件
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return mapping;
    }

    /**
     * 添加实体字段补全项 - 显示实体类的所有字段
     */
    private void addEntityFieldCompletionItems(CompletionResultSet result, String entityQualifiedName,
                                             Project project, String aptClass, VirtualFile virtualFile) {
        CompletionResultSet completionResultSet = result.caseInsensitive();
        String prefix = completionResultSet.getPrefixMatcher().getPrefix();

        try {
            JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
            GlobalSearchScope scope = GlobalSearchScope.allScope(project);

            // 查找实体类
            PsiClass entityClass = findEntityClass(entityQualifiedName, psiFacade, scope);
            if (entityClass != null) {
                PsiField[] fields = entityClass.getAllFields();

                for (PsiField field : fields) {
                    // 跳过静态字段和常量
                    if (field.hasModifierProperty(PsiModifier.STATIC) ||
                        field.hasModifierProperty(PsiModifier.FINAL)) {
                        continue;
                    }

                    String fieldName = field.getName();
                    String columnName = getColumnAnnotationValue(field);
                    if (StrUtil.isEmpty(columnName)) {
                        columnName = toUnderscoreCase(fieldName);
                    }

                    // 获取字段类型
                    String fieldType = field.getType().getPresentableText();

                    // 创建字段补全项
                    LookupElement fieldElement = LookupElementBuilder.create(fieldName)
                            .withPresentableText(fieldName)
                            .withTypeText(fieldType)
                            .withTailText(" -> " + columnName, true)
                            .withIcon(Icons.FLEX);

                    completionResultSet.addElement(fieldElement);
                }
            }

            // 如果有对应的TableDef，也添加TableDef字段
            if (StrUtil.isNotEmpty(aptClass)) {
                addTableDefFieldCompletionItems(completionResultSet, aptClass, project);
            } else {
                // 如果没有找到aptClass，尝试通过实体类名构建TableDef类名
                String simpleEntityName = StrUtil.subAfter(entityQualifiedName, ".", true);
                String possibleTableDefClass = simpleEntityName + "TableDef";
                addTableDefFieldCompletionItems(completionResultSet, possibleTableDefClass, project);

                // 也尝试其他可能的命名方式
                String[] possibleNames = {
                    simpleEntityName + "TableDef",
                    simpleEntityName.toUpperCase() + "_TABLE_DEF",
                    "TABLE_" + simpleEntityName.toUpperCase()
                };

                for (String possibleName : possibleNames) {
                    addTableDefFieldsFromUtils(completionResultSet, possibleName, project);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 添加TableDef字段补全项
     */
    private void addTableDefFieldCompletionItems(CompletionResultSet result, String aptClass, Project project) {
        if (StrUtil.isEmpty(aptClass)) {
            return;
        }

        try {
            JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
            GlobalSearchScope scope = GlobalSearchScope.allScope(project);

            PsiClass tableDefClass = findTableDefClass(aptClass, psiFacade, scope);
            if (tableDefClass != null) {
                PsiField[] fields = tableDefClass.getAllFields();

                for (PsiField field : fields) {
                    if (isTableDefField(field)) {
                        String fieldName = field.getName();
                        String columnName = extractColumnNameFromField(field);

                        // 创建TableDef字段补全项
                        LookupElement fieldElement = LookupElementBuilder.create(fieldName)
                                .withPresentableText(fieldName)
                                .withTypeText("QueryColumn")
                                .withTailText(" (TableDef: " + columnName + ")", true)
                                .withIcon(Icons.FLEX);

                        result.addElement(fieldElement);
                    }
                }
            } else {
                // 如果找不到TableDef类，尝试通过TableDefUtils获取
                addTableDefFieldsFromUtils(result, aptClass, project);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 通过TableDefUtils获取TableDef字段
     */
    private void addTableDefFieldsFromUtils(CompletionResultSet result, String aptClass, Project project) {
        try {
            // 使用TableDefUtils中的映射信息
            Map<String, String> tableDefMappingMap = TableDefUtils.getTableDefMappingMap();

            for (Map.Entry<String, String> entry : tableDefMappingMap.entrySet()) {
                String tableName = entry.getKey();
                String tableDefClassName = entry.getValue();

                if (tableDefClassName.contains(aptClass) || aptClass.contains(tableDefClassName)) {
                    // 创建TableDef实例补全项
                    LookupElement tableDefElement = LookupElementBuilder.create(tableName)
                            .withPresentableText(tableName)
                            .withTypeText("TableDef")
                            .withTailText(" (APT generated)", true)
                            .withIcon(Icons.FLEX);

                    result.addElement(tableDefElement);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 添加基于实体属性的方法补全（listBy, getBy等）
     */
    private void addEntityBasedMethodCompletionItems(CompletionResultSet result, String entityQualifiedName,
                                                   Project project, VirtualFile virtualFile) {
        CompletionResultSet completionResultSet = result.caseInsensitive();

        try {
            JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
            GlobalSearchScope scope = GlobalSearchScope.allScope(project);

            // 查找实体类
            PsiClass entityClass = findEntityClass(entityQualifiedName, psiFacade, scope);
            if (entityClass != null) {
                PsiField[] fields = entityClass.getAllFields();
                String simpleEntityClassName = StrUtil.subAfter(entityQualifiedName, ".", true);

                for (PsiField field : fields) {
                    // 跳过静态字段和常量
                    if (field.hasModifierProperty(PsiModifier.STATIC) ||
                        field.hasModifierProperty(PsiModifier.FINAL)) {
                        continue;
                    }

                    String fieldName = field.getName();
                    String capitalizedFieldName = StrUtil.upperFirst(fieldName);
                    String fieldType = field.getType().getPresentableText();

                    // 生成基于字段的方法
                    String[] methodPrefixes = {"listBy", "getBy", "findBy", "countBy", "existsBy", "removeBy"};

                    for (String prefix : methodPrefixes) {
                        String methodName = prefix + capitalizedFieldName;
                        String returnType = getReturnTypeForMethod(prefix, simpleEntityClassName);
                        String methodSignature = methodName + "(" + fieldType + " " + fieldName + ")";

                        LookupElement methodElement = LookupElementBuilder.create(methodSignature)
                                .withPresentableText(methodSignature)
                                .withTypeText(returnType)
                                .withTailText(" (based on " + fieldName + ")", true)
                                .withIcon(Icons.FLEX)
                                .withInsertHandler((context, item) -> {
                                    // 可以在这里添加插入后的处理逻辑，比如自动生成方法体
                                });

                        completionResultSet.addElement(methodElement);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 根据方法前缀获取返回类型
     */
    private String getReturnTypeForMethod(String prefix, String entityClassName) {
        switch (prefix) {
            case "listBy":
            case "findBy":
                return "List<" + entityClassName + ">";
            case "getBy":
                return entityClassName;
            case "countBy":
                return "long";
            case "existsBy":
                return "boolean";
            case "removeBy":
                return "boolean";
            default:
                return "Object";
        }
    }

    /**
     * 添加APT字段补全项
     */
    private void addAptFieldCompletionItems(CompletionResultSet result, String entityQualifiedName,
                                          Project project, String aptClass, VirtualFile virtualFile) {
        CompletionResultSet completionResultSet = result.caseInsensitive();
        String prefix = completionResultSet.getPrefixMatcher().getPrefix();

        if (StrUtil.isEmpty(prefix)) {
            return;
        }

        try {
            // 获取APT字段信息
            List<AptInfo> aptFields = getAptFieldsFromTableDef(entityQualifiedName, aptClass, project);

            for (AptInfo aptField : aptFields) {
                // 创建字段补全项
                LookupElement fieldElement = LookupElementBuilder.create(aptField.getName())
                        .withPresentableText(aptField.getName())
                        .withTypeText(aptField.getColumnName())
                        .withTailText(" (" + aptField.getColumnName() + ")", true)
                        .withIcon(Icons.FLEX)
                        .withInsertHandler((context, item) -> {
                            // 可以在这里添加插入后的处理逻辑
                        });

                completionResultSet.addElement(fieldElement);

                // 如果字段较大，还可以添加相关的查询方法
                if (aptField.isLarge()) {
                    addQueryMethodsForField(completionResultSet, aptField);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 为特定字段添加查询方法补全
     */
    private void addQueryMethodsForField(CompletionResultSet result, AptInfo aptField) {
        String fieldName = aptField.getName();
        String columnName = aptField.getColumnName();

        // 添加常用的查询方法
        String[] queryMethods = {
            fieldName + ".eq(value)",
            fieldName + ".ne(value)",
            fieldName + ".in(values)",
            fieldName + ".notIn(values)",
            fieldName + ".like(value)",
            fieldName + ".notLike(value)",
            fieldName + ".isNull()",
            fieldName + ".isNotNull()"
        };

        for (String method : queryMethods) {
            LookupElement methodElement = LookupElementBuilder.create(method)
                    .withPresentableText(method)
                    .withTypeText("QueryCondition")
                    .withTailText(" (for " + columnName + ")", true)
                    .withIcon(Icons.FLEX);

            result.addElement(methodElement);
        }
    }

    /**
     * 从TableDef类获取APT字段信息
     */
    private List<AptInfo> getAptFieldsFromTableDef(String entityQualifiedName, String aptClass, Project project) {
        List<AptInfo> aptFields = new ArrayList<>();

        try {
            JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
            GlobalSearchScope scope = GlobalSearchScope.allScope(project);

            // 查找TableDef类
            PsiClass tableDefClass = findTableDefClass(aptClass, psiFacade, scope);
            if (tableDefClass != null) {
                PsiField[] fields = tableDefClass.getAllFields();
                for (PsiField field : fields) {
                    if (isTableDefField(field)) {
                        String fieldName = field.getName();
                        String columnName = extractColumnNameFromField(field);
                        boolean isLarge = isLargeField(field);

                        aptFields.add(new AptInfo(columnName, fieldName, isLarge));
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return aptFields;
    }

    /**
     * 判断是否是TableDef的字段（排除静态常量等）
     */
    private boolean isTableDefField(PsiField field) {
        // 检查字段修饰符
        if (!field.hasModifierProperty(PsiModifier.PUBLIC) ||
            !field.hasModifierProperty(PsiModifier.FINAL)) {
            return false;
        }

        // 检查字段类型是否是QueryColumn
        PsiType fieldType = field.getType();
        if (fieldType != null) {
            String typeName = fieldType.getCanonicalText();
            if (typeName.contains("QueryColumn")) {
                return true;
            }
        }

        // 检查字段名是否符合TableDef字段命名规范
        String fieldName = field.getName();
        if (fieldName != null) {
            // 排除特殊字段
            if (fieldName.equals("$allColumns") || fieldName.equals("$defaultColumns") ||
                fieldName.startsWith("$") || fieldName.toLowerCase().contains("instance")) {
                return false;
            }
            // TableDef字段通常是大写字母和下划线
            return fieldName.matches("^[A-Z][A-Z0-9_]*$");
        }

        return false;
    }

    /**
     * 从字段初始化器中提取列名
     */
    private String extractColumnNameFromField(PsiField field) {
        PsiExpression initializer = field.getInitializer();
        if (initializer != null) {
            String initText = initializer.getText();
            // 尝试从初始化器中提取列名
            // 格式通常是：new QueryColumn(this, "column_name")
            if (initText.contains("\"")) {
                // 查找最后一个引号对，因为列名通常是最后一个参数
                int lastQuoteStart = initText.lastIndexOf("\"", initText.lastIndexOf("\"") - 1);
                int lastQuoteEnd = initText.lastIndexOf("\"");
                if (lastQuoteStart >= 0 && lastQuoteEnd > lastQuoteStart) {
                    return initText.substring(lastQuoteStart + 1, lastQuoteEnd);
                }

                // 如果上面的方法失败，尝试第一个引号对
                int firstQuoteStart = initText.indexOf("\"");
                int firstQuoteEnd = initText.indexOf("\"", firstQuoteStart + 1);
                if (firstQuoteStart >= 0 && firstQuoteEnd > firstQuoteStart) {
                    return initText.substring(firstQuoteStart + 1, firstQuoteEnd);
                }
            }
        }
        // 如果无法提取，使用字段名的下划线形式
        return toUnderscoreCase(field.getName());
    }

    /**
     * 判断字段是否为大字段（如TEXT、BLOB等）
     */
    private boolean isLargeField(PsiField field) {
        // 可以通过字段名或注解来判断
        String fieldName = field.getName().toLowerCase();
        return fieldName.contains("content") || fieldName.contains("text") ||
               fieldName.contains("blob") || fieldName.contains("data");
    }

    /**
     * 将驼峰命名转换为下划线命名
     */
    private String toUnderscoreCase(String camelCase) {
        if (StrUtil.isEmpty(camelCase)) {
            return camelCase;
        }

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                result.append('_');
            }
            result.append(Character.toLowerCase(c));
        }
        return result.toString();
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
     * 添加代码补全项 - 增强版，包含APT相关补全
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
        String simpleEntityClassName = StrUtil.subAfter(entityClassName, ".", true);

        // 根据实体类名添加基本的CRUD方法补全
        List<String> methods = new ArrayList<>();
        methods.add("save(" + simpleEntityClassName + " entity)");
        methods.add("saveOrUpdate(" + simpleEntityClassName + " entity)");
        methods.add("removeById(Serializable id)");
        methods.add("getById(Serializable id)");
        methods.add("list()");
        methods.add("page(Page<" + simpleEntityClassName + "> page)");

        // 添加APT相关的查询方法
        methods.add("list(QueryWrapper queryWrapper)");
        methods.add("listAs(QueryWrapper queryWrapper, Class<R> asType)");
        methods.add("count(QueryWrapper queryWrapper)");
        methods.add("getOne(QueryWrapper queryWrapper)");
        methods.add("exists(QueryWrapper queryWrapper)");

        for (String method : methods) {
            LookupElement lookupElement = LookupElementBuilder.create(method)
                    .withPresentableText(method)
                    .withTypeText(simpleEntityClassName)
                    .withIcon(Icons.FLEX);

            completionResultSet.addElement(lookupElement);
        }

        // 添加TableDef实例的补全
        if (StrUtil.isNotEmpty(aptClass)) {
            String tableDefInstanceName = getTableDefInstanceName(simpleEntityClassName);
            LookupElement tableDefElement = LookupElementBuilder.create(tableDefInstanceName)
                    .withPresentableText(tableDefInstanceName)
                    .withTypeText("TableDef")
                    .withTailText(" (APT generated)", true)
                    .withIcon(Icons.FLEX);

            completionResultSet.addElement(tableDefElement);
        }
    }

    /**
     * 获取TableDef实例名称
     */
    private String getTableDefInstanceName(String entityClassName) {
        // 根据MyBatis-Flex的命名规范，通常是实体类名的大写形式
        // 例如：ActivitiModel -> ACTIVITI_MODEL
        return toUpperUnderscoreCase(entityClassName);
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