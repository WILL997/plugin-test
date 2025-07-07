package TActions.org.intelllij;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import config.IpConfig;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import org.jetbrains.annotations.NotNull;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GetTestAction extends AnAction {

    //对应训练下的问题集
    static  List<Map<String, String>> problemList=null;

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        String uid = LoginAction.getUid(); //获取用户id
        String authentication = LoginAction.getAuthorization(); //获取用户登录后的权限
        if (uid == null || uid.isEmpty() || authentication == null || authentication.isEmpty()) {
            Messages.showInfoMessage("Please LoginTOJ", "Info");
            return;
        }
        String userName=toPinyin(LoginAction.getUserName());  //获取用户登录后的名称   容易空指针
        System.out.println(userName);
        try {
            // 发起HTTP请求,获取该用户所在的所有团队
            String jsonResponse = sendHttpGetRequest("http://"+ IpConfig.IP_ADDRESS+ "/api/get-user-group?uid=" + uid, authentication);
            System.out.println(jsonResponse.toString());
            if (jsonResponse == null) {
                Messages.showInfoMessage("Please LoginTOJ", "Info");
                return;
            }

            // 解析JSON转换成用户选择团队界面所需数据
            Map<String, Integer> nameIdMap = parseJsonResponse(jsonResponse);
            if (nameIdMap == null || nameIdMap.isEmpty()) {
                throw new Exception("Failed to parse JSON response or empty data received");
            }

            // 团队name和Id的Map中提取names用来展示
            String[] names = nameIdMap.keySet().toArray(new String[0]);

            // 创建并显示用户选择团队界面，并返回用户选择的团队名称
            String selectedName = showSingleSelectRadioButtonDialog("选取团队", names);
            if (selectedName == null) {
                return; // 用户取消了选择
            }

            // 获取用户选择的团队名称对应的团队id
            Integer selectedId = nameIdMap.get(selectedName);
            if (selectedId == null) {
                throw new Exception("Selected ID is null for name: " + selectedName);
            }
            //产生随机包命  user.team.randName
            String packageName="";
            String teamName=toPinyin(selectedName);
            packageName+=teamName;
            // 发起二次HTTP请求获取该团队的训练列表
            String trainTitle = showTrainingListDialog(selectedId, authentication, 1,packageName);
            String trainName=toPinyin(trainTitle);
            packageName+="."+trainName+"."+userName;
            Project project = e.getProject();

            // 创建团队目录
            VirtualFile teamDir = ApplicationManager.getApplication().runWriteAction(new Computable<VirtualFile>() {
                @Override
                public VirtualFile compute() {
                    try {
                        VirtualFile sourceRoot = project.getBaseDir().findChild("src");
                        if (sourceRoot == null) {
                            System.out.println("src目录不存在！");
                            return null;
                        }

                        // 在src目录下创建team团队目录
                        VirtualFile teamDir = sourceRoot.findChild(teamName);
                        if (teamDir == null) {
                            teamDir = sourceRoot.createChildDirectory(this, teamName);
                            System.out.println("已创建团队目录: " + teamDir.getPath());
                        }
                        return teamDir;
                    } catch (IOException ex) {
                        ex.printStackTrace();
                        return null;
                    }
                }
            });

            // 创建训练目录
            VirtualFile trainDir = null;
            if (teamDir != null && trainTitle != null && !trainTitle.isEmpty()) {
                trainDir = ApplicationManager.getApplication().runWriteAction(new Computable<VirtualFile>() {
                    @Override
                    public VirtualFile compute() {
                        try {
                            VirtualFile trainDir = teamDir.findChild(trainName);
                            if (trainDir == null) {
                                trainDir = teamDir.createChildDirectory(this, trainName);
                                System.out.println("已创建训练目录: " + trainDir.getPath());
                            }
                            return trainDir;
                        } catch (IOException ex) {
                            ex.printStackTrace();
                            return null;
                        }
                    }
                });
            }

            // 创建用户目录
            VirtualFile userDir = null;
            if (trainDir != null) {
                VirtualFile finalTrainDir = trainDir;
                userDir = ApplicationManager.getApplication().runWriteAction(new Computable<VirtualFile>() {
                    @Override
                    public VirtualFile compute() {
                        try {
                            VirtualFile userDir = finalTrainDir.findChild(userName);
                            if (userDir == null) {
                                userDir = finalTrainDir.createChildDirectory(this, userName);
                                System.out.println("已创建用户目录: " + userDir.getPath());
                            }
                            return userDir;
                        } catch (IOException ex) {
                            ex.printStackTrace();
                            return null;
                        }
                    }
                });
            }



            //创建对应json格式文件
            JSONArray jsonProblems = new JSONArray();
            JSONObject jsonGid = new JSONObject();
            jsonGid.put("gid",selectedId);
            jsonProblems.add(jsonGid);

            VirtualFile finalUserDir = userDir;
            String finalPackageName = packageName;
            ApplicationManager.getApplication().runWriteAction(() -> {
                // 循环在该目录下创建对应被测试类
                for (Map<String, String> problem : problemList) {
                    if (finalUserDir != null) {
                        VirtualFile testClassFile = finalUserDir.findChild(problem.get("title") + ".java");
                        if (testClassFile == null) {
                            String testCode = problem.get("description");
                            testCode = "package " + finalPackageName + ";\n\n" + testCode;  // 创建的Java文件加上对应包名

                            try {
                                // 创建对应方法的单元测试模板方法
                                testClassFile = finalUserDir.createChildData(this, problem.get("title") + ".java");
                                testClassFile.setBinaryContent(testCode.getBytes(StandardCharsets.UTF_8));
                                System.out.println("Created test class file: " + testClassFile.getPath());
                            } catch (IOException ex) {
                                ex.printStackTrace();
                            }
                        } else {
                            System.out.println("Test class file already exists: " + testClassFile.getPath());
                        }
                    }

                    // 添加问题信息到 JSON 数组
                    JSONObject jsonProblem = new JSONObject();
                    jsonProblem.put("PID", problem.get("pid"));
                    jsonProblem.put("ProblemID", problem.get("problemId"));
                    jsonProblem.put("Title", problem.get("title"));
                    jsonProblems.add(jsonProblem);
                }
            });


            VirtualFile finalUserDir1 = userDir;
            ApplicationManager.getApplication().runWriteAction(() -> {
                try {
                    // 在 userDir 中查找或创建 JSON 文件
                    if (finalUserDir1 != null) {
                        VirtualFile jsonFile = finalUserDir1.findOrCreateChildData(this, "problems.json");
                        jsonFile.setBinaryContent(jsonProblems.toString().getBytes(StandardCharsets.UTF_8));
                        System.out.println("Created JSON file: " + jsonFile.getPath());
                    } else {
                        System.out.println("User directory does not exist.");
                    }
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            });


        } catch (Exception ex) {
            //Messages.showErrorDialog("An error occurred: " + ex.getMessage(), "Error");
        }
    }

    //封装的get请求方法，返回值为json格式的String
    private String sendHttpGetRequest(String urlString, String authorization) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", authorization);
        System.out.println(conn.toString());
        try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            String inputLine;
            StringBuilder content = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                content.append(inputLine);
            }
            System.out.println(content.toString());
            return content.toString();
        } catch (Exception e) {
            throw new Exception("Failed to get response from server: " + e.getMessage());
        }
    }

    //用户选择团队界面所需的数据转换  jsonResponse为请求接口所返回的值需过滤
    private Map<String, Integer> parseJsonResponse(String jsonResponse) throws Exception {
        try {
            JSONObject jsonObject = JSON.parseObject(jsonResponse);
            JSONObject dataObject = jsonObject.getJSONObject("data");
            JSONArray recordsArray = dataObject.getJSONArray("records");

            Map<String, Integer> nameIdMap = new HashMap<>();
            for (int i = 0; i < recordsArray.size(); i++) {
                JSONObject record = recordsArray.getJSONObject(i);
                String name = record.getString("name");
                Integer id = record.getInteger("id");
                nameIdMap.put(name, id);
            }
            return nameIdMap;
        } catch (Exception e) {
            throw new Exception("Failed to parse JSON response: " + e.getMessage());
        }
    }

    //用户团队选择界面
    public static String showSingleSelectRadioButtonDialog(String title, String[] options) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(5, 5, 5, 5);

        ButtonGroup buttonGroup = new ButtonGroup();
        List<JRadioButton> radioButtons = new ArrayList<>();
        for (String option : options) {
            JRadioButton radioButton = new JRadioButton(option);
            panel.add(radioButton, gbc);
            buttonGroup.add(radioButton);
            radioButtons.add(radioButton);
            gbc.gridy++;
        }

        int result = JOptionPane.showConfirmDialog(null, panel, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            for (JRadioButton radioButton : radioButtons) {
                if (radioButton.isSelected()) {
                    return radioButton.getText();
                }
            }
        }

        return null; // 返回null表示未选择任何选项
    }

    // 二次请求获取该团队里的训练列表
    private String showTrainingListDialog(int gid, String authorization, int currentPage,String packageName) {
        int limit = 10;

        try {
            String jsonResponse = sendHttpGetRequest("http://"+ IpConfig.IP_ADDRESS+ "/api/group/get-training-list?currentPage=" + currentPage + "&limit=" + limit + "&gid=" + gid, authorization);
            if (jsonResponse == null) {
                throw new Exception("Received null response from server");
            }

            // 转换成对应训练列表展示数据 jsonResponse为请求接口所返回的值需过滤所需数据
            Map<String, Integer> trainingMap = parseTrainingJsonResponse(jsonResponse);
            if (trainingMap.isEmpty()) {
                Messages.showInfoMessage("No Train", "Info");
                return null; // 返回 null 表示没有更多数据
            }

            // 获取总的训练列表名字
            String[] titles = trainingMap.keySet().toArray(new String[0]);
            // 返回值为选中的训练名称
            String selectedTitle = showSingleSelectRadioButtonDialogWithPaging("选取训练", titles, currentPage, gid, authorization);

            // 如果为下一页就继续请求该接口，并把currentPage加一
            if (selectedTitle != null && selectedTitle.equals("NEXT_PAGE")) {
                currentPage++; // 递增currentPage
                return showTrainingListDialog(gid, authorization, currentPage,packageName);
            } else if (selectedTitle != null && selectedTitle.equals("PREV_PAGE")) {
                currentPage--; // 递减currentPage
                if (currentPage < 1) {
                    currentPage = 1;
                }
                return showTrainingListDialog(gid, authorization, currentPage,packageName);
            } else {
                // 获取该训练下的问题列表并展示
                if (selectedTitle != null) {
                    Integer selectedTrainingId = trainingMap.get(selectedTitle);
                    showTrainingProblems(gid,selectedTrainingId, authorization,packageName);
                }
                return selectedTitle;
            }
        } catch (Exception ex) {
            //Messages.showErrorDialog("An error occurred: " + ex.getMessage(), "Error");
            return null; // 返回 null 表示发生错误
        }
    }


    //转换训练列表用户选择界面所需数据   jsonResponse为请求接口所返回的值需过滤所需数据
    private Map<String, Integer> parseTrainingJsonResponse(String jsonResponse) throws Exception {
        try {
            JSONObject jsonObject = JSON.parseObject(jsonResponse);
            JSONObject dataObject = jsonObject.getJSONObject("data");
            JSONArray recordsArray = dataObject.getJSONArray("records");

            Map<String, Integer> trainingMap = new HashMap<>();
            for (int i = 0; i < recordsArray.size(); i++) {
                JSONObject record = recordsArray.getJSONObject(i);
                String title = record.getString("title");
                Integer id = record.getInteger("id");
                trainingMap.put(title, id);
            }
            return trainingMap;
        } catch (Exception e) {
            throw new Exception("Failed to parse JSON response: " + e.getMessage());
        }
    }

    //用户选取训练的列表界面
    public static String showSingleSelectRadioButtonDialogWithPaging(String title, String[] options, int currentPage, int gid, String authorization) {
        final boolean[] nextPage = {false};
        final boolean[] prevPage = {false};
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(5, 5, 5, 5);

        ButtonGroup buttonGroup = new ButtonGroup();
        List<JRadioButton> radioButtons = new ArrayList<>();
        for (String option : options) {
            JRadioButton radioButton = new JRadioButton(option);
            panel.add(radioButton, gbc);
            buttonGroup.add(radioButton);
            radioButtons.add(radioButton);
            gbc.gridy++;
        }

        gbc.gridx = 0;
        gbc.gridy++;
        JButton prevPageButton = new JButton("PREV_PAGE");
        panel.add(prevPageButton, gbc);

        gbc.gridx++;
        JButton nextPageButton = new JButton("NEXT_PAGE");
        panel.add(nextPageButton, gbc);

        prevPageButton.addActionListener(e -> {
            prevPage[0] = true;
            Window window = SwingUtilities.getWindowAncestor(panel); // 获取当前窗口
            if (window != null) {
                window.dispose(); // 关闭当前窗口
            }
        });

        nextPageButton.addActionListener(e -> {
            nextPage[0] = true;
            Window window = SwingUtilities.getWindowAncestor(panel); // 获取当前窗口
            if (window != null) {
                window.dispose(); // 关闭当前窗口
            }
        });

        int result = JOptionPane.showConfirmDialog(null, panel, title + " (Page " + currentPage + ")", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            for (JRadioButton radioButton : radioButtons) {
                if (radioButton.isSelected()) {
                    return radioButton.getText();
                }
            }
        }

        return nextPage[0] ? "NEXT_PAGE" : (prevPage[0] ? "PREV_PAGE" : null);
    }

    // 获取该训练下的问题列表并展示
    private void showTrainingProblems(int gid, int selectedTrainingId, String authorization,String packageName) {
        try {
            String jsonResponse = sendHttpGetRequest("http://"+ IpConfig.IP_ADDRESS+ "/api/get-training-problem-list?tid=" + selectedTrainingId, authorization);
            if (jsonResponse == null) {
                throw new Exception("Received null response from server");
            }

            // 解析JSON并展示该训练下的问题列表
            problemList = parseTrainingProblemJsonResponse(gid, jsonResponse,authorization,packageName);
            if (problemList.isEmpty()) {
                Messages.showInfoMessage("No Problem", "Info");
                return;
            }

        } catch (Exception ex) {
            //Messages.showErrorDialog("An error occurred: " + ex.getMessage(), "Error");
        }
    }

    // 解析训练问题列表的JSON响应
    private List<Map<String, String>> parseTrainingProblemJsonResponse(int gid, String jsonResponse, String authorization,String packageName) throws Exception {
        try {
            JSONObject jsonObject = JSON.parseObject(jsonResponse);
            JSONArray dataArray = jsonObject.getJSONArray("data");

            List<Map<String, String>> problemList = new ArrayList<>();
            for (int i = 0; i < dataArray.size(); i++) {
                JSONObject record = dataArray.getJSONObject(i);
                Map<String, String> problemData = new HashMap<>();
                String problemId = record.getString("problemId");

                problemData.put("pid", record.getString("pid"));
                problemData.put("problemId", problemId);
                problemData.put("title", record.getString("title"));
                // 获取问题详细信息并添加description字段
                String description = fetchProblemDescription(problemId, gid, authorization);
                // 处理description，去除```并替换SUT为title
                description = processDescription(description, record.getString("title"),packageName);
                problemData.put("description", description);

                problemList.add(problemData);
            }
            return problemList;
        } catch (Exception e) {
            throw new Exception("Failed to parse JSON response: " + e.getMessage());
        }
    }

    // 获取问题详细信息
    private String fetchProblemDescription(String problemId, int gid, String authorization) throws Exception {
        try {
            String jsonResponse = sendHttpGetRequest("http://"+ IpConfig.IP_ADDRESS+ "/api/get-problem-detail?problemId=" + problemId + "&gid=" + gid, authorization);
            if (jsonResponse == null) {
                throw new Exception("Received null response from server");
            }

            JSONObject jsonObject = JSON.parseObject(jsonResponse);
            JSONObject dataObject = jsonObject.getJSONObject("data");
            JSONObject problemObject = dataObject.getJSONObject("problem");
            return problemObject.getString("description");
        } catch (Exception e) {
            throw new Exception("Failed to fetch problem description: " + e.getMessage());
        }
    }

    // 处理description，去除```并替换SUT为title  添加包命
    private String processDescription(String description, String title,String packageName) {
        // 去除```标记
        description = description.replaceAll("```", "");
        // 替换SUT为title
        description = description.replaceAll("SUT", title);
        // 添加package声明
        //description = "package " + packageName + ";\n\n" + description;
        return description;
    }


    //汉字转拼音
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
