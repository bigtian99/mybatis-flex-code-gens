package club.bigtian.mf.plugin.core.handler;

import cn.hutool.core.util.StrUtil;
import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.ProcessCanceledException;

import java.util.ArrayList;
import java.util.List;

import java.util.ArrayList;
import java.util.List;

/**
 * Service方法生成插入处理器
 * 当用户选择生成Service方法的补全项时，自动在接口和实现类中生成对应方法
 * Author: BigTian
 */
public class ServiceMethodGenerationInsertHandler implements InsertHandler<LookupElement> {

    private final PsiClass serviceInterface;
    private final String entityQualifiedName;
    private final String methodPrefix;

    public ServiceMethodGenerationInsertHandler(PsiClass serviceInterface, String entityQualifiedName, String methodPrefix) {
        this.serviceInterface = serviceInterface;
        this.entityQualifiedName = entityQualifiedName;
        this.methodPrefix = methodPrefix;
    }

    @Override
    public void handleInsert(InsertionContext context, LookupElement item) {
        Project project = context.getProject();
        String methodName = item.getLookupString();

        // 在后台线程中生成方法，避免阻塞UI
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            WriteCommandAction.runWriteCommandAction(project, () -> {
                try {
                    generateServiceMethods(project, methodName);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        });
    }

    /**
     * 生成Service方法
     */
    private void generateServiceMethods(Project project, String methodName) {
        try {
            // 查找Service实现类
            PsiClass serviceImpl = findServiceImplementation(serviceInterface, project);
            if (serviceImpl == null) {
                return;
            }

            // 生成返回类型
            String simpleEntityName = StrUtil.subAfter(entityQualifiedName, ".", true);
            String returnType = getReturnTypeForMethodPrefix(methodPrefix, simpleEntityName);

            // 在接口中添加方法声明
            addMethodToInterface(project, serviceInterface, methodName, returnType);

            // 在实现类中添加方法实现
            addMethodToImplementation(project, serviceImpl, methodName, returnType, methodPrefix);

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
                return null;
            }

            // 使用IDEA原生API查找接口的所有实现类
            GlobalSearchScope scope = GlobalSearchScope.allScope(project);

            // 查找直接实现该接口的类
            for (PsiClass implementor : ClassInheritorsSearch.search(serviceInterface, scope, false)) {
                // 检查进度是否被取消
                ProgressManager.checkCanceled();

                // 跳过接口，只要具体的实现类
                if (!implementor.isInterface() && !implementor.hasModifierProperty(PsiModifier.ABSTRACT)) {
                    return implementor;
                }
            }

            return null;
        } catch (ProcessCanceledException e) {
            // 进度被取消，直接返回null
            return null;
        } catch (Exception e) {
            // 其他异常，返回null
            return null;
        }
    }

