# 获取 Apple 深色 App 图标：给 HQ Icon 的接入说明

核对日期：2026-11-15。本文基于 KeiHome 当前实现，并重新验证了 ChatGPT 的美区 iOS 图标。

## 1. 关键入口

**先取得 App Store 扩展元数据里的 `iconArtwork.url`，再请求 `appearance=dark`。**

完整流程是：

```text
确认 App Store ID 和商店地区
→ 请求扩展元数据，取得平台对应的 iconArtwork.url
→ 替换尺寸、裁切和格式占位符
→ 分别请求 appearance=light / appearance=dark
→ 解码图片，确认是否确实存在独立的深色版本
```

iTunes Search / Lookup 仍适合查找 App。但它们常见的 `artworkUrl512`、`artworkUrl100` 通常是传统图片入口，不能保证通过添加 `appearance=dark` 得到深色版本。

这里使用的扩展元数据接口是 **App Store 网页端使用、经实际观察验证的接口**，Apple 没有为第三方提供稳定性承诺。本文实测请求没有携带 Apple 账号、Cookie、Authorization 或开发者密钥。

## 2. 确认 App 身份和地区

已有 App Store 链接时，取其中的数字 ID。例如：

```text
https://apps.apple.com/us/app/chatgpt/id6448311069
App Store ID = 6448311069
商店地区 = us
```

需要搜索时，可以继续使用 iTunes Search：

```text
https://itunes.apple.com/search?term=ChatGPT&country=us&entity=software&limit=5
```

核对 `trackId`、`trackName`、`artistName` 和 `bundleId`，不要直接采用第一个同名结果。搜索得到的 `trackId` 就是后续使用的数字 ID；Android 包名需要另外建立映射。

**搜索、元数据请求和缓存应保留同一个有效商店地区。** 某个 App 在 `cn` 不可用，不代表它在 `us` 也不可用。切换地区应明确记录，避免将不同商店的结果混为一份。

搜索参数说明见 [Apple iTunes Search API 文档](https://performance-partners.apple.com/search-api)。

## 3. 请求包含深色资源入口的元数据

以 ChatGPT 的美区 iOS 版本为例：

```bash
curl --fail --show-error --silent --get \
  'https://apps.apple.com/api/apps/v1/catalog/us/apps' \
  --data-urlencode 'ids=6448311069' \
  --data-urlencode 'platform=web' \
  --data-urlencode 'additionalPlatforms=iphone' \
  --data-urlencode 'extend=iconArtwork' \
  --data-urlencode 'fields[apps]=name,bundleId,artwork,iconArtwork' \
  --data-urlencode 'l=en-US'
```

在 `data` 数组中按 `id` 找到对应 App，再读取：

```text
attributes.platformAttributes.ios.iconArtwork.url
```

响应结构示意，URL 中的 `...` 仅用于说明：

```json
{
  "data": [{
    "id": "6448311069",
    "attributes": {
      "platformAttributes": {
        "ios": {
          "iconArtwork": {
            "url": "https://is1-ssl.mzstatic.com/image/thumb/.../Placeholder.mill/{w}x{h}{c}.{f}"
          }
        }
      }
    }
  }]
}
```

优先使用 `iconArtwork`，缺失时才考虑 `artwork` 或 iTunes Lookup 的传统图片。**回退成功只代表取得了可用图标，不代表已取得独立深色图标。** 也不要把 URL 包含 `Placeholder.mill` 当作支持深色的充分条件。

批量请求可以用 `ids=ID1,ID2,ID3`。KeiHome 按地区和平台分组，每批最多 25 个去重 ID；这是客户端采用的保守批次大小，并非 Apple 公布的接口上限。

## 4. 构造浅色和深色图片 URL

对元数据返回的模板替换以下占位符：

| 占位符或参数 | 示例值 | 含义 |
| --- | --- | --- |
| `{w}`、`{h}` | `512`、`512` | 请求尺寸；实际返回尺寸仍需检查 |
| `{c}` | `Icon.ApSiOS01` | 当前验证过的 iOS 图标呈现方式 |
| `{f}` | `png` | 便于保留透明度、解码和导出 |
| `appearance` | `light` 或 `dark` | 通过 URL 查询参数指定外观 |

最终形式类似：

```text
https://is1-ssl.mzstatic.com/image/thumb/.../Placeholder.mill/512x512Icon.ApSiOS01.png?appearance=light
https://is1-ssl.mzstatic.com/image/thumb/.../Placeholder.mill/512x512Icon.ApSiOS01.png?appearance=dark
```

请保留模板中其他查询参数，通过 URL API 设置或替换 `appearance`，避免重复追加参数。资源路径应来自最新元数据，不要长期硬编码上面的 CDN 路径。

其他呈现选项：

- 请求原始裁切形式时，KeiHome 使用 `{c}=bb`；不保证所有源图都无圆角、无留白。
- macOS 使用 `additionalPlatforms=mac`，读取 `platformAttributes.osx.iconArtwork.url`，对应呈现裁切为 `Icon.ApSmac01`。这些是当前实现采用的请求方式，本文本次在线复核只覆盖 iOS。
- iPadOS 当前同样读取 `ios` 平台的图片元数据，同时另行核对 App 是否支持 iPad；这不保证能取得一份独立于 iPhone 的 iPad 专用图标。
- 请求 1024px 不保证原始设计本身具有 1024px 的有效细节。

## 5. 最小 JavaScript 示例

适用于 Node.js 18+ 的 ESM 环境，无第三方依赖。示例下载 ChatGPT 的两种外观并保存来源信息，便于先跑通链路。

浏览器直连可能受 CORS 限制；在 HQ Icon 中可将请求放到现有的应用网络层。生产接入时沿用自身的下载大小限制、图片解码验证和缓存机制。

```javascript
import { mkdir, writeFile } from "node:fs/promises";

const appId = "6448311069";
const country = "us";
const size = 512;
const output = "./apple-icons";

async function download(url) {
  const response = await fetch(url, {
    signal: AbortSignal.timeout(25000),
    redirect: "error",
    headers: {
      "User-Agent": "HQIcon/AppleArtwork",
      Accept: "application/json,image/png",
    },
  });
  if (!response.ok) {
    throw new Error(
      `HTTP ${response.status}; Retry-After=${response.headers.get("retry-after")}`
    );
  }
  return Buffer.from(await response.arrayBuffer());
}

const endpoint = new URL(
  `https://apps.apple.com/api/apps/v1/catalog/${country}/apps`
);
endpoint.search = new URLSearchParams({
  ids: appId,
  platform: "web",
  additionalPlatforms: "iphone",
  extend: "iconArtwork",
  "fields[apps]": "name,bundleId,artwork,iconArtwork",
  l: "en-US",
}).toString();

