package club.bigtian.mf.plugin.core.parser;

import club.bigtian.mf.plugin.core.generator.ServiceMethodGenerator;
import cn.hutool.core.util.StrUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.javadoc.PsiDocComment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 字段解析器
 * 负责解析实体类字段和方法名中的字段信息
 */
public class FieldParser {

    /**
     * 获取实体类字段信息
     */
    public Map<String, ServiceMethodGenerator.FieldInfo> getEntityFieldsInfo(PsiClass entityClass) {
        if (entityClass == null) {
            return new HashMap<>();
        }

        // 确保在读取线程中执行 PSI 访问
        return ApplicationManager.getApplication().runReadAction((Computable<Map<String, ServiceMethodGenerator.FieldInfo>>) () -> {
            Map<String, ServiceMethodGenerator.FieldInfo> fieldsInfo = new HashMap<>();

            try {
                PsiField[] fields = entityClass.getAllFields();
                if (fields == null) {
                    return fieldsInfo;
                }

                for (PsiField field : fields) {
                    try {
                        // 安全检查字段是否为空
                        if (field == null) {
                            continue;
                        }

                        // 跳过静态字段和常量
                        if (field.hasModifierProperty(PsiModifier.STATIC) ||
                            field.hasModifierProperty(PsiModifier.FINAL)) {
                            continue;
                        }

                        String fieldName = field.getName();
                        if (StrUtil.isEmpty(fieldName)) {
                            continue;
                        }

                        String fieldType;
                        try {
                            fieldType = field.getType().getPresentableText();
                        } catch (Exception e) {
                            fieldType = "Object"; // 默认类型
                        }

                        String columnName = getColumnAnnotationValue(field);
                        if (StrUtil.isEmpty(columnName)) {
                            columnName = toUnderscoreCase(fieldName);
                        }

                        // 获取字段的文档注释
                        String fieldComment = getFieldDocComment(field);

                        fieldsInfo.put(fieldName, new ServiceMethodGenerator.FieldInfo(fieldName, fieldType, columnName, fieldComment));
                    } catch (ProcessCanceledException e) {
                        // 重新抛出 ProcessCanceledException
                        throw e;
                    } catch (Exception e) {
                        // 跳过有问题的字段，继续处理其他字段
                        System.err.println("Failed to process field: " + e.getMessage());
                    }
                }
            } catch (ProcessCanceledException e) {
                // 重新抛出 ProcessCanceledException
                throw e;
            } catch (Exception e) {
                System.err.println("Failed to get entity fields info: " + e.getMessage());
            }

            return fieldsInfo;
        });
    }

    /**
     * 获取字段的文档注释
     */
    private String getFieldDocComment(PsiField field) {
        try {
            PsiDocComment docComment = field.getDocComment();
            if (docComment != null) {
                String commentText = docComment.getText();
                if (StrUtil.isNotEmpty(commentText)) {
                    // 提取注释内容，去除 /** */ 和 * 前缀
                    String[] lines = commentText.split("\n");
                    StringBuilder result = new StringBuilder();
                    for (String line : lines) {
                        line = line.trim();
                        if (line.startsWith("/**") || line.startsWith("*/") || line.equals("*")) {
                            continue;
                        }
                        if (line.startsWith("* ")) {
                            line = line.substring(2);
                        } else if (line.startsWith("*")) {
                            line = line.substring(1);
                        }
                        if (StrUtil.isNotEmpty(line.trim())) {
                            if (result.length() > 0) {
                                result.append(" ");
                            }
                            result.append(line.trim());
                        }
                    }
                    return result.toString();
                }
            }
        } catch (ProcessCanceledException e) {
            // 重新抛出 ProcessCanceledException
            throw e;
        } catch (Exception e) {
            // 如果获取注释失败，返回空字符串
            System.err.println("Failed to get field doc comment: " + e.getMessage());
        }
        return "";
    }

