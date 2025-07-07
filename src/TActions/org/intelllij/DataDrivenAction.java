
package TActions.org.intelllij;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
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

public class DataDrivenAction extends AnAction {

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

                    VirtualFile packageDir = sourceRoot.findFileByRelativePath(packageName.replace(".", "/"));
                    VirtualFile testDirectory = null;

                    if (packageDir != null) {
                        // 使用 runWriteAction 封装所有写操作
                        testDirectory = ApplicationManager.getApplication().runWriteAction(new Computable<VirtualFile>() {
                            @Override
                            public VirtualFile compute() {
                                VirtualFile testDir = packageDir.findChild("test");
                                try {
                                    // 检查并创建 test 目录
                                    if (testDir == null) {
                                        testDir = packageDir.createChildDirectory(this, "test");
                                        System.out.println("已创建测试目录: " + testDir.getPath());
                                    }

                                    // 检查并创建 datadriven 目录
                                    VirtualFile datadrivenDir = testDir.findChild("datadriven");
                                    if (datadrivenDir == null) {
                                        datadrivenDir = testDir.createChildDirectory(this, "datadriven");
                                        System.out.println("已创建数据驱动测试目录: " + datadrivenDir.getPath());
                                    }

                                    // 返回 datadriven 目录
                                    return datadrivenDir;

                                } catch (IOException ex) {
                                    ex.printStackTrace();
                                    return null;
                                }
                            }
                        });
                    }

