# mc-login-verify

我的世界Minecraft用户管理插件mods，鉴权用户身份判断是否允许进入服务器。
NeoForge 1.21.1 自定义验证 Mod。玩家加入服务器时，验证通过才允许进入，并支持黑名单功能。

## 使用方式

1. 服务器设置 `online-mode=false`
2. 将 `mc_login_verify-1.0.0.jar` 放入 `mods/`
3. 启动服务器，程序自动生成toml后，修改 `config/mc_login_verify-server.toml` 中的 `authUrl`
4. 执行 `/reload` 重载配置

## 构建

```bash
./gradlew build
```

产物在 `build/libs/mc_login_verify-1.0.0.jar`。

## 配置示例

```toml
[auth]
authUrl = "http://你的API地址/verify"
```

## 现成方案
如果你不想部署，也可以直接使用我们现成的程序去管理。
1. 将 `mc_login_verify-1.0.0.jar` 放入服务器 `mods` 文件夹
2. 微信扫一扫下方二维码，进入“MC面板”页面。

![mc.meng.me](https://mc.meng.me/img/mcWechat.png)

3. 顶部选择“管理游戏”
4. 点击“添加服务器”
5. 随意输入一个名称，便于记忆，点击“确认”按钮保存。
6. 复制“插件连接地址”，填写到 `config/mc_login_verify-server.toml` 中的 `authUrl`
7. 重载配置或重启服务。
8. 完成。您的用户可以通过“加入游戏”来绑定游戏角色进入您的服务器。

> 详细请参考：[mc-login-verify mod官方教程](https://mc.meng.me "点击访问官网")