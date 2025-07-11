package club.bigtian.mf.plugin.core.generator;

import cn.hutool.core.util.StrUtil;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Service方法生成器
 * 负责在接口和实现类中生成方法
 */
public class ServiceMethodGenerator {

    private final ImportManager importManager;
    private final MethodBodyGenerator bodyGenerator;

    public ServiceMethodGenerator() {
        this.importManager = new ImportManager();
        this.bodyGenerator = new MethodBodyGenerator();
    }

    /**
     * 获取ImportManager实例
     */
    public ImportManager getImportManager() {
        return importManager;
    }

    /**
     * 在Service接口和实现类中生成方法
     */
    public void generateServiceMethodInInterfaceAndImpl(InsertionContext context, PsiClass serviceInterface,
                                                       String methodName, String methodBody, String returnType,
                                                       String fieldsCompletion, Map<String, FieldInfo> entityFields,
                                                       String entityQualifiedName, Map<String, String> fieldToTableDefMapping) {
        
        System.out.println("ServiceMethodGenerator: generateServiceMethodInInterfaceAndImpl called");
        System.out.println("  methodName: " + methodName);
        System.out.println("  returnType: " + returnType);
        System.out.println("  serviceInterface: " + (serviceInterface != null ? serviceInterface.getName() : "null"));

        ApplicationManager.getApplication().runWriteAction(() -> {
            try {
                Project project = context.getProject();

                // 确保文档已提交
                PsiDocumentManager.getInstance(project).commitAllDocuments();

                // 1. 生成参数列表
                String parameters = generateParameters(fieldsCompletion, entityFields, entityQualifiedName, fieldToTableDefMapping);

                // 2. 先替换用户输入为方法调用，避免影响接口方法声明的添加
                replaceUserInput(context, methodName, parameters, returnType, fieldsCompletion);

                // 3. 在接口中生成方法声明
                generateInterfaceMethod(serviceInterface, methodName, returnType, parameters, fieldsCompletion, entityFields, entityQualifiedName, project);

                // 4. 在实现类中生成方法实现
                generateImplementationMethodAsync(project, serviceInterface, methodName, returnType, parameters, methodBody, fieldsCompletion, entityFields, entityQualifiedName);

                // 5. 将光标定位到括号内
                positionCursor(context, methodName);

            } catch (Exception e) {
                System.err.println("Error generating service method: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * 替换用户输入
     */
    private void replaceUserInput(InsertionContext context, String methodName, String parameters, String returnType, String fieldsCompletion) {
        com.intellij.openapi.editor.Document document = context.getDocument();
        int startOffset = context.getStartOffset();
        int tailOffset = context.getTailOffset();

        // 检查当前类是否是接口
        PsiElement element = context.getFile().findElementAt(context.getStartOffset());
        PsiClass currentClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);

        String userInputReplacement;
        if (currentClass != null && currentClass.isInterface()) {
            // 对于接口，生成完整的方法声明
            String description = bodyGenerator.generateMethodDescription(methodName);
            userInputReplacement = "/**\n     * " + description + "\n     */\n    " +
                                 returnType + " " + methodName + "(" + parameters + ");";
        } else {
            // 对于实现类或Controller，生成空括号的方法调用，让用户手动填写参数
            userInputReplacement = methodName + "()";
        }

        document.replaceString(startOffset, tailOffset, userInputReplacement);

        // 提交文档更改，确保PSI同步
        PsiDocumentManager.getInstance(context.getProject()).commitDocument(document);
    }

    /**
     * 生成参数列表
     */
    private String generateParameters(String fieldsCompletion, Map<String, FieldInfo> entityFields,
                                    String entityQualifiedName, Map<String, String> fieldToTableDefMapping) {
        StringBuilder paramBuilder = new StringBuilder();

        // 检查是否是整个实体方法
        if ("Entity".equals(fieldsCompletion) || "EntityLike".equals(fieldsCompletion)) {
            String simpleEntityName = StrUtil.subAfter(entityQualifiedName, ".", true);
            paramBuilder.append(simpleEntityName).append(" ").append(StrUtil.lowerFirst(simpleEntityName));
        } else {
            // 普通字段方法，解析字段生成参数
            List<String> fields = parseFieldsFromCompletion(fieldsCompletion);
            boolean firstParam = true;
            for (String field : fields) {
                FieldInfo fieldInfo = entityFields.get(field);

                // 如果直接匹配失败，尝试小写首字母的版本
                if (fieldInfo == null) {
                    String lowerFirstField = StrUtil.lowerFirst(field);
                    fieldInfo = entityFields.get(lowerFirstField);
                }

                if (fieldInfo != null) {
                    // 对于单字段方法，如果字段信息存在就生成参数
                    // fieldToTableDefMapping的检查已经在调用方进行过了
                    if (!firstParam) paramBuilder.append(", ");
                    // 使用字段名的小写形式作为参数名
                    String paramName = StrUtil.lowerFirst(field);
                    paramBuilder.append(fieldInfo.fieldType).append(" ").append(paramName);
                    firstParam = false;
                }
            }
        }

        return paramBuilder.toString();
    }

    /**
     * 在接口中生成方法声明
     */
    private void generateInterfaceMethod(PsiClass serviceInterface, String methodName, String returnType,
                                       String parameters, String fieldsCompletion, Map<String, FieldInfo> entityFields,
                                       String entityQualifiedName, Project project) {
        try {
            // 检查方法是否已存在
            PsiMethod[] existingMethods = serviceInterface.getAllMethods();
            for (PsiMethod existingMethod : existingMethods) {
                if (methodName.equals(existingMethod.getName())) {
                    System.out.println("Method " + methodName + " already exists in interface, skipping");
                    return;
                }
            }

            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

            // 创建纯粹的方法声明
            String methodDeclaration = returnType + " " + methodName + "(" + parameters + ");";
            PsiMethod interfaceMethod = factory.createMethodFromText(methodDeclaration, serviceInterface);

            // 添加方法到接口
            PsiMethod addedMethod = (PsiMethod) serviceInterface.add(interfaceMethod);

            // 添加JavaDoc注释到接口方法
            try {
                addJavaDocToMethod(addedMethod, methodName, fieldsCompletion, entityFields, entityQualifiedName, factory);
            } catch (Exception e) {
                System.err.println("Failed to add JavaDoc to interface method: " + e.getMessage());
            }

        } catch (Exception e) {
            System.err.println("Error generating interface method: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 异步在实现类中生成方法实现
     */
    public void generateImplementationMethodAsync(Project project, PsiClass serviceInterface, String methodName,
                                                 String returnType, String parameters, String methodBody,
                                                 String fieldsCompletion, Map<String, FieldInfo> entityFields, String entityQualifiedName) {
        // 在读取操作中获取必要信息
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
                            generateImplementationMethod(serviceImpl, methodName, returnType, parameters, methodBody, fieldsCompletion, entityFields, entityQualifiedName, project);
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
     * 生成实现类方法
     */
    private void generateImplementationMethod(PsiClass serviceImpl, String methodName, String returnType, 
                                            String parameters, String methodBody, String fieldsCompletion,
                                            Map<String, FieldInfo> entityFields, String entityQualifiedName, Project project) {
        try {
            // 确保文档已提交
            PsiDocumentManager.getInstance(project).commitAllDocuments();

            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

            // 生成方法实现
            String methodText = "@Override\n" +
                              "public " + returnType + " " + methodName + "(" + parameters + ") {\n" +
                              "    " + methodBody.replace("\n", "\n    ") + "\n" +
                              "}";

            System.out.println("Generated method text: " + methodText);

            PsiMethod method = factory.createMethodFromText(methodText, serviceImpl);

            // 在实现类中插入方法
            PsiMethod addedMethod = (PsiMethod) serviceImpl.add(method);

            System.out.println("Successfully added method to implementation: " + addedMethod.getName());
            System.out.println("Implementation method text: " + addedMethod.getText());
            System.out.println("Implementation class: " + serviceImpl.getName());
            System.out.println("Implementation file: " + serviceImpl.getContainingFile().getName());

            // 添加JavaDoc注释到实现方法
            addJavaDocToMethod(addedMethod, methodName, fieldsCompletion, entityFields, entityQualifiedName, factory);

            // 使用统一的导入处理方法
            addRequiredImportsForMethod(serviceImpl.getContainingFile(), methodBody, project);

        } catch (Exception e) {
            System.err.println("Error generating method implementation: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 统一的导入处理方法
     * 为Service接口和Service实现类提供一致的导入处理逻辑
     */
    private void addRequiredImportsForMethod(PsiFile psiFile, String methodBody, Project project) {
        try {
            System.out.println("Adding imports for method body: " + methodBody);
            System.out.println("Target file: " + psiFile.getName());

            // 检查方法体是否包含ObjectUtil
            if (methodBody.contains("ObjectUtil")) {
                System.out.println("Method body contains ObjectUtil, will add import");
            } else {
                System.out.println("Method body does NOT contain ObjectUtil");
            }

            // 确保文档已提交
            PsiDocumentManager.getInstance(project).commitAllDocuments();

            // 调用ImportManager处理导入
            importManager.addRequiredImportsToFile(psiFile, methodBody, project);

            System.out.println("Import processing completed");
        } catch (Exception e) {
            System.err.println("Error in unified import processing: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 查找Service实现类
     */
    private PsiClass findServiceImplementation(PsiClass serviceInterface, Project project) {
        try {
            if (serviceInterface == null || !serviceInterface.isInterface()) {
                return null;
            }

            GlobalSearchScope scope = GlobalSearchScope.allScope(project);

            // 查找直接实现该接口的类
            for (PsiClass implementor : ClassInheritorsSearch.search(serviceInterface, scope, false)) {
                if (!implementor.isInterface() && !implementor.hasModifierProperty(PsiModifier.ABSTRACT)) {
                    return implementor;
                }
            }

        } catch (Exception e) {
            System.err.println("Error searching for implementation: " + e.getMessage());
        }
        return null;
    }

    /**
     * 定位光标
     */
    private void positionCursor(InsertionContext context, String methodName) {
        int startOffset = context.getStartOffset();
        int cursorPosition = startOffset + methodName.length() + 1; // +1 for opening parenthesis
        context.getEditor().getCaretModel().moveToOffset(cursorPosition);
    }

    // 临时方法，后续需要从其他类中提取
    private List<String> parseFieldsFromCompletion(String fieldsCompletion) {
        // 简单的解析逻辑：按And/Or分割
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

    private void addJavaDocToMethod(PsiMethod method, String methodName, String fieldsCompletion,
                                  Map<String, FieldInfo> entityFields, String entityQualifiedName, PsiElementFactory factory) {
        try {
            // 生成简单的JavaDoc注释
            String description = generateMethodDescription(methodName);
            String commentText = "/**\n     * " + description + "\n     */";

            PsiDocComment docComment = factory.createDocCommentFromText(commentText);
            if (method.getDocComment() == null) {
                method.addBefore(docComment, method.getFirstChild());
            }
        } catch (Exception e) {
            System.err.println("Failed to add JavaDoc: " + e.getMessage());
        }
    }

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
     * 统一的方法生成入口 - 供所有类型的补全使用
     *
     * @param context 插入上下文
     * @param currentClass 当前类（可能是Controller、Service接口或Service实现类）
     * @param methodName 方法名
     * @param methodBody 方法体
     * @param returnType 返回类型
     * @param fieldsCompletion 字段补全信息
     * @param entityFields 实体字段信息
     * @param entityQualifiedName 实体类全限定名
     * @param fieldToTableDefMapping 字段到TableDef的映射
     */
    public void generateUnifiedMethod(InsertionContext context, PsiClass currentClass,
                                    String methodName, String methodBody, String returnType,
                                    String fieldsCompletion, Map<String, FieldInfo> entityFields,
                                    String entityQualifiedName, Map<String, String> fieldToTableDefMapping) {

        if (currentClass == null) {
            return;
        }

        if (currentClass.isInterface()) {
            // 如果是接口（Service接口），生成接口声明 + 实现类方法
            generateServiceMethodInInterfaceAndImpl(context, currentClass, methodName, methodBody, returnType,
                                                  fieldsCompletion, entityFields, entityQualifiedName, fieldToTableDefMapping);
        } else {
            // 如果是实现类（Service实现类），使用原来的文档插入方式，但共用相同的导入处理
            generateSingleMethodImplementation(context, currentClass, methodName, methodBody, returnType,
                                             fieldsCompletion, entityFields, entityQualifiedName, fieldToTableDefMapping);
        }
    }

    /**
     * 处理实现类中的用户输入替换和光标定位
     */
    private void handleUserInputForImplementation(InsertionContext context, String methodName) {
        com.intellij.openapi.editor.Document document = context.getDocument();
        com.intellij.openapi.editor.Editor editor = context.getEditor();

        // 获取当前光标位置
        int startOffset = context.getStartOffset();
        int tailOffset = context.getTailOffset();

        // 替换用户输入为方法名和空括号
        String userInputReplacement = methodName + "()";
        document.replaceString(startOffset, tailOffset, userInputReplacement);

        // 计算光标在括号内的位置
        int cursorPosition = startOffset + methodName.length() + 1; // +1 for opening parenthesis

        // 将光标定位到括号内
        editor.getCaretModel().moveToOffset(cursorPosition);
    }



    /**
     * 生成单个方法实现（用于Service实现类或Controller）
     * 使用文档插入方式，与原来的 insertMethodImplementationWithCursor 保持一致
     */
    private void generateSingleMethodImplementation(InsertionContext context, PsiClass targetClass,
                                                  String methodName, String methodBody, String returnType,
                                                  String fieldsCompletion, Map<String, FieldInfo> entityFields,
                                                  String entityQualifiedName, Map<String, String> fieldToTableDefMapping) {

        ApplicationManager.getApplication().runWriteAction(() -> {
            try {
                // 获取当前文档和编辑器
                com.intellij.openapi.editor.Document document = context.getDocument();
                com.intellij.openapi.editor.Editor editor = context.getEditor();
                Project project = context.getProject();

                // 获取当前光标位置
                int startOffset = context.getStartOffset();
                int tailOffset = context.getTailOffset();

                // 生成参数列表（使用统一的方法）
                String parameters = generateParameters(fieldsCompletion, entityFields, entityQualifiedName, fieldToTableDefMapping);

                // 找到合适的插入位置
                PsiFile psiFile = context.getFile();
                PsiElement elementAtCaret = psiFile.findElementAt(startOffset);
                int insertOffset = findInsertPosition(elementAtCaret, document);

                // 替换用户输入的内容为方法名和空括号
                String userInputReplacement = methodName + "()";
                document.replaceString(startOffset, tailOffset, userInputReplacement);

                // 生成方法注释（使用统一的方法）
                String methodComment = generateMethodComment(methodName, fieldsCompletion, entityFields, entityQualifiedName);

                // 生成完整的方法实现
                StringBuilder methodCode = new StringBuilder();
                methodCode.append("\n\n");
                methodCode.append(methodComment);
                methodCode.append("    public ").append(returnType).append(" ").append(methodName).append("(");
                methodCode.append(parameters).append(") {\n");

                // 直接使用方法体，保持原有缩进
                methodCode.append("        ").append(methodBody.replace("\n", "\n        ")).append("\n");

                methodCode.append("    }");

                // 插入完整的方法到合适位置
                document.insertString(insertOffset, methodCode.toString());

                // 提交文档更改，确保PSI同步
                PsiDocumentManager.getInstance(project).commitDocument(document);

                // 使用统一的导入处理方法
                addRequiredImportsForMethod(targetClass.getContainingFile(), methodBody, project);

                // 使用与Service接口相同的光标定位方法
                positionCursor(context, methodName);

            } catch (Exception e) {
                System.err.println("Error generating single method implementation: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * 查找合适的插入位置
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
     * 生成方法注释
     */
    private String generateMethodComment(String methodName, String fieldsCompletion,
                                       Map<String, FieldInfo> entityFields, String entityQualifiedName) {
        StringBuilder comment = new StringBuilder();
        comment.append("    /**\n");

        // 使用 MethodBodyGenerator 生成描述
        String description = bodyGenerator.generateMethodDescription(methodName);

        comment.append("     * ").append(description).append("\n");
        comment.append("     */\n");

        return comment.toString();
    }

    /**
     * 字段信息类
     */
    public static class FieldInfo {
        public String fieldName;
        public String fieldType;
        public String columnName;

        public FieldInfo(String fieldName, String fieldType, String columnName) {
            this.fieldName = fieldName;
            this.fieldType = fieldType;
            this.columnName = columnName;
        }
    }
}
