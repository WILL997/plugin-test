package TActions.org.intelllij;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompileStatusNotification;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.CompileScope;

public class MutationTestAction extends AnAction {

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

                VirtualFile packageDir = sourceRoot.findFileByRelativePath(packageName.replace(".", "/"));
                VirtualFile testDirectory = null;

                if (packageDir != null) {
                    // 使用 runWriteAction 封装所有写操作
                    testDirectory = ApplicationManager.getApplication().runWriteAction(new Computable<VirtualFile>() {
                        @Override
                        public VirtualFile compute() {
                            try {
                                // 检查并创建 test 目录
                                VirtualFile testDir = packageDir.findChild("test");
                                if (testDir == null) {
                                    testDir = packageDir.createChildDirectory(this, "test");
                                    System.out.println("已创建测试目录: " + testDir.getPath());
                                }

                                // 检查并创建 mutationtest 目录
                                VirtualFile mutationtestDir = testDir.findChild("mutationtest");
                                if (mutationtestDir == null) {
                                    mutationtestDir = testDir.createChildDirectory(this, "mutationtest");
                                    System.out.println("已创建变异测试目录: " + mutationtestDir.getPath());
                                }

                                // 返回 mutationtest 目录
                                return mutationtestDir;

                            } catch (IOException ex) {
                                ex.printStackTrace();
                                return null;
                            }
                        }
                    });
                }

