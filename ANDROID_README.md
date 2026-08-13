# VlessVPN — Android 全局代理 VPN 工具

用 `VpnService` + tun2socks + vless-ws-client 三部分拼起来的 Android VPN 客户端。

> **重要提醒**：这份代码是在 Linux 沙盒环境里写的，**没有 Android SDK/模拟器，
> 没法在这编译或运行验证**。跟之前的 macOS 版本不一样，这次连"编译看看语法
> 对不对"这一步都做不到，纯粹是按 Android 官方 API 文档和 `VpnService` 通用
> 实现模式写的。你在 Android Studio 里打开这个项目，大概率需要处理一些编译
> 报错、权限问题、甚至是运行时的逻辑问题，报错发我可以继续帮你调。

## 已经实际验证过的部分

- **tun2socks**：直接从 [xjasonlyu/tun2socks](https://github.com/xjasonlyu/tun2socks)
  官方 GitHub Releases 下载的 v2.6.0 预编译二进制（没有自己编译——这个项目主
  要用 Bazel 构建，其中一个依赖 gvisor 的 go.mod 生态在沙盒里编译会踩到一堆
  版本兼容问题，直接用官方发布的现成二进制更可靠）。四个架构
  （arm64/amd64/armv7/386）都下载了，用 `file` 命令确认了都是静态链接、架构
  正确的可执行文件，amd64 版本在沙盒里直接运行验证过 `--version`/`--help`
  能正常输出。
- **vless-ws-client**：延用之前 macOS 项目里已经验证过的同一份 Go 源码，
  同样用 `CGO_ENABLED=0` 编译成纯静态的 Android 版本。**新增的 SOCKS5 UDP
  ASSOCIATE 支持**在沙盒里用手写测试客户端 + UDP echo 服务器做了完整的
  往返测试（单目标、多目标并发两种场景），协议层面是真正跑通的。

## 完全没法验证的部分

- 所有 Kotlin 代码（`MainActivity`/`VlessVpnService`/`NativeProcessManager`）
- Gradle 构建配置本身对不对
- `VpnService` 的整个生命周期、`Builder.establish()`、通知权限这些实际跑起来
  是什么效果
- 最关键的一个技巧——把 TUN 文件描述符从 App 进程"过继"给 tun2socks 子进程
  这一步（下面详细说）——这个逻辑没有任何办法在没有真机/模拟器的情况下验证

## 架构说明

```
Android 系统 VpnService（虚拟网卡）
        │  产生一个文件描述符（不是常规的 /dev/tun0 设备名）
        ▼
tun2socks（-device fd://<fd号>）
        │  把 TUN 网卡里的原始 IP 包转换成 SOCKS5 协议
        ▼
vless-ws-client（本地 SOCKS5 服务，-local-port 10808）
        │  VLESS over WebSocket
        ▼
你的服务器
```

### 关键技巧：文件描述符怎么"过继"给子进程

Android 应用不能自己创建 TUN 设备，只能通过 `VpnService.Builder().establish()`
拿到一个 `ParcelFileDescriptor`。而 tun2socks 是个独立的可执行文件（子进程），
没法直接"传"一个 Kotlin 对象过去，只能传一个数字（文件描述符编号）。

这里能work的原理是 Linux/Android 的标准 `fork()+exec()` 语义：子进程默认会
继承父进程所有"没有标记 close-on-exec"的文件描述符，而且继承之后编号不变。
所以只要：
1. 在启动子进程之前，用 `Os.fcntlInt(fd, F_SETFD, 0)` 显式清掉这个 fd 的
   close-on-exec 标记（`VlessVpnService.kt` 里已经这么做了）
2. 把这同一个数字（`vpnInterface.fd`）当作命令行参数传给 tun2socks
   （`-device fd://<这个数字>`）

子进程 fork 出来之后，这个数字对应的就是同一个底层的 TUN 文件描述符。这是
社区里其他开源 tun2socks 类 Android VPN 客户端通用的做法，不是我瞎编的，
但具体到这份代码，没有实机测试过，可能需要根据实际报错调整。

## 目录结构

```
android-project/
├── build.gradle.kts / settings.gradle.kts
├── app/
│   ├── build.gradle.kts
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/example/vlessvpn/
│   │   │   ├── MainActivity.kt         # 配置表单 + 连接开关
│   │   │   ├── VlessVpnService.kt      # 核心 VpnService，建 TUN + 拉起两个子进程
│   │   │   ├── NativeProcessManager.kt # 管理两个子进程的启停
│   │   │   └── VpnConfig.kt            # 配置数据模型 + SharedPreferences 持久化
│   │   ├── res/layout/activity_main.xml
│   │   ├── res/values/ (strings/colors/themes)
│   │   ├── res/mipmap-*/ic_launcher.png（图标，跟 macOS 版本同一个源图生成的）
│   │   └── jniLibs/
│   │       ├── arm64-v8a/{libvlessclient.so, libtun2socks.so}
│   │       ├── armeabi-v7a/{libvlessclient.so, libtun2socks.so}
│   │       ├── x86_64/{libvlessclient.so, libtun2socks.so}
│   │       └── x86/{libvlessclient.so, libtun2socks.so}
```

## DNS/UDP 现在已经支持了

之前这里写的是"DNS/UDP 大概率不通"——`vless-ws-client` 那时候的 SOCKS5 实现
只支持 TCP CONNECT，没有 UDP ASSOCIATE，而 tun2socks 会把 UDP 流量（DNS 查询
走的就是 UDP）通过 SOCKS5 UDP ASSOCIATE 转发，两边对不上，DNS 解析会失败。

现在 `vless-ws-client`（服务端 + 客户端）已经补上了完整的 SOCKS5 UDP
ASSOCIATE 支持，`jniLibs/` 里的 `libvlessclient.so` 是最新编译的、带 UDP
支持的版本。这部分已经用手写的 SOCKS5 UDP 测试客户端 + UDP echo 服务器在
沙盒里做了完整的往返测试（单目标、三个并发不同目标各自独立隧道两种场景），
链路本身是真正跑通的——但那是在 Linux 沙盒里直接测的 SOCKS5 协议层面，
不是通过 tun2socks + Android VpnService 这整条链路测的，实际在 Android
真机上 DNS 解析走不走得通、tun2socks 那边对 UDP ASSOCIATE 的具体处理细节
对不对得上，还是需要你在真机上验证。

## 编译前需要你自己补的东西

1. 用 Android Studio 打开 `android-project/` 目录，让它自动生成
   `local.properties`（指向你本机的 Android SDK 路径）和 Gradle Wrapper
   文件——这些是每个人机器上都不一样的东西，没有随项目提供。
2. `app/src/main/AndroidManifest.xml` 和 `app/build.gradle.kts` 里的
   `applicationId`/`namespace` 现在是占位符 `com.example.vlessvpn`，正式
   使用建议改成你自己的。
3. Android 13+ 需要运行时请求通知权限（`POST_NOTIFICATIONS`）才能正常显示
   前台服务通知，`MainActivity.kt` 里现在没有加这个权限请求逻辑，需要补上
   （不加的话前台服务可能会因为拿不到通知权限被系统限制或报错）。

## 与 macOS 版本的关系

`vless-ws-client` 的 Go 源码是同一份，只是编译目标不同
（`GOOS=linux GOARCH=arm64/amd64/arm/386` + `CGO_ENABLED=0`，纯静态无 libc
依赖）。以后 Go 客户端加新参数，`VpnConfig.kt` 的 `toVlessClientArgs()`
跟着加一行、`activity_main.xml` 表单里加个输入框即可，逻辑上跟 macOS 版本
的 `ConfigFormFields.swift` 是对应的。
