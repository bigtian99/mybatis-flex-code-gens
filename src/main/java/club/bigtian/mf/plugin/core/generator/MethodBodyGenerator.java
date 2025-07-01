package club.bigtian.mf.plugin.core.generator;

import cn.hutool.core.util.StrUtil;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiModifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 方法体生成器
 * 负责生成Service方法的具体实现代码
 */
public class MethodBodyGenerator {

    /**
     * 生成单字段方法体
     */
    public String generateSingleFieldMethodBody(String methodPrefix, ServiceMethodGenerator.FieldInfo fieldInfo,
                                              Map<String, String> fieldToTableDefMapping, String simpleEntityName) {
        return generateSingleFieldMethodBody(methodPrefix, fieldInfo, fieldToTableDefMapping, simpleEntityName, "eq");
    }

    /**
     * 生成单字段方法体（支持指定查询类型）
     */
    public String generateSingleFieldMethodBody(String methodPrefix, ServiceMethodGenerator.FieldInfo fieldInfo,
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
    public String generateEntityMethodBody(String methodPrefix, Map<String, ServiceMethodGenerator.FieldInfo> entityFields,
                                         Map<String, String> fieldToTableDefMapping, String simpleEntityName,
                                         PsiClass entityClass) {
        return generateEntityMethodBody(methodPrefix, entityFields, fieldToTableDefMapping, simpleEntityName, entityClass, "eq");
    }

    /**
     * 生成整个实体的方法体（使用Condition条件判断，支持指定默认查询类型）
     */
    public String generateEntityMethodBody(String methodPrefix, Map<String, ServiceMethodGenerator.FieldInfo> entityFields,
                                         Map<String, String> fieldToTableDefMapping, String simpleEntityName,
                                         PsiClass entityClass, String defaultQueryType) {

        // 获取TableDef静态变量名
        String tableDefInstanceName = getTableDefInstanceName(simpleEntityName);

        StringBuilder methodBody = new StringBuilder();
        methodBody.append("QueryWrapper queryWrapper = QueryWrapper.create()");

        // 按照实体类中字段的定义顺序处理
        List<ServiceMethodGenerator.FieldInfo> orderedFields = new ArrayList<>();
        if (entityClass != null) {
            PsiField[] fields = entityClass.getAllFields();
            for (PsiField field : fields) {
                // 跳过静态字段和常量
                if (field.hasModifierProperty(PsiModifier.STATIC) ||
                    field.hasModifierProperty(PsiModifier.FINAL)) {
                    continue;
                }

                String fieldName = field.getName();
                ServiceMethodGenerator.FieldInfo fieldInfo = entityFields.get(fieldName);
                String tableDefField = fieldToTableDefMapping.get(fieldName);

                // 只处理在APT中确实存在的字段
                if (fieldInfo != null && tableDefField != null) {
                    orderedFields.add(fieldInfo);
                }
            }
        } else {
            // 如果没有实体类，使用传入的字段顺序
            orderedFields.addAll(entityFields.values());
        }

        boolean isFirst = true;
        for (ServiceMethodGenerator.FieldInfo fieldInfo : orderedFields) {
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
     * 获取TableDef实例名
     */
    private String getTableDefInstanceName(String simpleEntityName) {
        return StrUtil.toUnderlineCase(simpleEntityName).toUpperCase();
    }

    /**
     * 生成返回语句
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
     * 根据方法前缀获取返回类型
     */
    public String getReturnTypeForMethodPrefix(String methodPrefix, String simpleEntityName) {
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
                return "boolean";
            case "removeby":
            case "deleteby":
                return "boolean";
            default:
                return "List<" + simpleEntityName + ">";
        }
    }

    /**
     * 生成方法描述
     */
    public String generateMethodDescription(String methodName) {
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
}
