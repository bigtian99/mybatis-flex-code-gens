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
            // 如果没有实体类，使用传入的字段顺序，但只包含有TableDef映射的字段
            for (ServiceMethodGenerator.FieldInfo fieldInfo : entityFields.values()) {
                String tableDefField = fieldToTableDefMapping.get(fieldInfo.fieldName);
                if (tableDefField != null) {
                    orderedFields.add(fieldInfo);
                }
            }
        }

        boolean isFirst = true;
        for (ServiceMethodGenerator.FieldInfo fieldInfo : orderedFields) {
            String tableDefField = fieldToTableDefMapping.get(fieldInfo.fieldName);

            // orderedFields 中的字段已经保证有 TableDef 映射，但为了安全起见还是检查一下
            if (StrUtil.isEmpty(tableDefField)) {
                System.out.println("Warning: No TableDef mapping found for field: " + fieldInfo.fieldName + ", skipping...");
                continue;
            }

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
    public String generateReturnStatement(String methodPrefix, String queryWrapper, String simpleEntityName) {
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
        return generateMethodDescription(methodName, null, null);
    }

    /**
     * 生成方法描述（支持根据字段注释动态生成）
     */
    public String generateMethodDescription(String methodName, String fieldsCompletion, Map<String, ServiceMethodGenerator.FieldInfo> entityFields) {
        String lowerMethodName = methodName.toLowerCase();
        String baseDescription;

        if (lowerMethodName.startsWith("listby")) {
            baseDescription = "根据条件查询列表";
        } else if (lowerMethodName.startsWith("findby")) {
            baseDescription = "根据条件查找数据";
        } else if (lowerMethodName.startsWith("getby")) {
            baseDescription = "根据条件获取单个数据";
        } else if (lowerMethodName.startsWith("countby")) {
            baseDescription = "根据条件统计数量";
        } else if (lowerMethodName.startsWith("existsby")) {
            baseDescription = "根据条件检查是否存在";
        } else if (lowerMethodName.startsWith("removeby") || lowerMethodName.startsWith("deleteby")) {
            baseDescription = "根据条件删除数据";
        } else {
            baseDescription = "根据条件查询数据";
        }

        // 如果有字段信息，尝试从字段注释生成更具体的描述
        if (StrUtil.isNotEmpty(fieldsCompletion) && entityFields != null) {
            String fieldDescription = generateFieldBasedDescription(fieldsCompletion, entityFields);
            if (StrUtil.isNotEmpty(fieldDescription)) {
                return baseDescription.replace("条件", fieldDescription);
            }
        }

        return baseDescription;
    }

    /**
     * 根据字段注释生成字段描述
     */
    private String generateFieldBasedDescription(String fieldsCompletion, Map<String, ServiceMethodGenerator.FieldInfo> entityFields) {
        try {
            // 解析字段名（简单的解析逻辑：按And/Or分割）
            List<String> fields = parseFieldsFromCompletion(fieldsCompletion);
            if (fields.isEmpty()) {
                return "";
            }

            StringBuilder description = new StringBuilder();
            for (int i = 0; i < fields.size(); i++) {
                String field = fields.get(i);
                ServiceMethodGenerator.FieldInfo fieldInfo = entityFields.get(field);

                // 如果直接匹配失败，尝试小写首字母的版本
                if (fieldInfo == null) {
                    String lowerFirstField = StrUtil.lowerFirst(field);
                    fieldInfo = entityFields.get(lowerFirstField);
                }

                if (fieldInfo != null && StrUtil.isNotEmpty(fieldInfo.fieldComment)) {
                    if (description.length() > 0) {
                        description.append("和");
                    }
                    description.append(fieldInfo.fieldComment);
                } else {
                    // 如果没有注释，使用字段名
                    if (description.length() > 0) {
                        description.append("和");
                    }
                    description.append(field);
                }
            }

            return description.toString();
        } catch (Exception e) {
            System.err.println("Failed to generate field-based description: " + e.getMessage());
            return "";
        }
    }

    /**
     * 解析字段名（从补全信息中提取字段名）
     */
    private List<String> parseFieldsFromCompletion(String fieldsCompletion) {
        List<String> fields = new ArrayList<>();
        if (StrUtil.isEmpty(fieldsCompletion)) {
            return fields;
        }

        String[] parts = fieldsCompletion.split("(?=And|Or)");
        for (String part : parts) {
            // 移除And/Or前缀
            String fieldPart = part;
            if (fieldPart.startsWith("And")) {
                fieldPart = fieldPart.substring(3);
            } else if (fieldPart.startsWith("Or")) {
                fieldPart = fieldPart.substring(2);
            }

            // 移除查询类型后缀
            String[] suffixes = {"LikeLeft", "LikeRight", "NotLikeLeft", "NotLikeRight", "NotLike", "Like",
                               "GreaterThanEqual", "LessThanEqual", "GreaterThan", "LessThan", "NotEqual",
                               "Gt", "Lt", "Ge", "Le", "Ne", "Eq", "In", "NotIn", "Between", "NotBetween",
                               "IsNull", "IsNotNull"};
            for (String suffix : suffixes) {
                if (fieldPart.endsWith(suffix)) {
                    fieldPart = fieldPart.substring(0, fieldPart.length() - suffix.length());
                    break;
                }
            }

            if (StrUtil.isNotEmpty(fieldPart)) {
                fields.add(fieldPart);
            }
        }
        return fields;
    }
}
