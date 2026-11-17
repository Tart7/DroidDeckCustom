# DroidDeck 二开：自定义商城模块

在官方 DroidDeck 上增加自己的商城页签，同时保持上游**自动追更**。

## 为什么不用 hook

`Store` 是封闭的 `enum class`。Xposed 模块只能靠反射寄生：

- `CatalogItem` 23 个参数、`StoreGameSidecar` 18 个、`DownloadEntry` 26 个，全靠数参数个数构造
- Kotlin `object` 的方法在 JVM 上是 `INSTANCE` 上的实例方法，不是静态方法
- `Function0`／`DownloadJob` 等类型在两个 classloader 里不是同一个类
- 上游改任何签名，模块静默失效（`runCatching` 吞掉异常）

二开改的是源码，编译器帮你检查，不需要猜。

## 上游足迹：16 行

| 文件 | 改动 |
|---|---|
| `stores/Store.kt` | +6 新增 `STEAMRIP` 常量 |
| `App.kt` | +2 注册 backend 一行 |
| `stores/CloudSaves.kt` | 3 处 catch-all 列表加 `STEAMRIP` |
| `stores/StoreSizes.kt` | 2 处 catch-all 列表加 `STEAMRIP` |
| `stores/LoginFlows.kt` | +1 `else -> null` |
| `ui/StoresPage.kt` | +2 `else -> return base` 配色兜底 |

其余全部在独立包 `com.droiddeck.customstore.*`，永不与上游冲突。

## 两个关键设计

**1. 枚举常量放在列表最前**

上游习惯在末尾追加新 store。把 `STEAMRIP` 放在 `GOG` 之前，就避开了最热的冲突点。
ordinal 会整体后移，但 ordinal 不持久化（`byId` 按 id 查），安全。

**2. 配色 when 用 `else` 兜底，且加在 `null ->` 之后**

```kotlin
Store.ROCKSTAR -> ...
null -> return base
else -> return base     // 我们的
```

上游加新 store 时会插在 `null ->` 上面，与我们不重叠。

## 自动追更

`.github/workflows/sync-upstream.yml`：每天 03:00 UTC

```
fetch upstream → rebase → 成功则编译签名发 Release
                        → 失败则开 issue，分支不动，绝不带病发包
```

rebase 用 `--empty=drop`，我们已被上游吸收的提交会自动丢弃。

**已验证**：模拟上游新增 store 常量 + 修改同一配色函数，rebase 全自动通过，零冲突。

## 新增一个站点

1. 在 `CustomStoreSite.kt` 实现接口
2. `Store.kt` 加常量，5 处 catch-all 列表加上它
3. `CustomStoreBackend` 里换 `site`

## 构建

本地需要 NDK 27.3.13750724 + CMake 3.22.1：

```bash
git submodule update --init --recursive
./gradlew :app:assembleRelease
```

或直接靠 CI 出包，本地不装 NDK。

## CI 需要的 Secrets

| Name | 说明 |
|---|---|
| `KEYSTORE_B64` | `base64 -i release.p12` |
| `KEYSTORE_PASS` | keystore 密码 |
| `KEY_ALIAS` | key 别名 |
| `KEY_PASS` | key 密码 |

不配置也能编译，只是不签名。
