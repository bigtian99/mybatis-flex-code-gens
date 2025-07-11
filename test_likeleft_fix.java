import club.bigtian.mf.plugin.core.contributor.ServiceCompletionContributor;
import java.util.HashMap;
import java.util.Map;

/**
 * 测试 LikeLeft 修复
 */
class TestLikeLeftFix {
    
    public static void main(String[] args) {
        ServiceCompletionContributor contributor = new ServiceCompletionContributor();
        
        // 测试查询方法映射
        System.out.println("测试查询方法映射:");
        Map<String, String> queryMethods = new HashMap<>();
        // 模拟 addDefaultQueryMethods 的调用
        addDefaultQueryMethods(queryMethods);
        
        System.out.println("LikeLeft -> " + queryMethods.get("LikeLeft"));
        System.out.println("LikeRight -> " + queryMethods.get("LikeRight"));
        System.out.println("Like -> " + queryMethods.get("Like"));
        
        // 测试字段名提取
        System.out.println("\n测试字段名提取:");
        String[] testCases = {
            "CreateByLikeLeft",
            "CreateByLike", 
            "CreateBy",
            "CreateByGt",
            "CreateByLe"
        };
        
        for (String testCase : testCases) {
            String fieldName = extractFirstField(testCase);
            String queryType = extractQueryTypeFromFieldsPart(testCase, queryMethods);
            System.out.println(testCase + " -> 字段: " + fieldName + ", 查询类型: " + queryType);
        }
    }
    
    private static void addDefaultQueryMethods(Map<String, String> methodMapping) {
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
    
    private static String extractFirstField(String fieldsPart) {
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

        if (fieldName.contains("And")) {
            fieldName = fieldName.substring(0, fieldName.indexOf("And"));
        } else if (fieldName.contains("Or")) {
            fieldName = fieldName.substring(0, fieldName.indexOf("Or"));
        }

        return fieldName;
    }
    
    private static String extractQueryTypeFromFieldsPart(String fieldsPart, Map<String, String> queryMethods) {
        for (Map.Entry<String, String> entry : queryMethods.entrySet()) {
            String suffix = entry.getKey();
            String methodName = entry.getValue();
            if (fieldsPart.endsWith(suffix)) {
                return methodName;
            }
        }
        return "eq";
    }
}
