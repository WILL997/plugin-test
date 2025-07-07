package TActions.org.intelllij;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.swing.*;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MetamorphicTestAction extends AnAction {

    private static final Logger logger = LoggerFactory.getLogger(UnitTestAction.class);
    private static final Charset UTF8 = StandardCharsets.UTF_8;

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        PsiElement element = e.getData(CommonDataKeys.PSI_ELEMENT);

        if (element instanceof PsiMethod) {
            PsiMethod method = (PsiMethod) element;
            if (method.hasModifierProperty("public") && method.getReturnType() != null) {
                VirtualFile srcDirectory = project.getBaseDir().findChild("src");

                //获取对应方法的Java类
                PsiClass psiClass = method.getContainingClass();
                PsiFile containingFile = psiClass.getContainingFile();
                if (containingFile instanceof PsiJavaFile) {
                    PsiJavaFile javaFile = (PsiJavaFile) containingFile;
                    String className = psiClass.getName();
                    System.out.println("Selected class name: " + className);

                    String packageName = javaFile.getPackageName();  // 获取对应Java文件的包名
                    VirtualFile sourceRoot = project.getBaseDir().findChild("src");

                    // 对应选中类所在包
                    VirtualFile packageDir = sourceRoot.findFileByRelativePath(packageName.replace(".", "/"));
                    VirtualFile[] testDirectory1 = new VirtualFile[1]; // 使用数组以便在内部类中修改
                    VirtualFile testDirectory = null;

                    if (packageDir != null) {
                        // 在 runWriteAction 中安全地创建目录
                        ApplicationManager.getApplication().runWriteAction(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    // 检查是否存在test文件夹，如果不存在则创建
                                    VirtualFile testDir = packageDir.findChild("test");
                                    if (testDir == null) {
                                        testDir = packageDir.createChildDirectory(this, "test");
                                        System.out.println("已创建测试目录: " + testDir.getPath());
                                    }

                                    // 在test目录下创建metamorphictest目录
                                    VirtualFile metamorphictestDir = testDir.findChild("metamorphictest");
                                    if (metamorphictestDir == null) {
                                        metamorphictestDir = testDir.createChildDirectory(this, "metamorphictest");
                                        System.out.println("已创建蜕变测试目录: " + metamorphictestDir.getPath());
                                    }

                                    // 将metamorphictestDir赋值给外部变量
                                    testDirectory1[0] = metamorphictestDir;
                                } catch (IOException ex) {
                                    ex.printStackTrace();
                                }
                            }
                        });
                    }
                     // 获取testDirectory的值
                    testDirectory = testDirectory1[0];

                    // 在对应metamorphictest目录下创建className+MetamorphicTest.java的对应蜕变测试类
                    if (testDirectory != null) {
                        VirtualFile finalTestDirectory = testDirectory;
                        ApplicationManager.getApplication().runWriteAction(new Runnable() {
                            @Override
                            public void run() {
                                VirtualFile testClassFile = finalTestDirectory.findChild(className + "MetamorphicTest.java");
                                if (testClassFile == null) {
                                    String testCode = generateMetamorphicTestTemplate(className, packageName);
                                    try {
                                        // 创建对应方法的蜕变测试类
                                        testClassFile = finalTestDirectory.createChildData(this, className + "MetamorphicTest.java");
                                        testClassFile.setBinaryContent(testCode.getBytes(StandardCharsets.UTF_8));
                                        System.out.println("Created test class file: " + testClassFile.getPath());
                                    } catch (IOException ex) {
                                        ex.printStackTrace();
                                    }
                                } else {
                                    System.out.println("Test class file already exists: " + testClassFile.getPath());
                                }
                            }
                        });
                    }


                    StringBuilder methodTestTemplates = new StringBuilder();
                    // 只生成是公共且有返回值的方法对应的模板方法     静态也考虑生成 !method.hasModifierProperty(PsiModifier.STATIC)&&
                    if (method.hasModifierProperty(PsiModifier.PUBLIC) &&  !method.getReturnType().equalsToText("void")) {
                        String methodName = method.getName();
                        System.out.println("Public non-static method in " + className + ": " + methodName);

                        // 在该蜕变测试模板中创建对应蜕变测试方法
                        String methodTestCode = generateMethodMetamorphicTestTemplate(methodName, method, className, project);
                        //当类有新增方法时，以追加的形式添加到原模板测试类中
                        if (!testMethodExists(project, className, methodName, methodTestCode,packageName)) {
                            methodTestTemplates.append(methodTestCode);
                        }
                    }

                    // 添加所有对应模板方法到对应模板类中
                    if (testDirectory != null && methodTestTemplates.length() > 0) {
                        VirtualFile finalTestDirectory1 = testDirectory;
                        ApplicationManager.getApplication().runWriteAction(new Runnable() {
                            @Override
                            public void run() {
                                VirtualFile testClassFile = finalTestDirectory1.findChild(className + "MetamorphicTest.java");
                                if (testClassFile != null) {
                                    try {
                                        String currentContent = new String(testClassFile.contentsToByteArray(), StandardCharsets.UTF_8);
                                        // 找到插入点插入新的方法
                                        int index = currentContent.lastIndexOf("}");
                                        if (index != -1) {
                                            String newContent = currentContent.substring(0, index) + methodTestTemplates.toString() + "\n}\n";
                                            testClassFile.setBinaryContent(newContent.getBytes(StandardCharsets.UTF_8));
                                            System.out.println("Added method tests to file: " + testClassFile.getPath());
                                        }
                                    } catch (IOException ex) {
                                        ex.printStackTrace();
                                    }
                                }
                            }
                        });
                    }

                }

            } else {
                JOptionPane.showMessageDialog(null, "所选方法不是公共有返回值的方法或所选方法名不全！", "警告", JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    //生成变异测试类模板
    private String generateMetamorphicTestTemplate(String className, String packageName) {
        StringBuilder template = new StringBuilder();
        template.append("package "+packageName+ ".test.metamorphictest;\n\n");
        // 添加测试类所在的包
        template.append("import ").append(packageName).append(".").append(className).append(";\n");
        template.append("import static org.junit.jupiter.api.Assertions.*;\n");
        template.append("import org.junit.jupiter.api.Test;\n\n");
        /**修改导入包的内容
         template.append("import org.junit.Test;\n");
         template.append("import static org.junit.Assert.*;\n\n");*/
        template.append("public class ").append(className).append("MetamorphicTest {\n\n");
        // 创建对应测试类的对象并设置为public
        template.append("\tpublic ").append(className).append(" ").append(toCamelCase(className)).append(" = new ").append(className).append("();\n\n");
        template.append("}\n");
        return template.toString();
    }

    // 生成变异测试方法模板
    private String generateMethodMetamorphicTestTemplate(String methodName, PsiMethod method, String className, Project project) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("现在你是编写基于蜕变测试(Metamorphic Testing)测试用例的专家.\n");
        prompt.append("请为以下 Java 方法生成基于蜕变测试的测试用例：\n");
        prompt.append("类名: ").append(className).append("\n");
        prompt.append("方法签名: ").append(method.getText()).append("\n");
        prompt.append("请你记住如下要求: \n");
        prompt.append("1.我需要你创建一个完整的基于蜕变测试的测试用例. \n");
        prompt.append("2.我需要你分析给出的java程序，并为生成的每个函数写出完整的实现，包括函数体内容。每个函数都应包含具体的示例代码或逻辑，不能只留下空白. \n");
        prompt.append("3.蜕变测试应该定义多组蜕变关系(Metamorphic Relations)，每个蜕变关系应该明确定义输入变换和预期输出关系. \n");
        prompt.append("4.确保编译没有任何错误. \n");
        prompt.append("5.生成的测试用例代码需要清晰地体现蜕变测试的结构. \n");
        prompt.append("6.用户只需要测试用例代码，不需要任何解释语句. \n");
        prompt.append("7.请参考以下基于蜕变测试的示例代码格式：\n");
        prompt.append("@Test\n");
        prompt.append("public void testMetamorphicRelation() {\n");
// 添加Arrange部分
        prompt.append("    // Arrange: 设置初始测试输入\n");
        prompt.append("    int x1 = initialInput1();\n");
        prompt.append("    int x2 = initialInput2();\n\n");

// 添加生成蜕变输入部分
        prompt.append("    // 产生蜕变输入\n");
        prompt.append("    int x1_prime = transformInput1(x1, x2);\n");
        prompt.append("    int x2_prime = transformInput2(x1, x2);\n\n");

// 添加Act部分
        prompt.append("    // Act: 调用被测的方法\n");
        prompt.append("    long y = classUnderTest.methodUnderTest(x1, x2);\n");
        prompt.append("    long y_prime = classUnderTest.methodUnderTest(x1_prime, x2_prime);\n\n");

// 添加Assert部分
        prompt.append("    // Assert: 验证蜕变关系\n");
        prompt.append("    assertTrue(verifyRelation(y, y_prime));\n");
        prompt.append("}\n\n");

// 添加初始输入生成方法
        prompt.append("// 初始输入生成方法\n");
        prompt.append("private int initialInput1() {\n");
        prompt.append("    // 根据需求生成初始输入\n");
        prompt.append("    return 0;\n");
        prompt.append("}\n\n");

        prompt.append("private int initialInput2() {\n");
        prompt.append("    // 根据需求生成初始输入\n");
        prompt.append("    return 0;\n");
        prompt.append("}\n\n");

// 添加输入变换方法
        prompt.append("// 输入变换方法\n");
        prompt.append("private int transformInput1(int x1, int x2) {\n");
        prompt.append("    // 根据需求定义输入变换\n");
        prompt.append("    return x1 + 1; // 示例变换\n");
        prompt.append("}\n\n");

        prompt.append("private int transformInput2(int x1, int x2) {\n");
        prompt.append("    // 根据需求定义输入变换\n");
        prompt.append("    return x2 + 1; // 示例变换\n");
        prompt.append("}\n\n");

// 添加验证蜕变关系的方法
        prompt.append("// 验证蜕变关系的方法\n");
        prompt.append("private boolean verifyRelation(long y, long y_prime) {\n");
        prompt.append("    // 根据需求定义蜕变关系\n");
        prompt.append("    // 例如，R(y, y') = y == y_prime\n");
        prompt.append("    return y == y_prime; // 示例关系\n");
        prompt.append("}\n");

        try {
            // 调用 LLM 获取测试方法代码
            String llmResult = callLLMForMetamorphicTest(prompt.toString());
            if (llmResult != null && !llmResult.isEmpty()) {
                System.out.println("llmresult:" + llmResult);
//                JSONObject jsonObject = JSON.parseObject(llmResult);
//                JSONObject messageObjec=jsonObject.getJSONObject("messages");
//                String content = messageObjec.getString("content");
                String content= llmResult;
                System.out.println(content);
                // 去除 ```java 和 ```
                content = content.replaceAll("```java", "").replaceAll("```", "");
                // 去除 import 相关
                content = content.replaceAll("import static org\\.junit\\.jupiter\\.api\\.Assertions\\.assertEquals;\\s*", "");
                content = content.replaceAll("import static org\\.junit\\.jupiter\\.api\\.Assertions\\.\\*;\\s*", "");
                content = content.replaceAll("import org\\.junit\\.jupiter\\.params\\.ParameterizedTest;\\s*", "");
                content = content.replaceAll("import org\\.junit\\.jupiter\\.params\\.provider\\.CsvSource;\\s*", "");
                content = content.replaceAll("import org\\.junit\\.jupiter\\.api\\.Test;\\s*", "");
                content = content.replaceAll("import org\\.junit\\.Test;\\s*", "");
                content = content.replaceAll("import static org\\.junit\\.Assert\\.\\*;\\s*", "");
                content = content.replaceAll("import .*\\.(\\w+);\\s*", "");
                // 去除多余的自然语言注释（如以“这段代码提供了一个”开头的行）
                content = content.replaceAll("(?m)^.*这段代码提供了一个.*$", "");
                content = content.replaceAll("(?m)^.*In this test.*$", "");
                content = content.replaceAll("(?m)^.*请注意.*$", "");
                content = content.replaceAll("(?m)^.*注意：.*$", "");
                // 去除多余的 public class ... { ... }
                content = content.replaceAll("(?s)public class \\w+\\s*\\{(.*)\\}\\s*$", "$1");
                content = content.replaceAll("(?s)class \\w+\\s*\\{(.*)\\}\\s*$", "$1");
                // 新增：移除所有中文解释文本段落
                content = content.replaceAll("(?m)^\\s*这个测试用例.*$", "");
                content = content.replaceAll("(?s)\\s*这个测试用例.*?(?=\\s*@Test|\\s*$)", "");
                content = content.replaceAll("(?s)\\s*这个测试用例.*?(?=\\s*\\}\\s*$)", "");
                // 移除任何包含中文字符的行
                content = content.replaceAll("(?m)^.*[\u4e00-\u9fa5]+.*$", "");
                // 移除测试方法后的中文解释段落
                content = content.replaceAll("(?s)(\\s*\\}\\s*)[\u4e00-\u9fa5]+.*?(?=\\s*$|\\s*@Test)", "$1");

                return content.trim() + "\n";
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        StringBuilder template = new StringBuilder();
        template.append("\t//y=P(x) x_2=r(x_1) y_2=R(y_1)\n");
        template.append("\t@Test\n");
        template.append("\tpublic void test").append(methodName.substring(0, 1).toUpperCase()).append(methodName.substring(1)).append("() {\n");
        template.append("\t\t//arrange 基础环境设置，对参数进行赋值\n");
        PsiParameterList parameterList = method.getParameterList();
        PsiParameter[] parameters = parameterList.getParameters();
        PsiType returnType = method.getReturnType();
        // 创建对应原测试用例输入参数列表
        StringBuilder parameter1=new StringBuilder();
        // 创建对应映射后测试用例输入参数列表
        StringBuilder parameter2=new StringBuilder();
        if (!returnType.equalsToText("void")) {

            for (int i = 0; i < parameters.length; i++) {
                if(parameters.length==1){
                    //一个输入参数就命名为x_1
                    template.append("\t\t").append(parameters[i].getType().getCanonicalText()).append(" x_1=");
                    template.append(initializeVariable(parameters[i].getType())+";\n");
                    parameter1.append("x_1");
                }else {
                    //多个输入参数就命名为x_11的形式
                    template.append("\t\t").append(parameters[i].getType().getCanonicalText()).append(" x_1"+(i+1)+"=");
                    template.append(initializeVariable(parameters[i].getType())+";\n");
                    parameter1.append("x_1"+(i+1));
                }
                if (i < parameters.length - 1) {
                    parameter1.append(", ");
                }
            }
            for (int i = 0; i < parameters.length; i++) {
                if (parameters.length==1){
                    //一个输入参数映射参数命名为x_2=r(x_1)的形式
                    template.append("\t\t").append(parameters[i].getType().getCanonicalText()).append(" x_2="+"r"+"("+parameter1+")  ;\n");
                    parameter2.append("x_2");
                }else {
                    //多个输入参数映射参数就命名为x_2=r1(x_11,x_12,...)的形式
                    template.append("\t\t").append(parameters[i].getType().getCanonicalText()).append(" x_2"+(i+1)+" =r"+(i+1)+"("+parameter1+");\n");;
                    parameter2.append("x_2"+(i+1));
                }
                if (i < parameters.length - 1) {
                    parameter2.append(", ");
                }
            }
        }

        //act 调用被测的方法
        template.append("\t\t//act 调用被测的方法\n");
        // 完成对应模板方法的调用，并通过y_1,y_2变量获取对应返回值
        template.append("\t\t").append(returnType.getCanonicalText()).append(" y_1 = ").append(toCamelCase(className)).append(".").append(methodName).append("(").append(parameter1); // Invoking the method
        template.append(");\n");
        template.append("\t\t").append(returnType.getCanonicalText()).append(" y_2 = ").append(toCamelCase(className)).append(".").append(methodName).append("(").append(parameter2); // Invoking the method
        template.append(");\n");

        //assert 断言结果
        template.append("\t\t//assert 判断真假\n");
        if (!returnType.equalsToText("void")) {
            template.append("\t\t").append("assertEquals(y_1, R(y_2));\n");
        }

        template.append("\t}\n\n");
        return template.toString();
    }


    // 单独方法用于根据类型初始化变量
    private String initializeVariable(PsiType type) {
        if (type instanceof PsiPrimitiveType) {
            PsiPrimitiveType primitiveType = (PsiPrimitiveType) type;
            if (primitiveType.equalsToText("int") || primitiveType.equalsToText("long") || primitiveType.equalsToText("short") || primitiveType.equalsToText("byte")) {
                return "0";
            } else if (primitiveType.equalsToText("double") || primitiveType.equalsToText("float")) {
                return "0.0";
            } else if (primitiveType.equalsToText("char")) {
                return "' '";
            } else if (primitiveType.equalsToText("boolean")) {
                return "false";
            }
        } else if (type instanceof PsiArrayType) {
            return "new " + type.getCanonicalText() + "{}";
        } else {
            return "null";
        }
        return "";
    }

    // 将字符串转换为驼峰命名
    private String toCamelCase(String className) {
        String[] parts = className.split("_");
        StringBuilder camelCaseString = new StringBuilder();
        for (String part : parts) {
            camelCaseString.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                camelCaseString.append(part.substring(1));
            }
        }
        return camelCaseString.toString();
    }

    // 判断该模板测试方法在该模板测试类中是否已存在    methodContent为生成的模板方法内容
    private boolean testMethodExists(Project project, String className, String methodName, String methodContent,String packageName) {
        VirtualFile sourceRoot = project.getBaseDir().findChild("src");
        //对应选中类所在包
        VirtualFile testDirectory = sourceRoot.findFileByRelativePath(packageName.replace(".", "/")).findChild("test").findChild("metamorphictest");
        if (testDirectory != null) {
            VirtualFile testClassFile = testDirectory.findChild(className + "MetamorphicTest.java");
            if (testClassFile != null) {
                try {
                    String currentContent = new String(testClassFile.contentsToByteArray(), StandardCharsets.UTF_8);
                    // 构建待比较的方法字符串
                    String methodString = methodContent;
                    return currentContent.contains(methodString);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return false;
    }
    private String callLLMForMetamorphicTest(String prompt) {
        // 这里以 OpenAI GPT-3/4 API 为例，实际使用时请替换为你的 LLM 服务地址和密钥
        String apiUrl = "https://api.chatanywhere.tech/v1/chat/completions";
        String apiKey = "sk-nDGzX5E8fP5UmeBY3VQj5TD7zT8UcXivsualqHKVGrXTTofn"; // 请替换为你的API密钥

        try {
            java.net.URL url = new java.net.URL(apiUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);

            // 构造请求体，注意转义
            String body = "{"
                    + "\"model\": \"gpt-4.1-mini\","
                    + "\"messages\": [{\"role\": \"user\", \"content\": \"" + prompt.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"}],"
                    + "\"temperature\": 0.5,"
                    + "\"max_tokens\": 2048"
                    + "}";
            System.out.println(body);
            try (java.io.OutputStream os = conn.getOutputStream()) {
                byte[] input = body.getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream(), "utf-8"))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                    // 解析返回的 JSON，提取生成的内容
                    String json = response.toString();
                    int index = json.indexOf("\"content\":\"");
                    if (index != -1) {
                        int start = index + 11;
                        int end = json.indexOf("\"", start);
                        if (end > start) {
                            String content = json.substring(start, end);
                            // 处理转义字符
                            content = content.replace("\\n", "\n").replace("\\\"", "\"");
                            return content;
                        }
                    }
                }
            } else {
                System.out.println("LLM API 调用失败，HTTP状态码: " + code);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

}
