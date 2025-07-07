# 智测蓝枢 - TOJ IntelliJ IDEA 插件

## 项目简介

智测蓝枢是一款功能丰富的 IntelliJ IDEA 插件，专为 Java 开发者设计的智能测试工具。该插件支持本地快速构建测试脚本，在线拉取训练题目，提交获取评分等功能，兼容最新的 IDEA 版本。

**开发团队：**
- 开发团队：一元二次方程组
- 参与人员：
  - 23研曾聪
  - 23研黄俊
  - 21软卓林嘉明
  - 23研谢慧偲
- 指导老师：李萌

## 核心功能

### 🔐 在线功能
- **用户登录**：连接 TOJ 平台进行身份认证
- **题目获取**：从 TOJ 平台拉取训练题目到本地
- **成绩查询**：提交测试用例并获取对应评分

### 🧪 测试生成功能
插件提供多种测试类型的自动化生成：

1. **单元测试 (Unit Test)**
   - 自动为 Java 类生成单元测试模板
   - 支持公共方法的测试用例生成
   - 智能变量初始化

2. **数据驱动测试 (Data Driven Test)**
   - 基于参数化测试框架
   - 自动生成多组测试数据
   - 覆盖边界条件和常规场景

3. **基于属性测试 (Property Based Test)**
   - 智能生成属性测试用例
   - 随机输入验证方法属性

4. **变异测试 (Mutation Test)**
   - 集成 PIT 变异测试框架
   - 支持多种变异算子选择
   - 生成详细的变异测试报告

5. **蜕变测试 (Metamorphic Test)**
   - 基于蜕变关系的测试用例生成
   - 智能分析输入变换和预期输出关系

## 系统要求

- **IDE**: IntelliJ IDEA 2017.3+ (build 173.0+)
- **JDK**: Java 8+
- **依赖**: 项目需要 Java 模块支持

## 安装说明

1. 下载 `plugin-test.zip` 插件包
2. 打开 IntelliJ IDEA
3. 进入 `File` → `Settings` → `Plugins`
4. 点击齿轮图标 → `Install Plugin from Disk...`
5. 选择下载的插件包并安装
6. 重启 IDEA

## 使用指南

### 菜单结构

插件在 IDEA 中添加了两个主要菜单组：

#### AllTest 菜单
- **Login**: 登录 TOJ 平台
- **GetTest**: 获取测试题目
- **GetScore**: 查询测试分数

#### TestCase 菜单
- **UnitTest**: 生成单元测试
- **DataDriven**: 生成数据驱动测试  
- **PropertyBased**: 生成基于属性的测试
- **MutationTest**: 执行变异测试
- **MetamorphicTest**: 生成蜕变测试

### 快速开始

1. **登录平台**
   ```
   菜单栏 → AllTest → Login
   输入 TOJ 平台的用户名和密码
   ```

2. **获取题目**
   ```
   菜单栏 → AllTest → GetTest
   选择团队 → 选择训练 → 自动拉取题目到本地
   ```

3. **生成测试**
   ```
   右键点击 Java 类 → TestCase → 选择测试类型
   插件将自动生成对应的测试文件
   ```

### 目录结构

插件会在项目中自动创建以下目录结构：

```
src/
├── [团队名]/
│   └── [训练名]/
│       └── [用户名]/
│           ├── 题目1.java
│           ├── 题目2.java
│           └── problems.json
└── [包名]/
    └── test/
        ├── unittest/          # 单元测试
        ├── datadriven/        # 数据驱动测试
        ├── propertybased/     # 基于属性测试
        ├── mutationtest/      # 变异测试
        └── metamorphictest/   # 蜕变测试
```

## 技术架构

### 核心依赖

- **测试框架**: JUnit 4/5, JUnit Quickcheck
- **变异测试**: PIT (Pitest)
- **HTTP 通信**: Spring Web
- **JSON 处理**: Fastjson
- **中文拼音**: Pinyin4j

### 主要类结构

```
TActions/org/intelllij/
├── LoginAction.java           # 登录功能
├── GetTestAction.java         # 获取题目
├── GetScoreAction.java        # 分数查询
├── UnitTestAction.java        # 单元测试生成
├── DataDrivenAction.java      # 数据驱动测试
├── PropertyBasedAction.java   # 属性测试
├── MutationTestAction.java    # 变异测试
└── MetamorphicTestAction.java # 蜕变测试
```

## 特色功能

### 🤖 智能代码生成
- 基于 LLM 的智能测试用例生成
- 自动分析方法签名和类结构
- 生成符合最佳实践的测试代码

### 📊 变异测试集成
- 支持 20+ 种变异算子
- 可视化变异测试报告
- 自动编译和测试执行

### 🌐 在线平台集成
- 与 TOJ 平台无缝对接
- 支持团队协作和题目分享
- 实时成绩反馈

### 🎯 多维度测试覆盖
- 单元测试：方法级别测试
- 数据驱动：参数化测试
- 属性测试：随机化验证
- 变异测试：代码质量评估
- 蜕变测试：关系验证

## 配置说明

### 服务器配置
默认连接服务器：`223.4.248.47`
可在 `config/IpConfig.java` 中修改服务器地址

### 认证机制
- 使用 JWT Token 进行身份验证
- 支持会话保持和自动重连
- 安全的密码传输

## 开发说明

### 构建项目
```bash
# 编译项目
javac -cp "lib/*" src/**/*.java

# 打包插件
jar cvf plugin-test.jar resources/ src/
```

### 扩展开发
插件采用模块化设计，可以轻松扩展新的测试类型：

1. 创建新的 Action 类继承 `AnAction`
2. 在 `plugin.xml` 中注册新的动作
3. 实现 `actionPerformed` 方法
4. 添加对应的图标资源

## 问题反馈

如果您在使用过程中遇到任何问题，请通过以下方式联系我们：

- **邮箱**: junhuang@stu.usc.edu.cn
- **开发机构**: 南华大学计算机学院



## 许可证

本项目由南华大学计算机学院一元二次方程组开发，用于学术研究和教学目的。

---
## 团队口号

**智测蓝枢** - 蓝枢驱动未来，测试定义卓越