    /**
     * 从补全中解析字段
     */
    public List<String> parseFieldsFromCompletion(String completion) {
        List<FieldQueryInfo> fieldQueryInfos = parseFieldQueryInfoFromCompletion(completion, null);
        List<String> fields = new ArrayList<>();
        for (FieldQueryInfo info : fieldQueryInfos) {
            fields.add(info.fieldName);
        }
        return fields;
    }

    /**
     * 从补全中解析字段查询信息
     */
    public List<FieldQueryInfo> parseFieldQueryInfoFromCompletion(String completion, Map<String, String> queryMethods) {
        List<FieldQueryInfo> fieldQueryInfos = new ArrayList<>();

        if (StrUtil.isEmpty(completion)) {
            return fieldQueryInfos;
        }

        // 简单的解析逻辑：按And/Or分割
        String[] parts = completion.split("(?=And|Or)");

        for (String part : parts) {
            // 移除And/Or前缀
            String fieldPart = part;
            if (fieldPart.startsWith("And")) {
                fieldPart = fieldPart.substring(3);
            } else if (fieldPart.startsWith("Or")) {
                fieldPart = fieldPart.substring(2);
            }

            // 解析查询类型和字段名
            FieldQueryInfo queryInfo = parseFieldQueryInfo(fieldPart, queryMethods);
            if (queryInfo != null) {
                fieldQueryInfos.add(queryInfo);
            }
        }

        return fieldQueryInfos;
    }

    /**
     * 解析单个字段的查询信息
     */
    private FieldQueryInfo parseFieldQueryInfo(String fieldPart, Map<String, String> queryMethods) {
        if (StrUtil.isEmpty(fieldPart)) {
            return null;
        }

        // 检查是否有查询类型后缀
        if (queryMethods != null) {
            for (String suffix : queryMethods.keySet()) {
                if (fieldPart.endsWith(suffix)) {
                    String fieldName = fieldPart.substring(0, fieldPart.length() - suffix.length());
                    String queryType = queryMethods.get(suffix);
                    return new FieldQueryInfo(fieldName, queryType);
                }
            }
        }

        // 默认查询类型后缀
        String[] suffixes = {"Like", "Gt", "Lt", "Ge", "Le", "Ne", "In", "NotIn", "Between", "NotBetween", "IsNull", "IsNotNull"};
        for (String suffix : suffixes) {
            if (fieldPart.endsWith(suffix)) {
                String fieldName = fieldPart.substring(0, fieldPart.length() - suffix.length());
                String queryType = suffix.toLowerCase();
                return new FieldQueryInfo(fieldName, queryType);
            }
        }

        // 没有后缀，默认为eq查询
        return new FieldQueryInfo(fieldPart, "eq");
    }

    /**
     * 提取方法前缀（不区分大小写）
     */
    public String extractMethodPrefix(String prefix) {
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
     * 判断是否是方法名补全
     */
    public boolean isMethodNameCompletion(String prefix) {
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
     * 检查是否是实体名模式
     */
    public boolean isEntityNamePattern(String fieldsPart, String simpleEntityName) {
        if (StrUtil.isEmpty(fieldsPart) || StrUtil.isEmpty(simpleEntityName)) {
            return false;
        }

        // 检查是否是实体名或实体名+Like
        return fieldsPart.equals(simpleEntityName) || 
               fieldsPart.equals(simpleEntityName + "Like") ||
               fieldsPart.startsWith(simpleEntityName);
    }

    /**
     * 获取@Column注解的值
     */
    private String getColumnAnnotationValue(PsiField field) {
        try {
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
        } catch (ProcessCanceledException e) {
            // 重新抛出 ProcessCanceledException
            throw e;
        } catch (Exception e) {
            // 如果获取注解失败，返回 null
            System.err.println("Failed to get column annotation value: " + e.getMessage());
        }
        return null;
    }

    /**
     * 转换为下划线命名
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
     * 字段查询信息类
     */
    public static class FieldQueryInfo {
        public String fieldName;      // 字段名
        public String queryType;      // 查询类型：eq, like, gt, lt 等

        public FieldQueryInfo(String fieldName, String queryType) {
            this.fieldName = fieldName;
            this.queryType = queryType;
        }
    }
}