const metadata = JSON.parse((await download(endpoint)).toString("utf8"));
const app = metadata.data?.find((item) => String(item.id) === appId);
const template = app?.attributes?.platformAttributes?.ios?.iconArtwork?.url;
if (!template || !["{w}", "{h}", "{c}", "{f}"].every((s) => template.includes(s))) {
  throw new Error("没有可用的 iconArtwork 模板；不能据此宣称支持深色版本");
}

const rendered = template
  .replaceAll("{w}", String(size))
  .replaceAll("{h}", String(size))
  .replaceAll("{c}", "Icon.ApSiOS01")
  .replaceAll("{f}", "png");
const sources = {};
await mkdir(output, { recursive: true });
for (const appearance of ["light", "dark"]) {
  const url = new URL(rendered);
  if (url.protocol !== "https:" || !/^is[0-9]+-ssl\.mzstatic\.com$/.test(url.hostname)) {
    throw new Error("非预期的 Apple 图片地址");
  }
  url.searchParams.set("appearance", appearance);
  const bytes = await download(url);
  if (!bytes.subarray(0, 8).equals(Buffer.from("89504e470d0a1a0a", "hex"))) {
    throw new Error("响应不是 PNG 图片");
  }
  await writeFile(`${output}/${appearance}.png`, bytes);
  sources[appearance] = url.href;
}
await writeFile(
  `${output}/source.json`,
  JSON.stringify({ appId, country, size, sources }, null, 2)
);
console.log("下载完成；请继续解码比较 light.png 与 dark.png 的实际像素。");
```

## 6. 怎样判断“真的取得了深色版本”

HTTP 200、不同 URL、不同文件大小都不足以证明取得了独立深色图标。建议先实际解码，核对尺寸和透明度，再比较统一为 RGBA 后的像素。

例如，使用 Python 和 Pillow 对刚才下载的两张图片做基本核验：

```python
from PIL import Image

