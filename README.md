# Skin Switching · 皮肤切换

在游戏里输入正版 Java 版用户名，直接使用对方当前的自定义皮肤。无需手动下载、导入图片，也无需对方账号密码。提供两个独立安装包，**一次只装其中一个**。

## 环境与安装

- Minecraft **Java 版 1.21.1**。
- **NeoForge 21.1.251 或更新的 21.1.x**，Java 21。
- 不适用于 Forge、Fabric、基岩版或其他游戏版本。

| 安装包后缀 | 安装位置 | 谁能看到 | 适用情况 |
| --- | --- | --- | --- |
| `-client.jar` | 自己客户端的 `mods` 文件夹 | 只有自己 | 单人游戏，或服务器没有安装本模组时 |
| `-sync.jar` | 服务器及所有玩家客户端的 `mods` 文件夹 | 安装联机版的同服玩家 | 希望大家看到彼此切换的皮肤 |

两个文件的模组 ID 都是 `skin_switching`，**不能同时安装，也不能在同一联机服混用两种版本**。联机版使用必需的同步通道，缺少对应版本的客户端无法进入该服务器。

关闭游戏，将所选 JAR 放入当前游戏实例的 `mods` 文件夹，再启动游戏。不需要解压 JAR，也没有额外前置模组。启动器启用版本隔离时，请使用该实例自己的 `mods` 文件夹。

## 指令

```text
/Skin Switching SXUUZ
/skin switching SXUUZ
/skin set SXUUZ
/skin reset
/skin status
```

前三条都是切换自己的皮肤；`reset` 恢复原皮肤；`status` 查看模式、当前选择和简要帮助。根指令也可用 `/skinswitch`，例如 `/skinswitch switching SXUUZ`。保留用户最初要求的大小写指令 `/Skin Switching 用户名`。

普通玩家即可使用，不要求开启作弊或拥有 OP。只能切换自己的外观，没有替其他玩家更换皮肤的管理指令。输入的是 Java 版正版用户名，不是昵称、UUID、网址或基岩版 Xbox 名字。

切换完成后可按 F5 或打开背包查看。包括皮肤第二层和粗／细手臂模型；披风、鞘翅外观仍使用自己的。玩家名、UUID、背包、权限和聊天身份不变。不会更改 Minecraft 官网的账号皮肤，也不会影响未使用本模组的其他游戏客户端。

## 保存与网络

- 客户端版：选择保存在 `config/skin-switching-client.json`，按自己的玩家 UUID 记忆；重新进入世界或服务器后恢复。
- 联机版：选择保存在 `<世界目录>/data/skin-switching.json`，按玩家 UUID 记忆；登录时由服务器同步已保存的选择。
- 图片缓存位于客户端游戏目录下的 `skin-switching-cache`。关闭游戏后可以删除此缓存，之后需要重新下载。
- `reset` 会清除相应选择，并取消尚未完成的切换；查询、图片或文件保存失败时保留当前显示的皮肤。
- 联机版先保存选择，再通知客户端下载。若某个客户端网络不通，它会继续显示旧外观；网络恢复后可重新执行切换指令或重连重试。`status` 显示的是服务器已保存的选择。
- 每位玩家两次查询至少间隔 5 秒；同名查询合并，成功资料缓存 10 分钟。保存的是查询时的皮肤资料，不会持续追踪对方之后的换肤。

用户名查询访问 `api.mojang.com`，签名皮肤资料访问 `sessionserver.mojang.com`，图片访问 `textures.minecraft.net`。全部使用 HTTPS；客户端验证 Mojang 的纹理签名。不会上传自己的账号令牌。联机版服务器需要能够访问前两个域名，客户端需要能够下载官方图片。

如果用户名不存在、账号没有可用的自定义皮肤，或 Mojang 限流，聊天栏会给出原因。国内网络访问失败时，请检查上述域名的连通性后重试。

## 开发与构建

完整工程包含 Gradle Wrapper、源码、中文和英文语言文件、自动测试及独立的开发测试模组。

```powershell
# 使用本机 Java 21；设置 JAVA_HOME 后运行：
.\gradlew.bat build
```

macOS/Linux 使用 `./gradlew build`。第一次构建需要联网下载依赖。产物位于 `build/libs/`：`-client.jar`、`-sync.jar` 与 `-sources.jar`。完整源码 ZIP 比 `-sources.jar` 多了构建脚本和测试。

```powershell
.\gradlew.bat test              # 单元测试及 NeoForge 服务端环境加载检查
.\gradlew.bat runSmokeClient    # 联网皮肤下载、签名、渲染入口和恢复测试
.\scripts\smoke-both.ps1        # 依次启动两种模式，在新世界里执行实际指令
```

冒烟测试会短暂打开游戏窗口，创建独立测试目录和世界，完成后自动退出。`smoke-both.ps1` 会临时切换开发资源中的模式标记，并在结束时恢复；不要与其他构建任务同时运行。它不进入用户自己的世界。开发测试代码位于 `src/smoke`，不会打进发布 JAR。

实现通过客户端 `PlayerInfo.getSkin()` 注入替换展示外观；联机版通过 NeoForge 自定义 S2C 数据包同步来源资料，不修改玩家的实际 GameProfile，不依赖踢出重进或伪造重生包。

## 兼容范围

这是 0.1.0 首版。其他接管玩家皮肤、玩家渲染或 `/skin` 指令的模组可能冲突；指令冲突时可先试 `/skinswitch`。YSM 等替换整个玩家模型的模组可能使用自己的材质，不保证会采用本模组的皮肤。具体已测项目和未完成的多人验证见 `TESTING.md`。

项目原创代码使用 MIT 许可证；Gradle Wrapper 的原有版权与 Apache 2.0 许可另见 `THIRD_PARTY_NOTICES.md`。