    /**
     * 在接口中添加方法声明
     */
    private void addMethodToInterface(Project project, PsiClass serviceInterface, String methodName, String returnType) {
        try {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

            // 生成方法注释
            String comment = generateMethodComment(methodName);

            // 解析方法名并生成参数
            String parameters = generateMethodParameters(methodName);

            // 生成方法声明
            String methodText = comment + "\n    " + returnType + " " + methodName + "(" + parameters + ");";
            PsiMethod method = factory.createMethodFromText(methodText, serviceInterface);

            serviceInterface.add(method);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 在实现类中添加方法实现
     */
    private void addMethodToImplementation(Project project, PsiClass serviceImpl, String methodName, String returnType, String methodPrefix) {
        try {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

            // 生成方法注释
            String comment = generateMethodComment(methodName);

            // 解析方法名并生成参数
            String parameters = generateMethodParameters(methodName);

            // 生成方法体
            String methodBody = generateMethodBody(project, methodName, methodPrefix);

            // 生成完整方法
            String methodText = comment + "\n    @Override\n    public " + returnType + " " + methodName + "(" + parameters + ") {\n" + methodBody + "\n    }";
            PsiMethod method = factory.createMethodFromText(methodText, serviceImpl);

            serviceImpl.add(method);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 生成方法注释
     */
    private String generateMethodComment(String methodName) {
        // 从方法名提取查询意图
        String description = generateMethodDescription(methodName);
        return "    /**\n     * " + description + "\n     */";
    }

    /**
     * 生成方法参数
     */
    private String generateMethodParameters(String methodName) {
        try {
            // 提取方法前缀
            String methodPrefix = extractMethodPrefix(methodName);
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
                String paramType = getParameterType(fieldName);
                String paramName = StrUtil.lowerFirst(fieldName);
                parameters.add(paramType + " " + paramName);
            }

            return String.join(", ", parameters);

        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 从方法名解析字段名列表
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
     * 获取参数类型
     */
    private String getParameterType(String fieldName) {
        // 根据字段名推断类型，这里可以后续优化为从实体类获取真实类型
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
     * 提取方法前缀
     */
    private String extractMethodPrefix(String methodName) {
        String lowerMethodName = methodName.toLowerCase();
        String[] queryPrefixes = {"listby", "findby", "getby", "countby", "existsby", "removeby", "deleteby"};
        String[] originalPrefixes = {"listBy", "findBy", "getBy", "countBy", "existsBy", "removeBy", "deleteBy"};

        for (int i = 0; i < queryPrefixes.length; i++) {
            String queryPrefix = queryPrefixes[i];
            if (lowerMethodName.startsWith(queryPrefix)) {
                return originalPrefixes[i]; // 返回标准格式的前缀
            }
        }
        return "listBy"; // 默认返回listBy
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
     * 获取返回类型
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
     * 生成方法体
     */
    private String generateMethodBody(Project project, String methodName, String methodPrefix) {
        try {
            // 解析方法名获取字段信息
            String fieldsPart = methodName.substring(methodPrefix.length());
            if (StrUtil.isEmpty(fieldsPart)) {
                return generateSimpleMethodBody(methodPrefix);
            }

            // 生成基本的QueryWrapper查询结构
            String simpleEntityName = StrUtil.subAfter(entityQualifiedName, ".", true);
            String tableDefInstanceName = getTableDefInstanceName(simpleEntityName);

            StringBuilder methodBody = new StringBuilder();
            methodBody.append("        QueryWrapper queryWrapper = QueryWrapper.create()");

            // 解析字段和查询条件
            List<FieldCondition> conditions = parseFieldConditions(fieldsPart);

            for (int i = 0; i < conditions.size(); i++) {
                FieldCondition condition = conditions.get(i);
                String tableDefField = StrUtil.toUnderlineCase(condition.fieldName).toUpperCase();
                String paramName = StrUtil.lowerFirst(condition.fieldName);

                if (i == 0) {
                    methodBody.append("\n                .where(").append(tableDefInstanceName).append(".").append(tableDefField).append(".").append(condition.queryType).append("(").append(paramName).append("))");
                } else {
                    String connector = condition.connector.toLowerCase();
                    methodBody.append("\n                .").append(connector).append("(").append(tableDefInstanceName).append(".").append(tableDefField).append(".").append(condition.queryType).append("(").append(paramName).append("))");
                }
            }

            methodBody.append(";\n");
            methodBody.append("        ").append(generateReturnStatement(methodPrefix, "queryWrapper"));

            return methodBody.toString();

        } catch (Exception e) {
            return generateSimpleMethodBody(methodPrefix);
        }
    }

    /**
     * 解析字段条件
     */
    private List<FieldCondition> parseFieldConditions(String fieldsPart) {
        List<FieldCondition> conditions = new ArrayList<>();

        // 按And/Or分割
        String[] parts = fieldsPart.split("(?=And|Or)");

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            String connector = "";
            String fieldPart = part;

            // 提取连接符
            if (part.startsWith("And")) {
                connector = "and";
                fieldPart = part.substring(3);
            } else if (part.startsWith("Or")) {
                connector = "or";
                fieldPart = part.substring(2);
            }

            // 提取查询类型和字段名
            String queryType = "eq"; // 默认查询类型
            String fieldName = fieldPart;

            // 检查是否有查询类型后缀
            String[] queryTypes = {"Like", "Gt", "Lt", "Ge", "Le", "Ne", "In", "NotIn", "Between", "NotBetween", "IsNull", "IsNotNull"};
            for (String type : queryTypes) {
                if (fieldPart.endsWith(type)) {
                    queryType = StrUtil.lowerFirst(type);
                    fieldName = fieldPart.substring(0, fieldPart.length() - type.length());
                    break;
                }
            }

            conditions.add(new FieldCondition(fieldName, queryType, connector));
        }

        return conditions;
    }

    /**
     * 字段条件类
     */
    private static class FieldCondition {
        final String fieldName;
        final String queryType;
        final String connector;

        FieldCondition(String fieldName, String queryType, String connector) {
            this.fieldName = fieldName;
            this.queryType = queryType;
            this.connector = connector;
        }
    }

    /**
     * 生成简单的方法体（当解析失败时使用）
     */
    private String generateSimpleMethodBody(String methodPrefix) {
        String lowerMethodPrefix = methodPrefix.toLowerCase();
        switch (lowerMethodPrefix) {
            case "listby":
            case "findby":
                return "        // TODO: 实现查询逻辑\n        return null;";
            case "getby":
                return "        // TODO: 实现查询逻辑\n        return null;";
            case "countby":
                return "        // TODO: 实现计数逻辑\n        return 0L;";
            case "existsby":
            case "removeby":
            case "deleteby":
                return "        // TODO: 实现逻辑\n        return false;";
            default:
                return "        // TODO: 实现逻辑\n        return null;";
        }
    }

    /**
     * 获取TableDef实例名
     */
    private String getTableDefInstanceName(String simpleEntityName) {
        // 将实体名转换为常量命名风格
        String constantName = StrUtil.toUnderlineCase(simpleEntityName).toUpperCase();
        return constantName;
    }

    /**
     * 生成返回语句
     */
    private String generateReturnStatement(String methodPrefix, String queryWrapper) {
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
}