                // 循环单个选中的java文件
                for (VirtualFile selectedFile : selectedFiles) {
                    PsiFile psiFile = PsiManager.getInstance(project).findFile(selectedFile);
                    if (psiFile instanceof PsiJavaFile) {
                        PsiJavaFile javaFile = (PsiJavaFile) psiFile;
                        PsiClass[] classes = javaFile.getClasses();
                        for (PsiClass psiClass : classes) {
                            String className = psiClass.getName();
                            System.out.println("Selected class name: " + className);

                            VirtualFile testClassDirectory = null;

                            // 在 mutationtest 目录下创建 className + "mutationReports" 对应的变异测试报告文件夹
                            if (testDirectory != null) {
                                VirtualFile finalTestDirectory = testDirectory;
                                testClassDirectory = ApplicationManager.getApplication().runWriteAction(new Computable<VirtualFile>() {
                                    @Override
                                    public VirtualFile compute() {
                                        try {
                                            // 检查是否已存在对应的变异测试报告文件夹
                                            VirtualFile testClassDir = finalTestDirectory.findChild(className + "mutationReports");
                                            if (testClassDir == null) {
                                                // 创建对应类的变异测试报告文件夹
                                                testClassDir = finalTestDirectory.createChildDirectory(this, className + "mutationReports");
                                                System.out.println("Created test directory: " + testClassDir.getPath());
                                            } else {
                                                System.out.println("Test class directory already exists: " + testClassDir.getPath());
                                            }
                                            return testClassDir;
                                        } catch (IOException ex) {
                                            ex.printStackTrace();
                                            return null;
                                        }
                                    }
                                });
                            }

                            String projectPath = e.getProject().getBasePath() + "/out/production/" + e.getProject().getName();   // 项目地址 必须用绝对路径
                            String reportDir = testClassDirectory != null ? testClassDirectory.getPath() : "";                                                 // 生成的报告地址 必须用绝对路径
                            String targetClasses = packageName + "." + className;                       // 要变异的类
                            String basePath = e.getProject().getBasePath();                           // 项目根目录

                            // 在同级目录搜索名为 "test" 的文件夹
                            VirtualFile testFolder = selectedFile.getParent().findChild("test");
                            // 对于在线拉取的代码还得getParent().getParent() 代码得先运行才会在对应文件夹生成class文件
                            if (testFolder == null) {
                                testFolder = selectedFile.getParent().getParent().getParent().findChild("test");
                            }

                            if (testFolder != null && testFolder.isDirectory()) {
                                // 获取 "test" 文件夹下所有以当前 Java 文件名为前缀的 Java 文件的类名
                                List<String> classNames = getClassNames(project, testFolder, className);

                                // 变量用于后续逻辑
                                final String[] targetTests = new String[1]; // 使用数组以便在 lambda 中修改
                                final String[] mutators = new String[1]; // 同样处理

                                // 编译文件，并在编译成功后执行后续逻辑   需要异步执行成功后再执行变异操作
                                VirtualFile finalTestFolder = testFolder;
                                compileProject(project, () -> {
                                    // 检测test文件夹下是否存在对应的测试用例
                                    if (classNames.size() == 0) {    // 不存在对应测试用例
                                        JOptionPane.showMessageDialog(null, "所选类" + className + "在test文件夹及其子文件夹下不存对应以" + className + "开头的测试用例，请先添加!", "Info", JOptionPane.WARNING_MESSAGE);
                                    } else {  // 存在对应测试用例就添加
                                        StringBuilder builder = new StringBuilder();
                                        for (int i = 0; i < classNames.size(); i++) {
                                            builder.append(classNames.get(i));
                                            if (i < classNames.size() - 1) {
                                                builder.append(",");
                                            }
                                        }
                                        targetTests[0] = builder.toString();  // 构建对应格式的测试用例
                                        System.out.println(targetTests[0]);

                                        // 变异算子选取
                                        mutators[0] = showMultiSelectCheckboxDialog("变异算子选取", new String[]{
                                                "CONDITIONALS_BOUNDARY", "INCREMENTS", "INVERT_NEGS", "MATH", "NEGATE_CONDITIONALS",
                                                "VOID_METHOD_CALLS", "EMPTY_RETURNS", "FALSE_RETURNS", "TRUE_RETURNS", "NULL_RETURNS",
                                                "PRIMITIVE_RETURNS", "CONSTRUCTOR_CALLS", "INLINE_CONSTS", "NON_VOID_METHOD_CALLS",
                                                "REMOVE_CONDITIONALS", "REMOVE_INCREMENTS", "EXPERIMENTAL_ARGUMENT_PROPAGATION",
                                                "EXPERIMENTAL_BIG_INTEGER", "EXPERIMENTAL_NAKED_RECEIVER", "EXPERIMENTAL_MEMBER_VARIABLE",
                                                "EXPERIMENTAL_SWITCH", "ALL"}, new String[]{
                                                "CONDITIONALS_BOUNDARY", "INCREMENTS", "INVERT_NEGS", "MATH", "NEGATE_CONDITIONALS",
                                                "VOID_METHOD_CALLS", "EMPTY_RETURNS", "FALSE_RETURNS", "TRUE_RETURNS", "NULL_RETURNS",
                                                "PRIMITIVE_RETURNS"});

                                        // 如果用户选择了变异算子
                                        if (mutators[0] != null && !mutators[0].isEmpty()) {
                                            try {
                                                mutationTest(projectPath, reportDir, targetClasses, targetTests[0], mutators[0], basePath);
                                            } catch (IOException ioException) {
                                                ioException.printStackTrace();
                                            }
                                        }
                                        try {
                                            revertFile(classNames, finalTestFolder,project);
                                        } catch (IOException ioException) {
                                            ioException.printStackTrace();
                                        }

                                    }
                                });
                                /**try {
                                    revertFile(classNames,testFolder,project);
                                    //compileProject1(project);
                                } catch (IOException ioException) {
                                    ioException.printStackTrace();
                                }**/
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

    //包命必须用绝对路径   程序入口不一样   在插件中运行工作路径为 D:/IDEA/bin   在正常代码中运行main函数当前就是D:/plugin-test   插件运行类似一个容器，与外界隔离
    //String projectRootPath = System.getProperty("user.dir");
    //System.out.println("项目根目录路径：" + projectRootPath);     或用类加载器获取的路径也不对
    public static void mutationTest(String projectPath,String reportDir,String targetClasses,String targetTests,String mutators,String basePath) throws IOException {
        String[] command = {
                "java",
                "-cp",
                basePath+"/lib/pitest-1.0.0-SNAPSHOT.jar;"+basePath+"/lib/pitest-command-line-1.0.0-SNAPSHOT.jar;"+basePath+"/lib/pitest-entry-1.0.0-SNAPSHOT.jar;"+basePath+"/lib/junit-4.13.2.jar;"+basePath+"/lib/hamcrest-core-1.3.jar;"+basePath+"/lib/pitest-html-report.jar;"+projectPath,
                "org.pitest.mutationtest.commandline.MutationCoverageReport",
                "--reportDir",
                reportDir,
                "--targetClasses",
                targetClasses,
                "--targetTests",
                targetTests,
                "--sourceDirs",
                projectPath,
                "--mutators",
                mutators,
                "--verbose",
                "true"
        };
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        // 读取命令输出
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            System.out.println(line);
        }

        // 等待进程执行完毕
        try {
            int exitCode = process.waitFor();
            System.out.println("Process exited with code " + exitCode);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }


    // 获取 "test" 文件夹下所有以指定前缀的 Java 文件的类名
    private List<String> getClassNames(Project project,VirtualFile testFolder, String prefix) {
        List<String> classNames = new ArrayList<>();
        collectClassNames(testFolder, prefix, classNames);
        for (String className:classNames){
            String classPath=testFolder.getParent().getParent().getPath()+"/"+className.replace(".", "/");
            classPath=classPath+".java";
            //className.replace("/","\\");
            System.out.println(classPath);
            try {
                modifyFile(classPath,project);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return classNames;
    }

    // 把import static org.junit.jupiter.api.Assertions.*; import org.junit.jupiter.api.Test; 替换成
    //import static org.junit.Assert.assertEquals;
    //import org.junit.Test;
    //pit test只支持上述导入语句
    private void modifyFile(String filePath,Project project) throws IOException {
        Path path = Paths.get(filePath);
        List<String> lines = Files.readAllLines(path);

        // 用于存储需要添加的导入
        Set<String> newImports = new LinkedHashSet<>();

        // 检查和替换导入
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().equals("import org.junit.jupiter.api.Test;")) {
                lines.set(i, "import org.junit.Test;");
            } else if (line.trim().equals("import static org.junit.jupiter.api.Assertions.*;")) {
                lines.set(i, "import static org.junit.Assert.assertEquals;");
            }
        }

        if (lines.stream().noneMatch(line -> line.contains("import org.junit.Test;"))) {
            newImports.add("import org.junit.Test;");
        }

        if (lines.stream().noneMatch(line -> line.contains("import static org.junit.Assert.assertEquals;"))) {
            newImports.add("import static org.junit.Assert.assertEquals;");
        }


        // 确保包声明在导入之后
        int packageIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("package ")) {
                packageIndex = i;
                break;
            }
        }

        // 添加新导入到包声明之后
        if (packageIndex != -1) {
            for (String importLine : newImports) {
                lines.add(packageIndex + 1, importLine);
            }
        } else {
            // 如果没有包声明，直接添加到文件开头
            newImports.forEach(importLine -> lines.add(0, importLine));
        }

        Files.write(path, lines);


    }

    //import static org.junit.Assert.assertEquals;
    //import org.junit.Test;   替换成import static org.junit.jupiter.api.Assertions.*; import org.junit.jupiter.api.Test;
    //方便进行TOJ提交测试用来
    private void revertFile(List<String> classNames,VirtualFile testFolder, Project project) throws IOException {
        for (String className:classNames){
            String classPath=testFolder.getParent().getParent().getPath()+"/"+className.replace(".", "/");
            classPath=classPath+".java";
            //className.replace("/","\\");
            System.out.println(classPath);
            Path path = Paths.get(classPath);
            List<String> lines = Files.readAllLines(path);

            // 用于存储需要添加的导入
            Set<String> newImports = new LinkedHashSet<>();

            // 检查和替换导入
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.trim().equals("import org.junit.Test;")) {
                    lines.set(i, "import org.junit.jupiter.api.Test;");
                } else if (line.trim().equals("import static org.junit.Assert.assertEquals;")) {
                    lines.set(i, "import static org.junit.jupiter.api.Assertions.*;");
                }
            }

            if (lines.stream().noneMatch(line -> line.contains("import org.junit.jupiter.api.Test;"))) {
                newImports.add("import org.junit.jupiter.api.Test;");
            }

            if (lines.stream().noneMatch(line -> line.contains("import static org.junit.jupiter.api.Assertions.*;"))) {
                newImports.add("import static org.junit.jupiter.api.Assertions.*;");
            }

            // 确保包声明在导入之后
            int packageIndex = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("package ")) {
                    packageIndex = i;
                    break;
                }
            }