                      // 在对应 datadriven 目录下创建 className + "DataDrive.java" 的数据驱动类
                    if (testDirectory != null) {
                        VirtualFile finalTestDirectory = testDirectory;

                        // 使用 runWriteAction 封装文件创建操作
                        ApplicationManager.getApplication().runWriteAction(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    VirtualFile testClassFile = finalTestDirectory.findChild(className + "DataDrive.java");
                                    if (testClassFile == null) {
                                        String testCode = generateDataDrivenTestTemplate(className, packageName);

                                        // 创建数据驱动类文件
                                        testClassFile = finalTestDirectory.createChildData(this, className + "DataDrive.java");
                                        testClassFile.setBinaryContent(testCode.getBytes(StandardCharsets.UTF_8));
                                        System.out.println("Created test class file: " + testClassFile.getPath());
                                    } else {
                                        System.out.println("Test class file already exists: " + testClassFile.getPath());
                                    }
                                } catch (IOException ex) {
                                    ex.printStackTrace();
                                }
                            }
                        });
                    }


                    StringBuilder methodTestTemplates = new StringBuilder();
                    // 只生成是公共且有返回值的方法对应的模板方法     静态也考虑生成 !method.hasModifierProperty(PsiModifier.STATIC)&&
                    if (method.hasModifierProperty(PsiModifier.PUBLIC) &&  !method.getReturnType().equalsToText("void")) {
                        String methodName = method.getName();
                        System.out.println("Public non-static method in " + className + ": " + methodName);

                        // 在该数据驱动模板中创建对应数据驱动方法
                        String methodTestCode = generateMethodDataDrivenTestTemplate(methodName, method, className, project);
                        //当类有新增方法时，以追加的形式添加到原模板测试类中
                        if (!testMethodExists(project, className, methodName, methodTestCode,packageName)) {
                            methodTestTemplates.append(methodTestCode);
                        }
                    }

                    if (testDirectory != null && methodTestTemplates.length() > 0) {
                        VirtualFile testClassFile = testDirectory.findChild(className + "DataDrive.java");

                        if (testClassFile != null) {
                            // 使用 runWriteAction 封装文件修改操作
                            ApplicationManager.getApplication().runWriteAction(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        // 读取当前文件内容
                                        String currentContent = new String(testClassFile.contentsToByteArray(), StandardCharsets.UTF_8);

                                        // 找到插入点（文件末尾的 "}" 之前）
                                        int index = currentContent.lastIndexOf("}");
                                        if (index != -1) {
                                            // 将新方法模板插入到类末尾的 "}" 前
                                            String newContent = currentContent.substring(0, index) + methodTestTemplates.toString() + "\n}\n";

                                            // 更新文件内容
                                            testClassFile.setBinaryContent(newContent.getBytes(StandardCharsets.UTF_8));
                                            System.out.println("Added method tests to file: " + testClassFile.getPath());
                                        }
                                    } catch (IOException ex) {
                                        ex.printStackTrace();
                                    }
                                }
                            });
                        }
                    }

                }

            } else {
                JOptionPane.showMessageDialog(null, "所选方法不是公共有返回值的方法或所选方法名不全！", "警告", JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    //生成数据驱动测试类模板
    private String generateDataDrivenTestTemplate(String className, String packageName) {
        StringBuilder template = new StringBuilder();
        template.append("package "+packageName+ ".test.datadriven;\n\n");
        // 添加测试类所在的包
        template.append("import ").append(packageName).append(".").append(className).append(";\n");
        template.append("import org.junit.jupiter.params.ParameterizedTest;\n");
        template.append("import org.junit.jupiter.params.provider.CsvSource;\n");
        template.append("import static org.junit.Assert.*;\n\n");
        template.append("public class ").append(className).append("DataDrive {\n\n");
        // 创建对应测试类的对象并设置为public
        template.append("\tpublic ").append(className).append(" ").append(toCamelCase(className)).append(" = new ").append(className).append("();\n\n");
        template.append("}\n");
        return template.toString();
    }

    // 生成数据驱动测试方法模板
    private String generateMethodDataDrivenTestTemplate(String methodName, PsiMethod method, String className, Project project) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("现在你是编写基于数据驱动测试测试用例的专家.\n");
        prompt.append("请为以下 Java 方法生成基于数据驱动的测试用例：\n");
        prompt.append("类名: ").append(className).append("\n");
        prompt.append("方法签名: ").append(method.getText()).append("\n");
        prompt.append("请你记住如下要求: \n");
        prompt.append("1.我需要你创建一个完整的基于数据驱动的测试. \n");
        prompt.append("2.测试用例应包含多组不同的输入数据，覆盖各种边界条件和常规场景. \n");
        prompt.append("3.使用参数化测试方法，如JUnit的@ParameterizedTest和相关注解. \n");
        prompt.append("4.确保测试用例能有效验证方法在不同输入数据下的正确性. \n");
        prompt.append("5.确保编译没有任何错误. \n");
        prompt.append("6.生成的测试用例代码需要清晰地体现数据驱动测试的结构. \n");
        prompt.append("7.用户只需要测试用例代码，不需要任何解释语句. \n");
        prompt.append("8.请参考以下基于数据驱动测试的示例代码格式：\n");
        prompt.append("    @ParameterizedTest\n");
        prompt.append("    @CsvSource({\n");
        prompt.append("        \"1, 1, 2\",\n");
        prompt.append("        \"5, 3, 8\",\n");
        prompt.append("        \"0, 0, 0\",\n");
        prompt.append("        \"-1, -2, -3\"\n");
        prompt.append("    })\n");
        prompt.append("    void testAdd(int a, int b, int expected) {\n");
        prompt.append("        assertEquals(expected, Calculator.add(a, b));\n");
        prompt.append("    }\n\n");

        try {
            // 调用 LLM 获取测试方法代码
            String llmResult = callLLMForDataDrivenTest(prompt.toString());
            if (llmResult != null && !llmResult.isEmpty()) {
                JSONObject jsonObject = JSON.parseObject(llmResult);
                JSONObject dataObject = jsonObject.getJSONObject("data");
                String content = dataObject.getString("content");
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
                // 去除多余空行
                content = content.replaceAll("(?m)^\\s*\\n", "");
                // 去除末尾的中文注释
                content = content.replaceAll("(?s)(.*?)(?:\\s*//.*[\u4e00-\u9fa5].*$|\\s*[\\u4e00-\\u9fa5].*$)", "$1");
                return content.trim() + "\n";
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        StringBuilder template = new StringBuilder();

        // 创建数据驱动测试方法
        template.append("\t@ParameterizedTest\n");
        template.append("\t@CsvSource({\n");

        // 添加 CSV 数据，每一行表示一组测试数据
        // 根据被测方法的参数列表和返回值的初始值来填充 CSV 数据
        PsiParameterList parameterList = method.getParameterList();
        PsiParameter[] parameters = parameterList.getParameters();
        int paramCount = parameters.length;
        //函数返回值初始化
        String defaultValue = initializeVariable(method.getReturnType());
        for (int i = 0; i < 2; i++) { // 默认生成两行数据
            template.append("\t\t\"");
            for (int j = 0; j < paramCount; j++) {
                template.append(initializeVariable(parameters[j].getType()));
                if (j < paramCount - 1) {
                    template.append(", ");
                }
            }
            // 添加返回值的初始值
            template.append(", ").append(defaultValue).append("\"");
            if (i < 1) {
                template.append(",\n");
            }
        }

        template.append("\n\t})\n");
        template.append("\tpublic void ").append(method.getName()).append("(");
        // 添加测试方法的参数列表
        for (int i = 0; i < paramCount; i++) {
            template.append(parameters[i].getType().getPresentableText()).append(" ").append(parameters[i].getName());
            if (i < paramCount - 1) {
                template.append(", ");
            }
        }
        template.append(", ").append(method.getReturnType().getPresentableText()).append(" expected) {\n");
        // 使用数据驱动进行测试
        template.append("\t\t// arrange\n\n");
        template.append("\t\t// act\n");
        // 完成对应模板方法的调用，并通过actual变量获取对应返回值
        // 创建一个对应类型的名叫expected的预期局部变量并初始化值
        PsiType returnType = method.getReturnType();
        template.append("\t\t").append(returnType.getCanonicalText()).append(" actual = ").append(toCamelCase(className)).append(".").append(method.getName()).append("("); // Invoking the method
        for (int i = 0; i < parameters.length; i++) {
            template.append(parameters[i].getName()); // Passing parameters
            if (i < parameters.length - 1) {
                template.append(", ");
            }
        }
        template.append(");\n");
        template.append("\t\t// assert\n");
        template.append("\t\t").append("assertEquals(expected, actual);\n");
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
        VirtualFile testDirectory = sourceRoot.findFileByRelativePath(packageName.replace(".", "/")).findChild("test").findChild("datadriven");
        if (testDirectory != null) {
            VirtualFile testClassFile = testDirectory.findChild(className + "DataDrive.java");
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
    private String callLLMForDataDrivenTest(String prompt) {
        // vivo LLM API参数
        String appId = "2025526367"; // TODO: 替换为实际appId
        String appKey = "ncuHJofWsMlcArbL"; // TODO: 替换为实际appKey
        String URI = "/vivogpt/completions";
        String DOMAIN = "api-ai.vivo.com.cn";
        String METHOD = "POST";
        String model = "vivo-BlueLM-TB-Pro";

        try {
            // 构建请求参数
            UUID requestId = UUID.randomUUID();
            Map<String, Object> map = new HashMap<>();
            map.put("requestId", requestId.toString());
            String queryStr = mapToQueryString(map);

            // 构建请求体
            Map<String, Object> data = new HashMap<>();
            data.put("prompt", prompt);
            data.put("model", model);
            Map<String, Object> extra = new HashMap<>();
            extra.put("temperature",new Float(0.1));
            data.put("extra",extra);
            UUID sessionId = UUID.randomUUID();
            data.put("sessionId", sessionId.toString());
            System.out.println(data.toString());
            // 生成鉴权头
            org.springframework.http.HttpHeaders headers = generateAuthHeaders(appId, appKey, METHOD, URI, queryStr);
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON); // 推荐用setContentType
            System.out.println("鉴权头:"+ headers);

            // 构造URL和请求体
            String url = String.format("http://%s%s?%s", DOMAIN, URI, queryStr);
            String requsetBodyString = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data);

            RestTemplate restTemplate = new RestTemplate();
            // headers 已经包含所有需要的头部信息
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> requestEntity = new HttpEntity<String>(requsetBodyString, (org.springframework.util.MultiValueMap<String, String>) headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, requestEntity, String.class);
            if (response.getStatusCode() == org.springframework.http.HttpStatus.OK) {
                // 直接返回body，后续解析内容
                return response.getBody();
            } else {
                System.out.println("Error response: " + response.getStatusCode());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }
    // vivogpt风格的mapToQueryString方法
    public static String mapToQueryString(Map<String, Object> map) {
        if (map.isEmpty()) {
            return "";
        }
        StringBuilder queryStringBuilder = new StringBuilder();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (queryStringBuilder.length() > 0) {
                queryStringBuilder.append("&");
            }
            queryStringBuilder.append(entry.getKey());
            queryStringBuilder.append("=");
            queryStringBuilder.append(entry.getValue());
        }
        return queryStringBuilder.toString();
    }

    public static HttpHeaders generateAuthHeaders(String appId, String appKey, String method, String uri, String queryParams)
            throws UnsupportedEncodingException {
        String nonce = generateRandomString(8);
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String canonical_query_string = generateCanonicalQueryString(queryParams);
        String signed_headers_string = String.format("x-ai-gateway-app-id:%s\n" +
                "x-ai-gateway-timestamp:%s\nx-ai-gateway-nonce:%s", appId, timestamp, nonce);
//        System.out.println(signed_headers_string);
        String[] fields = {
                method,
                uri,
                canonical_query_string,
                appId,
                timestamp,
                signed_headers_string
        };
        final StringBuilder buf = new StringBuilder(fields.length * 16);
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                buf.append("\n");
            }
            if (fields[i] != null) {
                buf.append(fields[i]);
            }
        }
//        System.out.println(buf.toString());
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-AI-GATEWAY-APP-ID", appId.toString());
        headers.add("X-AI-GATEWAY-TIMESTAMP", timestamp.toString());
        headers.add("X-AI-GATEWAY-NONCE", nonce.toString());
        headers.add("X-AI-GATEWAY-SIGNED-HEADERS", "x-ai-gateway-app-id;x-ai-gateway-timestamp;x-ai-gateway-nonce");
        headers.add("X-AI-GATEWAY-SIGNATURE", generateSignature(appKey, buf.toString()));
        return headers;
    }

    private static String generateRandomString(int len) {
        String chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        Random rnd = new Random();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++)
            sb.append(chars.charAt(rnd.nextInt(chars.length())));
        return sb.toString();
    }

    private static String generateCanonicalQueryString(String queryParams) throws UnsupportedEncodingException {
        if (queryParams == null || queryParams.length() <= 0) {
            return "";
        }

        HashMap<String, String> params = new HashMap<>();
        String[] param = queryParams.split("&");
        for (String item : param) {
            String[] pair = item.split("=");
            if (pair.length == 2) {
                params.put(pair[0], pair[1]);
            } else {
                params.put(pair[0], "");
            }
        }
        SortedSet<String> keys = new TreeSet<>(params.keySet());
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String key : keys) {
            if (!first) {
                sb.append("&");
            }
            String item = URLEncoder.encode(key, UTF8.name()) + "=" + URLEncoder.encode(params.get(key), UTF8.name());
            sb.append(item);
            first = false;
        }

        return sb.toString();
    }

    private static String generateSignature(String appKey, String signingString) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret = new SecretKeySpec(appKey.getBytes(UTF8), mac.getAlgorithm());
            mac.init(secret);
            return Base64.getEncoder().encodeToString(mac.doFinal(signingString.getBytes()));
        } catch (Exception err) {
            logger.error("create sign exception", err);
            return "";
        }
    }

}
