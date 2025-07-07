package TActions.org.intelllij;


import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpMethod;
import org.springframework.util.MultiValueMap;

public class UnitTestAction extends AnAction {


    private static final Logger logger = LoggerFactory.getLogger(UnitTestAction.class);
    private static final Charset UTF8 = StandardCharsets.UTF_8;

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile[] selectedFiles = getSelectedFiles(project, e);
        VirtualFile srcDirectory = project.getBaseDir().findChild("src");

        if (selectedFiles != null) {
            // 获取选中的第一个文件
            PsiFile psiFile0 = PsiManager.getInstance(project).findFile(selectedFiles[0]);
            PsiJavaFile javaFile0 = null;

            // 如果是Java文件才创建对应文件夹和对应模板测试类
            if (psiFile0 instanceof PsiJavaFile) {
                javaFile0 = (PsiJavaFile) psiFile0;
                // 获取对应Java文件的包名
                String packageName = javaFile0.getPackageName();
                VirtualFile sourceRoot = project.getBaseDir().findChild("src");

                // 对应选中类所在包
                VirtualFile packageDir = sourceRoot.findFileByRelativePath(packageName.replace(".", "/"));
                VirtualFile testDirectory = null;

                if (packageDir != null) {
                    // 检查是否存在test文件夹，如果不存在则创建
                    VirtualFile testDir = packageDir.findChild("test");
                    if (testDir == null) {
                        try {
                            testDir = ApplicationManager.getApplication().runWriteAction(new Computable<VirtualFile>() {
                                @Override
                                public VirtualFile compute() {
                                    try {
                                        return packageDir.createChildDirectory(this, "test");
                                    } catch (IOException ex) {
                                        ex.printStackTrace();
                                        return null;
                                    }
                                }
                            });
                            if (testDir != null) {
                                System.out.println("已创建测试目录: " + testDir.getPath());
                            }
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }

                    // 在test目录下创建unittest目录
                    VirtualFile unittestDir = testDir != null ? testDir.findChild("unittest") : null;
                    if (unittestDir == null && testDir != null) {
                        try {
                            VirtualFile finalTestDir = testDir;
                            unittestDir = ApplicationManager.getApplication().runWriteAction(new Computable<VirtualFile>() {
                                @Override
                                public VirtualFile compute() {
                                    try {
                                        return finalTestDir.createChildDirectory(this, "unittest");
                                    } catch (IOException ex) {
                                        ex.printStackTrace();
                                        return null;
                                    }
                                }
                            });
                            if (unittestDir != null) {
                                System.out.println("已创建单元测试目录: " + unittestDir.getPath());
                            }
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }

                    // 确保 unittestDir 不为 null
                    if (unittestDir != null) {
                        testDirectory = unittestDir;
                    } else {
                        System.out.println("Error: 'unittest' directory could not be created or found.");
                    }
                }

                // 循环处理选中的Java文件
                for (VirtualFile selectedFile : selectedFiles) {
                    PsiFile psiFile = PsiManager.getInstance(project).findFile(selectedFile);
                    if (psiFile instanceof PsiJavaFile) {
                        PsiJavaFile javaFile = (PsiJavaFile) psiFile;
                        PsiClass[] classes = javaFile.getClasses();
                        for (PsiClass psiClass : classes) {
                            String className = psiClass.getName();
                            System.out.println("Selected class name: " + className);

                            // 在unittest目录下创建对应单元测试模板类
                            if (testDirectory != null) {
                                VirtualFile testClassFile = testDirectory.findChild(className + "UnitTest.java");
                                if (testClassFile == null) {
                                    String testCode = generateUnitTestTemplate(className, packageName);
                                    try {
                                        VirtualFile finalTestDirectory = testDirectory;
                                        testClassFile = ApplicationManager.getApplication().runWriteAction(new Computable<VirtualFile>() {
                                            @Override
                                            public VirtualFile compute() {
                                                try {
                                                    return finalTestDirectory.createChildData(this, className + "UnitTest.java");
                                                } catch (IOException ex) {
                                                    ex.printStackTrace();
                                                    return null;
                                                }
                                            }
                                        });

                                        if (testClassFile != null) {
                                            // 使用 runWriteAction 进行写操作
                                            VirtualFile finalTestClassFile = testClassFile;
                                            ApplicationManager.getApplication().runWriteAction(() -> {
                                                try {
                                                    finalTestClassFile.setBinaryContent(testCode.getBytes(StandardCharsets.UTF_8));
                                                } catch (IOException ioException) {
                                                    ioException.printStackTrace();
                                                }
                                                System.out.println("Created test class file: " + finalTestClassFile.getPath());

                                            });
                                        }
                                    } catch (Exception ex) {
                                        ex.printStackTrace();
                                    }
                                } else {
                                    System.out.println("Test class file already exists: " + testClassFile.getPath());
                                }
                            }

                            // 生成单元测试模板方法
                            PsiMethod[] methods = psiClass.getMethods();
                            StringBuilder methodTestTemplates = new StringBuilder();
                            methodTestTemplates.setLength(0);
                            for (PsiMethod method : methods) {
                                // 只生成公共且有返回值的方法的模板方法
                                if (method.hasModifierProperty(PsiModifier.PUBLIC) && !method.getReturnType().equalsToText("void")) {
                                    String methodName = method.getName();
                                    System.out.println("Public method in " + className + ": " + methodName);

                                    // 在测试类模板中创建对应的模板方法
                                    String methodTestCode = generateMethodUnitTestTemplate(methodName, method, className, project);
                                    if (!testMethodExists(project, className, methodName, packageName)) {
                                        methodTestTemplates.append(methodTestCode);
                                    }
                                }
                            }

                            // 将所有模板方法添加到模板类中
                            if (testDirectory != null && methodTestTemplates.length() > 0) {
                                VirtualFile testClassFile = testDirectory.findChild(className + "UnitTest.java");
                                if (testClassFile != null) {
                                    try {
                                        // 读取当前文件内容
                                        String currentContent = new String(testClassFile.contentsToByteArray(), StandardCharsets.UTF_8);
                                        int index = currentContent.lastIndexOf("}");
                                        if (index != -1) {
                                            // 构造新内容
                                            String newContent = currentContent.substring(0, index) + methodTestTemplates.toString() + "\n}\n";

                                            // 使用 runWriteAction 进行写操作
                                            ApplicationManager.getApplication().runWriteAction(() -> {
                                                try {
                                                    testClassFile.setBinaryContent(newContent.getBytes(StandardCharsets.UTF_8));
                                                    System.out.println("Added method tests to file: " + testClassFile.getPath());
                                                } catch (IOException ex) {
                                                    ex.printStackTrace();
                                                }
                                            });
                                        }
                                    } catch (IOException ex) {
                                        ex.printStackTrace();
                                    }
                                } else {
                                    System.out.println("Error: Test class file does not exist.");
                                }
                            }

                        }
                    }
                }
            }
        }
    }

    @NotNull
    private VirtualFile[] getSelectedFiles(Project project, AnActionEvent e) {
        return e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
    }

    // 生成单元测试类模板
    private String generateUnitTestTemplate(String className, String packageName) {
        StringBuilder template = new StringBuilder();
        template.append("package ").append(packageName).append(".test.unittest;\n\n");
        template.append("import ").append(packageName).append(".").append(className).append(";\n");
        template.append("import static org.junit.jupiter.api.Assertions.*;\n");
        template.append("import org.junit.jupiter.api.Test;\n\n");
        template.append("public class ").append(className).append("UnitTest {\n\n");
        template.append("\tpublic ").append(className).append(" ").append(toCamelCase(className)).append(" = new ").append(className).append("();\n\n");
        template.append("}\n");
        return template.toString();
    }

    // 生成单元测试方法模板
    private String generateMethodUnitTestTemplate(String methodName, PsiMethod method, String className, Project project) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("现在你是编写java测试用例的专家.\n");
        prompt.append("请为以下Java方法生成 JUnit4 单元测试方法：\n");
        prompt.append("类名: ").append(className).append("\n");
        prompt.append("方法签名: ").append(method.getText()).append("\n");
        prompt.append("请你记住如下要求: \n");
        prompt.append("1.我需要你使用JUnit4创建一个完整的单元测试. \n");
        prompt.append("2.确保最佳的行覆盖和分支覆盖. \n");
        prompt.append("3.确保编译没有任何错误. \n");
        prompt.append("4.生成的测试用例代码需要满足3A原则. \n");
        prompt.append("5.用户只需要测试用例代码，不需要任何解释语句. \n");
        prompt.append("6.请参考以下单元测试的示例代码格式：\n");
        prompt.append("@Test \n");
        prompt.append("public void testPow() { \n");
        prompt.append("    //arrange  基础环境设置，对参数进行赋值 \n");
        prompt.append("    int a = 0; \n");
        prompt.append("    int b = 0; \n");
        prompt.append("    long expected = 0; \n");
        prompt.append("    //act 调用被测的方法 \n");
        prompt.append("    long actual = pow.pow(a, b); \n");
        prompt.append("    //assert 判断真假 \n");
        prompt.append("    assertEquals(expected, actual); \n");
        prompt.append("} \n");

        try {
            // 调用 LLM 获取测试方法代码
            String llmResult = callLLMForUnitTest(prompt.toString());
            if (llmResult != null && !llmResult.isEmpty()) {
                JSONObject jsonObject = JSON.parseObject(llmResult);
                JSONObject dataObject = jsonObject.getJSONObject("data");
                String content = dataObject.getString("content");
                // 去除 ```java 和 ```
                content = content.replaceAll("```java", "").replaceAll("```", "");
                // 去除 import 相关
                content = content.replaceAll("import static org\\.junit\\.jupiter\\.api\\.Assertions\\.assertEquals;\\s*", "");
                content = content.replaceAll("import static org\\.junit\\.jupiter\\.api\\.Assertions\\.\\*;\\s*", "");
                content = content.replaceAll("import org\\.junit\\.jupiter\\.api\\.Test;\\s*", "");
                content = content.replaceAll("import org\\.junit\\.Test;\\s*", "");
                content = content.replaceAll("import static org\\.junit\\.Assert\\.\\*;\\s*", "");
                // 去除多余的自然语言注释（如以“这段代码提供了一个”开头的行）
                content = content.replaceAll("(?m)^.*这段代码提供了一个.*$", "");
                content = content.replaceAll("(?m)^.*In this test.*$", "");
                // 去除多余的 public class ... { ... }
                content = content.replaceAll("(?s)public class \\w+\\s*\\{(.*)\\}\\s*$", "$1");
                // 去除多余空行
                content = content.replaceAll("(?m)^\\s*\\n", "");
                return content.trim() + "\n";
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        StringBuilder template = new StringBuilder();
        template.append("\t@Test\n");
        template.append("\tpublic void test").append(methodName.substring(0, 1).toUpperCase()).append(methodName.substring(1)).append("() {\n");
        template.append("\t\t//arrange  基础环境设置，对参数进行赋值\n");
        template.append(generateLocalVariableInitialization(method));
        PsiType returnType = method.getReturnType();
        if (!returnType.equalsToText("void")) {
            template.append("\t\t").append(returnType.getCanonicalText()).append(" expected = ").append(initializeVariable(returnType)).append(";\n");
        }
        template.append("\t\t//act 调用被测的方法\n");
        template.append("\t\t").append(returnType.getCanonicalText()).append(" actual = ").append(toCamelCase(className)).append(".").append(methodName).append("(");
        PsiParameter[] parameters = method.getParameterList().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            template.append(parameters[i].getName());
            if (i < parameters.length - 1) {
                template.append(", ");
            }
        }
        template.append(");\n");
        template.append("\t\t//assert 判断真假\n");
        if (!returnType.equalsToText("void")) {
            template.append("\t\tassertEquals(expected, actual);\n");
        }
        template.append("\t}\n\n");
        return template.toString();
    }

    private String generateLocalVariableInitialization(PsiMethod method) {
        StringBuilder initializationCode = new StringBuilder();
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            initializationCode.append("\t\t").append(parameter.getType().getCanonicalText()).append(" ").append(parameter.getName()).append(" = ");
            initializationCode.append(initializeVariable(parameter.getType())).append(";\n");
        }
        return initializationCode.toString();
    }

