package club.bigtian.mf.plugin.core.contributor;

import club.bigtian.mf.plugin.core.generator.MethodBodyGenerator;
import club.bigtian.mf.plugin.core.generator.ServiceMethodGenerator;
import club.bigtian.mf.plugin.core.icons.Icons;
import club.bigtian.mf.plugin.core.parser.FieldParser;
import club.bigtian.mf.plugin.core.util.TableDefUtils;
import club.bigtian.mf.plugin.core.util.VirtualFileUtils;
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

    // 拆分后的组件
    private final ServiceMethodGenerator methodGenerator;
    private final MethodBodyGenerator bodyGenerator;
    private final FieldParser fieldParser;

    public ServiceCompletionContributor() {
        this.methodGenerator = new ServiceMethodGenerator();
        this.bodyGenerator = new MethodBodyGenerator();
        this.fieldParser = new FieldParser();
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
     * 判断是否是方法名补全（委托给FieldParser）
     */
    private boolean isMethodNameCompletion(String prefix) {
        return fieldParser.isMethodNameCompletion(prefix);
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
            generateMethodCompletions(result, methodPrefix, fieldsPart, entityFields, fieldToTableDefMapping, entityQualifiedName, currentServiceClass, project, entityClass);

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
                    String methodBody = bodyGenerator.generateSingleFieldMethodBody(queryPrefix, convertToNewFieldInfo(fieldInfo), fieldToTableDefMapping, simpleEntityName);
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
                                // 使用统一的方法生成
                                PsiElement element = context.getFile().findElementAt(context.getStartOffset());
                                PsiClass currentClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
                                Map<String, ServiceMethodGenerator.FieldInfo> newEntityFields = new HashMap<>();
                                newEntityFields.put(fieldInfo.fieldName, convertToNewFieldInfo(fieldInfo));
                                methodGenerator.generateUnifiedMethod(context, currentClass, methodName, methodBody, returnType,
                                                                    capitalizedFieldName, newEntityFields, entityQualifiedName, fieldToTableDefMapping);
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
     * 获取实体类字段信息（委托给FieldParser）
     */
    private Map<String, FieldInfo> getEntityFieldsInfo(PsiClass entityClass) {
        Map<String, ServiceMethodGenerator.FieldInfo> newFieldsInfo = fieldParser.getEntityFieldsInfo(entityClass);
        Map<String, FieldInfo> oldFieldsInfo = new HashMap<>();
        for (Map.Entry<String, ServiceMethodGenerator.FieldInfo> entry : newFieldsInfo.entrySet()) {
            oldFieldsInfo.put(entry.getKey(), convertToOldFieldInfo(entry.getValue()));
        }
        return oldFieldsInfo;
    }

    /**
     * 提取方法前缀（委托给FieldParser）
     */
    private String extractMethodPrefix(String prefix) {
        return fieldParser.extractMethodPrefix(prefix);
    }

    /**
     * 生成方法补全
     */
    private void generateMethodCompletions(CompletionResultSet result, String methodPrefix, String fieldsPart,
                                         Map<String, FieldInfo> entityFields, Map<String, String> fieldToTableDefMapping,
                                         String entityQualifiedName, PsiClass currentServiceClass, Project project, PsiClass entityClass) {

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
        if (fieldParser.isEntityNamePattern(fieldsPart, simpleEntityName)) {
            System.out.println("  Detected entity name pattern, calling generateEntityMethodsForCompletion");
            generateEntityMethodsForCompletion(result, methodPrefix, fieldsPart, simpleEntityName, entityFields, fieldToTableDefMapping, entityQualifiedName, currentServiceClass, entityClass);
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

            // 生成包含参数的方法签名
            String methodParameters = generateMethodParametersFromName(fullMethodName, entityFields);
            String methodSignature = fullMethodName + "(" + methodParameters + ")";
            String methodBody = generateMethodBody(methodPrefix, completion, fieldToTableDefMapping, simpleEntityName, project);
            String returnType = getReturnTypeForMethodPrefix(methodPrefix, simpleEntityName);

            LookupElement methodElement = LookupElementBuilder.create(methodSignature)
                    .withPresentableText(methodSignature)
                    .withTypeText(returnType)
                    .withTailText(" { " + methodBody + " }", true)
                    .withIcon(Icons.FLEX)
                    .withInsertHandler((context, item) -> {
                        // 使用统一的方法生成逻辑
                        PsiElement element = context.getFile().findElementAt(context.getStartOffset());
                        PsiClass currentClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
                        Map<String, ServiceMethodGenerator.FieldInfo> newEntityFields = convertEntityFieldsMap(entityFields);
                        methodGenerator.generateUnifiedMethod(context, currentClass, fullMethodName, methodBody, returnType,
                                                             completion, newEntityFields, entityQualifiedName, fieldToTableDefMapping);
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

            // 统一使用methodGenerator.generateUnifiedMethod
            Map<String, ServiceMethodGenerator.FieldInfo> newEntityFields = convertEntityFieldsMap(entityFields);
            String entityMethodBody = bodyGenerator.generateEntityMethodBody(queryPrefix, newEntityFields, fieldToTableDefMapping, simpleEntityName, entityClass, "eq");
            builder = builder.withTailText(" { generate in interface and implementation }", true)
                    .withInsertHandler((context, item) -> {
                        // 为整个实体方法创建特殊的字段映射
                        Map<String, FieldInfo> entityMethodFields = new HashMap<>();
                        entityMethodFields.put("entity", new FieldInfo("entity", simpleEntityName, "entity"));

                        // 统一使用methodGenerator.generateUnifiedMethod，不再区分接口和实现类
                        PsiElement element = context.getFile().findElementAt(context.getStartOffset());
                        PsiClass currentClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
                        Map<String, ServiceMethodGenerator.FieldInfo> newEntityFields1 = convertEntityFieldsMap(entityMethodFields);
                        methodGenerator.generateUnifiedMethod(context, currentClass, entityMethodName, entityMethodBody, returnType,
                                                             "Entity", newEntityFields1, entityQualifiedName, fieldToTableDefMapping);
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

            // 统一使用methodGenerator.generateUnifiedMethod
            Map<String, ServiceMethodGenerator.FieldInfo> newEntityFieldsLike = convertEntityFieldsMap(entityFields);
            String entityMethodBodyLike = bodyGenerator.generateEntityMethodBody(queryPrefix, newEntityFieldsLike, fieldToTableDefMapping, simpleEntityName, entityClass, "like");
            builderLike = builderLike.withTailText(" { generate in interface and implementation }", true)
                    .withInsertHandler((context, item) -> {
                        // 为整个实体方法创建特殊的字段映射
                        Map<String, FieldInfo> entityMethodFields = new HashMap<>();
                        entityMethodFields.put("entity", new FieldInfo("entity", simpleEntityName, "entity"));

                        // 统一使用methodGenerator.generateUnifiedMethod，不再区分接口和实现类
                        PsiElement element = context.getFile().findElementAt(context.getStartOffset());
                        PsiClass currentClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
                        Map<String, ServiceMethodGenerator.FieldInfo> newEntityFields = convertEntityFieldsMap(entityMethodFields);
                        methodGenerator.generateUnifiedMethod(context, currentClass, entityMethodNameLike, entityMethodBodyLike, returnType,
                                                             "EntityLike", newEntityFields, entityQualifiedName, fieldToTableDefMapping);
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
                            insertInterfaceMethodDeclaration(context, methodName, returnType, entityFields);
                        });
            } else {
                // 在实现类中，显示方法体
                String methodBody = bodyGenerator.generateSingleFieldMethodBody(methodPrefix, convertToNewFieldInfo(fieldInfo), fieldToTableDefMapping, simpleEntityName);
                Map<String, FieldInfo> singleFieldMap = new HashMap<>();
                singleFieldMap.put(fieldInfo.fieldName, fieldInfo);

                builder = builder.withTailText(" { " + methodBody + " }", true)
                        .withInsertHandler((context, item) -> {
                            // 使用统一的方法生成逻辑
                            PsiElement element = context.getFile().findElementAt(context.getStartOffset());
                            PsiClass currentClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
                            Map<String, ServiceMethodGenerator.FieldInfo> newSingleFieldMap = convertEntityFieldsMap(singleFieldMap);
                            methodGenerator.generateUnifiedMethod(context, currentClass, methodName, methodBody, returnType,
                                                                 capitalizedFieldName, newSingleFieldMap, entityQualifiedName, fieldToTableDefMapping);
                        });
            }

            result.addElement(builder);
        }
    }

    /**
     * 在接口中插入方法声明，并在实现类中生成方法实现
     */
    private void insertInterfaceMethodDeclaration(InsertionContext context, String methodName, String returnType, Map<String, FieldInfo> entityFields) {
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

                                    // 解析方法名生成参数（使用实体字段信息）
                                    String parameters = generateMethodParametersFromName(methodName, entityFields);

                                    // 生成接口方法声明
                                    String methodText = "/**\n     * " + bodyGenerator.generateMethodDescription(methodName) + "\n     */\n    " +
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
    private String generateMethodParametersFromName(String methodName, Map<String, FieldInfo> entityFields) {
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
                String paramType = getParameterTypeFromFieldName(fieldName, entityFields);
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
        String[] suffixes = {"LikeLeft", "LikeRight", "NotLikeLeft", "NotLikeRight", "NotLike", "Like",
                           "GreaterThanEqual", "LessThanEqual", "GreaterThan", "LessThan", "NotEqual",
                           "Gt", "Lt", "Ge", "Le", "Ne", "Eq", "In", "NotIn", "Between", "NotBetween",
                           "IsNull", "IsNotNull"};

        for (String suffix : suffixes) {
            if (fieldPart.endsWith(suffix)) {
                return fieldPart.substring(0, fieldPart.length() - suffix.length());
            }
        }

        return fieldPart;
    }

    /**
     * 根据字段名从实体字段信息中获取实际的参数类型
     */
    private String getParameterTypeFromFieldName(String fieldName, Map<String, FieldInfo> entityFields) {
        if (entityFields != null) {
            // 首先尝试直接匹配
            if (entityFields.containsKey(fieldName)) {
                FieldInfo fieldInfo = entityFields.get(fieldName);
                return fieldInfo.fieldType;
            }

            // 如果直接匹配失败，尝试小写首字母的版本
            String lowerFirstFieldName = StrUtil.lowerFirst(fieldName);
            if (entityFields.containsKey(lowerFirstFieldName)) {
                FieldInfo fieldInfo = entityFields.get(lowerFirstFieldName);
                return fieldInfo.fieldType;
            }
        }

        // 如果找不到字段信息，使用默认的类型推断作为后备方案
        return getDefaultParameterTypeFromFieldName(fieldName);
    }

    /**
     * 根据字段名进行默认的类型推断（后备方案）
     */
    private String getDefaultParameterTypeFromFieldName(String fieldName) {
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
            String methodText = "/**\n     * " + bodyGenerator.generateMethodDescription(methodName) + "\n     */\n" +
                              "    @Override\n" +
                              "    public " + returnType + " " + methodName + "(" + parameters + ") {\n" +
                              methodBody + "\n" +
                              "    }";

            PsiMethod method = factory.createMethodFromText(methodText, serviceImpl);

            // 在实现类中插入方法
            WriteCommandAction.runWriteCommandAction(project, () -> {
                serviceImpl.add(method);

                // 使用统一的导入处理方法
                methodGenerator.getImportManager().addRequiredImportsToFile(serviceImpl.getContainingFile(), methodBody, project);
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

            // 解析第一个字段和查询类型
            if (StrUtil.isNotEmpty(fieldsPart)) {
                String firstField = extractFirstField(fieldsPart);
                String queryType = extractQueryTypeFromFieldsPart(fieldsPart);
                String tableDefField = StrUtil.toUnderlineCase(firstField).toUpperCase();
                String paramName = StrUtil.lowerFirst(firstField);

                methodBody.append("\n                .where(").append(tableDefInstanceName).append(".").append(tableDefField).append(".").append(queryType).append("(").append(paramName).append("));");
            } else {
                methodBody.append(";");
            }

            methodBody.append("\n        ").append(bodyGenerator.generateReturnStatement(methodPrefix, "queryWrapper", ""));

            return methodBody.toString();

        } catch (Exception e) {
            return "        // TODO: 实现查询逻辑\n        return null;";
        }
    }

    /**
     * 从字段部分提取查询类型
     */
    private String extractQueryTypeFromFieldsPart(String fieldsPart) {
        // 获取查询方法映射
        Map<String, String> queryMethods = getQueryColumnMethods(null);

        // 检查是否有查询类型后缀
        for (Map.Entry<String, String> entry : queryMethods.entrySet()) {
            String suffix = entry.getKey();
            String methodName = entry.getValue();
            if (fieldsPart.endsWith(suffix)) {
                return methodName;
            }
        }

        // 默认使用 eq
        return "eq";
    }

    /**
     * 提取第一个字段名
     */
    private String extractFirstField(String fieldsPart) {
        // 移除查询类型后缀
        String[] suffixes = {"LikeLeft", "LikeRight", "NotLikeLeft", "NotLikeRight", "NotLike", "Like",
                           "GreaterThanEqual", "LessThanEqual", "GreaterThan", "LessThan", "NotEqual",
                           "Gt", "Lt", "Ge", "Le", "Ne", "Eq", "In", "NotIn", "Between", "NotBetween",
                           "IsNull", "IsNotNull"};
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

    // 这些方法已迁移到 MethodBodyGenerator 类中

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
            // 方法名已经完整，添加完整方法选项
            completions.add(fieldsPart);

            // 但仍然可以继续添加And/Or连接符
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
                    // 只添加基本字段补全，用户可以手动添加查询类型后缀
                    completions.add(fieldsPart + capitalizedFieldName);
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

                        // 添加查询类型后缀选项
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
            // 移除这部分逻辑，用户可以手动添加查询类型后缀

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

                                // 添加查询类型后缀选项
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
                                }
                            }
                        }

                        // 移除字段名+部分QueryColumn方法名的匹配逻辑，用户可以手动添加查询类型后缀
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
                    String fieldPart = currentField.toString();
                    // 移除查询类型后缀
                    String fieldName = removeQueryTypeSuffixFromField(fieldPart);
                    usedFields.add(StrUtil.lowerFirst(fieldName));
                    currentField.setLength(0);
                }
            } else {
                currentField.append(part);
            }
        }

        // 处理最后一个字段
        if (currentField.length() > 0) {
            String fieldPart = currentField.toString();
            // 移除查询类型后缀
            String fieldName = removeQueryTypeSuffixFromField(fieldPart);
            usedFields.add(StrUtil.lowerFirst(fieldName));
        }

        return usedFields;
    }

    /**
     * 从字段部分移除查询类型后缀
     */
    private String removeQueryTypeSuffixFromField(String fieldPart) {
        // 常见的查询类型后缀
        String[] suffixes = {"LikeLeft", "LikeRight", "NotLikeLeft", "NotLikeRight", "NotLike", "Like",
                           "GreaterThanEqual", "LessThanEqual", "GreaterThan", "LessThan", "NotEqual",
                           "Gt", "Lt", "Ge", "Le", "Ne", "Eq", "In", "NotIn", "Between", "NotBetween",
                           "IsNull", "IsNotNull"};

        for (String suffix : suffixes) {
            if (fieldPart.endsWith(suffix)) {
                return fieldPart.substring(0, fieldPart.length() - suffix.length());
            }
        }

        return fieldPart;
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
            addServiceCallMethodNameBasedCompletion(result, serviceInterface, entityQualifiedName, project, aptClass, prefix, parameters, entityFields, fieldToTableDefMapping, entityClass);

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
                                                       Map<String, String> fieldToTableDefMapping, PsiClass entityClass) {
        try {
            // 解析方法名前缀
            String methodPrefix = extractMethodPrefix(prefix);
            if (StrUtil.isEmpty(methodPrefix)) {
                // 如果没有完整的方法前缀，提供方法前缀补全
                addServiceCallMethodPrefixCompletions(result, serviceInterface, prefix, entityFields, fieldToTableDefMapping, entityQualifiedName, entityClass);
                return;
            }

            String fieldsPart = prefix.substring(methodPrefix.length());

            // 如果字段部分为空（即刚输入完方法前缀如listBy），直接显示所有字段补全
            if (StrUtil.isEmpty(fieldsPart)) {
                addServiceCallFieldCompletions(result, serviceInterface, methodPrefix, entityFields, fieldToTableDefMapping, entityQualifiedName);
                return;
            }

            // 生成可能的方法补全
            generateServiceCallMethodCompletions(result, serviceInterface, methodPrefix, fieldsPart, entityFields, fieldToTableDefMapping, entityQualifiedName, project, entityClass);

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

        // 首先添加实体方法（如 listByActivitiModel 和 listByActivitiModelLike）
        if ("listBy".equals(methodPrefix) || "findBy".equals(methodPrefix)) {
            // 生成eq版本的实体方法
            String entityMethodName = methodPrefix + simpleEntityName;
            if (!methodExists(serviceInterface, entityMethodName)) {
                String entityMethodSignature = entityMethodName + "()";
                String returnType = getReturnTypeForMethodPrefix(methodPrefix, simpleEntityName);
                String entityMethodBody = bodyGenerator.generateEntityMethodBody(methodPrefix, convertEntityFieldsMap(entityFields), fieldToTableDefMapping, simpleEntityName, null, "eq");

                LookupElement methodElement = LookupElementBuilder.create(entityMethodSignature)
                        .withPresentableText(entityMethodSignature)
                        .withTypeText(returnType)
                        .withTailText(" { generate in interface and implementation }", true)
                        .withIcon(Icons.FLEX)
                        .withInsertHandler((context, item) -> {
                            Map<String, FieldInfo> entityMethodFields = new HashMap<>();
                            entityMethodFields.put("entity", new FieldInfo("entity", simpleEntityName, "entity"));
                            // 这里是为Service接口设计的，应该保持原来的调用
                            generateServiceMethodInInterfaceAndImpl(context, serviceInterface, entityMethodName, entityMethodBody, returnType,
                                                                   "Entity", entityMethodFields, entityQualifiedName, fieldToTableDefMapping);
                        });

                result.addElement(methodElement);
            }

            // 生成like版本的实体方法
            String entityLikeMethodName = methodPrefix + simpleEntityName + "Like";
            if (!methodExists(serviceInterface, entityLikeMethodName)) {
                String entityLikeMethodSignature = entityLikeMethodName + "()";
                String returnType = getReturnTypeForMethodPrefix(methodPrefix, simpleEntityName);
                String entityLikeMethodBody = bodyGenerator.generateEntityMethodBody(methodPrefix, convertEntityFieldsMap(entityFields), fieldToTableDefMapping, simpleEntityName, null, "like");

                LookupElement methodElement = LookupElementBuilder.create(entityLikeMethodSignature)
                        .withPresentableText(entityLikeMethodSignature)
                        .withTypeText(returnType)
                        .withTailText(" { generate in interface and implementation }", true)
                        .withIcon(Icons.FLEX)
                        .withInsertHandler((context, item) -> {
                            Map<String, FieldInfo> entityMethodFields = new HashMap<>();
                            entityMethodFields.put("entity", new FieldInfo("entity", simpleEntityName, "entity"));
                            // 这里是为Service接口设计的，应该保持原来的调用
                            generateServiceMethodInInterfaceAndImpl(context, serviceInterface, entityLikeMethodName, entityLikeMethodBody, returnType,
                                                                   "EntityLike", entityMethodFields, entityQualifiedName, fieldToTableDefMapping);
                        });

                result.addElement(methodElement);
            }
        }

        // 然后添加字段方法
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

            // 只添加基本字段方法，用户可以手动添加查询类型后缀
            addServiceCallFieldMethod(result, serviceInterface, methodName, methodPrefix, fieldInfo,
                                    fieldToTableDefMapping, simpleEntityName, entityQualifiedName, "");
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
        String methodBody = bodyGenerator.generateSingleFieldMethodBody(methodPrefix, convertToNewFieldInfo(fieldInfo), fieldToTableDefMapping, simpleEntityName);

        LookupElement methodElement = LookupElementBuilder.create(methodSignature)
                .withPresentableText(methodSignature)
                .withTypeText(returnType)
                .withTailText(" { generate in interface and implementation }", true)
                .withIcon(Icons.FLEX)
                .withInsertHandler((context, item) -> {
                    Map<String, ServiceMethodGenerator.FieldInfo> singleFieldMap = new HashMap<>();
                    singleFieldMap.put(fieldInfo.fieldName, convertToNewFieldInfo(fieldInfo));
                    // 这里是为Service接口设计的，应该保持原来的调用
                    methodGenerator.generateServiceMethodInInterfaceAndImpl(context, serviceInterface, methodName, methodBody, returnType,
                                                           fieldInfo.fieldName, singleFieldMap, entityQualifiedName, fieldToTableDefMapping);
                });

        result.addElement(methodElement);
    }

    /**
     * 添加Service方法调用的方法前缀补全
     */
    private void addServiceCallMethodPrefixCompletions(CompletionResultSet result, PsiClass serviceInterface,
                                                     String prefix, Map<String, FieldInfo> entityFields,
                                                     Map<String, String> fieldToTableDefMapping, String entityQualifiedName, PsiClass entityClass) {

        String simpleEntityName = StrUtil.subAfter(entityQualifiedName, ".", true);
        String[] queryPrefixes = {"listBy", "findBy", "getBy", "countBy", "existsBy", "removeBy", "deleteBy"};
        String lowerPrefix = prefix.toLowerCase();

        for (String queryPrefix : queryPrefixes) {
            if (queryPrefix.toLowerCase().startsWith(lowerPrefix)) {

                // 添加整个实体的方法补全（如listBy + 实体名）
                if (queryPrefix.equals("listBy") || queryPrefix.equals("findBy")) {
                    // 生成两个版本：eq查询和like查询
                    generateServiceCallEntityMethods(result, serviceInterface, queryPrefix, simpleEntityName, entityFields, fieldToTableDefMapping, entityQualifiedName, entityClass);
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

                    String methodBody = bodyGenerator.generateSingleFieldMethodBody(queryPrefix, convertToNewFieldInfo(fieldInfo), fieldToTableDefMapping, simpleEntityName);
                    String returnType = getReturnTypeForMethodPrefix(queryPrefix, simpleEntityName);

                    // 使用统一的方法生成逻辑，先生成一个临时签名用于显示
                    String methodSignature = methodName + "()";

                    LookupElement methodElement = LookupElementBuilder.create(methodSignature)
                            .withPresentableText(methodSignature)
                            .withTypeText(returnType)
                            .withTailText(" { generate in interface and implementation }", true)
                            .withIcon(Icons.FLEX)
                            .withInsertHandler((context, item) -> {
                                // 使用统一的方法生成逻辑
                                PsiElement element = context.getFile().findElementAt(context.getStartOffset());
                                PsiClass currentClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
                                Map<String, ServiceMethodGenerator.FieldInfo> newEntityFields = new HashMap<>();
                                newEntityFields.put(fieldInfo.fieldName, convertToNewFieldInfo(fieldInfo));
                                methodGenerator.generateUnifiedMethod(context, currentClass, methodName, methodBody, returnType,
                                                                    capitalizedFieldName, newEntityFields, entityQualifiedName, fieldToTableDefMapping);
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
                                                    String entityQualifiedName, Project project, PsiClass entityClass) {

        String simpleEntityName = StrUtil.subAfter(entityQualifiedName, ".", true);

        // 如果字段部分为空，提供所有可能的字段组合
        if (StrUtil.isEmpty(fieldsPart)) {
            generateServiceCallSingleFieldMethods(result, serviceInterface, methodPrefix, entityFields, fieldToTableDefMapping, simpleEntityName, entityQualifiedName);
            return;
        }

        // 检查是否是实体名（如ActivitiModel或ActivitiModelLike）
        if (fieldParser.isEntityNamePattern(fieldsPart, simpleEntityName)) {
            generateServiceCallEntityMethodForCompletion(result, serviceInterface, methodPrefix, fieldsPart, simpleEntityName, entityFields, fieldToTableDefMapping, entityQualifiedName, entityClass);
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

            String methodBody = generateMethodBody(methodPrefix, completion, fieldToTableDefMapping, simpleEntityName, project);
            String returnType = getReturnTypeForMethodPrefix(methodPrefix, simpleEntityName);

            // 使用统一的方法生成逻辑，先生成一个临时签名用于显示
            String methodSignature = fullMethodName + "()";

            LookupElement methodElement = LookupElementBuilder.create(methodSignature)
                    .withPresentableText(methodSignature)
                    .withTypeText(returnType)
                    .withTailText(" { generate in interface and implementation }", true)
                    .withIcon(Icons.FLEX)
                    .withInsertHandler((context, item) -> {
                        // 对于Service方法调用，应该在Service接口和实现类中生成方法
                        Map<String, ServiceMethodGenerator.FieldInfo> newEntityFields = convertEntityFieldsMap(entityFields);
                        methodGenerator.generateServiceMethodInInterfaceAndImpl(context, serviceInterface, fullMethodName, methodBody, returnType,
                                                             completion, newEntityFields, entityQualifiedName, fieldToTableDefMapping);
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
            String methodBody = bodyGenerator.generateSingleFieldMethodBody(methodPrefix, convertToNewFieldInfo(fieldInfo), fieldToTableDefMapping, simpleEntityName);

            LookupElement methodElement = LookupElementBuilder.create(methodSignature)
                    .withPresentableText(methodSignature)
                    .withTypeText(returnType)
                    .withTailText(" { generate in interface and implementation }", true)
                    .withIcon(Icons.FLEX)
                    .withInsertHandler((context, item) -> {
                        Map<String, FieldInfo> singleFieldMap = new HashMap<>();
                        singleFieldMap.put(fieldInfo.fieldName, fieldInfo);
                        // 这里是为Service接口设计的，应该保持原来的调用
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
                                                String entityQualifiedName, PsiClass entityClass) {

        // 生成eq版本的实体方法
        String entityMethodName = queryPrefix + simpleEntityName;
        if (!methodExists(serviceInterface, entityMethodName)) {
            String entityMethodSignature = entityMethodName + "()";
            String returnType = getReturnTypeForMethodPrefix(queryPrefix, simpleEntityName);
            String entityMethodBody = generateEntityMethodBody(queryPrefix, entityFields, fieldToTableDefMapping, simpleEntityName, entityClass, "eq");

            LookupElement methodElement = LookupElementBuilder.create(entityMethodSignature)
                    .withPresentableText(entityMethodSignature)
                    .withTypeText(returnType)
                    .withTailText(" { generate in interface and implementation }", true)
                    .withIcon(Icons.FLEX)
                    .withInsertHandler((context, item) -> {
                        Map<String, FieldInfo> entityMethodFields = new HashMap<>();
                        entityMethodFields.put("entity", new FieldInfo("entity", simpleEntityName, "entity"));
                        // 这里是为Service接口设计的，应该保持原来的调用
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
            String entityMethodBodyLike = generateEntityMethodBody(queryPrefix, entityFields, fieldToTableDefMapping, simpleEntityName, entityClass, "like");

            LookupElement methodElementLike = LookupElementBuilder.create(entityMethodSignatureLike)
                    .withPresentableText(entityMethodSignatureLike)
                    .withTypeText(returnType)
                    .withTailText(" { generate in interface and implementation }", true)
                    .withIcon(Icons.FLEX)
                    .withInsertHandler((context, item) -> {
                        Map<String, FieldInfo> entityMethodFields = new HashMap<>();
                        entityMethodFields.put("entity", new FieldInfo("entity", simpleEntityName, "entity"));
                        // 这里是为Service接口设计的，应该保持原来的调用
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
                                                            String entityQualifiedName, PsiClass entityClass) {

        boolean isLikeVersion = fieldsPart.endsWith("Like");
        String queryType = isLikeVersion ? "like" : "eq";
        String methodName = methodPrefix + fieldsPart;

        // 检查方法是否已存在
        if (methodExists(serviceInterface, methodName)) {
            return; // 跳过已存在的方法
        }

        String returnType = getReturnTypeForMethodPrefix(methodPrefix, simpleEntityName);
        String methodBody = generateEntityMethodBody(methodPrefix, entityFields, fieldToTableDefMapping, simpleEntityName, entityClass, queryType);

        // 为整个实体方法创建特殊的字段映射
        Map<String, FieldInfo> entityMethodFields = new HashMap<>();
        entityMethodFields.put("entity", new FieldInfo("entity", simpleEntityName, "entity"));
        String fieldsCompletion = isLikeVersion ? "EntityLike" : "Entity";

        // 使用统一的方法生成逻辑，先生成一个临时签名用于显示
        String methodSignature = methodName + "()";

        LookupElement methodElement = LookupElementBuilder.create(methodSignature)
                .withPresentableText(methodSignature)
                .withTypeText(returnType)
                .withTailText(" { generate in interface and implementation }", true)
                .withIcon(Icons.FLEX)
                .withInsertHandler((context, item) -> {
                    // 使用统一的方法生成逻辑
                    PsiElement element = context.getFile().findElementAt(context.getStartOffset());
                    PsiClass currentClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
                    Map<String, ServiceMethodGenerator.FieldInfo> newEntityFields = new HashMap<>();
                    newEntityFields.put("entity", new ServiceMethodGenerator.FieldInfo("entity", simpleEntityName, "entity"));
                    methodGenerator.generateUnifiedMethod(context, currentClass, methodName, methodBody, returnType,
                                                         fieldsCompletion, newEntityFields, entityQualifiedName, fieldToTableDefMapping);
                });

        result.addElement(methodElement);
    }

    /**
     * 在Service接口和实现类中生成方法（委托给ServiceMethodGenerator）
     */
    private void generateServiceMethodInInterfaceAndImpl(InsertionContext context, PsiClass serviceInterface,
                                                       String methodName, String methodBody, String returnType,
                                                       String fieldsCompletion, Map<String, FieldInfo> entityFields,
                                                       String entityQualifiedName, Map<String, String> fieldToTableDefMapping) {
        // 转换FieldInfo类型
        Map<String, ServiceMethodGenerator.FieldInfo> newEntityFields = new HashMap<>();
        for (Map.Entry<String, FieldInfo> entry : entityFields.entrySet()) {
            newEntityFields.put(entry.getKey(), convertToNewFieldInfo(entry.getValue()));
        }

        // 委托给ServiceMethodGenerator
        methodGenerator.generateServiceMethodInInterfaceAndImpl(context, serviceInterface, methodName, methodBody,
                                                              returnType, fieldsCompletion, newEntityFields,
                                                              entityQualifiedName, fieldToTableDefMapping);
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
                // 只添加基本字段补全，用户可以手动添加查询类型后缀
                completions.add(fieldsPart + "And" + capitalizedFieldName);
                completions.add(fieldsPart + "Or" + capitalizedFieldName);
            }
        }
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
        methodBody.append(bodyGenerator.generateReturnStatement(methodPrefix, "queryWrapper", simpleEntityName));

        return methodBody.toString();
    }

    // 方法体生成相关方法已迁移到 MethodBodyGenerator 类中

    /**
     * 生成整个实体的方法体（委托给MethodBodyGenerator）
     */
    private String generateEntityMethodBody(String methodPrefix, Map<String, FieldInfo> entityFields,
                                          Map<String, String> fieldToTableDefMapping, String simpleEntityName,
                                          PsiClass entityClass, String defaultQueryType) {
        // 转换字段信息格式
        Map<String, ServiceMethodGenerator.FieldInfo> newEntityFields = convertEntityFieldsMap(entityFields);
        return bodyGenerator.generateEntityMethodBody(methodPrefix, newEntityFields, fieldToTableDefMapping, simpleEntityName, entityClass, defaultQueryType);
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

    // isEntityNamePattern 方法已迁移到 FieldParser 类中

    /**
     * 为实体名模式生成方法补全
     */
    private void generateEntityMethodsForCompletion(CompletionResultSet result, String methodPrefix, String fieldsPart,
                                                  String simpleEntityName, Map<String, FieldInfo> entityFields,
                                                  Map<String, String> fieldToTableDefMapping, String entityQualifiedName,
                                                  PsiClass currentServiceClass, PsiClass entityClass) {


        boolean isLikeVersion = fieldsPart.endsWith("Like");
        String queryType = isLikeVersion ? "like" : "eq";
        String methodName = methodPrefix + fieldsPart;


        // 检查方法是否已存在
        if (currentServiceClass != null && methodExists(currentServiceClass, methodName)) {
            System.out.println("  Method already exists, skipping");
            return; // 跳过已存在的方法
        }

        String methodSignature = methodName + "()";
        String returnType = getReturnTypeForMethodPrefix(methodPrefix, simpleEntityName);
        String methodBody = generateEntityMethodBody(methodPrefix, entityFields, fieldToTableDefMapping, simpleEntityName, entityClass, queryType);

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
                    // 使用统一的方法生成逻辑
                    PsiElement element = context.getFile().findElementAt(context.getStartOffset());
                    PsiClass currentClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
                    Map<String, ServiceMethodGenerator.FieldInfo> newEntityMethodFields = convertEntityFieldsMap(entityMethodFields);
                    methodGenerator.generateUnifiedMethod(context, currentClass, methodName, methodBody, returnType,
                                                         fieldsCompletion, newEntityMethodFields, entityQualifiedName, fieldToTableDefMapping);
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
        methodMapping.put("LikeLeft", "likeLeft");
        methodMapping.put("LikeRight", "likeRight");
        methodMapping.put("NotLike", "notLike");
        methodMapping.put("NotLikeLeft", "notLikeLeft");
        methodMapping.put("NotLikeRight", "notLikeRight");
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
        methodMapping.put("Eq", "eq");
        methodMapping.put("Gt", "gt");
        methodMapping.put("Lt", "lt");
        methodMapping.put("Ge", "ge");
        methodMapping.put("Le", "le");
        methodMapping.put("Ne", "ne");
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

    // generateReturnStatement 方法已迁移到 MethodBodyGenerator 类中

    /**
     * 根据方法前缀获取返回类型（委托给MethodBodyGenerator）
     */
    private String getReturnTypeForMethodPrefix(String methodPrefix, String simpleEntityName) {
        return bodyGenerator.getReturnTypeForMethodPrefix(methodPrefix, simpleEntityName);
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
                    } else {
                        System.out.println("Warning: No TableDef field found for entity field: " + entityFieldName);
                        // 如果在TableDef中找不到对应字段，则不添加到映射中，这样就不会生成查询条件
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return mapping;
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

    /**
     * 转换旧的FieldInfo到新的FieldInfo
     */
    private ServiceMethodGenerator.FieldInfo convertToNewFieldInfo(FieldInfo oldFieldInfo) {
        return new ServiceMethodGenerator.FieldInfo(oldFieldInfo.fieldName, oldFieldInfo.fieldType, oldFieldInfo.columnName);
    }

    /**
     * 转换新的FieldInfo到旧的FieldInfo
     */
    private FieldInfo convertToOldFieldInfo(ServiceMethodGenerator.FieldInfo newFieldInfo) {
        return new FieldInfo(newFieldInfo.fieldName, newFieldInfo.fieldType, newFieldInfo.columnName);
    }

    /**
     * 转换FieldInfo Map
     */
    private Map<String, ServiceMethodGenerator.FieldInfo> convertEntityFieldsMap(Map<String, FieldInfo> oldMap) {
        Map<String, ServiceMethodGenerator.FieldInfo> newMap = new HashMap<>();
        for (Map.Entry<String, FieldInfo> entry : oldMap.entrySet()) {
            newMap.put(entry.getKey(), convertToNewFieldInfo(entry.getValue()));
        }
        return newMap;
    }
}