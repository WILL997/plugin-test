package TActions.org.intelllij;

import com.alibaba.fastjson.JSONObject;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ui.Messages;
import config.IpConfig;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class LoginAction extends AnAction {

    static   String authentication="";  //用户登录后权限验证
    static   String uid="";             //用户id
    static   String userName="";       //用户名称

    @Override
    public void actionPerformed(AnActionEvent e) {
        // 创建登录界面
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("LOGINTOJ");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setSize(400, 250);  // 设置窗口大小

            JPanel panel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            frame.add(panel);

            // 设置通用的 GridBagConstraints 属性
            gbc.insets = new Insets(10, 10, 10, 10);  // 增加内边距
            gbc.anchor = GridBagConstraints.WEST;

            // 设置用户标签和文本框
            JLabel userLabel = new JLabel("账号:");
            userLabel.setFont(new Font("宋体", Font.PLAIN, 14));  // 使用支持中文的字体，增大字体
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.anchor = GridBagConstraints.EAST;
            panel.add(userLabel, gbc);

            JTextField userText = new JTextField(28);  // 设置较长的文本框
            userText.setFont(new Font("宋体", Font.PLAIN, 14));  // 使用支持中文的字体，增大字体
            gbc.gridx = 1;
            gbc.gridy = 0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.anchor = GridBagConstraints.WEST;
            panel.add(userText, gbc);

            // 设置密码标签和密码框
            JLabel passwordLabel = new JLabel("密码:");
            passwordLabel.setFont(new Font("宋体", Font.PLAIN, 14));  // 使用支持中文的字体，增大字体
            gbc.gridx = 0;
            gbc.gridy = 1;
            gbc.fill = GridBagConstraints.NONE;
            gbc.anchor = GridBagConstraints.EAST;
            panel.add(passwordLabel, gbc);

            JPasswordField passwordText = new JPasswordField(28);  // 设置较长的密码框
            passwordText.setFont(new Font("宋体", Font.PLAIN, 14));  // 使用支持中文的字体，增大字体
            gbc.gridx = 1;
            gbc.gridy = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.anchor = GridBagConstraints.WEST;
            panel.add(passwordText, gbc);

            // 设置登录按钮
            JButton loginButton = new JButton("登录");
            loginButton.setFont(new Font("宋体", Font.PLAIN, 14));  // 使用支持中文的字体，增大字体
            loginButton.setPreferredSize(new Dimension(60, 38));  // 设置按钮尺寸
            gbc.gridx = 1;
            gbc.gridy = 2;
            //gbc.gridwidth = 1;
            gbc.anchor = GridBagConstraints.CENTER;  // 居中对齐
            panel.add(loginButton, gbc);

            // 设置窗口居中显示
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            loginButton.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    String username = userText.getText();
                    String password = new String(passwordText.getPassword());

                    try {
                        // 发送 HTTP 请求
                        JSONObject jsonResponse = sendLoginRequest(username, password);
                        if (jsonResponse.getInteger("status") == 200) {
                            //JOptionPane.showMessageDialog(frame, "登录成功", "提示", JOptionPane.WARNING_MESSAGE);
                            Messages.showInfoMessage("Login Success", "Info");
                            authentication = (String) jsonResponse.get("Authorization");
                            JSONObject response = (JSONObject) jsonResponse.get("data");
                            uid = (String) response.get("uid");
                            userName= (String) response.get("username");
                            System.out.println(authentication);
                            System.out.println(uid);
                            frame.dispose();  // 关闭登录界面
                        } else {
                            Messages.showInfoMessage("Account Password Error", "Info");
                        }
                    } catch (Exception ex) {
                        Messages.showInfoMessage("Account Password Cannot Empty", "Info");
                        //JOptionPane.showMessageDialog(frame, "Account Password Cannot Empty", "提示", JOptionPane.WARNING_MESSAGE);
                    }
                }
            });
        });
    }

    private JSONObject sendLoginRequest(String username, String password) throws Exception {
        String urlString = "http://"+ IpConfig.IP_ADDRESS+ "/api/login"; // 替换为你的接口 URL
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; utf-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setDoOutput(true);

        String jsonInputString = "{\"username\": \"" + username + "\", \"password\": \"" + password + "\"}";

        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = jsonInputString.getBytes("utf-8");
            os.write(input, 0, input.length);
        }

        int responseCode = connection.getResponseCode();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "utf-8"))) {
            StringBuilder response = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
            //通过RequestHeader的Authorization值（请求时随机产生）完成接口验证
            JSONObject jsonResponse = JSONObject.parseObject(response.toString());
            jsonResponse.put("Authorization",connection.getHeaderField("Authorization"));
            return jsonResponse;
        }
    }

    //获取登录后的authentication
    public static String getAuthorization(){
        if (authentication!=null&&authentication!=""){
            return authentication;
        }else return null;
    }

    //获取登录后的Uid
    public static String getUid(){
        if (uid!=null&&uid!=""){
            return uid;
        }else return null;
    }

    //获取登录后的用户名称
    public static String getUserName() {
        if (userName!=null&&userName!=""){
            return userName;
        }else return null;
    }
}