with Image.open("apple-icons/light.png") as a, Image.open("apple-icons/dark.png") as b:
    light, dark = a.convert("RGBA"), b.convert("RGBA")
    same = light.size == dark.size and light.tobytes() == dark.tobytes()
    print("尺寸：", light.size, dark.size)
    print("像素相同：", same)
```

结果应按下面的含义处理：

| 结果 | 建议显示或处理方式 |
| --- | --- |
| 两张图片像素不同 | 存在不同的服务器外观渲染，再做一次视觉核对 |
| 像素完全相同 | 暂记“深浅共用 / 未确认独立深色”，不要自动认定为下载失败 |
| 仅有传统 `artwork` | 标记资源受限，保留浅色回退 |
| 深色下载失败 | 保留已有可用资源，允许用户稍后补全 |

像素相同可能是 App 本来共用图标，也可能是参数未被支持，单靠相同结果无法区分。更严格的研究可以增加一个刻意不支持的外观值作为对照，但不建议每次正常生成都额外请求。

不要通过反色、压暗浅色图片来冒充 Apple 原生深色图标。如果提供本地生成效果，应明确标注来源。

## 7. 缓存和限流建议

- 元数据按 **App Store ID + 地区 + 平台** 缓存；图片缓存再区分尺寸、裁切、外观及源 URL。
- 预览和生成优先复用已有图片，普通进入页面不重新查询全部 App；更新时只请求待更新或缺失的资源。
- 元数据请求串行或使用较低并发。KeiHome 同一实例默认对每个元数据主机设置至少 3.5 秒的请求间隔，批量合并重复 ID；这不是避免限流的保证。
- 遇到 429 / 503，应暂停对应主机请求，解析 `Retry-After` 的秒数或 HTTP 日期；没有该字段时使用有上限的指数退避。不要失败后立即循环重试。
- 持久化尚未结束的冷却状态，避免重启应用后再次碰撞。元数据主机与图片 CDN 的冷却分别管理。
- 更新失败时保留旧的有效图片；传统图片回退不要覆盖已经验证过的完整扩展元数据。

Apple 的 Search API 文档提到约每分钟 20 次调用，且可能变化；这个数字不能套用为扩展 catalog 接口或图片 CDN 的配额承诺。

## 8. Apple 自带系统图标需要单独处理

设置、相机等系统 App 不一定存在可用的 App Store 商品条目，不能统一依赖上面的在线接口。

KeiHome 对部分系统图标采用另一条开发期链路：从本地 Apple 系统或 Simulator 的 `Assets.car` 取得分层图标，借助 macOS 的原生框架渲染浅色、深色，再打包为静态资源。Android 端读取渲染结果，不调用这些 Apple 私有 API。

如果需要研究这条链路，要保留源系统版本、目录、图层名称、外观和渲染目标平台等来源记录。KeiHome 在已验证的原生调用中使用 `0=默认、1=深色`；这不是 UIKit 的 `UIUserInterfaceStyle` 数值，也不能直接推广为所有系统版本的通用约定。macOS 源图按 iOS 呈现渲染，也不等于取得了原生 iOS 源图。

另外，本文在线链路确认的是 **light / dark 静态图片**。目前没有确认通用的在线 Clear / Tinted 获取方式；把 `appearance` 改成 `clear` 或 `tinted` 后收到 200，并不足以证明支持。PNG 也不能完整复现跟随壁纸、光照实时变化的 Liquid Glass。

分层图标的官方设计背景可参考 [Apple Icon Composer 文档](https://developer.apple.com/documentation/xcode/creating-your-app-icon-using-icon-composer)；本文的扩展接口和原生提取方法属于实际研究结果，不能视为 Apple 官方提供的下载规范。

## 9. 本次复核范围

2026-11-15 重新请求了美区 ChatGPT（`6448311069`）的扩展元数据，以及 `Icon.ApSiOS01`、512px、PNG 的 light / dark 两张图片：

- 两张图片均成功下载，实际尺寸均为 512 × 512。
- 解码为 RGBA 后像素不同，确认本次取得了不同的深浅外观。
- 请求未使用登录凭据或鉴权令牌。
- 随后单独运行 JavaScript 示例时，元数据接口返回了 429，且没有 `Retry-After`；示例按设计终止，未继续请求图片或自动重试。下载成功流程另用本次已取得的真实响应做了本地回放验证。因此不能保证相同条目随时都能在线取得。
- 本次未重新在线穷举其他 App、地区、macOS、Clear / Tinted；具体覆盖应以每个 App 当时的返回结果为准。
