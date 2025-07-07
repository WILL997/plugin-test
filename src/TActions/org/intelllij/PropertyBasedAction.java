
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

public class PropertyBasedAction extends AnAction {
    private static final Logger logger = LoggerFactory.getLogger(PropertyBasedAction.class);
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
                    // 获取对应Java文件的包名
                    String packageName = javaFile.getPackageName();
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

                                    // 检查并创建 propertybased 目录
                                    VirtualFile propertybasedDir = testDir.findChild("propertybased");
                                    if (propertybasedDir == null) {
                                        propertybasedDir = testDir.createChildDirectory(this, "propertybased");
                                        System.out.println("已创建基于属性驱动测试目录: " + propertybasedDir.getPath());
                                    }

                                    // 返回 propertybased 目录
                                    return propertybasedDir;

                                } catch (IOException ex) {
                                    ex.printStackTrace();
                                    return null;
                                }
                            }
                        });
                    }

                    // 在对应 propertybased 目录下创建 className + "PropertyBased.java" 的基于属性驱动类
                    if (testDirectory != null) {
                        VirtualFile finalTestDirectory = testDirectory;

                        // 使用 runWriteAction 封装文件创建操作
                        ApplicationManager.getApplication().runWriteAction(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    VirtualFile testClassFile = finalTestDirectory.findChild(className + "PropertyBased.java");
                                    if (testClassFile == null) {
                                        String testCode = generatePropertyDrivenTestTemplate(className, packageName);

                                        // 创建基于属性驱动类文件
                                        testClassFile = finalTestDirectory.createChildData(this, className + "PropertyBased.java");
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

                        // 在该基于属性驱动模板中创建对应基于属性驱动方法
                        String methodTestCode = generatePropertyDrivenTestMethod( method, className, project);
                        //当类有新增方法时，以追加的形式添加到原模板测试类中
                        if (!testMethodExists(project, className, methodName, methodTestCode,packageName)) {
                            methodTestTemplates.append(methodTestCode);
                        }
                    }

                    if (testDirectory != null && methodTestTemplates.length() > 0) {
                        VirtualFile testClassFile = testDirectory.findChild(className + "PropertyBased.java");

                        if (testClassFile != null) {
                            // 使用 runWriteAction 封装文件内容的读取和写入操作
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

    //生成对应基于属性驱动测试类模板
    private String generatePropertyDrivenTestTemplate(String className, String packageName) {
        StringBuilder template = new StringBuilder();
        template.append("package "+packageName+ ".test.propertybased;\n\n");
        // 添加测试类所在的包
        template.append("import ").append(packageName).append(".").append(className).append(";\n");
        template.append("import org.junit.runner.RunWith;\n");
        template.append("import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;\n");
        template.append("import com.pholser.junit.quickcheck.Property;\n");
        template.append("import com.pholser.junit.quickcheck.generator.InRange;\n");
        template.append("import com.pholser.junit.quickcheck.generator.Size;\n");
        template.append("import static org.junit.Assert.*;\n\n");
        template.append("@RunWith(JUnitQuickcheck.class)\n");
        template.append("public class ").append(className).append("PropertyBased {\n\n");
        // 创建对应测试类的对象并设置为public
        template.append("\tpublic ").append(className).append(" ").append(toCamelCase(className)).append(" = new ").append(className).append("();\n\n");
        template.append("}\n");
        return template.toString();
    }


    //生成对应基于属性驱动测试类模板
    private String generatePropertyDrivenTestMethod(PsiMethod method, String className, Project project) {
        // 获取方法名
         String methodName = method.getName();
        // 构造 prompt
        StringBuilder prompt = new StringBuilder();
        prompt.append("现在你是编写基于属性测试测试用例的专家.\n");
        prompt.append("请为以下 Java 方法生成基于属性测试的测试用例：\n");
        prompt.append("类名: ").append(className).append("\n");
        prompt.append("方法签名: ").append(method.getText()).append("\n");
        prompt.append("请你记住如下要求: \n");
        prompt.append("1.我需要你创建一个完整的基于属性的测试. \n");
        prompt.append("2.确保测试用例能有效验证方法在不同输入属性下的正确性. \n");
        prompt.append("3.确保编译没有任何错误. \n");
        prompt.append("4.生成的测试用例代码需要清晰地体现对属性的验证逻辑. \n");
        prompt.append("5.用户只需要测试用例代码，不需要任何解释语句. \n");
        prompt.append("6.请参考以下基于属性测试的示例代码格式：\n");
        prompt.append("// shrink模式，收缩模式，使得属性不成立的最小输入集合 \n");
        prompt.append("@Property(shrink = true) \n");
        prompt.append("public void testPow(@InRange(min = \"-1000\", max = \"1000\") int a, @InRange(min = \"-1000\", max = \"1000\") int b) throws Exception { \n");
        prompt.append("    // act \n");
        prompt.append("    long actual = Pow.pow(a, b); \n");
        prompt.append("    // 打印随机输入与实际结果 \n");
        prompt.append("    System.out.printf(\"number is \" + a+\", \"+b + \" , actual is \" + actual + \"\\n\"); \n");
        prompt.append("    // assert 填入对应属性 \n");
        prompt.append("    // assertTrue(actual ?) \n");
        prompt.append("} \n");
        try {
            // 调用 LLM 获取测试方法代码
            String llmResult = callLLMForPropertyDrivenTest(prompt.toString());
            if (llmResult != null && !llmResult.isEmpty()) {
                JSONObject jsonObject = JSON.parseObject(llmResult);
                JSONObject dataObject = jsonObject.getJSONObject("data");
                String content = dataObject.getString("content");
                // 去除 ```java 和 ```
                content = content.replaceAll("```java", "").replaceAll("```", "");
//                // 去除 import 相关
//                content = content.replaceAll("import static org\\.junit\\.jupiter\\.api\\.Assertions\\.assertEquals;\\s*", "");
//                content = content.replaceAll("import static org\\.junit\\.jupiter\\.api\\.Assertions\\.\\*;\\s*", "");
//                content = content.replaceAll("import org\\.junit\\.jupiter\\.api\\.Test;\\s*", "");
//                content = content.replaceAll("import org\\.junit\\.Test;\\s*", "");
//                content = content.replaceAll("import static org\\.junit\\.Assert\\.\\*;\\s*", "");
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



        // 获取方法的参数列表
        PsiParameterList parameterList = method.getParameterList();
        PsiParameter[] parameters = parameterList.getParameters();
        int paramCount = parameters.length;

        // 构建方法签名
        template.append("\t// shrink模式，收缩模式，使得属性不成立的最小输入集合\n");
        template.append("\t@Property(shrink = true)\n");
        template.append("\tpublic void ").append("test").append(methodName.substring(0, 1).toUpperCase()).append(methodName.substring(1)).append("(");

        // 添加参数注解和方法参数
        for (int i = 0; i < paramCount; i++) {
            PsiParameter parameter = parameters[i];
            // 添加@InRange注解
            template.append(generateParameterAnnotation(parameter)).append(" ");
            // 如果参数是数组，添加@Size注解
            if (parameter.getType() instanceof PsiArrayType) {
                String array=parameter.getType().getPresentableText();
                // 找到 '[' 的索引
                int index = array.indexOf('[');
                // 提取 '[' 之前的子字符串
                String extractedString = array.substring(0, index);
                template.append(extractedString).append(" ");
                template.append(generateSizeAnnotation(parameter)).append(" ");
                template.append("[]").append(" ");
            }else {
                template.append(parameter.getType().getPresentableText()).append(" ");
            }
            template.append(parameter.getName());
            if (i < paramCount - 1) {
                template.append(", ");
            }
        }

        // 添加方法主体
        template.append(") throws Exception {\n");
        template.append("\t\t// act\n");

        // 创建方法调用语句
        template.append("\t\t").append(method.getReturnType().getPresentableText()).append(" actual = ").append(toCamelCase(className)).append(".").append(methodName).append("(");
        for (int i = 0; i < paramCount; i++) {
            template.append(parameters[i].getName());
            if (i < paramCount - 1) {
                template.append(", ");
            }
        }
        template.append(");\n");

        template.append("\t\t// 打印随机输入与实际结果\n");
        StringBuilder params=new StringBuilder();
        for (int i = 0; i < paramCount; i++) {
            //如果为数组还需添加.length作为输出
            if(parameters[i].getType() instanceof PsiArrayType){
                params.append(parameters[i].getName()+".length");
            }else {
                params.append(parameters[i].getName());
            }
            if (i < paramCount - 1) {
                params.append("+\", \"+");
            }
        }
        template.append("\t\tSystem.out.printf(\"number is \" + ").append(params).append(" + \" , actual is \" + actual + \"\\n\");\n");
        // 添加断言语句
        template.append("\t\t// assert 填入对应属性\n");
        template.append("\t\t// assertTrue(actual ?)\n");

        // 方法结束
        template.append("\t}\n\n");

        return template.toString();
    }




    // 生成参数的@InRange注解
    private String generateParameterAnnotation(PsiParameter parameter) {
        StringBuilder annotation = new StringBuilder();
        annotation.append("@InRange(min = \"");
        annotation.append(initializeMinValue(parameter.getType()));
        annotation.append("\", max = \"");
        annotation.append(initializeMaxValue(parameter.getType()));
        annotation.append("\")");
        return annotation.toString();
    }

    // 生成参数的@Size注解
    private String generateSizeAnnotation(PsiParameter parameter) {
        return "@Size(min = 5, max = 10)";
    }

    // 根据参数类型初始化 min 的默认值
    private String initializeMinValue(PsiType type) {
        StringBuilder defaultValue = new StringBuilder();
        if (type instanceof PsiPrimitiveType) {
            PsiPrimitiveType primitiveType = (PsiPrimitiveType) type;
            if (primitiveType.equalsToText("int") || primitiveType.equalsToText("long") || primitiveType.equalsToText("short") || primitiveType.equalsToText("byte")) {
                defaultValue.append("-1000");
            } else if (primitiveType.equalsToText("double") || primitiveType.equalsToText("float")) {
                defaultValue.append("-1000.0");
            } else if (primitiveType.equalsToText("char")) {
                defaultValue.append("'A'");
            } else if (primitiveType.equalsToText("boolean")) {
                defaultValue.append("false");
            }
        } else if (type instanceof PsiArrayType) {   //数组初始化
            PsiType componentType = ((PsiArrayType) type).getComponentType();
            if (componentType instanceof PsiPrimitiveType) {
                defaultValue.append("-1000");
            } else {
                defaultValue.append("null");
            }
        } else {
            defaultValue.append("null");
        }

        return defaultValue.toString();
    }

    // 根据参数类型初始化 max 的默认值
    private String initializeMaxValue(PsiType type) {
        StringBuilder defaultValue = new StringBuilder();

        if (type instanceof PsiPrimitiveType) {
            PsiPrimitiveType primitiveType = (PsiPrimitiveType) type;
            if (primitiveType.equalsToText("int") || primitiveType.equalsToText("long") || primitiveType.equalsToText("short") || primitiveType.equalsToText("byte")) {
                defaultValue.append("1000");
            } else if (primitiveType.equalsToText("double") || primitiveType.equalsToText("float")) {
                defaultValue.append("1000.0");
            } else if (primitiveType.equalsToText("char")) {
                defaultValue.append("'Z'");
            } else if (primitiveType.equalsToText("boolean")) {
                defaultValue.append("true");
            }
        } else if (type instanceof PsiArrayType) {  //数组初始化
            PsiType componentType = ((PsiArrayType) type).getComponentType();
            if (componentType instanceof PsiPrimitiveType) {
                defaultValue.append("1000");
            } else {
                defaultValue.append("null");
            }
        } else {
            defaultValue.append("null");
        }

        return defaultValue.toString();
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
        VirtualFile testDirectory = sourceRoot.findFileByRelativePath(packageName.replace(".", "/")).findChild("test").findChild("propertybased");
        if (testDirectory != null) {
            VirtualFile testClassFile = testDirectory.findChild(className + "PropertyBased.java");
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

    private String callLLMForPropertyDrivenTest(String prompt) {
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
