package club.bigtian.mf.plugin.core.contributor;

import club.bigtian.mf.plugin.core.icons.Icons;
import club.bigtian.mf.plugin.core.util.ProjectUtils;
import club.bigtian.mf.plugin.core.util.TableDefUtils;
import club.bigtian.mf.plugin.core.util.VirtualFileUtils;
import club.bigtian.mf.plugin.entity.AptInfo;
import cn.hutool.core.util.StrUtil;
import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Service层代码补全 - 增强APT字段补全功能
 * Author: BigTian
 */
public class ServiceCompletionContributor extends CompletionContributor {

    private static final String ISERVICE_CLASS = "com.mybatisflex.core.service.IService";

    public ServiceCompletionContributor() {
        // 注册在Java文件中的补全模式
        extend(CompletionType.BASIC,
               PlatformPatterns.psiElement().inFile(PlatformPatterns.psiFile(PsiJavaFile.class)),
                new CompletionProvider<>() {
                    @Override
                    protected void addCompletions(@NotNull CompletionParameters parameters,
                                                  @NotNull ProcessingContext context,
                                                  @NotNull CompletionResultSet result) {
                        fillCompletionVariants(parameters, result);
                    }
                });
    }

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

            // 检查是否是方法调用补全（在其他类中调用Service方法）
            if (isServiceMethodCallCompletion(position, parameters)) {
                handleServiceMethodCallCompletion(position, parameters, result, project);
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
     * 检查是否是Service方法调用补全
     */
    private boolean isServiceMethodCallCompletion(PsiElement position, CompletionParameters parameters) {
        // 检查是否在方法调用表达式中（已完成的调用）
        PsiElement parent = position.getParent();
        while (parent != null) {
            if (parent instanceof PsiMethodCallExpression) {
                PsiMethodCallExpression methodCall = (PsiMethodCallExpression) parent;
                PsiExpression qualifierExpression = methodCall.getMethodExpression().getQualifierExpression();
                if (qualifierExpression != null) {
                    PsiType type = qualifierExpression.getType();
                    if (type != null) {
                        String typeName = type.getCanonicalText();
                        // 检查是否是Service接口调用
                        return isServiceType(typeName, position.getProject());
                    }
                }
            }
            parent = parent.getParent();
        }
        // 检查是否在正在输入的方法调用中（如 service.listBy）
        return isInProgressServiceMethodCall(position, parameters);
    }

    /**
     * 检查是否在正在输入的Service方法调用中
     */
    private boolean isInProgressServiceMethodCall(PsiElement position, CompletionParameters parameters) {
        try {
            // 检查是否在引用表达式中（如 service.methodName）
            PsiElement parent = position.getParent();
            if (parent instanceof PsiReferenceExpression) {
                PsiReferenceExpression refExpr = (PsiReferenceExpression) parent;
                PsiExpression qualifierExpression = refExpr.getQualifierExpression();
                if (qualifierExpression != null) {
                    PsiType type = qualifierExpression.getType();
                    if (type != null) {
                        String typeName = type.getCanonicalText();
                        // 检查是否是Service接口调用
                        return isServiceType(typeName, position.getProject());
                    }
                }
            }

            // 检查父级是否是引用表达式
            if (parent != null && parent.getParent() instanceof PsiReferenceExpression) {
                PsiReferenceExpression refExpr = (PsiReferenceExpression) parent.getParent();
                PsiExpression qualifierExpression = refExpr.getQualifierExpression();
                if (qualifierExpression != null) {
                    PsiType type = qualifierExpression.getType();
                    if (type != null) {
                        String typeName = type.getCanonicalText();
                        // 检查是否是Service接口调用
                        return isServiceType(typeName, position.getProject());
                    }
                }
            }

            // 额外检查：通过文本分析来检测Service调用
            String text = parameters.getEditor().getDocument().getText();
            int offset = parameters.getOffset();
            if (offset > 10) {
                String beforeCursor = text.substring(Math.max(0, offset - 50), offset);
                // 检查是否包含Service调用模式
                if (beforeCursor.matches(".*\\w+Service\\s*\\.\\s*\\w*$")) {
                    return true;
                }
            }

        } catch (Exception e) {
            // 忽略异常
        }
        return false;
    }

    /**
     * 检查是否是Service类型
     */
    private boolean isServiceType(String typeName, Project project) {
        try {
            // 首先通过命名约定快速检查
            if (typeName != null && typeName.contains("Service")) {
                JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
                PsiClass psiClass = psiFacade.findClass(typeName, GlobalSearchScope.allScope(project));
                if (psiClass != null && psiClass.isInterface()) {
                    // 检查是否继承自IService
                    PsiClassType[] extendsTypes = psiClass.getExtendsListTypes();
                    for (PsiClassType extendsType : extendsTypes) {
                        String extendsTypeName = extendsType.getCanonicalText();
                        if (extendsTypeName != null && extendsTypeName.contains("IService")) {
                            return true;
                        }
                    }

                    // 如果没有找到IService继承，但类名包含Service，也认为是Service类型
                    return true;
                }
            }
        } catch (Exception e) {
            // 忽略异常
        }
        return false;
    }

    /**
     * 处理Service方法调用补全
     */
    private void handleServiceMethodCallCompletion(PsiElement position, CompletionParameters parameters,
                                                 CompletionResultSet result, Project project) {
        try {
            // 尝试获取Service接口和实体类型
            ServiceCallInfo serviceCallInfo = getServiceCallInfo(position, project);
            if (serviceCallInfo == null) {
                return;
            }

            // 获取输入前缀
            String prefix = result.getPrefixMatcher().getPrefix();
            if (isMethodNameCompletion(prefix)) {
                // 生成Service方法补全，包括自动生成方法的选项
                addServiceMethodCallCompletion(result, serviceCallInfo.serviceInterface, serviceCallInfo.entityQualifiedName, project, prefix, parameters);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 获取Service调用信息
     */
    private ServiceCallInfo getServiceCallInfo(PsiElement position, Project project) {
        try {
            // 首先尝试从方法调用表达式获取
            PsiElement parent = position.getParent();
            while (parent != null && !(parent instanceof PsiMethodCallExpression)) {
                parent = parent.getParent();
            }

            if (parent instanceof PsiMethodCallExpression) {
                PsiMethodCallExpression methodCall = (PsiMethodCallExpression) parent;
                PsiExpression qualifierExpression = methodCall.getMethodExpression().getQualifierExpression();
                if (qualifierExpression != null) {
                    PsiType qualifierType = qualifierExpression.getType();
                    if (qualifierType != null) {
                        return createServiceCallInfo(qualifierType.getCanonicalText(), project);
                    }
                }
            }

            // 如果没有找到方法调用表达式，尝试从引用表达式获取
            parent = position.getParent();
            if (parent instanceof PsiReferenceExpression) {
                PsiReferenceExpression refExpr = (PsiReferenceExpression) parent;
                PsiExpression qualifierExpression = refExpr.getQualifierExpression();
                if (qualifierExpression != null) {
                    PsiType qualifierType = qualifierExpression.getType();
                    if (qualifierType != null) {
                        return createServiceCallInfo(qualifierType.getCanonicalText(), project);
                    }
                }
            }

            // 检查父级是否是引用表达式
            if (parent != null && parent.getParent() instanceof PsiReferenceExpression) {
                PsiReferenceExpression refExpr = (PsiReferenceExpression) parent.getParent();
                PsiExpression qualifierExpression = refExpr.getQualifierExpression();
                if (qualifierExpression != null) {
                    PsiType qualifierType = qualifierExpression.getType();
                    if (qualifierType != null) {
                        return createServiceCallInfo(qualifierType.getCanonicalText(), project);
                    }
                }
            }

        } catch (Exception e) {
            // 忽略异常
        }
        return null;
    }

    /**
     * 创建Service调用信息
     */
    private ServiceCallInfo createServiceCallInfo(String typeName, Project project) {
        try {
            JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
            PsiClass serviceInterface = psiFacade.findClass(typeName, GlobalSearchScope.allScope(project));
            if (serviceInterface == null || !serviceInterface.isInterface()) {
                return null;
            }

            // 获取实体类型
            String entityQualifiedName = getEntityClassFromInterface(serviceInterface, project);
            if (entityQualifiedName == null) {
                return null;
            }

            return new ServiceCallInfo(serviceInterface, entityQualifiedName);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Service调用信息
     */
    private static class ServiceCallInfo {
        final PsiClass serviceInterface;
        final String entityQualifiedName;

        ServiceCallInfo(PsiClass serviceInterface, String entityQualifiedName) {
            this.serviceInterface = serviceInterface;
            this.entityQualifiedName = entityQualifiedName;
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
                addMethodPrefixCompletions(result, prefix, entityFields, fieldToTableDefMapping, entityQualifiedName, currentServiceClass, entityClass);
                return;
            }

            String fieldsPart = prefix.substring(methodPrefix.length());

            // 生成可能的方法补全
            generateMethodCompletions(result, methodPrefix, fieldsPart, entityFields, fieldToTableDefMapping, entityQualifiedName, currentServiceClass, project);

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
                                          String entityQualifiedName, PsiClass currentServiceClass, PsiClass entityClass) {

        String simpleEntityName = StrUtil.subAfter(entityQualifiedName, ".", true);
        String[] queryPrefixes = {"listBy", "findBy", "getBy", "countBy", "existsBy", "removeBy", "deleteBy"};
        String lowerPrefix = prefix.toLowerCase();

        System.out.println("addMethodPrefixCompletions called with:");
        System.out.println("  prefix: " + prefix);
        System.out.println("  lowerPrefix: " + lowerPrefix);
        System.out.println("  simpleEntityName: " + simpleEntityName);
        System.out.println("  currentServiceClass: " + (currentServiceClass != null ? currentServiceClass.getName() : "null"));
        System.out.println("  isInterface: " + (currentServiceClass != null && currentServiceClass.isInterface()));

        for (String queryPrefix : queryPrefixes) {
            System.out.println("  Checking queryPrefix: " + queryPrefix + ", startsWith: " + queryPrefix.toLowerCase().startsWith(lowerPrefix));
            if (queryPrefix.toLowerCase().startsWith(lowerPrefix)) {

                // 添加整个实体的方法补全（如listBy + 实体名）
                if (queryPrefix.equals("listBy") || queryPrefix.equals("findBy")) {
                    System.out.println("  Calling generateEntityMethods for: " + queryPrefix);
                    // 生成两个版本：eq查询和like查询
                    generateEntityMethods(result, queryPrefix, simpleEntityName, entityFields, fieldToTableDefMapping,
                                        entityQualifiedName, entityClass, currentServiceClass);
                }

                // 为每个字段生成方法补全
                for (FieldInfo fieldInfo : entityFields.values()) {
                    // 只处理在APT TableDef中存在的字段
                    if (!fieldToTableDefMapping.containsKey(fieldInfo.fieldName)) {
                        continue; // 跳过APT中不存在的字段
                    }

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
     * 字段查询信息类
     */
    private static class FieldQueryInfo {
        String fieldName;      // 字段名
        String queryType;      // 查询类型：eq, like, gt, lt 等

        FieldQueryInfo(String fieldName, String queryType) {
            this.fieldName = fieldName;
            this.queryType = queryType;
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
            if (lowerPrefix.startsWith(queryPrefix) && prefix.length() >= queryPrefix.length()) {
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
                                         String entityQualifiedName, PsiClass currentServiceClass, Project project) {

        String simpleEntityName = StrUtil.subAfter(entityQualifiedName, ".", true);

        System.out.println("generateMethodCompletions called with:");
        System.out.println("  methodPrefix: " + methodPrefix);
        System.out.println("  fieldsPart: " + fieldsPart);
        System.out.println("  simpleEntityName: " + simpleEntityName);
        System.out.println("  entityQualifiedName: " + entityQualifiedName);

        // 如果字段部分为空，提供所有可能的字段组合
        if (StrUtil.isEmpty(fieldsPart)) {
            System.out.println("  fieldsPart is empty, calling generateSingleFieldMethods");
            generateSingleFieldMethods(result, methodPrefix, entityFields, fieldToTableDefMapping, simpleEntityName, currentServiceClass, entityQualifiedName);
            return;
        }

        // 检查是否是实体名（如ActivitiModel或ActivitiModelLike）
        if (isEntityNamePattern(fieldsPart, simpleEntityName)) {
            System.out.println("  Detected entity name pattern, calling generateEntityMethodsForCompletion");
            generateEntityMethodsForCompletion(result, methodPrefix, fieldsPart, simpleEntityName, entityFields, fieldToTableDefMapping, entityQualifiedName, currentServiceClass);
            return;
        }

        // 解析已输入的字段部分
        List<String> possibleCompletions = parseFieldsAndGenerateCompletions(fieldsPart, entityFields, fieldToTableDefMapping, project);

        for (String completion : possibleCompletions) {
            String fullMethodName = methodPrefix + completion;

            // 检查方法是否已存在
            if (currentServiceClass != null && methodExists(currentServiceClass, fullMethodName)) {
                continue; // 跳过已存在的方法
            }

            // 生成空的方法签名，不包含参数
            String methodSignature = fullMethodName + "()";
            String methodBody = generateMethodBody(methodPrefix, completion, fieldToTableDefMapping, simpleEntityName, project);
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
     * 生成实体方法（eq和like两个版本）
     */
    private void generateEntityMethods(CompletionResultSet result, String queryPrefix, String simpleEntityName,
                                     Map<String, FieldInfo> entityFields, Map<String, String> fieldToTableDefMapping,
                                     String entityQualifiedName, PsiClass entityClass, PsiClass currentServiceClass) {

        System.out.println("generateEntityMethods called with:");
        System.out.println("  queryPrefix: " + queryPrefix);
        System.out.println("  simpleEntityName: " + simpleEntityName);
        System.out.println("  currentServiceClass: " + (currentServiceClass != null ? currentServiceClass.getName() : "null"));

        // 判断当前是否在接口中
        boolean isInterface = currentServiceClass != null && currentServiceClass.isInterface();
        System.out.println("  isInterface: " + isInterface);

        // 生成eq版本的实体方法
        String entityMethodName = queryPrefix + simpleEntityName;
        System.out.println("  entityMethodName: " + entityMethodName);

        boolean methodExists = currentServiceClass != null && methodExists(currentServiceClass, entityMethodName);
        System.out.println("  methodExists: " + methodExists);

        if (currentServiceClass == null || !methodExists) {
            String entityMethodSignature = entityMethodName + "()";
            String returnType = getReturnTypeForMethodPrefix(queryPrefix, simpleEntityName);

            LookupElementBuilder builder = LookupElementBuilder.create(entityMethodSignature)
                    .withPresentableText(entityMethodSignature)
                    .withTypeText(returnType)
                    .withIcon(Icons.FLEX);

            // 统一使用接口+实现类生成逻辑
            String entityMethodBody = generateEntityMethodBody(queryPrefix, entityFields, fieldToTableDefMapping, simpleEntityName, entityClass, "eq");
            builder = builder.withTailText(" { generate in interface and implementation }", true)
                    .withInsertHandler((context, item) -> {
                        // 为整个实体方法创建特殊的字段映射
                        Map<String, FieldInfo> entityMethodFields = new HashMap<>();
                        entityMethodFields.put("entity", new FieldInfo("entity", simpleEntityName, "entity"));

                        if (isInterface) {
                            // 在接口中，生成接口+实现
                            generateServiceMethodInInterfaceAndImpl(context, currentServiceClass, entityMethodName, entityMethodBody, returnType, "Entity", entityMethodFields, entityQualifiedName, fieldToTableDefMapping);
                        } else {
                            // 在实现类中，使用原有逻辑
                            insertMethodImplementationWithCursor(context, entityMethodName, entityMethodBody, returnType, "Entity", entityMethodFields, entityQualifiedName, fieldToTableDefMapping);
                        }
                    });

            result.addElement(builder);
        }

        // 生成like版本的实体方法
        String entityMethodNameLike = queryPrefix + simpleEntityName + "Like";
        if (currentServiceClass == null || !methodExists(currentServiceClass, entityMethodNameLike)) {
            String entityMethodSignatureLike = entityMethodNameLike + "()";
            String returnType = getReturnTypeForMethodPrefix(queryPrefix, simpleEntityName);

            LookupElementBuilder builderLike = LookupElementBuilder.create(entityMethodSignatureLike)
                    .withPresentableText(entityMethodSignatureLike)
                    .withTypeText(returnType)
                    .withIcon(Icons.FLEX);

            // 统一使用接口+实现类生成逻辑
            String entityMethodBodyLike = generateEntityMethodBody(queryPrefix, entityFields, fieldToTableDefMapping, simpleEntityName, entityClass, "like");
            builderLike = builderLike.withTailText(" { generate in interface and implementation }", true)
                    .withInsertHandler((context, item) -> {
                        // 为整个实体方法创建特殊的字段映射
                        Map<String, FieldInfo> entityMethodFields = new HashMap<>();
                        entityMethodFields.put("entity", new FieldInfo("entity", simpleEntityName, "entity"));

                        if (isInterface) {
                            // 在接口中，生成接口+实现
                            generateServiceMethodInInterfaceAndImpl(context, currentServiceClass, entityMethodNameLike, entityMethodBodyLike, returnType, "EntityLike", entityMethodFields, entityQualifiedName, fieldToTableDefMapping);
                        } else {
                            // 在实现类中，使用原有逻辑
                            insertMethodImplementationWithCursor(context, entityMethodNameLike, entityMethodBodyLike, returnType, "EntityLike", entityMethodFields, entityQualifiedName, fieldToTableDefMapping);
                        }
                    });

            result.addElement(builderLike);
        }
    }

    /**
     * 生成单字段方法
     */
    private void generateSingleFieldMethods(CompletionResultSet result, String methodPrefix,
                                          Map<String, FieldInfo> entityFields, Map<String, String> fieldToTableDefMapping,
                                          String simpleEntityName, PsiClass currentServiceClass, String entityQualifiedName) {

        // 判断当前是否在接口中
        boolean isInterface = currentServiceClass != null && currentServiceClass.isInterface();

        for (FieldInfo fieldInfo : entityFields.values()) {
            // 只处理在APT TableDef中存在的字段
            if (!fieldToTableDefMapping.containsKey(fieldInfo.fieldName)) {
                continue; // 跳过APT中不存在的字段
            }

            String capitalizedFieldName = StrUtil.upperFirst(fieldInfo.fieldName);
            String methodName = methodPrefix + capitalizedFieldName;

            // 检查方法是否已存在
            if (currentServiceClass != null && methodExists(currentServiceClass, methodName)) {
                continue; // 跳过已存在的方法
            }

            String methodSignature = methodName + "()";
            String returnType = getReturnTypeForMethodPrefix(methodPrefix, simpleEntityName);

            LookupElementBuilder builder = LookupElementBuilder.create(methodSignature)
                    .withPresentableText(methodSignature)
                    .withTypeText(returnType)
                    .withIcon(Icons.FLEX);

            if (isInterface) {
                // 在接口中，只显示方法签名，不显示方法体
                builder = builder.withTailText(" (generate method declaration)", true)
                        .withInsertHandler((context, item) -> {
                            insertInterfaceMethodDeclaration(context, methodName, returnType);
                        });
            } else {
                // 在实现类中，显示方法体
                String methodBody = generateSingleFieldMethodBody(methodPrefix, fieldInfo, fieldToTableDefMapping, simpleEntityName);
                Map<String, FieldInfo> singleFieldMap = new HashMap<>();
                singleFieldMap.put(fieldInfo.fieldName, fieldInfo);

                builder = builder.withTailText(" { " + methodBody + " }", true)
                        .withInsertHandler((context, item) -> {
                            insertMethodImplementationWithCursor(context, methodName, methodBody, returnType, capitalizedFieldName, singleFieldMap, entityQualifiedName, fieldToTableDefMapping);
                        });
            }

            result.addElement(builder);
        }
    }

    /**
     * 在接口中插入方法声明，并在实现类中生成方法实现
     */
    private void insertInterfaceMethodDeclaration(InsertionContext context, String methodName, String returnType) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                Project project = context.getProject();

                // 使用ReadAction确保在读取线程中执行PSI操作
                ApplicationManager.getApplication().runReadAction(() -> {
                    try {
                        PsiElement element = context.getFile().findElementAt(context.getStartOffset());
                        PsiClass psiClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);

                        if (psiClass != null && psiClass.isInterface()) {
                            // 在写入线程中执行修改操作
                            WriteCommandAction.runWriteCommandAction(project, () -> {
                                try {
                                    PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

                                    // 解析方法名生成参数
                                    String parameters = generateMethodParametersFromName(methodName);

                                    // 生成接口方法声明
                                    String methodText = "/**\n     * " + generateMethodDescription(methodName) + "\n     */\n    " +
                                                      returnType + " " + methodName + "(" + parameters + ");";

                                    PsiMethod method = factory.createMethodFromText(methodText, psiClass);

                                    // 在接口中插入方法声明
                                    psiClass.add(method);

                                    // 同时在实现类中生成方法实现
                                    generateImplementationMethodSafely(project, psiClass, methodName, returnType, parameters);

                                } catch (Exception e) {
                                    // 静默处理异常，避免影响用户体验
                                }
                            });
                        }
                    } catch (Exception e) {
                        // 静默处理异常
                    }
                });

                // 在EDT线程中清除已插入的文本
                ApplicationManager.getApplication().invokeLater(() -> {
                    try {
                        context.getDocument().deleteString(context.getStartOffset(), context.getTailOffset());
                    } catch (Exception e) {
                        // 静默处理异常
                    }
                });

            } catch (Exception e) {
                // 静默处理异常
            }
        });
    }


    /**
     * 从方法名生成参数列表
     */
    private String generateMethodParametersFromName(String methodName) {
        try {
            // 提取方法前缀
            String methodPrefix = extractMethodPrefixFromMethodName(methodName);
            String fieldsPart = methodName.substring(methodPrefix.length());

            if (StrUtil.isEmpty(fieldsPart)) {
                return "";
            }

            // 解析字段名
            List<String> fieldNames = parseFieldNamesFromMethodName(fieldsPart);

            if (fieldNames.isEmpty()) {
                return "";
            }

            // 生成参数列表
            List<String> parameters = new ArrayList<>();
            for (String fieldName : fieldNames) {
                String paramType = getParameterTypeFromFieldName(fieldName);
                String paramName = StrUtil.lowerFirst(fieldName);
                parameters.add(paramType + " " + paramName);
            }

            return String.join(", ", parameters);

        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 解析字段名列表
     */
    private List<String> parseFieldNamesFromMethodName(String fieldsPart) {
        List<String> fieldNames = new ArrayList<>();

        // 简单的解析逻辑：按And/Or分割
        String[] parts = fieldsPart.split("(?=And|Or)");

        for (String part : parts) {
            // 移除And/Or前缀
            String fieldPart = part;
            if (fieldPart.startsWith("And")) {
                fieldPart = fieldPart.substring(3);
            } else if (fieldPart.startsWith("Or")) {
                fieldPart = fieldPart.substring(2);
            }

            // 移除查询类型后缀（如Like, Gt, Lt等）
            String fieldName = removeQueryTypeSuffix(fieldPart);
            if (StrUtil.isNotEmpty(fieldName)) {
                fieldNames.add(fieldName);
            }
        }

        return fieldNames;
    }

    /**
     * 移除查询类型后缀
     */
    private String removeQueryTypeSuffix(String fieldPart) {
        // 常见的查询类型后缀
        String[] suffixes = {"Like", "Gt", "Lt", "Ge", "Le", "Ne", "In", "NotIn", "Between", "NotBetween", "IsNull", "IsNotNull"};

        for (String suffix : suffixes) {
            if (fieldPart.endsWith(suffix)) {
                return fieldPart.substring(0, fieldPart.length() - suffix.length());
            }
        }

        return fieldPart;
    }

    /**
     * 根据字段名获取参数类型
     */
    private String getParameterTypeFromFieldName(String fieldName) {
        String lowerFieldName = fieldName.toLowerCase();

        if (lowerFieldName.contains("id")) {
            return "Long";
        } else if (lowerFieldName.contains("time") || lowerFieldName.contains("date")) {
            return "Date";
        } else if (lowerFieldName.contains("count") || lowerFieldName.contains("num")) {
            return "Integer";
        } else if (lowerFieldName.contains("amount") || lowerFieldName.contains("price")) {
            return "BigDecimal";
        } else if (lowerFieldName.contains("flag") || lowerFieldName.contains("enable")) {
            return "Boolean";
        } else {
            return "String"; // 默认为String类型
        }
    }

    /**
     * 安全地在实现类中生成方法实现
     */
    private void generateImplementationMethodSafely(Project project, PsiClass serviceInterface, String methodName, String returnType, String parameters) {
        try {
            // 在后台线程中执行，避免阻塞UI
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                ApplicationManager.getApplication().runReadAction(() -> {
                    try {
                        generateImplementationMethod(project, serviceInterface, methodName, returnType, parameters);
                    } catch (Exception e) {
                        // 静默处理异常
                    }
                });
            });
        } catch (Exception e) {
            // 静默处理异常
        }
    }

    /**
     * 在实现类中生成方法实现
     */
    private void generateImplementationMethod(Project project, PsiClass serviceInterface, String methodName, String returnType, String parameters) {
        try {
            // 查找实现类
            PsiClass serviceImpl = findServiceImplementation(serviceInterface, project);
            if (serviceImpl == null) {
                return;
            }

            // 获取实体类型
            String entityQualifiedName = getEntityClassFromInterface(serviceInterface, project);
            if (entityQualifiedName == null) {
                return;
            }

            // 生成方法实现
            String methodPrefix = extractMethodPrefixFromMethodName(methodName);
            String methodBody = generateMethodBodyFromName(methodName, methodPrefix, entityQualifiedName);

            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

            // 生成完整的方法实现
            String methodText = "/**\n     * " + generateMethodDescription(methodName) + "\n     */\n" +
                              "    @Override\n" +
                              "    public " + returnType + " " + methodName + "(" + parameters + ") {\n" +
                              methodBody + "\n" +
                              "    }";

            PsiMethod method = factory.createMethodFromText(methodText, serviceImpl);

            // 在实现类中插入方法
            WriteCommandAction.runWriteCommandAction(project, () -> {
                serviceImpl.add(method);
            });

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 查找Service实现类 - 使用IDEA原生API
     */
    private PsiClass findServiceImplementation(PsiClass serviceInterface, Project project) {
        try {
            // 检查进度是否被取消
            ProgressManager.checkCanceled();

            if (serviceInterface == null || !serviceInterface.isInterface()) {
                System.err.println("Service interface is null or not an interface");
                return null;
            }

            System.out.println("Searching for implementation of: " + serviceInterface.getQualifiedName());

            // 使用IDEA原生API查找接口的所有实现类
            GlobalSearchScope scope = GlobalSearchScope.allScope(project);

            // 查找直接实现该接口的类
            for (PsiClass implementor : ClassInheritorsSearch.search(serviceInterface, scope, false)) {
                // 检查进度是否被取消
                ProgressManager.checkCanceled();

                System.out.println("Found implementor: " + implementor.getQualifiedName() +
                                 ", isInterface: " + implementor.isInterface() +
                                 ", isAbstract: " + implementor.hasModifierProperty(PsiModifier.ABSTRACT));

                // 跳过接口，只要具体的实现类
                if (!implementor.isInterface() && !implementor.hasModifierProperty(PsiModifier.ABSTRACT)) {
                    System.out.println("Selected implementation: " + implementor.getQualifiedName());
                    return implementor;
                }
            }

            System.err.println("No implementation found for: " + serviceInterface.getQualifiedName());
            return null;
        } catch (ProcessCanceledException e) {
            // 进度被取消，直接返回null
            System.err.println("Search cancelled for: " + serviceInterface.getQualifiedName());
            return null;
        } catch (Exception e) {
            // 其他异常，返回null
            System.err.println("Error searching for implementation: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 根据方法名生成方法体
     */
    private String generateMethodBodyFromName(String methodName, String methodPrefix, String entityQualifiedName) {
        try {
            String simpleEntityName = StrUtil.subAfter(entityQualifiedName, ".", true);
            String tableDefInstanceName = StrUtil.toUnderlineCase(simpleEntityName).toUpperCase();

            // 解析字段部分
            String fieldsPart = methodName.substring(methodPrefix.length());

            StringBuilder methodBody = new StringBuilder();
            methodBody.append("        QueryWrapper queryWrapper = QueryWrapper.create()");

            // 简单解析第一个字段（可以后续优化为更复杂的解析）
            if (StrUtil.isNotEmpty(fieldsPart)) {
                String firstField = extractFirstField(fieldsPart);
                String tableDefField = StrUtil.toUnderlineCase(firstField).toUpperCase();
                String paramName = StrUtil.lowerFirst(firstField);

                methodBody.append("\n                .where(").append(tableDefInstanceName).append(".").append(tableDefField).append(".eq(").append(paramName).append("));");
            } else {
                methodBody.append(";");
            }

            methodBody.append("\n        ").append(generateReturnStatementFromPrefix(methodPrefix, "queryWrapper"));

            return methodBody.toString();

        } catch (Exception e) {
            return "        // TODO: 实现查询逻辑\n        return null;";
        }
    }

    /**
     * 提取第一个字段名
     */
    private String extractFirstField(String fieldsPart) {
        // 移除查询类型后缀
        String[] suffixes = {"Like", "Gt", "Lt", "Ge", "Le", "Ne", "In", "NotIn", "Between", "NotBetween", "IsNull", "IsNotNull"};
        String fieldName = fieldsPart;

        for (String suffix : suffixes) {
            if (fieldName.endsWith(suffix)) {
                fieldName = fieldName.substring(0, fieldName.length() - suffix.length());
                break;
            }
        }

        // 如果包含And/Or，只取第一部分
        if (fieldName.contains("And")) {
            fieldName = fieldName.substring(0, fieldName.indexOf("And"));
        } else if (fieldName.contains("Or")) {
            fieldName = fieldName.substring(0, fieldName.indexOf("Or"));
        }

        return fieldName;
    }

    /**
     * 根据方法前缀生成返回语句
     */
    private String generateReturnStatementFromPrefix(String methodPrefix, String queryWrapper) {
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
     * 生成方法描述
     */
    private String generateMethodDescription(String methodName) {
        String lowerMethodName = methodName.toLowerCase();
        if (lowerMethodName.startsWith("listby")) {
            return "根据条件查询列表";
        } else if (lowerMethodName.startsWith("findby")) {
            return "根据条件查找数据";
        } else if (lowerMethodName.startsWith("getby")) {
            return "根据条件获取单个数据";
        } else if (lowerMethodName.startsWith("countby")) {
            return "根据条件统计数量";
        } else if (lowerMethodName.startsWith("existsby")) {
            return "根据条件检查是否存在";
        } else if (lowerMethodName.startsWith("removeby") || lowerMethodName.startsWith("deleteby")) {
            return "根据条件删除数据";
        } else {
            return "根据条件查询数据";
        }
    }

    /**
     * 解析字段并生成补全
     */
    private List<String> parseFieldsAndGenerateCompletions(String fieldsPart, Map<String, FieldInfo> entityFields,
                                                          Map<String, String> fieldToTableDefMapping, Project project) {
        List<String> completions = new ArrayList<>();

        // 获取已使用的字段列表，避免重复
        Set<String> usedFields = getUsedFieldsFromInput(fieldsPart);

        // 获取QueryColumn方法映射
        Map<String, String> queryMethods = getQueryColumnMethods(project);

        // 检查是否以QueryColumn方法结尾，如果是，则可以直接作为完整方法名
        if (endsWithQueryColumnMethod(fieldsPart, entityFields, project)) {
            // 方法名已经完整，但仍然可以继续添加And/Or连接符
            // 检查是否可以添加And/Or
            addAndOrCompletions(completions, fieldsPart, entityFields, usedFields, queryMethods);
            return completions;
        }

        // 检查是否以单个字母结尾，可能是要输入And/Or或QueryColumn方法
        if (fieldsPart.length() > 0) {
            char lastChar = fieldsPart.charAt(fieldsPart.length() - 1);
            if (lastChar == 'a' || lastChar == 'A') {
                // 检查前面是否是完整的字段名
                String prefix = fieldsPart.substring(0, fieldsPart.length() - 1);
                if (endsWithValidField(prefix, entityFields, project)) {
                    completions.add(prefix + "And");
                    return completions; // 只返回And选项，保持连贯性
                }
            } else if (lastChar == 'o' || lastChar == 'O') {
                String prefix = fieldsPart.substring(0, fieldsPart.length() - 1);
                if (endsWithValidField(prefix, entityFields, project)) {
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
                    // 添加基本字段补全
                    completions.add(fieldsPart + capitalizedFieldName);

                    // 添加带查询类型后缀的字段补全
                    Map<String, String> queryMethods1 = getQueryColumnMethods(project);
                    for (String suffix : queryMethods1.keySet()) {
                        completions.add(fieldsPart + capitalizedFieldName + suffix);
                    }
                }
            }
            return completions;
        }

        // 查找可能的字段匹配
        for (String fieldName : entityFields.keySet()) {
            String capitalizedFieldName = StrUtil.upperFirst(fieldName);

            // 1. 检查字段名是否匹配当前输入（部分匹配或完全匹配）
            if (capitalizedFieldName.startsWith(fieldsPart)) {
                // 避免重复字段
                if (!usedFields.contains(fieldName)) {
                    // 如果是完全匹配，提供扩展选项
                    if (capitalizedFieldName.equals(fieldsPart)) {
                        // 单字段（默认eq查询）
                        completions.add(capitalizedFieldName);

                        // 动态添加查询类型后缀的选项
                        for (String suffix : queryMethods.keySet()) {
                            completions.add(capitalizedFieldName + suffix);
                        }

                        // 添加And/Or连接符选项
                        completions.add(capitalizedFieldName + "And");
                        completions.add(capitalizedFieldName + "Or");
                    } else {
                        // 部分匹配，只添加字段名本身
                        completions.add(capitalizedFieldName);
                    }
                }
            }

            // 2. 检查字段名+QueryColumn方法的组合是否匹配输入
            for (String suffix : queryMethods.keySet()) {
                String fieldWithSuffix = capitalizedFieldName + suffix;
                // 只有当组合完全匹配用户输入的前缀时才添加
                if (fieldWithSuffix.startsWith(fieldsPart) && !fieldWithSuffix.equals(fieldsPart)) {
                    // 避免重复字段
                    if (!usedFields.contains(fieldName)) {
                        completions.add(fieldWithSuffix);
                    }
                }
            }

            // 3. 检查是否是部分QueryColumn方法名的匹配（如输入"TitleL"想要"TitleLike"）
            if (fieldsPart.length() > capitalizedFieldName.length() &&
                fieldsPart.startsWith(capitalizedFieldName)) {
                String remainingSuffix = fieldsPart.substring(capitalizedFieldName.length());
                for (String suffix : queryMethods.keySet()) {
                    if (suffix.startsWith(remainingSuffix) && !suffix.equals(remainingSuffix)) {
                        // 避免重复字段
                        if (!usedFields.contains(fieldName)) {
                            completions.add(capitalizedFieldName + suffix);
                        }
                    }
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
                            String prefix = fieldsPart.substring(0, fieldsPart.length() - remainingPart.length());
                            String completion = prefix + capitalizedFieldName;

                            // 如果是完全匹配，总是显示选项
                            if (capitalizedFieldName.equals(remainingPart)) {
                                completions.add(completion);

                                // 动态添加查询类型后缀的选项
                                for (String suffix : queryMethods.keySet()) {
                                    completions.add(completion + suffix);
                                }

                                // 添加And/Or连接符选项
                                completions.add(completion + "And");
                                completions.add(completion + "Or");
                            } else {
                                // 部分匹配，避免重复字段
                                if (!usedFields.contains(fieldName)) {
                                    completions.add(completion);

                                    // 动态添加查询类型后缀的选项
                                    for (String suffix : queryMethods.keySet()) {
                                        completions.add(completion + suffix);
                                    }
                                }
                            }
                        }

                        // 检查字段名+部分QueryColumn方法名的匹配（在And/Or组合中）
                        for (String suffix : queryMethods.keySet()) {
                            String fieldWithSuffix = capitalizedFieldName + suffix;
                            if (fieldWithSuffix.startsWith(remainingPart) && fieldWithSuffix.length() > remainingPart.length()) {
                                // 避免重复字段
                                if (!usedFields.contains(fieldName)) {
                                    String prefix = fieldsPart.substring(0, fieldsPart.length() - remainingPart.length());
                                    completions.add(prefix + fieldWithSuffix);
                                }
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

        if (StrUtil.isEmpty(input)) {
            return usedFields;
        }

        // 处理以And或Or结尾的情况
        String workingInput = input;
        if (input.endsWith("And") || input.endsWith("Or")) {
            // 移除末尾的And/Or
            workingInput = input.endsWith("And") ?
                input.substring(0, input.length() - 3) :
                input.substring(0, input.length() - 2);
        }

        // 使用正则表达式分割字段名
        // 匹配And或Or前面的内容
        String[] parts = workingInput.split("(?=And|Or)|(?<=And|Or)");

        StringBuilder currentField = new StringBuilder();
        for (String part : parts) {
            if ("And".equals(part) || "Or".equals(part)) {
                // 遇到连接符，保存当前字段
                if (currentField.length() > 0) {
                    String fieldName = StrUtil.lowerFirst(currentField.toString());
                    usedFields.add(fieldName);
                    currentField.setLength(0);
                }
            } else {
                currentField.append(part);
            }
        }

        // 处理最后一个字段
        if (currentField.length() > 0) {
            String fieldName = StrUtil.lowerFirst(currentField.toString());
            usedFields.add(fieldName);
        }

        return usedFields;
    }

    /**
     * 检查输入是否以有效字段结尾（包括带查询类型后缀的字段）
     */
    private boolean endsWithValidField(String input, Map<String, FieldInfo> entityFields, Project project) {
        for (String fieldName : entityFields.keySet()) {
            String capitalizedFieldName = StrUtil.upperFirst(fieldName);
            // 检查纯字段名
            if (input.endsWith(capitalizedFieldName)) {
                return true;
            }

            // 检查字段名+查询类型后缀
            // 动态获取QueryColumn的方法名作为查询类型
            Map<String, String> queryMethods = getQueryColumnMethods(project);
            for (String suffix : queryMethods.keySet()) {
                if (input.endsWith(capitalizedFieldName + suffix)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 检查输入是否以QueryColumn方法结尾
     */
    private boolean endsWithQueryColumnMethod(String input, Map<String, FieldInfo> entityFields, Project project) {
        // 动态获取QueryColumn的方法名
        Map<String, String> queryMethods = getQueryColumnMethods(project);

        for (String suffix : queryMethods.keySet()) {
            if (input.endsWith(suffix)) {
                // 检查去掉后缀后是否以有效字段结尾
                String withoutSuffix = input.substring(0, input.length() - suffix.length());
                if (endsWithValidFieldName(withoutSuffix, entityFields)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 检查输入是否以有效字段名结尾（不包括查询类型后缀）
     */
    private boolean endsWithValidFieldName(String input, Map<String, FieldInfo> entityFields) {
        for (String fieldName : entityFields.keySet()) {
            String capitalizedFieldName = StrUtil.upperFirst(fieldName);
            if (input.endsWith(capitalizedFieldName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 添加Service方法调用补全
     */
    private void addServiceMethodCallCompletion(CompletionResultSet result, PsiClass serviceInterface,
                                              String entityQualifiedName, Project project, String prefix,
                                              CompletionParameters parameters) {
        try {
            // 获取实体类字段信息
            JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
            GlobalSearchScope scope = GlobalSearchScope.allScope(project);
            PsiClass entityClass = findEntityClass(entityQualifiedName, psiFacade, scope);
            if (entityClass == null) {
                return;
            }

            Map<String, FieldInfo> entityFields = getEntityFieldsInfo(entityClass);

            // 获取TableDef字段映射
            VirtualFile virtualFile = serviceInterface.getContainingFile().getVirtualFile();
            String aptClass = TableDefUtils.getTableDefQualifiedName(entityQualifiedName, virtualFile);
            Map<String, String> fieldToTableDefMapping = getFieldToTableDefMapping(entityQualifiedName, aptClass, project);

            // 根据方法名生成查询方法，但使用特殊的插入处理器来生成接口+实现
            addServiceCallMethodNameBasedCompletion(result, serviceInterface, entityQualifiedName, project, aptClass, prefix, parameters, entityFields, fieldToTableDefMapping);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 添加Service方法调用的方法名补全（生成接口+实现）
     */
    private void addServiceCallMethodNameBasedCompletion(CompletionResultSet result, PsiClass serviceInterface,
                                                       String entityQualifiedName, Project project, String aptClass,
                                                       String prefix, CompletionParameters parameters,
                                                       Map<String, FieldInfo> entityFields,
                                                       Map<String, String> fieldToTableDefMapping) {
        try {
            // 解析方法名前缀
            String methodPrefix = extractMethodPrefix(prefix);
            if (StrUtil.isEmpty(methodPrefix)) {
                // 如果没有完整的方法前缀，提供方法前缀补全
                addServiceCallMethodPrefixCompletions(result, serviceInterface, prefix, entityFields, fieldToTableDefMapping, entityQualifiedName);
                return;
            }

            String fieldsPart = prefix.substring(methodPrefix.length());

            // 如果字段部分为空（即刚输入完方法前缀如listBy），直接显示所有字段补全
            if (StrUtil.isEmpty(fieldsPart)) {
                addServiceCallFieldCompletions(result, serviceInterface, methodPrefix, entityFields, fieldToTableDefMapping, entityQualifiedName);
                return;
            }

            // 生成可能的方法补全
            generateServiceCallMethodCompletions(result, serviceInterface, methodPrefix, fieldsPart, entityFields, fieldToTableDefMapping, entityQualifiedName, project);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 添加Service方法调用的字段补全（当刚输入完方法前缀时）
     */
    private void addServiceCallFieldCompletions(CompletionResultSet result, PsiClass serviceInterface,
                                              String methodPrefix, Map<String, FieldInfo> entityFields,
                                              Map<String, String> fieldToTableDefMapping, String entityQualifiedName) {

        String simpleEntityName = StrUtil.subAfter(entityQualifiedName, ".", true);

        for (FieldInfo fieldInfo : entityFields.values()) {
            // 只处理在APT TableDef中存在的字段
            if (!fieldToTableDefMapping.containsKey(fieldInfo.fieldName)) {
                continue; // 跳过APT中不存在的字段
            }

            String capitalizedFieldName = StrUtil.upperFirst(fieldInfo.fieldName);
            String methodName = methodPrefix + capitalizedFieldName;

            // 检查方法是否已存在
            if (methodExists(serviceInterface, methodName)) {
                continue; // 跳过已存在的方法
            }

            // 添加基本字段方法
            addServiceCallFieldMethod(result, serviceInterface, methodName, methodPrefix, fieldInfo,
                                    fieldToTableDefMapping, simpleEntityName, entityQualifiedName, "");

            // 添加带查询类型后缀的方法
            try {
                Map<String, String> queryMethods = getQueryColumnMethods(serviceInterface.getProject());
                for (String suffix : queryMethods.keySet()) {
                    String methodWithSuffix = methodPrefix + capitalizedFieldName + suffix;

                    // 检查方法是否已存在
                    if (methodExists(serviceInterface, methodWithSuffix)) {
                        continue; // 跳过已存在的方法
                    }

                    addServiceCallFieldMethod(result, serviceInterface, methodWithSuffix, methodPrefix, fieldInfo,
                                            fieldToTableDefMapping, simpleEntityName, entityQualifiedName, suffix);
                }
            } catch (Exception e) {
                // 如果获取查询方法失败，只添加基本方法
            }
        }
    }

    /**
     * 添加单个Service方法调用的字段方法
     */
    private void addServiceCallFieldMethod(CompletionResultSet result, PsiClass serviceInterface,
                                         String methodName, String methodPrefix, FieldInfo fieldInfo,
                                         Map<String, String> fieldToTableDefMapping, String simpleEntityName,
                                         String entityQualifiedName, String querySuffix) {

        String methodSignature = methodName + "()";
        String returnType = getReturnTypeForMethodPrefix(methodPrefix, simpleEntityName);
        String methodBody = generateSingleFieldMethodBody(methodPrefix, fieldInfo, fieldToTableDefMapping, simpleEntityName);

        LookupElement methodElement = LookupElementBuilder.create(methodSignature)
                .withPresentableText(methodSignature)
                .withTypeText(returnType)
                .withTailText(" { generate in interface and implementation }", true)
                .withIcon(Icons.FLEX)
                .withInsertHandler((context, item) -> {
                    Map<String, FieldInfo> singleFieldMap = new HashMap<>();
                    singleFieldMap.put(fieldInfo.fieldName, fieldInfo);
                    generateServiceMethodInInterfaceAndImpl(context, serviceInterface, methodName, methodBody, returnType,
                                                           fieldInfo.fieldName, singleFieldMap, entityQualifiedName, fieldToTableDefMapping);
                });

        result.addElement(methodElement);
    }

    /**
     * 添加Service方法调用的方法前缀补全
     */
    private void addServiceCallMethodPrefixCompletions(CompletionResultSet result, PsiClass serviceInterface,
                                                     String prefix, Map<String, FieldInfo> entityFields,
                                                     Map<String, String> fieldToTableDefMapping, String entityQualifiedName) {

        String simpleEntityName = StrUtil.subAfter(entityQualifiedName, ".", true);
        String[] queryPrefixes = {"listBy", "findBy", "getBy", "countBy", "existsBy", "removeBy", "deleteBy"};
        String lowerPrefix = prefix.toLowerCase();

        for (String queryPrefix : queryPrefixes) {
            if (queryPrefix.toLowerCase().startsWith(lowerPrefix)) {

                // 添加整个实体的方法补全（如listBy + 实体名）
                if (queryPrefix.equals("listBy") || queryPrefix.equals("findBy")) {
                    // 生成两个版本：eq查询和like查询
                    generateServiceCallEntityMethods(result, serviceInterface, queryPrefix, simpleEntityName, entityFields, fieldToTableDefMapping, entityQualifiedName);
                }

                // 为每个字段生成方法补全
                for (FieldInfo fieldInfo : entityFields.values()) {
                    // 只处理在APT TableDef中存在的字段
                    if (!fieldToTableDefMapping.containsKey(fieldInfo.fieldName)) {
                        continue; // 跳过APT中不存在的字段
                    }

                    String capitalizedFieldName = StrUtil.upperFirst(fieldInfo.fieldName);
                    String methodName = queryPrefix + capitalizedFieldName;

                    // 检查方法是否已存在
                    if (methodExists(serviceInterface, methodName)) {
                        continue; // 跳过已存在的方法
                    }

                    String methodSignature = methodName + "()";
                    String methodBody = generateSingleFieldMethodBody(queryPrefix, fieldInfo, fieldToTableDefMapping, simpleEntityName);
                    String returnType = getReturnTypeForMethodPrefix(queryPrefix, simpleEntityName);

                    LookupElement methodElement = LookupElementBuilder.create(methodSignature)
                            .withPresentableText(methodSignature)
                            .withTypeText(returnType)
                            .withTailText(" { generate in interface and implementation }", true)
                            .withIcon(Icons.FLEX)
                            .withInsertHandler((context, item) -> {
                                generateServiceMethodInInterfaceAndImpl(context, serviceInterface, methodName, methodBody, returnType,
                                                                       capitalizedFieldName, entityFields, entityQualifiedName, fieldToTableDefMapping);
                            });

                    result.addElement(methodElement);
                }
            }
        }
    }

    /**
     * 生成Service方法调用的方法补全
     */
    private void generateServiceCallMethodCompletions(CompletionResultSet result, PsiClass serviceInterface,
                                                    String methodPrefix, String fieldsPart,
                                                    Map<String, FieldInfo> entityFields, Map<String, String> fieldToTableDefMapping,
                                                    String entityQualifiedName, Project project) {

        String simpleEntityName = StrUtil.subAfter(entityQualifiedName, ".", true);

        // 如果字段部分为空，提供所有可能的字段组合
        if (StrUtil.isEmpty(fieldsPart)) {
            generateServiceCallSingleFieldMethods(result, serviceInterface, methodPrefix, entityFields, fieldToTableDefMapping, simpleEntityName, entityQualifiedName);
            return;
        }

        // 检查是否是实体名（如ActivitiModel或ActivitiModelLike）
        if (isEntityNamePattern(fieldsPart, simpleEntityName)) {
            generateServiceCallEntityMethodForCompletion(result, serviceInterface, methodPrefix, fieldsPart, simpleEntityName, entityFields, fieldToTableDefMapping, entityQualifiedName);
            return;
        }

        // 解析已输入的字段部分
        List<String> possibleCompletions = parseFieldsAndGenerateCompletions(fieldsPart, entityFields, fieldToTableDefMapping, project);

        for (String completion : possibleCompletions) {
            String fullMethodName = methodPrefix + completion;

            // 检查方法是否已存在
            if (methodExists(serviceInterface, fullMethodName)) {
                continue; // 跳过已存在的方法
            }

            // 生成空的方法签名，不包含参数
            String methodSignature = fullMethodName + "()";
            String methodBody = generateMethodBody(methodPrefix, completion, fieldToTableDefMapping, simpleEntityName, project);
            String returnType = getReturnTypeForMethodPrefix(methodPrefix, simpleEntityName);

            LookupElement methodElement = LookupElementBuilder.create(methodSignature)
                    .withPresentableText(methodSignature)
                    .withTypeText(returnType)
                    .withTailText(" { generate in interface and implementation }", true)
                    .withIcon(Icons.FLEX)
                    .withInsertHandler((context, item) -> {
                        generateServiceMethodInInterfaceAndImpl(context, serviceInterface, fullMethodName, methodBody, returnType,
                                                               completion, entityFields, entityQualifiedName, fieldToTableDefMapping);
                    });

            result.addElement(methodElement);
        }
    }

    /**
     * 生成Service方法调用的单字段方法
     */
    private void generateServiceCallSingleFieldMethods(CompletionResultSet result, PsiClass serviceInterface,
                                                      String methodPrefix, Map<String, FieldInfo> entityFields,
                                                      Map<String, String> fieldToTableDefMapping,
                                                      String simpleEntityName, String entityQualifiedName) {

        for (FieldInfo fieldInfo : entityFields.values()) {
            // 只处理在APT TableDef中存在的字段
            if (!fieldToTableDefMapping.containsKey(fieldInfo.fieldName)) {
                continue; // 跳过APT中不存在的字段
            }

            String capitalizedFieldName = StrUtil.upperFirst(fieldInfo.fieldName);
            String methodName = methodPrefix + capitalizedFieldName;

            // 检查方法是否已存在
            if (methodExists(serviceInterface, methodName)) {
                continue; // 跳过已存在的方法
            }

            String methodSignature = methodName + "()";
            String returnType = getReturnTypeForMethodPrefix(methodPrefix, simpleEntityName);
            String methodBody = generateSingleFieldMethodBody(methodPrefix, fieldInfo, fieldToTableDefMapping, simpleEntityName);

            LookupElement methodElement = LookupElementBuilder.create(methodSignature)
                    .withPresentableText(methodSignature)
                    .withTypeText(returnType)
                    .withTailText(" { generate in interface and implementation }", true)
                    .withIcon(Icons.FLEX)
                    .withInsertHandler((context, item) -> {
                        Map<String, FieldInfo> singleFieldMap = new HashMap<>();
                        singleFieldMap.put(fieldInfo.fieldName, fieldInfo);
                        generateServiceMethodInInterfaceAndImpl(context, serviceInterface, methodName, methodBody, returnType,
                                                               capitalizedFieldName, singleFieldMap, entityQualifiedName, fieldToTableDefMapping);
                    });

            result.addElement(methodElement);
        }
    }

    /**
     * 生成Service调用的实体方法（eq和like两个版本）
     */
    private void generateServiceCallEntityMethods(CompletionResultSet result, PsiClass serviceInterface,
                                                String queryPrefix, String simpleEntityName,
                                                Map<String, FieldInfo> entityFields, Map<String, String> fieldToTableDefMapping,
                                                String entityQualifiedName) {

        // 生成eq版本的实体方法
        String entityMethodName = queryPrefix + simpleEntityName;
        if (!methodExists(serviceInterface, entityMethodName)) {
            String entityMethodSignature = entityMethodName + "()";
            String returnType = getReturnTypeForMethodPrefix(queryPrefix, simpleEntityName);
            String entityMethodBody = generateEntityMethodBody(queryPrefix, entityFields, fieldToTableDefMapping, simpleEntityName, null, "eq");

            LookupElement methodElement = LookupElementBuilder.create(entityMethodSignature)
                    .withPresentableText(entityMethodSignature)
                    .withTypeText(returnType)
                    .withTailText(" { generate in interface and implementation }", true)
                    .withIcon(Icons.FLEX)
                    .withInsertHandler((context, item) -> {
                        Map<String, FieldInfo> entityMethodFields = new HashMap<>();
                        entityMethodFields.put("entity", new FieldInfo("entity", simpleEntityName, "entity"));
                        generateServiceMethodInInterfaceAndImpl(context, serviceInterface, entityMethodName, entityMethodBody, returnType,
                                                               "Entity", entityMethodFields, entityQualifiedName, fieldToTableDefMapping);
                    });

            result.addElement(methodElement);
        }

        // 生成like版本的实体方法
        String entityMethodNameLike = queryPrefix + simpleEntityName + "Like";
        if (!methodExists(serviceInterface, entityMethodNameLike)) {
            String entityMethodSignatureLike = entityMethodNameLike + "()";
            String returnType = getReturnTypeForMethodPrefix(queryPrefix, simpleEntityName);
            String entityMethodBodyLike = generateEntityMethodBody(queryPrefix, entityFields, fieldToTableDefMapping, simpleEntityName, null, "like");

            LookupElement methodElementLike = LookupElementBuilder.create(entityMethodSignatureLike)
                    .withPresentableText(entityMethodSignatureLike)
                    .withTypeText(returnType)
                    .withTailText(" { generate in interface and implementation }", true)
                    .withIcon(Icons.FLEX)
                    .withInsertHandler((context, item) -> {
                        Map<String, FieldInfo> entityMethodFields = new HashMap<>();
                        entityMethodFields.put("entity", new FieldInfo("entity", simpleEntityName, "entity"));
                        generateServiceMethodInInterfaceAndImpl(context, serviceInterface, entityMethodNameLike, entityMethodBodyLike, returnType,
                                                               "EntityLike", entityMethodFields, entityQualifiedName, fieldToTableDefMapping);
                    });

            result.addElement(methodElementLike);
        }
    }

    /**
     * 为Service调用生成单个实体方法补全
     */
    private void generateServiceCallEntityMethodForCompletion(CompletionResultSet result, PsiClass serviceInterface,
                                                            String methodPrefix, String fieldsPart, String simpleEntityName,
                                                            Map<String, FieldInfo> entityFields, Map<String, String> fieldToTableDefMapping,
                                                            String entityQualifiedName) {

        boolean isLikeVersion = fieldsPart.endsWith("Like");
        String queryType = isLikeVersion ? "like" : "eq";
        String methodName = methodPrefix + fieldsPart;

        // 检查方法是否已存在
        if (methodExists(serviceInterface, methodName)) {
            return; // 跳过已存在的方法
        }

        String methodSignature = methodName + "()";
        String returnType = getReturnTypeForMethodPrefix(methodPrefix, simpleEntityName);
        String methodBody = generateEntityMethodBody(methodPrefix, entityFields, fieldToTableDefMapping, simpleEntityName, null, queryType);

        LookupElement methodElement = LookupElementBuilder.create(methodSignature)
                .withPresentableText(methodSignature)
                .withTypeText(returnType)
                .withTailText(" { generate in interface and implementation }", true)
                .withIcon(Icons.FLEX)
                .withInsertHandler((context, item) -> {
                    // 为整个实体方法创建特殊的字段映射
                    Map<String, FieldInfo> entityMethodFields = new HashMap<>();
                    entityMethodFields.put("entity", new FieldInfo("entity", simpleEntityName, "entity"));

                    String fieldsCompletion = isLikeVersion ? "EntityLike" : "Entity";
                    generateServiceMethodInInterfaceAndImpl(context, serviceInterface, methodName, methodBody, returnType,
                                                           fieldsCompletion, entityMethodFields, entityQualifiedName, fieldToTableDefMapping);
                });

        result.addElement(methodElement);
    }

    /**
     * 在Service接口和实现类中生成方法
     */
    private void generateServiceMethodInInterfaceAndImpl(InsertionContext context, PsiClass serviceInterface,
                                                       String methodName, String methodBody, String returnType,
                                                       String fieldsCompletion, Map<String, FieldInfo> entityFields,
                                                       String entityQualifiedName, Map<String, String> fieldToTableDefMapping) {
        System.out.println("generateServiceMethodInInterfaceAndImpl called with:");
        System.out.println("  methodName: " + methodName);
        System.out.println("  returnType: " + returnType);
        System.out.println("  fieldsCompletion: " + fieldsCompletion);
        System.out.println("  serviceInterface: " + (serviceInterface != null ? serviceInterface.getName() : "null"));

        ApplicationManager.getApplication().runWriteAction(() -> {
            try {
                Project project = context.getProject();

                // 确保文档已提交
                PsiDocumentManager.getInstance(project).commitAllDocuments();

                // 解析字段并生成参数列表
                StringBuilder paramBuilder = new StringBuilder();

                // 检查是否是整个实体方法（通过fieldsCompletion判断）
                if ("Entity".equals(fieldsCompletion) || "EntityLike".equals(fieldsCompletion)) {
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

                // 生成方法注释
                String methodComment = generateMethodComment(methodName, fieldsCompletion, entityFields, entityQualifiedName);

                // 1. 先在接口中生成方法声明
                PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

                // 创建纯粹的方法声明
                String methodDeclaration = returnType + " " + methodName + "(" + paramBuilder.toString() + ");";
                PsiMethod interfaceMethod = factory.createMethodFromText(methodDeclaration, serviceInterface);

                // 添加方法到接口
                PsiMethod addedMethod = (PsiMethod) serviceInterface.add(interfaceMethod);

                System.out.println("Successfully added method to interface: " + addedMethod.getName());

                // 暂时跳过JavaDoc注释添加，避免可能的错误
                // addJavaDocToMethod(addedMethod, methodName, fieldsCompletion, entityFields, entityQualifiedName, factory);

                System.out.println("Added method to interface: " + methodName);

                // 2. 然后在实现类中生成方法实现
                generateImplementationMethodAsync(project, serviceInterface, methodName, returnType,
                                                paramBuilder.toString(), methodBody, fieldsCompletion, entityFields, entityQualifiedName);

                // 3. 替换用户输入为方法调用
                com.intellij.openapi.editor.Document document = context.getDocument();
                int startOffset = context.getStartOffset();
                int tailOffset = context.getTailOffset();

                String userInputReplacement = methodName + "()";
                document.replaceString(startOffset, tailOffset, userInputReplacement);

                // 4. 将光标定位到括号内
                int cursorPosition = startOffset + methodName.length() + 1; // +1 for opening parenthesis
                context.getEditor().getCaretModel().moveToOffset(cursorPosition);

            } catch (Exception e) {
                System.err.println("Error generating service method: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * 添加And/Or连接符的补全选项
     */
    private void addAndOrCompletions(List<String> completions, String fieldsPart,
                                   Map<String, FieldInfo> entityFields, Set<String> usedFields,
                                   Map<String, String> queryMethods) {
        // 为以QueryColumn方法结尾的字段添加And/Or连接符
        for (String fieldName : entityFields.keySet()) {
            String capitalizedFieldName = StrUtil.upperFirst(fieldName);
            // 避免重复字段
            if (!usedFields.contains(fieldName)) {
                completions.add(fieldsPart + "And" + capitalizedFieldName);
                completions.add(fieldsPart + "Or" + capitalizedFieldName);

                // 也添加带查询类型后缀的选项
                for (String suffix : queryMethods.keySet()) {
                    completions.add(fieldsPart + "And" + capitalizedFieldName + suffix);
                    completions.add(fieldsPart + "Or" + capitalizedFieldName + suffix);
                }
            }
        }
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
        return generateMethodBody(methodPrefix, fieldsCompletion, fieldToTableDefMapping, simpleEntityName, null);
    }

    /**
     * 生成方法体（支持动态查询类型）
     */
    private String generateMethodBody(String methodPrefix, String fieldsCompletion,
                                    Map<String, String> fieldToTableDefMapping, String simpleEntityName, Project project) {

        // 解析字段查询信息
        List<FieldQueryInfo> fieldQueryInfos = parseFieldQueryInfoFromCompletion(fieldsCompletion, project);
        List<String> connectors = parseConnectorsFromCompletion(fieldsCompletion);

        // 获取TableDef静态变量名
        String tableDefInstanceName = getTableDefInstanceName(simpleEntityName);

        StringBuilder methodBody = new StringBuilder();
        methodBody.append("QueryWrapper queryWrapper = QueryWrapper.create()");

        for (int i = 0; i < fieldQueryInfos.size(); i++) {
            FieldQueryInfo queryInfo = fieldQueryInfos.get(i);
            String field = queryInfo.fieldName;
            String queryType = queryInfo.queryType;

            String tableDefField = fieldToTableDefMapping.get(field);
            if (tableDefField == null) {
                tableDefField = field.toUpperCase();
            }

            String fullFieldReference = tableDefInstanceName + "." + tableDefField;

            if (i == 0) {
                methodBody.append("\n        .where(").append(fullFieldReference).append(".").append(queryType).append("(").append(field).append("))");
            } else {
                String connector = i - 1 < connectors.size() ? connectors.get(i - 1) : "and";
                if ("or".equalsIgnoreCase(connector)) {
                    methodBody.append("\n        .or(").append(fullFieldReference).append(".").append(queryType).append("(").append(field).append("))");
                } else {
                    methodBody.append("\n        .and(").append(fullFieldReference).append(".").append(queryType).append("(").append(field).append("))");
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
        return generateSingleFieldMethodBody(methodPrefix, fieldInfo, fieldToTableDefMapping, simpleEntityName, "eq");
    }

    /**
     * 生成单字段方法体（支持指定查询类型）
     */
    private String generateSingleFieldMethodBody(String methodPrefix, FieldInfo fieldInfo,
                                                Map<String, String> fieldToTableDefMapping, String simpleEntityName, String queryType) {

        String tableDefField = fieldToTableDefMapping.get(fieldInfo.fieldName);
        if (tableDefField == null) {
            tableDefField = fieldInfo.fieldName.toUpperCase();
        }

        // 获取TableDef静态变量名
        String tableDefInstanceName = getTableDefInstanceName(simpleEntityName);
        String fullFieldReference = tableDefInstanceName + "." + tableDefField;

        StringBuilder methodBody = new StringBuilder();
        methodBody.append("QueryWrapper queryWrapper = QueryWrapper.create()\n");
        methodBody.append("        .where(").append(fullFieldReference).append(".").append(queryType).append("(").append(fieldInfo.fieldName).append("));\n");
        methodBody.append(generateReturnStatement(methodPrefix, "queryWrapper", simpleEntityName));

        return methodBody.toString();
    }

    /**
     * 生成整个实体的方法体（使用Condition条件判断）
     */
    private String generateEntityMethodBody(String methodPrefix, Map<String, FieldInfo> entityFields,
                                          Map<String, String> fieldToTableDefMapping, String simpleEntityName,
                                          PsiClass entityClass) {
        return generateEntityMethodBody(methodPrefix, entityFields, fieldToTableDefMapping, simpleEntityName, entityClass, "eq");
    }

    /**
     * 生成整个实体的方法体（使用Condition条件判断，支持指定默认查询类型）
     */
    private String generateEntityMethodBody(String methodPrefix, Map<String, FieldInfo> entityFields,
                                          Map<String, String> fieldToTableDefMapping, String simpleEntityName,
                                          PsiClass entityClass, String defaultQueryType) {

        // 获取TableDef静态变量名
        String tableDefInstanceName = getTableDefInstanceName(simpleEntityName);

        StringBuilder methodBody = new StringBuilder();
        methodBody.append("QueryWrapper queryWrapper = QueryWrapper.create()");

        // 按照实体类中字段的定义顺序处理
        List<FieldInfo> orderedFields = new ArrayList<>();
        PsiField[] fields = entityClass.getAllFields();
        for (PsiField field : fields) {
            // 跳过静态字段和常量
            if (field.hasModifierProperty(PsiModifier.STATIC) ||
                field.hasModifierProperty(PsiModifier.FINAL)) {
                continue;
            }

            String fieldName = field.getName();
            FieldInfo fieldInfo = entityFields.get(fieldName);
            String tableDefField = fieldToTableDefMapping.get(fieldName);

            // 只处理在APT中确实存在的字段
            if (fieldInfo != null && tableDefField != null) {
                orderedFields.add(fieldInfo);
            }
        }

        boolean isFirst = true;
        for (FieldInfo fieldInfo : orderedFields) {
            String tableDefField = fieldToTableDefMapping.get(fieldInfo.fieldName);
            String fullFieldReference = tableDefInstanceName + "." + tableDefField;
            String entityFieldAccess = StrUtil.lowerFirst(simpleEntityName) + ".get" + StrUtil.upperFirst(fieldInfo.fieldName) + "()";

            // 根据默认查询类型和字段类型选择查询方法
            String queryMethod;
            if ("like".equals(defaultQueryType)) {
                // like版本：String字段使用like，其他字段使用eq
                queryMethod = "String".equals(fieldInfo.fieldType) ? "like" : "eq";
            } else {
                // eq版本：所有字段都使用eq
                queryMethod = "eq";
            }

            if (isFirst) {
                methodBody.append("\n        .where(").append(fullFieldReference).append(".").append(queryMethod).append("(").append(entityFieldAccess).append(", ObjectUtil.isNotNull(").append(entityFieldAccess).append(")))");
                isFirst = false;
            } else {
                methodBody.append("\n        .and(").append(fullFieldReference).append(".").append(queryMethod).append("(").append(entityFieldAccess).append(", ObjectUtil.isNotNull(").append(entityFieldAccess).append(")))");
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
        // 为了向后兼容，使用默认的查询类型映射
        List<FieldQueryInfo> fieldQueryInfos = parseFieldQueryInfoFromCompletion(completion, null);
        List<String> fields = new ArrayList<>();
        for (FieldQueryInfo info : fieldQueryInfos) {
            fields.add(info.fieldName);
        }
        return fields;
    }

    /**
     * 从补全中解析字段查询信息（包含查询类型）
     */
    private List<FieldQueryInfo> parseFieldQueryInfoFromCompletion(String completion, Project project) {
        List<FieldQueryInfo> fieldQueryInfos = new ArrayList<>();
        String[] parts = completion.split("(?=And|Or)");

        for (String part : parts) {
            String fieldPart;
            if (part.startsWith("And") || part.startsWith("Or")) {
                fieldPart = part.substring(part.startsWith("And") ? 3 : 2);
            } else {
                fieldPart = part;
            }

            if (StrUtil.isNotEmpty(fieldPart)) {
                // 解析字段名和查询类型
                FieldQueryInfo queryInfo = parseFieldAndQueryType(fieldPart, project);
                fieldQueryInfos.add(queryInfo);
            }
        }

        return fieldQueryInfos;
    }

    /**
     * 解析字段名和查询类型
     */
    private FieldQueryInfo parseFieldAndQueryType(String fieldPart, Project project) {
        String fieldName = fieldPart;
        String queryType = "eq"; // 默认使用eq

        // 动态获取QueryColumn的方法名作为查询类型
        Map<String, String> queryTypeMapping = getQueryColumnMethods(project);

        // 检查是否有查询类型后缀
        for (Map.Entry<String, String> entry : queryTypeMapping.entrySet()) {
            String suffix = entry.getKey();
            String methodName = entry.getValue();
            if (fieldPart.endsWith(suffix)) {
                fieldName = fieldPart.substring(0, fieldPart.length() - suffix.length());
                queryType = methodName;
                break;
            }
        }

        return new FieldQueryInfo(StrUtil.lowerFirst(fieldName), queryType);
    }

    /**
     * 获取QueryColumn类的public方法名，用作查询类型
     */
    private Map<String, String> getQueryColumnMethods(Project project) {
        // 如果project为null，直接使用默认映射
        if (project == null) {
            Map<String, String> methodMapping = new HashMap<>();
            addDefaultQueryMethods(methodMapping);
            return methodMapping;
        }

        String projectKey = project.getLocationHash();

        // 检查项目缓存
        Map<String, String> cachedMethods = projectQueryColumnMethodsCache.get(projectKey);
        if (cachedMethods != null) {
            return cachedMethods;
        }

        // 缓存不存在，需要初始化
        Map<String, String> methodMapping = initializeQueryColumnMethods(project);

        // 缓存结果
        projectQueryColumnMethodsCache.put(projectKey, methodMapping);

        return methodMapping;
    }

    /**
     * 初始化QueryColumn方法映射
     */
    private Map<String, String> initializeQueryColumnMethods(Project project) {
        Map<String, String> methodMapping = new HashMap<>();

        try {
            // 通过PSI查找QueryColumn类
            JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
            PsiClass queryColumnClass = psiFacade.findClass("com.mybatisflex.core.query.QueryColumn",
                GlobalSearchScope.allScope(project));

            if (queryColumnClass != null) {
                // 获取所有public方法
                PsiMethod[] methods = queryColumnClass.getAllMethods();
                Set<String> methodNames = new HashSet<>();

                for (PsiMethod method : methods) {
                    // 只获取public方法，排除构造方法、静态方法和Object的方法
                    if (method.hasModifierProperty(PsiModifier.PUBLIC) &&
                        !method.isConstructor() &&
                        !method.hasModifierProperty(PsiModifier.STATIC) &&
                        !isObjectMethod(method.getName())) {

                        String methodName = method.getName();

                        // 过滤掉不适合用于方法名生成的方法
                        if (!isExcludedQueryMethod(methodName)) {
                            methodNames.add(methodName);
                        }
                    }
                }

                // 将方法名转换为后缀映射
                for (String methodName : methodNames) {
                    String suffix = methodNameToSuffix(methodName);
                    if (suffix != null) {
                        methodMapping.put(suffix, methodName);
                    }
                }
            }

            // 如果没有找到方法或者为空，使用默认映射
            if (methodMapping.isEmpty()) {
                addDefaultQueryMethods(methodMapping);
            }

        } catch (Exception e) {
            // 如果出错，使用默认映射
            addDefaultQueryMethods(methodMapping);
        }

        return methodMapping;
    }

    /**
     * 检查是否是实体名模式
     */
    private boolean isEntityNamePattern(String fieldsPart, String simpleEntityName) {
        // 检查是否是实体名本身或实体名+Like
        boolean result = fieldsPart.equals(simpleEntityName) || fieldsPart.equals(simpleEntityName + "Like");
        System.out.println("isEntityNamePattern: fieldsPart='" + fieldsPart + "', simpleEntityName='" + simpleEntityName + "', result=" + result);
        return result;
    }

    /**
     * 为实体名模式生成方法补全
     */
    private void generateEntityMethodsForCompletion(CompletionResultSet result, String methodPrefix, String fieldsPart,
                                                  String simpleEntityName, Map<String, FieldInfo> entityFields,
                                                  Map<String, String> fieldToTableDefMapping, String entityQualifiedName,
                                                  PsiClass currentServiceClass) {

        System.out.println("generateEntityMethodsForCompletion called with:");
        System.out.println("  methodPrefix: " + methodPrefix);
        System.out.println("  fieldsPart: " + fieldsPart);
        System.out.println("  simpleEntityName: " + simpleEntityName);

        boolean isLikeVersion = fieldsPart.endsWith("Like");
        String queryType = isLikeVersion ? "like" : "eq";
        String methodName = methodPrefix + fieldsPart;

        System.out.println("  methodName: " + methodName);
        System.out.println("  currentServiceClass: " + (currentServiceClass != null ? currentServiceClass.getName() : "null"));

        // 检查方法是否已存在
        if (currentServiceClass != null && methodExists(currentServiceClass, methodName)) {
            System.out.println("  Method already exists, skipping");
            return; // 跳过已存在的方法
        }

        String methodSignature = methodName + "()";
        String returnType = getReturnTypeForMethodPrefix(methodPrefix, simpleEntityName);
        String methodBody = generateEntityMethodBody(methodPrefix, entityFields, fieldToTableDefMapping, simpleEntityName, null, queryType);

        LookupElement methodElement = LookupElementBuilder.create(methodSignature)
                .withPresentableText(methodSignature)
                .withTypeText(returnType)
                .withTailText(" { " + methodBody + " }", true)
                .withIcon(Icons.FLEX)
                .withInsertHandler((context, item) -> {
                    // 为整个实体方法创建特殊的字段映射
                    Map<String, FieldInfo> entityMethodFields = new HashMap<>();
                    entityMethodFields.put("entity", new FieldInfo("entity", simpleEntityName, "entity"));

                    String fieldsCompletion = isLikeVersion ? "EntityLike" : "Entity";
                    insertMethodImplementationWithCursor(context, methodName, methodBody, returnType, fieldsCompletion, entityMethodFields, entityQualifiedName, fieldToTableDefMapping);
                });

        result.addElement(methodElement);
    }

    /**
     * 检查是否是需要排除的QueryColumn方法
     */
    private boolean isExcludedQueryMethod(String methodName) {
        // 定义需要排除的方法列表
        Set<String> excludedMethods = new HashSet<>();

        // 排序相关方法
        excludedMethods.add("asc");
        excludedMethods.add("desc");

        // 别名相关方法
        excludedMethods.add("as");

        // 数学运算方法
        excludedMethods.add("add");
        excludedMethods.add("subtract");
        excludedMethods.add("multiply");
        excludedMethods.add("divide");
        excludedMethods.add("mod");

        // 函数相关方法
        excludedMethods.add("sum");
        excludedMethods.add("avg");
        excludedMethods.add("max");
        excludedMethods.add("min");
        excludedMethods.add("count");
        excludedMethods.add("distinct");

        // 其他不适合用于方法名的方法
        excludedMethods.add("when");
        excludedMethods.add("then");
        excludedMethods.add("otherwise");
        excludedMethods.add("concat");
        excludedMethods.add("substring");
        excludedMethods.add("length");
        excludedMethods.add("upper");
        excludedMethods.add("lower");
        excludedMethods.add("trim");
        excludedMethods.add("abs");
        excludedMethods.add("round");
        excludedMethods.add("floor");
        excludedMethods.add("ceil");
        excludedMethods.add("getTable");
        excludedMethods.add("getName");
        excludedMethods.add("getAlias");
        excludedMethods.add("setTable");
        excludedMethods.add("setName");
        excludedMethods.add("setAlias");

        return excludedMethods.contains(methodName);
    }

    /**
     * 添加默认的查询方法映射
     */
    private void addDefaultQueryMethods(Map<String, String> methodMapping) {
        methodMapping.put("Like", "like");
        methodMapping.put("GreaterThan", "gt");
        methodMapping.put("LessThan", "lt");
        methodMapping.put("GreaterThanEqual", "ge");
        methodMapping.put("LessThanEqual", "le");
        methodMapping.put("NotEqual", "ne");
        methodMapping.put("IsNull", "isNull");
        methodMapping.put("IsNotNull", "isNotNull");
        methodMapping.put("In", "in");
        methodMapping.put("NotIn", "notIn");
        methodMapping.put("Between", "between");
        methodMapping.put("NotBetween", "notBetween");
    }

    /**
     * 判断是否是Object类的方法
     */
    private boolean isObjectMethod(String methodName) {
        return "equals".equals(methodName) || "hashCode".equals(methodName) ||
               "toString".equals(methodName) || "clone".equals(methodName) ||
               "finalize".equals(methodName) || "getClass".equals(methodName) ||
               "notify".equals(methodName) || "notifyAll".equals(methodName) ||
               "wait".equals(methodName);
    }

    /**
     * 将方法名转换为后缀
     */
    private String methodNameToSuffix(String methodName) {
        // 将方法名转换为驼峰后缀
        if (methodName.length() <= 1) {
            return null;
        }

        // 首字母大写
        return StrUtil.upperFirst(methodName);
    }

    // 按项目缓存QueryColumn方法映射
    private static final Map<String, Map<String, String>> projectQueryColumnMethodsCache = new HashMap<>();

    /**
     * 预加载QueryColumn方法缓存（供项目启动监听器调用）
     */
    public static void preloadQueryColumnMethods(Project project) {
        if (project == null) {
            return;
        }

        String projectKey = project.getLocationHash();

        // 如果已经缓存过，直接返回
        if (projectQueryColumnMethodsCache.containsKey(projectKey)) {
            return;
        }

        // 创建临时实例来调用实例方法
        ServiceCompletionContributor contributor = new ServiceCompletionContributor();
        Map<String, String> methodMapping = contributor.initializeQueryColumnMethods(project);

        // 手动设置缓存
        projectQueryColumnMethodsCache.put(projectKey, methodMapping);
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
                Project project = context.getProject();

                // 确保文档已提交
                PsiDocumentManager.getInstance(project).commitAllDocuments();

                // 获取当前光标位置
                int startOffset = context.getStartOffset();
                int tailOffset = context.getTailOffset();

                // 获取当前类，判断是接口还是实现类
                PsiFile psiFile = context.getFile();
                PsiElement elementAtCaret = psiFile.findElementAt(startOffset);
                PsiClass currentClass = PsiTreeUtil.getParentOfType(elementAtCaret, PsiClass.class);
                boolean isInterface = currentClass != null && currentClass.isInterface();

                // 解析字段并生成参数列表
                StringBuilder paramBuilder = new StringBuilder();

                // 检查是否是整个实体方法（通过fieldsCompletion判断）
                if ("Entity".equals(fieldsCompletion) || "EntityLike".equals(fieldsCompletion)) {
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

                if (isInterface) {
                    // 在接口中，使用PSI操作生成完整的方法声明

                    // 先替换用户输入为方法名和空括号
                    String userInputReplacement = methodName + "()";
                    document.replaceString(startOffset, tailOffset, userInputReplacement);

                    // 计算光标在括号内的位置
                    int cursorPosition = startOffset + methodName.length() + 1;

                    // 提交文档更改，确保PSI同步
                    PsiDocumentManager.getInstance(project).commitDocument(document);

                    // 使用PSI操作在接口中生成完整的方法声明
                    PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

                    // 找到插入位置
                    PsiElement elementAtNewCaret = psiFile.findElementAt(startOffset);
                    int insertOffset = findInsertPosition(elementAtNewCaret, document);

                    // 创建纯粹的方法声明
                    String methodDeclaration = returnType + " " + methodName + "(" + paramBuilder.toString() + ");";
                    PsiMethod interfaceMethod = factory.createMethodFromText(methodDeclaration, currentClass);

                    // 在接口中插入方法
                    PsiMethod addedMethod = (PsiMethod) currentClass.add(interfaceMethod);

                    // 添加JavaDoc注释
                    addJavaDocToMethod(addedMethod, methodName, fieldsCompletion, entityFields, entityQualifiedName, factory);

                    // 同时在实现类中生成方法实现
                    generateImplementationMethodAsync(context.getProject(), currentClass, methodName, returnType,
                                                    paramBuilder.toString(), methodBody, fieldsCompletion, entityFields, entityQualifiedName);

                    // 将光标定位到用户输入的括号内
                    editor.getCaretModel().moveToOffset(cursorPosition);
                } else {
                    // 在实现类中，找到合适的插入位置生成完整的方法实现
                    int insertOffset = findInsertPosition(elementAtCaret, document);

                    // 替换用户输入的内容为方法名和空括号
                    String userInputReplacement = methodName + "()";
                    document.replaceString(startOffset, tailOffset, userInputReplacement);

                    // 计算光标在括号内的位置（在替换后）
                    int cursorPosition = startOffset + methodName.length() + 1; // +1 for opening parenthesis

                    // 生成方法注释
                    String methodComment = generateMethodComment(methodName, fieldsCompletion, entityFields, entityQualifiedName);

                    // 生成完整的方法实现
                    StringBuilder methodCode = new StringBuilder();
                    methodCode.append("\n\n");
                    methodCode.append(methodComment);
                    methodCode.append("    public ").append(returnType).append(" ").append(methodName).append("(");
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
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * 异步在实现类中生成方法实现
     */
    private void generateImplementationMethodAsync(Project project, PsiClass serviceInterface, String methodName,
                                                 String returnType, String parameters, String methodBody,
                                                 String fieldsCompletion, Map<String, FieldInfo> entityFields, String entityQualifiedName) {
        // 先在读取操作中获取必要信息
        ApplicationManager.getApplication().runReadAction(() -> {
            try {
                // 查找实现类
                PsiClass serviceImpl = findServiceImplementation(serviceInterface, project);
                if (serviceImpl == null) {
                    System.err.println("Service implementation not found for interface: " + serviceInterface.getQualifiedName());
                    return;
                }

                System.out.println("Found service implementation: " + serviceImpl.getQualifiedName());

                // 在EDT线程中执行写入操作
                ApplicationManager.getApplication().invokeLater(() -> {
                    WriteCommandAction.runWriteCommandAction(project, () -> {
                        try {
                            // 确保文档已提交
                            PsiDocumentManager.getInstance(project).commitAllDocuments();

                            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

                            // 生成纯粹的方法实现，不包含注释
                            String methodText = "@Override\n" +
                                              "public " + returnType + " " + methodName + "(" + parameters + ") {\n" +
                                              "    " + methodBody.replace("\n", "\n    ") + "\n" +
                                              "}";

                            System.out.println("Generated method text: " + methodText);

                            PsiMethod method = factory.createMethodFromText(methodText, serviceImpl);

                            // 在实现类中插入方法
                            PsiMethod addedMethod = (PsiMethod) serviceImpl.add(method);

                            // 添加JavaDoc注释到实现方法（使用与接口相同的逻辑）
                            addJavaDocToMethod(addedMethod, methodName, fieldsCompletion, entityFields, entityQualifiedName, factory);


                        } catch (Exception e) {
                            System.err.println("Error generating method implementation: " + e.getMessage());
                            e.printStackTrace();
                        }
                    });
                });

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * 为方法添加JavaDoc注释
     */
    private void addJavaDocToMethod(PsiMethod method, String methodName, String fieldsCompletion,
                                  Map<String, FieldInfo> entityFields, String entityQualifiedName,
                                  PsiElementFactory factory) {
        try {
            // 生成方法描述
            String description = generateMethodDescription(methodName, extractMethodPrefixFromMethodName(methodName),
                                                         fieldsCompletion, entityFields, entityQualifiedName);

            // 创建标准的JavaDoc注释文本
            String javaDocText = "/** " + description + " */";

            // 使用PSI工厂创建JavaDoc注释
            PsiComment javaDocComment = factory.createCommentFromText(javaDocText, method);

            // 在方法前添加注释
            PsiElement parent = method.getParent();
            parent.addBefore(javaDocComment, method);

        } catch (Exception e) {
            System.err.println("Error adding JavaDoc to method: " + e.getMessage());
            e.printStackTrace();
        }
    }



    /**
     * 生成方法注释
     */
    private String generateMethodComment(String methodName, String fieldsCompletion,
                                       Map<String, FieldInfo> entityFields, String entityQualifiedName) {
        StringBuilder comment = new StringBuilder();
        comment.append("    /**\n");

        // 解析方法名，生成描述
        String methodPrefix = extractMethodPrefixFromMethodName(methodName);
        String description = generateMethodDescription(methodName, methodPrefix, fieldsCompletion, entityFields, entityQualifiedName);

        comment.append("     * ").append(description).append("\n");
        comment.append("     */\n");

        return comment.toString();
    }

    /**
     * 从方法名中提取方法前缀
     */
    private String extractMethodPrefixFromMethodName(String methodName) {
        String lowerMethodName = methodName.toLowerCase();
        String[] queryPrefixes = {"listby", "findby", "getby", "countby", "existsby", "removeby", "deleteby"};
        String[] originalPrefixes = {"listBy", "findBy", "getBy", "countBy", "existsBy", "removeBy", "deleteBy"};

        for (int i = 0; i < queryPrefixes.length; i++) {
            if (lowerMethodName.startsWith(queryPrefixes[i])) {
                return originalPrefixes[i];
            }
        }
        return "listBy"; // 默认值
    }

    /**
     * 生成方法描述
     */
    private String generateMethodDescription(String methodName, String methodPrefix, String fieldsCompletion,
                                           Map<String, FieldInfo> entityFields, String entityQualifiedName) {
        String simpleEntityName = StrUtil.subAfter(entityQualifiedName, ".", true);

        // 根据方法前缀生成动作描述
        String action = getActionDescription(methodPrefix);

        if ("Entity".equals(fieldsCompletion)) {
            // 整个实体方法（eq版本）
            return action + simpleEntityName + "数据";
        } else if ("EntityLike".equals(fieldsCompletion)) {
            // 整个实体方法（like版本）
            return "模糊" + action + simpleEntityName + "数据";
        } else {
            // 解析字段名，生成描述
            List<String> fields = parseFieldsFromCompletion(fieldsCompletion);
            if (fields.isEmpty()) {
                return action + simpleEntityName + "数据";
            }

            StringBuilder fieldDesc = new StringBuilder();
            for (int i = 0; i < fields.size(); i++) {
                String field = fields.get(i);
                FieldInfo fieldInfo = entityFields.get(field);
                if (fieldInfo != null) {
                    if (i > 0) {
                        fieldDesc.append("和");
                    }
                    fieldDesc.append(getFieldDescription(fieldInfo.fieldName, entityQualifiedName, ProjectUtils.getCurrentProject()));
                }
            }

            return "根据" + fieldDesc.toString() + action + simpleEntityName + "数据";
        }
    }

    /**
     * 根据方法前缀获取动作描述
     */
    private String getActionDescription(String methodPrefix) {
        switch (methodPrefix.toLowerCase()) {
            case "listby":
            case "findby":
                return "查询";
            case "getby":
                return "获取";
            case "countby":
                return "统计";
            case "existsby":
                return "判断是否存在";
            case "removeby":
            case "deleteby":
                return "删除";
            default:
                return "查询";
        }
    }

    /**
     * 获取字段的中文描述（动态获取字段上的文档注释）
     */
    private String getFieldDescription(String fieldName, String entityQualifiedName, Project project) {
        try {
            // 首先尝试从实体类的字段注释中获取描述
            String commentDescription = getFieldCommentDescription(fieldName, entityQualifiedName, project);
            if (StrUtil.isNotEmpty(commentDescription)) {
                return commentDescription;
            }
        } catch (Exception e) {
            // 如果获取注释失败，继续使用默认映射
        }

        // 如果没有找到注释，使用常见字段名的中文映射
        return getDefaultFieldDescription(fieldName);
    }

    /**
     * 从实体类字段的文档注释中获取描述
     */
    private String getFieldCommentDescription(String fieldName, String entityQualifiedName, Project project) {
        try {
            JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
            PsiClass entityClass = psiFacade.findClass(entityQualifiedName, GlobalSearchScope.allScope(project));
            if (entityClass == null) {
                return null;
            }

            // 查找字段
            PsiField field = entityClass.findFieldByName(fieldName, false);
            if (field == null) {
                return null;
            }

            // 获取字段的文档注释
            PsiDocComment docComment = field.getDocComment();
            if (docComment != null) {
                String commentText = docComment.getText();
                // 提取注释中的描述文本
                return extractDescriptionFromComment(commentText);
            }

            // 如果没有文档注释，尝试获取行注释
            PsiComment[] comments = PsiTreeUtil.getChildrenOfType(field, PsiComment.class);
            if (comments != null && comments.length > 0) {
                for (PsiComment comment : comments) {
                    String commentText = comment.getText();
                    if (commentText.startsWith("//")) {
                        String description = commentText.substring(2).trim();
                        if (StrUtil.isNotEmpty(description)) {
                            return description;
                        }
                    }
                }
            }

        } catch (Exception e) {
            // 忽略异常，返回null
        }
        return null;
    }

    /**
     * 从注释文本中提取描述
     */
    private String extractDescriptionFromComment(String commentText) {
        if (StrUtil.isEmpty(commentText)) {
            return null;
        }

        // 移除注释标记
        String cleaned = commentText.replaceAll("/\\*\\*", "")
                                   .replaceAll("\\*/", "")
                                   .replaceAll("\\*", "")
                                   .trim();

        // 提取第一行非空内容作为描述
        String[] lines = cleaned.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (StrUtil.isNotEmpty(trimmed) && !trimmed.startsWith("@")) {
                return trimmed;
            }
        }

        return null;
    }

    /**
     * 获取默认的字段描述（基于字段名映射）
     */
    private String getDefaultFieldDescription(String fieldName) {
        // 常见字段名的中文映射
        switch (fieldName.toLowerCase()) {
            case "id":
                return "ID";
            case "name":
                return "名称";
            case "title":
                return "标题";
            case "createby":
            case "creator":
                return "创建人";
            case "createtime":
            case "createdat":
                return "创建时间";
            case "updateby":
            case "updater":
                return "更新人";
            case "updatetime":
            case "updatedat":
                return "更新时间";
            case "status":
                return "状态";
            case "type":
                return "类型";
            case "code":
                return "编码";
            case "key":
                return "键";
            case "value":
                return "值";
            case "description":
            case "desc":
                return "描述";
            case "remark":
                return "备注";
            case "email":
                return "邮箱";
            case "phone":
                return "电话";
            case "address":
                return "地址";
            case "url":
                return "链接";
            case "path":
                return "路径";
            case "version":
                return "版本";
            case "sort":
            case "order":
                return "排序";
            default:
                // 如果没有匹配的，使用驼峰转换
                return convertCamelToChineseDesc(fieldName);
        }
    }

    /**
     * 将驼峰命名转换为中文描述
     */
    private String convertCamelToChineseDesc(String camelCase) {
        if (StrUtil.isEmpty(camelCase)) {
            return camelCase;
        }

        // 简单处理：在大写字母前添加空格，然后返回原字段名
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                result.append(" ");
            }
            result.append(Character.toLowerCase(c));
        }
        return result.toString();
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