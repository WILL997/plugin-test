package TActions.org.intelllij;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.intellij.execution.impl.ConsoleViewImpl;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import config.IpConfig;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GetScoreAction extends AnAction {

    // 全局静态变量   存储团队id
    private static String gid = null;
    //存储从该用户目录下读取problems里面的测试集
    private static final Map<String, String> problemMap = new HashMap<>();
    //存储用户登录后的权限
    private static String authentication = null;
    //存储一次提交的分数 临时存储
    private List<String> scores = new ArrayList<>();

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            Messages.showErrorDialog("No project found.", "Error");
            return;
        }

        String uid = LoginAction.getUid(); // 获取用户id
        authentication = LoginAction.getAuthorization(); // 获取用户登录后的权限
        if (uid == null || uid.isEmpty() || authentication == null || authentication.isEmpty()) {
            Messages.showInfoMessage("Please LoginTOJ", "Info");
            return;
        }

        String userName = toPinyin(LoginAction.getUserName()); // 获取用户登录后的名称

        // 获取用户选择的文件或文件夹
        VirtualFile[] virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (virtualFiles != null && virtualFiles.length > 0) {
            //只能选择以下文件夹来提交获取分数
            List<String> validFolders = Arrays.asList("unittest", "propertybased", "metamorphictest", "datadriven");
            //判断是否是以上文件的标志
            boolean isValidSelection = false;

            for (VirtualFile virtualFile : virtualFiles) {
                if (virtualFile.isDirectory() && validFolders.contains(virtualFile.getName())) {
                    isValidSelection = true;

                    // 获取选中文件夹的上层的上层文件夹
                    VirtualFile parent = virtualFile.getParent();
                    if (parent != null) {
                        parent = parent.getParent();
                    }
                    if (parent != null) {
                        checkAndReadProblemsJson(parent, virtualFile);
                    }
                }
            }

            if (!isValidSelection) {
                Messages.showErrorDialog("Selected folder is not valid. Please select one of the following folders: unittest, propertybased, metamorphictest, datadriven.", "Error");
                return;
            }
        } else {
            Messages.showInfoMessage("No valid folders selected.", "Info");
        }
    }

    // 检查并读取problems.json文件
    private void checkAndReadProblemsJson(VirtualFile directory, VirtualFile selectedFolder) {
        VirtualFile[] children = directory.getChildren();
        for (VirtualFile child : children) {
            if (child.isDirectory()) {
                checkAndReadProblemsJson(child, selectedFolder);
            } else if (child.getName().equals("problems.json")) {
                try (InputStream inputStream = child.getInputStream();
                     Scanner scanner = new Scanner(inputStream, StandardCharsets.UTF_8.name())) {
                    String jsonContent = scanner.useDelimiter("\\A").next();
                    JSONArray jsonArray = JSONArray.parseArray(jsonContent);
                    processProblemsJson(jsonArray, selectedFolder);
                } catch (IOException ex) {
                    Messages.showInfoMessage("No Problems.json", "Info");
                }
            }
        }
    }

    // 处理problems.json文件内容  读取到problemMap中去
    private void processProblemsJson(JSONArray jsonArray,  VirtualFile selectedFolder) {
        for (Object obj : jsonArray) {
            if (obj instanceof JSONObject) {
                JSONObject problem = (JSONObject) obj;
                if (problem.containsKey("gid")) {
                    gid = problem.getString("gid");
                } else {
                    String problemId = problem.getString("ProblemID");
                    String title = problem.getString("Title");
                    String pid = problem.getString("PID");
                    problemMap.put(title, problemId);
                }
            }
        }
        // 对特定文件夹进行处理
        if ("unittest".equals(selectedFolder.getName())) {
            handleUnitTestFolder(selectedFolder);
        } else if ("propertybased".equals(selectedFolder.getName())) {
            // 对propertybased文件夹的处理逻辑
            handlePropertyBasedFolder(selectedFolder);
        } else if ("metamorphictest".equals(selectedFolder.getName())) {
            // 对metamorphictest文件夹的处理逻辑
            handleMetamorphicTestFolder(selectedFolder);
        } else if ("datadriven".equals(selectedFolder.getName())) {
            // 对datadriven文件夹的处理逻辑
            handleDataDriveFolder(selectedFolder);
        }
    }

    // 异步方法提交问题判定并获取提交详情   单元测试
    private void handleUnitTestFolder(VirtualFile directory) {
        scores=new ArrayList<>();
        VirtualFile[] children = directory.getChildren();
        //线程池限制为1个，因为接口做了提交限制
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        //用于获取线程池执行后的结果
        List<Future<?>> futures = new ArrayList<>();

        for (VirtualFile child : children) {
            System.out.println(child.getName());
            if (!child.isDirectory() && child.getName().endsWith("UnitTest.java")) {
                String prefix = child.getName().replace("UnitTest.java", "");
                String problemId = problemMap.get(prefix);
                if (problemId != null) {
                    //获取修改后的测试用例模板
                    String modifiedContent = modifyUnitTestJavaFile(child, prefix);
                    futures.add(CompletableFuture.runAsync(() -> {
                        try {

                            JSONObject response = submitProblemJudge(gid, problemId, modifiedContent,"UnitTest");

                            if (response != null && response.getIntValue("status") == 200) {
                                int status = response.getIntValue("status");
                                int submitId = response.getJSONObject("data").getIntValue("submitId");
                                //用于控制提交频率  目前测试10秒是最优解
                                TimeUnit.SECONDS.sleep(20);

                                // 获取已提交的任务详情
                                JSONObject submissionDetail = getSubmissionDetail(submitId);

                                if (submissionDetail != null && submissionDetail.getIntValue("status") == 200) {
                                    JSONObject submission = submissionDetail.getJSONObject("data").getJSONObject("submission");
                                    int time = submission.getIntValue("time");
                                    int memory = submission.getIntValue("memory");
                                    String code = submission.getString("code");
                                    // 提取<td class="ctr2">0%</td>值
                                    String coverageValue = extractCoverageValue(code);
                                    System.out.println("Time: " + time + ", Memory: " + memory + ", TD Value: " + coverageValue);

                                    // 计算得分并存储
                                    double score = calculateScore(time, memory, coverageValue,"Unit-Test");
                                    String scoreString = String.format(prefix+",  Time: %dms, Memory: %dKB, Coverage: %s, Score: %.2f", time, memory, coverageValue, score);
                                    synchronized (scores) {
                                        scores.add(scoreString);
                                    }
                                }
                            } else {
                                System.out.println("Submission failed or returned invalid response.");
                            }
                        } catch (IOException | InterruptedException ex) {
                            System.out.println("Failed to submit problem judge: " + ex.getMessage());
                        }
                    }, executorService));
                } else {
                    System.out.println("No matching ProblemID found for file: " + child.getName());
                }
            }
        }

        executorService.shutdown();
        try {
            for (Future<?> future : futures) {
                future.get(); // 等待所有任务完成
            }
            // 获取当前运行项目的 Project 对象
            Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
            if (openProjects.length == 0) {
                System.out.println("No open projects found.");
                return;
            }

            Project project = openProjects[0];

            // 创建并显示 ToolWindow
            ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
            ToolWindow toolWindow = toolWindowManager.getToolWindow("UnitTestResults");

            if (toolWindow == null) {
                toolWindow = toolWindowManager.registerToolWindow("UnitTestResults", true, ToolWindowAnchor.BOTTOM);
            }

            ConsoleView consoleView = new ConsoleViewImpl(project, true);
            Content content = ContentFactory.SERVICE.getInstance().createContent(consoleView.getComponent(), "UnitTest", false);
            toolWindow.getContentManager().addContent(content);
            toolWindow.show(null);

            // 输出所有分数表格形式
            StringBuilder result = new StringBuilder();
            result.append("+----------------------+-----------+-----------+-------------+--------+\n");
            result.append("| File Prefix          | Time (ms) | Memory (KB) | Coverage  | Score  |\n");
            result.append("+----------------------+-----------+-----------+-------------+--------+\n");
            for (String score : scores) {
                // 拆分分数字符串并提取信息
                String[] parts = score.split(", ");
                String prefix = parts[0].split(" ")[0];
                String time = parts[1].split(": ")[1].replace("ms", "");
                String memory = parts[2].split(": ")[1].replace("KB", "");
                String coverage = parts[3].split(": ")[1];
                String scoreValue = parts[4].split(": ")[1];
                result.append(String.format("| %-20s | %-9s | %-9s | %-11s | %-6s |\n", prefix, time, memory, coverage, scoreValue));
            }
            result.append("+----------------------+-----------+-----------+-------------+--------+\n");

            consoleView.print(result.toString(), ConsoleViewContentType.NORMAL_OUTPUT);

            // 清理资源
            Disposer.register(project, consoleView);
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
    }

    // 异步方法提交问题判定并获取提交详情    基于数据驱动测试
    private void handleDataDriveFolder(VirtualFile directory) {
        scores=new ArrayList<>();
        VirtualFile[] children = directory.getChildren();
        //线程池限制为1个，因为接口做了提交限制
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        //用于获取线程池执行后的结果
        List<Future<?>> futures = new ArrayList<>();

        for (VirtualFile child : children) {
            System.out.println(child.getName());
            if (!child.isDirectory() && child.getName().endsWith("DataDrive.java")) {
                String prefix = child.getName().replace("DataDrive.java", "");
                String problemId = problemMap.get(prefix);
                if (problemId != null) {
                    //获取修改后的测试用例模板
                    String modifiedContent = modifyDataDriveJavaFile(child, prefix);
                    System.out.println(modifiedContent);
                    futures.add(CompletableFuture.runAsync(() -> {
                        try {

                            JSONObject response = submitProblemJudge(gid, problemId, modifiedContent,"DataDrive");

                            if (response != null && response.getIntValue("status") == 200) {
                                int status = response.getIntValue("status");
                                int submitId = response.getJSONObject("data").getIntValue("submitId");
                                //用于控制提交频率  目前测试10秒是最优解
                                TimeUnit.SECONDS.sleep(20);

                                // 获取已提交的任务详情
                                JSONObject submissionDetail = getSubmissionDetail(submitId);

                                if (submissionDetail != null && submissionDetail.getIntValue("status") == 200) {
                                    JSONObject submission = submissionDetail.getJSONObject("data").getJSONObject("submission");
                                    int time = submission.getIntValue("time");
                                    int memory = submission.getIntValue("memory");
                                    String code = submission.getString("code");
                                    // 提取<td class="ctr2">0%</td>值
                                    String coverageValue = extractCoverageValue(code);
                                    System.out.println("Time: " + time + ", Memory: " + memory + ", TD Value: " + coverageValue);

                                    // 计算得分并存储
                                    double score = calculateScore(time, memory, coverageValue,"Data-Drive");
                                    String scoreString = String.format(prefix+",  Time: %dms, Memory: %dKB, Coverage: %s, Score: %.2f", time, memory, coverageValue, score);
                                    synchronized (scores) {
                                        scores.add(scoreString);
                                    }
                                }
                            } else {
                                System.out.println("Submission failed or returned invalid response.");
                            }
                        } catch (IOException | InterruptedException ex) {
                            System.out.println("Failed to submit problem judge: " + ex.getMessage());
                        }
                    }, executorService));
                } else {
                    System.out.println("No matching ProblemID found for file: " + child.getName());
                }
            }
        }

        executorService.shutdown();
        try {
            for (Future<?> future : futures) {
                future.get(); // 等待所有任务完成
            }
            // 获取当前运行项目的 Project 对象
            Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
            if (openProjects.length == 0) {
                System.out.println("No open projects found.");
                return;
            }

            Project project = openProjects[0];

            // 创建并显示 ToolWindow
            ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
            ToolWindow toolWindow = toolWindowManager.getToolWindow("DataDriveResults");

            if (toolWindow == null) {
                toolWindow = toolWindowManager.registerToolWindow("DataDriveResults", true, ToolWindowAnchor.BOTTOM);
            }

            ConsoleView consoleView = new ConsoleViewImpl(project, true);
            Content content = ContentFactory.SERVICE.getInstance().createContent(consoleView.getComponent(), "DataDrive", false);
            toolWindow.getContentManager().addContent(content);
            toolWindow.show(null);

            // 输出所有分数表格形式
            StringBuilder result = new StringBuilder();
            result.append("+----------------------+-----------+-----------+-------------+--------+\n");
            result.append("| File Prefix          | Time (ms) | Memory (KB) | Coverage  | Score  |\n");
            result.append("+----------------------+-----------+-----------+-------------+--------+\n");
            for (String score : scores) {
                // 拆分分数字符串并提取信息
                String[] parts = score.split(", ");
                String prefix = parts[0].split(" ")[0];
                String time = parts[1].split(": ")[1].replace("ms", "");
                String memory = parts[2].split(": ")[1].replace("KB", "");
                String coverage = parts[3].split(": ")[1];
                String scoreValue = parts[4].split(": ")[1];
                result.append(String.format("| %-20s | %-9s | %-9s | %-11s | %-6s |\n", prefix, time, memory, coverage, scoreValue));
            }
            result.append("+----------------------+-----------+-----------+-------------+--------+\n");

            consoleView.print(result.toString(), ConsoleViewContentType.NORMAL_OUTPUT);

            // 清理资源
            Disposer.register(project, consoleView);
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
    }

    // 异步方法提交问题判定并获取提交详情    基于属性驱动测试 不存在覆盖率
    private void handlePropertyBasedFolder(VirtualFile directory) {
        scores=new ArrayList<>();
        VirtualFile[] children = directory.getChildren();
        //线程池限制为1个，因为接口做了提交限制
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        //用于获取线程池执行后的结果
        List<Future<?>> futures = new ArrayList<>();

        for (VirtualFile child : children) {
            System.out.println(child.getName());
            if (!child.isDirectory() && child.getName().endsWith("PropertyBased.java")) {
                String prefix = child.getName().replace("PropertyBased.java", "");
                String problemId = problemMap.get(prefix);
                if (problemId != null) {
                    //获取修改后的测试用例模板
                    String modifiedContent = modifyPropertyBasedJavaFile(child, prefix);
                    System.out.println(modifiedContent);
                    futures.add(CompletableFuture.runAsync(() -> {
                        try {

                            JSONObject response = submitProblemJudge(gid, problemId, modifiedContent,"PropertyBased");

                            if (response != null && response.getIntValue("status") == 200) {
                                int status = response.getIntValue("status");
                                int submitId = response.getJSONObject("data").getIntValue("submitId");
                                //用于控制提交频率  目前测试10秒是最优解
                                TimeUnit.SECONDS.sleep(20);

                                // 获取已提交的任务详情
                                JSONObject submissionDetail = getSubmissionDetail(submitId);

                                if (submissionDetail != null && submissionDetail.getIntValue("status") == 200) {
                                    JSONObject submission = submissionDetail.getJSONObject("data").getJSONObject("submission");
                                    int time = submission.getIntValue("time");
                                    int memory = submission.getIntValue("memory");
                                    String code = submission.getString("code");
                                    // 提取<td class="ctr2">0%</td>值
                                    String coverageValue = extractCoverageValue(code);
                                    System.out.println("Time: " + time + ", Memory: " + memory + ", TD Value: " + coverageValue);

                                    // 计算得分并存储
                                    double score = calculateScore(time, memory, coverageValue,"Property-Based");
                                    //基于属性的不存在覆盖率
                                    String scoreString = String.format(prefix+",  Time: %dms, Memory: %dKB, Score: %.2f", time, memory, score);
                                    synchronized (scores) {
                                        scores.add(scoreString);
                                    }
                                }
                            } else {
                                System.out.println("Submission failed or returned invalid response.");
                            }
                        } catch (IOException | InterruptedException ex) {
                            System.out.println("Failed to submit problem judge: " + ex.getMessage());
                        }
                    }, executorService));
                } else {
                    System.out.println("No matching ProblemID found for file: " + child.getName());
                }
            }
        }

        executorService.shutdown();
        try {
            for (Future<?> future : futures) {
                future.get(); // 等待所有任务完成
            }
            // 获取当前运行项目的 Project 对象
            Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
            if (openProjects.length == 0) {
                System.out.println("No open projects found.");
                return;
            }

            Project project = openProjects[0];

            // 创建并显示 ToolWindow
            ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
            ToolWindow toolWindow = toolWindowManager.getToolWindow("PropertyBasedResults");

            if (toolWindow == null) {
                toolWindow = toolWindowManager.registerToolWindow("PropertyBasedResults", true, ToolWindowAnchor.BOTTOM);
            }

            ConsoleView consoleView = new ConsoleViewImpl(project, true);
            Content content = ContentFactory.SERVICE.getInstance().createContent(consoleView.getComponent(), "PropertyBased", false);
            toolWindow.getContentManager().addContent(content);
            toolWindow.show(null);

            // 输出所有分数表格形式
            StringBuilder result = new StringBuilder();
            result.append("+----------------------+-----------+-----------+--------+\n");
            result.append("| File Prefix          | Time (ms) | Memory (KB) | Score|\n");
            result.append("+----------------------+-----------+-----------+--------+\n");
            for (String score : scores) {
                // 拆分分数字符串并提取信息
                String[] parts = score.split(", ");
                String prefix = parts[0].split(" ")[0];
                String time = parts[1].split(": ")[1].replace("ms", "");
                String memory = parts[2].split(": ")[1].replace("KB", "");
                String scoreValue = parts[3].split(": ")[1];
                result.append(String.format("| %-20s | %-9s | %-9s | %-6s |\n", prefix, time, memory, scoreValue));
            }
            result.append("+----------------------+-----------+-----------+--------+\n");

            consoleView.print(result.toString(), ConsoleViewContentType.NORMAL_OUTPUT);

            // 清理资源
            Disposer.register(project, consoleView);
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
    }

    // 异步方法提交问题判定并获取提交详情   蜕变测试
    private void handleMetamorphicTestFolder(VirtualFile directory) {
        scores=new ArrayList<>();
        VirtualFile[] children = directory.getChildren();
        //线程池限制为1个，因为接口做了提交限制
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        //用于获取线程池执行后的结果
        List<Future<?>> futures = new ArrayList<>();

        for (VirtualFile child : children) {
            System.out.println(child.getName());
            if (!child.isDirectory() && child.getName().endsWith("MetamorphicTest.java")) {
                String prefix = child.getName().replace("MetamorphicTest.java", "");
                String problemId = problemMap.get(prefix);
                if (problemId != null) {
                    //获取修改后的测试用例模板
                    String modifiedContent = modifyMetamorphicTestJavaFile(child, prefix);
                    System.out.println(modifiedContent);
                    futures.add(CompletableFuture.runAsync(() -> {
                        try {

                            JSONObject response = submitProblemJudge(gid, problemId, modifiedContent,"MetamorphicTest");

                            if (response != null && response.getIntValue("status") == 200) {
                                int status = response.getIntValue("status");
                                int submitId = response.getJSONObject("data").getIntValue("submitId");
                                //用于控制提交频率  目前测试10秒是最优解
                                TimeUnit.SECONDS.sleep(20);

                                // 获取已提交的任务详情
                                JSONObject submissionDetail = getSubmissionDetail(submitId);

                                if (submissionDetail != null && submissionDetail.getIntValue("status") == 200) {
                                    JSONObject submission = submissionDetail.getJSONObject("data").getJSONObject("submission");
                                    int time = submission.getIntValue("time");
                                    int memory = submission.getIntValue("memory");
                                    String code = submission.getString("code");
                                    // 提取<td class="ctr2">0%</td>值
                                    String coverageValue = extractCoverageValue(code);
                                    System.out.println("Time: " + time + ", Memory: " + memory + ", TD Value: " + coverageValue);

                                    // 计算得分并存储
                                    double score = calculateScore(time, memory, coverageValue,"Metamorphic-Test");
                                    String scoreString = String.format(prefix+",  Time: %dms, Memory: %dKB, Coverage: %s, Score: %.2f", time, memory, coverageValue, score);
                                    synchronized (scores) {
                                        scores.add(scoreString);
                                    }
                                }
                            } else {
                                System.out.println("Submission failed or returned invalid response.");
                            }
                        } catch (IOException | InterruptedException ex) {
                            System.out.println("Failed to submit problem judge: " + ex.getMessage());
                        }
                    }, executorService));
                } else {
                    System.out.println("No matching ProblemID found for file: " + child.getName());
                }
            }
        }

        executorService.shutdown();
        try {
            for (Future<?> future : futures) {
                future.get(); // 等待所有任务完成
            }
            // 获取当前运行项目的 Project 对象
            Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
            if (openProjects.length == 0) {
                System.out.println("No open projects found.");
                return;
            }

            Project project = openProjects[0];

            // 创建并显示 ToolWindow
            ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
            ToolWindow toolWindow = toolWindowManager.getToolWindow("MetamorphicTestResults");

            if (toolWindow == null) {
                toolWindow = toolWindowManager.registerToolWindow("MetamorphicTestResults", true, ToolWindowAnchor.BOTTOM);
            }

            ConsoleView consoleView = new ConsoleViewImpl(project, true);
            Content content = ContentFactory.SERVICE.getInstance().createContent(consoleView.getComponent(), "MetamorphicTest", false);
            toolWindow.getContentManager().addContent(content);
            toolWindow.show(null);

            // 输出所有分数表格形式
            StringBuilder result = new StringBuilder();
            result.append("+----------------------+-----------+-----------+-------------+--------+\n");
            result.append("| File Prefix          | Time (ms) | Memory (KB) | Coverage  | Score  |\n");
            result.append("+----------------------+-----------+-----------+-------------+--------+\n");
            for (String score : scores) {
                // 拆分分数字符串并提取信息
                String[] parts = score.split(", ");
                String prefix = parts[0].split(" ")[0];
                String time = parts[1].split(": ")[1].replace("ms", "");
                String memory = parts[2].split(": ")[1].replace("KB", "");
                String coverage = parts[3].split(": ")[1];
                String scoreValue = parts[4].split(": ")[1];
                result.append(String.format("| %-20s | %-9s | %-9s | %-11s | %-6s |\n", prefix, time, memory, coverage, scoreValue));
            }
            result.append("+----------------------+-----------+-----------+-------------+--------+\n");

            consoleView.print(result.toString(), ConsoleViewContentType.NORMAL_OUTPUT);

            // 清理资源
            Disposer.register(project, consoleView);
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
    }

    // 提交问题到服务器进行判定
    private JSONObject submitProblemJudge(String gid, String pid, String code,String type) throws IOException {
        String urlString = "http://"+ IpConfig.IP_ADDRESS+ "/api/submit-problem-judge";
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Authorization", authentication);
        conn.setDoOutput(true);

        JSONObject requestBody = new JSONObject();
        requestBody.put("cid", 0);
        requestBody.put("code", code);
        requestBody.put("gid", gid);
        requestBody.put("isRemote", false);
        requestBody.put("language", "Java");
        requestBody.put("pid", pid);
        requestBody.put("subjecttype", "Coverage-Based");
        if(type=="PropertyBased"){
            requestBody.put("subjecttype", "Property-Based");
        }
        System.out.println(requestBody.get("subjecttype"));
        //需要修改训练的tid  不能默认给值为1
        requestBody.put("tid", 12);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = requestBody.toJSONString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int status = conn.getResponseCode();
        //System.out.println(status);
        InputStream responseStream = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();

        try (Scanner scanner = new Scanner(responseStream, StandardCharsets.UTF_8.name())) {
            String responseBody = scanner.useDelimiter("\\A").next();
            System.out.println(responseBody);
            return JSONObject.parseObject(responseBody);
        }
    }

    // 获取已提交测试用例提交详情
    private JSONObject getSubmissionDetail(int submitId) throws IOException {
        String urlString = "http://"+ IpConfig.IP_ADDRESS+ "/api/get-submission-detail?submitId=" + submitId;
        System.out.println(submitId);
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", authentication);
        int status = conn.getResponseCode();
        InputStream responseStream = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();

        try (Scanner scanner = new Scanner(responseStream, StandardCharsets.UTF_8.name())) {
            String responseBody = scanner.useDelimiter("\\A").next();
            return JSONObject.parseObject(responseBody);
        }
    }

    // 修改MetamorphicTestJava文件内容
    private String modifyMetamorphicTestJavaFile(VirtualFile file, String prefix) {
        StringBuilder modifiedContent = new StringBuilder();
        try (InputStream inputStream = file.getInputStream();
             Scanner scanner = new Scanner(inputStream, StandardCharsets.UTF_8.name())) {
            String content = scanner.useDelimiter("\\A").next();
            // 替换内容
            content = content.replaceAll("package\\s+[\\w\\.]+;", "");
            content = content.replaceAll("import\\s+[\\w\\.]+\\." + prefix + ";", "");
            content = content.replaceAll("public\\s+class\\s+" + prefix + "MetamorphicTest", "class SUTTest");
            content = content.replaceAll(prefix + "\\.", "SUT.");

            // 去除特定import语句和变量声明
            content = content.replaceAll("import\\s+[\\w\\.]+\\." + prefix + ";", "");
            content = content.replaceAll("(public\\s+)?\\s*" + prefix + "\\s*=\\s*new\\s*" + prefix + "\\(\\);", "");

            // 去除 public AbsoluteMax 字符串
            content = content.replaceAll("public\\s+" + prefix, "");
            modifiedContent.append(content);
        } catch (IOException ex) {
            modifiedContent.append("Failed to modify file: ").append(file.getName()).append(", Error: ").append(ex.getMessage());
        }
        return modifiedContent.toString();
    }

    // 修改DataDriveJava文件内容
    private String modifyDataDriveJavaFile(VirtualFile file, String prefix) {
        StringBuilder modifiedContent = new StringBuilder();
        try (InputStream inputStream = file.getInputStream();
             Scanner scanner = new Scanner(inputStream, StandardCharsets.UTF_8.name())) {
            String content = scanner.useDelimiter("\\A").next();
            // 替换内容
            content = content.replaceAll("package\\s+[\\w\\.]+;", "");
            content = content.replaceAll("import\\s+[\\w\\.]+\\." + prefix + ";", "");
            content = content.replaceAll("public\\s+class\\s+" + prefix + "DataDrive", "class SUTTest");
            content = content.replaceAll(prefix + "\\.", "SUT.");

            // 去除特定import语句和变量声明
            content = content.replaceAll("import\\s+[\\w\\.]+\\." + prefix + ";", "");
            content = content.replaceAll("(public\\s+)?\\s*" + prefix + "\\s*=\\s*new\\s*" + prefix + "\\(\\);", "");

            // 去除 public AbsoluteMax 字符串
            content = content.replaceAll("public\\s+" + prefix, "");
            modifiedContent.append(content);
        } catch (IOException ex) {
            modifiedContent.append("Failed to modify file: ").append(file.getName()).append(", Error: ").append(ex.getMessage());
        }
        return modifiedContent.toString();
    }

    // 修改PropertyBasedJava文件内容
    private String modifyPropertyBasedJavaFile(VirtualFile file, String prefix) {
        StringBuilder modifiedContent = new StringBuilder();
        try (InputStream inputStream = file.getInputStream();
             Scanner scanner = new Scanner(inputStream, StandardCharsets.UTF_8.name())) {
            String content = scanner.useDelimiter("\\A").next();
            // 替换内容
            content = content.replaceAll("package\\s+[\\w\\.]+;", "");
            content = content.replaceAll("import\\s+[\\w\\.]+\\." + prefix + ";", "");
            content = content.replaceAll("public\\s+class\\s+" + prefix + "PropertyBased", "public class SUTTest");
            content = content.replaceAll(prefix + "\\.", "SUT.");

            // 去除特定import语句和变量声明
            content = content.replaceAll("import\\s+[\\w\\.]+\\." + prefix + ";", "");
            content = content.replaceAll("(public\\s+)?\\s*" + prefix + "\\s*=\\s*new\\s*" + prefix + "\\(\\);", "");

            // 去除 public AbsoluteMax 字符串
            content = content.replaceAll("public\\s+" + prefix, "");
            modifiedContent.append(content);
        } catch (IOException ex) {
            modifiedContent.append("Failed to modify file: ").append(file.getName()).append(", Error: ").append(ex.getMessage());
        }
        return modifiedContent.toString();
    }

    // 修改UnitTestJava文件内容

    // 修改UnitTestJava文件内容
    private String modifyUnitTestJavaFile(VirtualFile file, String prefix) {
        StringBuilder modifiedContent = new StringBuilder();
        try (InputStream inputStream = file.getInputStream();
             Scanner scanner = new Scanner(inputStream, StandardCharsets.UTF_8.name())) {
            String content = scanner.useDelimiter("\\A").next();

            // 去除 package 行
            content = content.replaceAll("package\\s+[\\w\\.]+;", "");

            // 去除 import prefix 类
            content = content.replaceAll("import\\s+[\\w\\.]+\\." + prefix + ";", "");

            // 修改类名
            content = content.replaceAll("public\\s+class\\s+" + prefix + "UnitTest", "class SUTTest");

            // 替换前缀引用
            content = content.replaceAll(prefix + "\\.", "SUT.");

            // 去除特定变量声明，如 "public Pow pow = new Pow();" 或 "Pow pow = new Pow();"
            content = content.replaceAll("(public\\s+)?\\s*" + prefix + "\\s+\\w+\\s*=\\s*new\\s*" + prefix + "\\s*\\(\\s*\\);", "");

            // 去除 public Pow（如 public Pow ... 语句，若还有）
            content = content.replaceAll("public\\s+" + prefix, "");

            modifiedContent.append(content);
        } catch (IOException ex) {
            modifiedContent.append("Failed to modify file: ").append(file.getName()).append(", Error: ").append(ex.getMessage());
        }
        return modifiedContent.toString();
    }


    //解析html文件获取<tfoot>(.*?)</tfoot>标签内的<td> content: 0%内容    需修改提交代码的import包的内容否则会出现覆盖率为0的数值
    private String extractTdValueByClass(String code, String className, int occurrence) {
        String tdStartTag = "<td class=\\" + "\"" + className + "\\" + "\">";
        String tdEndTag = "</td>";

        int startIndex = -1;
        for (int i = 0; i < occurrence; i++) {
            startIndex = code.indexOf(tdStartTag, startIndex + 1);
            if (startIndex == -1) {
                System.out.println("Failed to find <td class=\"" + className + "\"> occurrence: " + occurrence);
                return null;
            }
        }

        startIndex += tdStartTag.length();
        int endIndex = code.indexOf(tdEndTag, startIndex);
        if (endIndex == -1) {
            System.out.println("Failed to find closing </td> tag after <td class=\"" + className + "\"> in provided code.");
            return null;
        }

        String tdContent = code.substring(startIndex, endIndex);
        System.out.println("Extracted <td> content: " + tdContent);
        return tdContent.trim(); // 去除前后空白字符
    }

    //解析html文件获取<tfoot>(.*?)</tfoot>标签内的内容
    private String extractCoverageValue(String code) {
        String totalRegex = "<tfoot>(.*?)</tfoot>";
        Pattern totalPattern = Pattern.compile(totalRegex, Pattern.DOTALL);
        Matcher totalMatcher = totalPattern.matcher(code);
        if (totalMatcher.find()) {
            String tfootContent = totalMatcher.group(1);
            System.out.println("Found <tfoot> content: " + tfootContent);
            return extractTdValueByClass(tfootContent, "ctr2", 1); // 获取第一个 <td class="ctr2"> 标签值
        }
        System.out.println("Failed to find <tfoot> content in provided code.");
        return "0%";
    }


    // 计算得分方法
    private double calculateScore(int time, int memory, String coverageValue,String type) {
        double coverage = Double.parseDouble(coverageValue.replace("%", ""));
        double normalizedTime = normalizeTime(time);
        double normalizedMemory = normalizeMemory(memory);
        double normalizedCoverage = coverage / 100.0;
        if (type=="Property-Based"){  //基于属性驱动的不存在覆盖率指标 不需要计算
            return (normalizedTime * 0.50 + normalizedMemory * 0.50 )*100;
        }
        return (normalizedTime * 0.20 + normalizedMemory * 0.20 + normalizedCoverage * 0.60)*100;
    }

    // 将 time 规范化（假设 time 范围为 0 到 1000 ms）
    private double normalizeTime(int time) {
        return Math.min(1.0, Math.max(0.0, (1000.0 - time) / 1000.0));
    }

    // 将 memory 规范化（假设 memory 范围为 0 到 1024 MB）
    private double normalizeMemory(int memory) {
        return Math.min(1.0, Math.max(0.0, (1024.0 - memory) / 1024.0));
    }

    // 汉字转拼音
    public static String toPinyin(String chinese) {
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        format.setVCharType(HanyuPinyinVCharType.WITH_V);

        StringBuilder pinyin = new StringBuilder();
        char[] chars = chinese.toCharArray();

        try {
            for (char c : chars) {
                // 检查字符是否为汉字
                if (Character.toString(c).matches("[\\u4E00-\\u9FA5]+")) {
                    String[] pinyinArray = PinyinHelper.toHanyuPinyinStringArray(c, format);
                    if (pinyinArray != null) {
                        pinyin.append(pinyinArray[0]);
                    }
                } else {
                    // 如果不是汉字，则直接添加
                    pinyin.append(c);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return pinyin.toString().trim();
    }
}