            // 添加新导入到包声明之后
            if (packageIndex != -1) {
                for (String importLine : newImports) {
                    lines.add(packageIndex + 1, importLine);
                }
            } else {
                // 如果没有包声明，直接添加到文件开头
                newImports.forEach(importLine -> lines.add(0, importLine));
            }

            Files.write(path, lines);
        }

    }


    // 编译项目
    public static void compileProject(Project project, Runnable onSuccess) {
        ApplicationManager.getApplication().invokeLater(() -> {
            // 创建项目编译范围
            CompileScope scope = CompilerManager.getInstance(project).createProjectCompileScope(project);

            // 开始编译
            CompilerManager.getInstance(project).compile(scope, new CompileStatusNotification() {
                @Override
                public void finished(boolean aborted, int exitCode, int compileStatus, @NotNull CompileContext compileContext) {
                    if (!aborted && exitCode == 0) {
                        System.out.println("Project compilation successful.");
                        onSuccess.run(); // 调用后续逻辑
                    } else {
                        System.out.println("Project compilation failed.");
                    }
                }
            });
        });
    }

    //异步执行 编译 应该用不上
    public static void compileProject1(Project project) {
        ApplicationManager.getApplication().invokeLater(() -> {
            // 创建项目编译范围
            CompileScope scope = CompilerManager.getInstance(project).createProjectCompileScope(project);

            // 开始编译
            CompilerManager.getInstance(project).compile(scope, new CompileStatusNotification() {
                @Override
                public void finished(boolean aborted, int exitCode, int compileStatus, @NotNull CompileContext compileContext) {
                    if (!aborted && exitCode == 0) {
                        System.out.println("Project compilation successful.");
                    } else {
                        System.out.println("Project compilation failed.");
                    }
                }
            });
        });
    }

    // 递归搜索 "test" 文件夹及其子文件夹下的所有 Java 文件
    private void collectClassNames(VirtualFile folder, String prefix, List<String> classNames) {
        for (VirtualFile file : folder.getChildren()) {
            if (file.isDirectory()) {
                // 如果是文件夹，递归搜索其中的文件
                collectClassNames(file, prefix, classNames);
            } else if (file.getName().startsWith(prefix) && file.getName().endsWith(".java")) {
                // 如果是以指定前缀开头且是 Java 文件，获取其类名并添加到列表中
                String filename = file.getName();
                String className="";
                int dotIndex = filename.indexOf('.');
                if (dotIndex != -1) {
                    className = filename.substring(0, dotIndex);
                }
                classNames.add(getPackageFromFileContent(file)+"."+className);
            }
        }
    }

    // 从 Java 文件内容中提取类路径 从package中提取
    private String getPackageFromFileContent(VirtualFile file) {
        // 读取 Java 文件内容
        StringBuilder contentBuilder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                contentBuilder.append(line).append("\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        // 使用正则表达式从文件内容中提取 package 声明
        String content = contentBuilder.toString();
        Pattern pattern = Pattern.compile("^\\s*package\\s+([a-zA-Z_$][a-zA-Z\\d_$]*(\\.[a-zA-Z_$][a-zA-Z\\d_$]*)*);.*", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        } else {
            // 如果未找到 package 声明，返回空字符串或者其他默认值
            return "";
        }
    }



    //用户选取变异算子交互界面
    public static String showMultiSelectCheckboxDialog(String title, String[] options, String[] defaultOptions) {
        List<String> selectedOptions = new ArrayList<>();

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(5, 5, 5, 5);

        List<JCheckBox> checkBoxes = new ArrayList<>();
        for (String option : options) {
            JCheckBox checkBox = new JCheckBox(option);
            if (isDefaultOption(option, defaultOptions)) {
                checkBox.setSelected(true);
            }
            panel.add(checkBox, gbc);
            checkBoxes.add(checkBox);
            gbc.gridy++;
        }

        int result = JOptionPane.showConfirmDialog(null, panel, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            for (JCheckBox checkBox : checkBoxes) {
                if (checkBox.isSelected()) {
                    selectedOptions.add(checkBox.getText());
                }
            }
        }

        return String.join(",", selectedOptions);
    }

    private static boolean isDefaultOption(String option, String[] defaultOptions) {
        for (String defaultOption : defaultOptions) {
            if (option.equals(defaultOption)) {
                return true;
            }
        }
        return false;
    }


}
