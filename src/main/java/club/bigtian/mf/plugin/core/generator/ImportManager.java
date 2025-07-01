package club.bigtian.mf.plugin.core.generator;

import cn.hutool.core.util.StrUtil;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;

import java.util.HashSet;
import java.util.Set;

/**
 * 导入管理器
 * 负责处理Java文件的导入语句
 */
public class ImportManager {

    /**
     * 添加必要的导入到指定文件
     */
    public void addRequiredImportsToFile(PsiFile psiFile, String methodBody, Project project) {
        try {
            if (!(psiFile instanceof PsiJavaFile)) {
                return;
            }

            PsiJavaFile javaFile = (PsiJavaFile) psiFile;

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
            String tableDefStaticImport = findTableDefStaticImportFromMethodBody(methodBody, project);
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
     * 从方法体中查找TableDef类的静态导入
     */
    private String findTableDefStaticImportFromMethodBody(String methodBody, Project project) {
        try {
            // 从方法体中提取TableDef实例名
            String[] parts = methodBody.split("\\.");
            for (String part : parts) {
                if (part.matches("^[A-Z][A-Z0-9_]*$") && !part.equals("QueryWrapper") && !part.contains("(")) {
                    // 这可能是TableDef实例名，但由于没有上下文，我们暂时跳过静态导入
                    // 在实际使用中，用户可能需要手动添加静态导入
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null; // 暂时返回null，避免错误的静态导入
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
     * 直接添加导入语句到文档
     */
    private void addImportDirectly(PsiJavaFile javaFile, String qualifiedName) {
        try {
            PsiDocumentManager documentManager = PsiDocumentManager.getInstance(javaFile.getProject());
            com.intellij.openapi.editor.Document document = documentManager.getDocument(javaFile);
            if (document != null) {
                // 检查是否已经有package语句
                String text = document.getText();
                if (text.contains("package ")) {
                    // 在package语句后添加import
                    int packageEnd = text.indexOf(";", text.indexOf("package ")) + 1;
                    if (packageEnd > 0) {
                        String importText = "\nimport " + qualifiedName + ";";
                        document.insertString(packageEnd, importText);
                    }
                } else {
                    // 在文件开头添加import
                    if (!text.contains("import " + qualifiedName)) {
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

            // 解析静态导入的类名和字段名
            int lastDotIndex = qualifiedName.lastIndexOf('.');
            if (lastDotIndex > 0) {
                String className = qualifiedName.substring(0, lastDotIndex);
                String fieldName = qualifiedName.substring(lastDotIndex + 1);

                PsiClass psiClass = psiFacade.findClass(className, GlobalSearchScope.allScope(project));
                if (psiClass != null && importList != null) {
                    PsiImportStaticStatement staticImportStatement = factory.createImportStaticStatement(psiClass, fieldName);
                    importList.add(staticImportStatement);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