    private String initializeVariable(PsiType type) {
        if (type instanceof PsiPrimitiveType) {
            PsiPrimitiveType primitiveType = (PsiPrimitiveType) type;
            if (primitiveType.equalsToText("int") || primitiveType.equalsToText("long") || primitiveType.equalsToText("short") || primitiveType.equalsToText("byte")) {
                return "0";
            } else if (primitiveType.equalsToText("double") || primitiveType.equalsToText("float")) {
                return "0.0";
            } else if (primitiveType.equalsToText("boolean")) {
                return "false";
            } else if (primitiveType.equalsToText("char")) {
                return "'\u0000'";
            }
        } else {
            return "null";
        }
        return "null";
    }

    // 检查测试方法是否存在
    private boolean testMethodExists(Project project, String className, String methodName, String packageName) {
        PsiClass testClass = JavaPsiFacade.getInstance(project).findClass(packageName + ".test.unittest." + className + "UnitTest", GlobalSearchScope.projectScope(project));
        if (testClass != null) {
            for (PsiMethod method : testClass.getMethods()) {
                if (method.getName().equalsIgnoreCase("test" + methodName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String toCamelCase(String str) {
        return str.substring(0, 1).toLowerCase() + str.substring(1);
    }

    private String callLLMForUnitTest(String prompt) {
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

