# ZooKeeper客户端插件故障排查指南

## 问题：连接卡在"连接中"状态

### 可能的原因和解决方案

#### 1. ZooKeeper服务器未运行

**检查方法：**
```bash
# 检查本地ZooKeeper是否运行
echo stat | nc localhost 2181

# 或使用telnet
telnet localhost 2181
# 输入: stat
```

**解决方案：**
- 启动ZooKeeper服务器
- 或修改连接字符串指向正确的服务器

---

#### 2. 端口被防火墙阻止

**检查方法：**
```bash
# 测试端口连通性
nc -zv localhost 2181

# 或
telnet localhost 2181
```

**解决方案：**
- 检查防火墙规则
- 确保2181端口开放

---

#### 3. 连接字符串配置错误

**常见错误：**
- ❌ `localhost:2181/` (末尾多了斜杠)
- ❌ `192.168.1.1:2181` (IP地址错误)
- ❌ `localhost` (缺少端口)

**正确格式：**
- ✅ `localhost:2181`
- ✅ `192.168.1.1:2181`
- ✅ `zk1:2181,zk2:2181,zk3:2181` (集群)
- ✅ `localhost:2181/prod` (带chroot)

---

#### 4. 会话超时设置过长

**默认超时：** 30000ms (30秒)

**建议：**
- 测试连接时使用较短超时：5000-10000ms
- 生产环境使用标准超时：30000ms

**在连接对话框中修改：**
```
Session timeout (ms): 10000  # 改为10秒进行快速测试
```

---

#### 5. 认证配置错误

**如果启用了Digest认证：**
- 确保用户名和密码正确
- 注意：插件的"Test Connection"不验证认证，只测试连通性

**检查方法：**
```bash
# 使用zkCli测试认证
zkCli.sh -server localhost:2181
addauth digest user:password
ls /
```

---

## 快速诊断步骤

### 步骤1：测试本地连接
```bash
# 启动一个临时的ZooKeeper（Docker）
docker run -d --name zk-test -p 2181:2181 zookeeper:3.9

# 等待5秒启动
sleep 5

# 测试连接
echo stat | nc localhost 2181
```

### 步骤2：在插件中连接
1. 打开插件工具窗口
2. 点击"+"添加连接
3. 配置：
   - Name: `test`
   - Connect string: `localhost:2181`
   - Session timeout: `10000` (10秒)
4. 点击"Test Connection"
   - 应该在8秒内显示"Connected ✓"
5. 点击OK
6. 点击"Connect"按钮
   - 应该在10秒内连接成功或超时

### 步骤3：查看IDEA日志
如果仍然卡住，查看IDEA日志：
```
菜单: Help → Show Log in Finder (macOS) / Show Log in Explorer (Windows)
日志文件: idea.log
搜索: "ZkSession" 或 "ZooKeeper"
```

---

## 调试模式

### 启用详细日志

**方法1：在IDEA中**
1. 菜单：Help → Diagnostic Tools → Debug Log Settings
2. 添加：`#wiki.twom.plugin.zk`
3. 点击OK
4. 重试连接
5. 查看日志文件

**方法2：手动编辑**
创建或编辑：`<IDEA-config>/bin/idea.properties`
```properties
log.debug.categories=#wiki.twom.plugin.zk
```

---

## 常见场景和解决方案

### 场景1：连接远程ZooKeeper卡住
**原因：** 网络延迟或防火墙
**解决：**
1. 增加超时：60000ms
2. 使用VPN或SSH隧道
3. 检查安全组规则

### 场景2：连接成功但树形结构不显示
**原因：** 权限问题或根节点不存在
**解决：**
1. 检查ACL权限
2. 确认认证信息正确
3. 尝试访问 `/` 节点

### 场景3："Test Connection"成功但实际连接失败
**原因：** 认证配置问题
**解决：**
1. 注意：Test Connection不验证认证
2. 实际连接会验证认证信息
3. 检查用户名密码是否正确

---

## 临时解决方案

### 快速重置连接状态

如果UI卡住：
1. 点击工具栏的"Disconnect"按钮
2. 等待3秒
3. 重新点击"Connect"

如果仍然卡住：
1. 关闭工具窗口
2. 重新打开（View → Tool Windows → ZooKeeper）

---

## 报告问题

如果以上方法都无法解决，请提供以下信息：

1. **连接配置**
   - Connect string: ?
   - Session timeout: ?
   - 是否使用认证: ?

2. **环境信息**
   - 操作系统: ?
   - IDEA版本: ?
   - ZooKeeper版本: ?

3. **日志片段**
   从 `idea.log` 中搜索 "ZkSession" 的相关日志

4. **重现步骤**
   详细描述如何重现问题

---

## 性能优化建议

### 大规模ZooKeeper集群

如果连接到包含数万节点的ZooKeeper：

1. **不要一次性展开所有节点**
   - 使用懒加载，逐级展开

2. **启用缓存淘汰**
   - 插件会在20000个节点后自动淘汰缓存
   - 折叠节点会释放内存

3. **使用搜索功能**
   - 递归搜索限制在500个结果
   - 对于大树，使用"Go to Path"直接跳转

4. **减少Watch使用**
   - Watch会增加服务器负载
   - 只在需要实时更新时启用

---

## 联系支持

- GitHub Issues: [项目地址]
- 邮件: [支持邮箱]
