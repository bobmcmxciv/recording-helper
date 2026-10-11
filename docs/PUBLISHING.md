# 上架流程报告：Google Play、国内应用商店、iOS 与鸿蒙

调研日期 2026-10-11。本文只写与录音助手直接相关的要求；每条结论后用 [n] 标注来源，文末列出链接。标注"未核实"的条目表示没有找到官方原文，上架前需要再确认。

## 一、结论先行

| 平台 | 个人开发者 | 必备前置 | 对录音助手的关键风险 |
| --- | --- | --- | --- |
| Google Play | 可以。25 美元一次性，政府证件 + 本人信用卡 + 实机验证 [1] | 2023-11-13 后新建的个人账号须先做封闭测试：12 名测试者连续 14 天 [2]；target API 36 [3]；AAB 包 [26]；Data safety 表 [6]；隐私政策 URL [8] | "All files access" 权限用于读媒体文件属无效用途 [5]；麦克风前台服务要提交演示视频 [30]；大陆用户基本无法使用 Play [9] |
| 华为 AppGallery | 可以，实名后即可 [10] | 软著建议提供；个人需承诺函 [35]；接入服务器的应用需备案 | 中等 |
| 小米 | **不接受个人开发者** [11] | 软著或 APP 电子版权 + 备案号，主体一致 [12] | 个人路径走不通，需企业主体 |
| OPPO | 个人认证范围不含普通应用（未核实）[13] | 软著 + 备案 | 个人能否上架未核实 |
| vivo | 资质须本人持有 [14] | 软著（名称一致）+ 备案 | 备案是硬前提 |
| 腾讯应用宝 | 可以，人脸认证 [15] | 2023-09 后新增应用须先备案 [15] | 备案是硬前提 |
| 荣耀 | 未核实 | 备案号或增值电信许可证 + 软著类证书 [16] | 备案是硬前提 |
| Apple App Store | 可以，99 美元/年，个人免 D-U-N-S [18] | 中国大陆区须填 ICP 备案号 [19]；隐私清单 [20]；录音须明确同意并有可见提示 [21] | 个人主体能否取得备案号未核实 |
| 鸿蒙 NEXT | 可以，个人实名 [22] | 用 ArkTS / ArkUI 重写，APK 不能运行 [23] | 工程量最大 |

**录音助手在提交任何商店前要先完成的五件事：**

1. 去掉 `MANAGE_EXTERNAL_STORAGE`，改用媒体权限（已在 2.1 加入 `READ_MEDIA_AUDIO` + MediaStore 发现）和 SAF 目录授权。向 Play 提交时若仍保留该权限，需要填写声明表并说明为什么 MediaStore 和 SAF 不够，通过率未核实 [5]。
2. 把 `targetSdk` 升到 36（当前 35）。Play 自 2026-08-31 起要求新应用与更新 target API 36，可申请延期到 2026-11-01 [3]。这需要把 Android Gradle Plugin 升到支持 compileSdk 36 的版本并重新回归。
3. 预编译的三个 arm64 原生库已核对为 16 KB 页对齐（LOAD 段 align 16384），满足 2027-02-01 起的硬性要求 [4]。
4. 国内商店统一要求软著和 APP 备案号；小米不收个人账号。若走国内商店，建议先以企业主体办软著与备案。
5. 声纹属于敏感个人信息 [46]。声纹录入与跨录音识别要单独征得同意、说明必要性，并在隐私政策中写明只存本机、不上传。

## 二、Google Play

### 账号与验证
- 注册费 25 美元一次性；个人需政府证件、本人名下信用卡，年满 18 岁，新个人账号要验证一台真实 Android 设备 [1]。身份验证通常 3 个工作日 [1][24]。
- 组织账号需 D-U-N-S 编号、组织证明文件、网站与电话验证 [25]。
- 2023-11-13 之后创建的个人账号，必须先完成封闭测试：至少 12 名测试者持续加入 14 天，之后才能申请正式发布；中断不累计 [2]。

### 技术门槛
- target API 36；Wear / Auto 为 35 [3]。
- 新应用必须以 Android App Bundle 提交 [26]。
- 16 KB 页大小：target API 35 及以上应用须支持；2027-02-01 起不支持的更新无法发布 [4]。检查方法：`zipalign -c -P 16 -v 4 app.apk`，或 `llvm-objdump -p lib.so | grep LOAD` 查看每个 LOAD 段 align 是否 ≥ 2**14。
- Play App Signing 由 Google 托管签名密钥，开发者持上传密钥 [27]。

### 政策要求
- Data safety 表：所有应用必须填写，即使不收集数据 [6]。录音助手需要如实声明：录音与转写文字留在本机；开启在线 AI 后转写文字会发送到用户自选的第三方；开启同步后文字发送到用户自建服务器；声纹只存本机。
- 隐私政策：Play Console 与应用内都要能打开，URL 公开、不设地域限制 [8]。
- 内容分级：完成 IARC 问卷 [28]。
- All files access：仅允许文件管理、备份、杀毒等核心功能，且须提交声明表；"访问媒体文件"明列为无效用途 [5]。
- 麦克风前台服务：须声明 `foregroundServiceType="microphone"`，后台不能新建该类服务 [7]；每种前台服务类型要提供演示视频 [30]。录音前要有应用内突显披露 [8]。
- 通话录音：Play 禁止用无障碍 API 录制远端通话 [31][32]。录音助手不录通话，只读取系统录音机生成的文件，描述里应写明。

### 提交步骤
1. 创建开发者账号并完成身份验证。
2. Play Console 创建应用，填写商店信息（图标 512×512 PNG、特色图 1024×500、截图 2–8 张 [55]）、隐私政策 URL、Data safety、内容分级、目标受众。
3. 填写权限声明（前台服务类型、照片与媒体权限用途）。
4. 上传 AAB 到封闭测试轨道，招募 12 名测试者运行 14 天。
5. 申请正式发布权限，通过后提升到正式轨道。审核时间官方无承诺，第三方经验 1–3 天（未核实）。

## 三、国内应用商店

### 共同前提：APP 备案
工信部 2023 年 7 月通知：境内提供互联网信息服务的 App 主办者须备案，新 App 先备案再上架；应用商店不得分发未备案的 App [34]。办理途径：通过接入服务商（云厂商）或分发平台提交到"国家互联网基础资源管理系统"，流程为平台初审、短信核验、通信管理局审核、发放备案号 [40]。个人主体能否直接备案：未核实；实际操作中各云厂商的 App 备案入口对个人主体的材料要求不一。

注意：录音助手默认不联网。若发布时不含同步功能，部分商店允许勾选"单机 App"免备案（华为有此选项，来自摘要，未核实于原文）；一旦提供同步服务器或在线 AI，就属于互联网信息服务。

### 软件著作权（软著）
中国版权保护中心为唯一登记机构，线上办理；受理后 60 日内完成，补正 30 日内 [37][38]。费用：官网收费页未能打开，是否已停征未核实，以官网公告为准。软著名称、应用名称、备案名称、包名要一致 [12][14]。

### 各商店

| 商店 | 账号 | 软著 | 备案 | 其他 |
| --- | --- | --- | --- | --- |
| 华为 | 个人实名（人脸 / 银行卡 / 身份证人工审核）[10] | 建议提供，个人需承诺函 [35] | 有服务器则需备案 | 实名 1–2 个工作日 |
| 小米 | 不接受个人 [11] | 必须 [12] | 必须，主体与包名一致 [12] | 首次运行须有可读的隐私政策弹窗 [12] |
| OPPO | 个人认证需身份证、证件照、银行卡 [13] | 三选一 [13] | 必须 | 生成式 AI 类应用须展示模型备案号 [13]（对本应用是否适用见第五节） |
| vivo | 资质须本人持有 [14] | 名称须一致 [14] | 必须 [36] | |
| 应用宝 | 个人满 18 岁，人脸认证 [15] | 未核实 | 2023-09 后新增必须 [15] | 审核含安全评估（摘要） |
| 荣耀 | 未核实 | 软著类证书 [16] | 备案号或增值电信许可证 [16] | 2023-12 起未备案不接受更新 |
| 三星 | 未核实 | 未核实 | 未核实 | 中国区需中文名称与描述 [17] |

### 个人信息合规
- 《App 违法违规收集使用个人信息行为认定方法》列举了无隐私政策、首次运行不提示、超范围收集等违规情形 [41]。录音助手应做到：首次运行弹出隐私政策；权限按需申请并说明用途；不读取通讯录；在线 AI 与同步单独征得同意。
- 国内商店未见针对录音类应用的专门禁令（检索有限，未核实）。

## 四、iOS

- 账号：99 美元/年；个人需开启双重认证并成年；组织需 D-U-N-S [18]。
- 审核指南要点 [21]：2.5.14 录音须征得明确同意并有不可关闭的视觉或听觉提示；5.1.2(i) 与第三方（含第三方 AI）共享个人数据前须清楚披露并取得明确许可；5.1.1(iii) 数据最小化；2.5.4 后台音频仅限既定用途，后台持续录音能否过审未核实。
- iOS 无法读取其他应用的通话录音，也没有系统通话录音文件可扫描。iOS 版的核心场景只能是：应用内录音、从文件 / 语音备忘录分享导入、本机转写与整理。
- 隐私清单：2024-05-01 起第三方 SDK 须提供 PrivacyInfo.xcprivacy 与所需理由，范围将扩展到整个二进制 [20]。出口合规在 Info.plist 用 `ITSAppUsesNonExemptEncryption` 声明（未核实）。
- 中国大陆区：2023 年 9 月起须填写 ICP 备案号 [19]；个人主体能否取得未核实。
- 审核：50% 提交 24 小时内完成，90% 48 小时内 [48]。TestFlight 内测 100 人、外测 1 万人，构建 90 天失效 [49]。截图至少一套，如 1179×2556 或 1206×2622，最多 10 张 [50]。
- 技术前置：Apple Silicon Mac + Xcode；语音模型需转成 Core ML 或在 iOS 上跑 ONNX / llama.cpp（llama.cpp 支持 iOS）；SenseVoice 的 Core ML 转换未验证。

## 五、生成式 AI 与深度合成

- 《生成式人工智能服务管理暂行办法》适用于"向境内公众提供生成文本等内容的服务"，具有舆论属性或社会动员能力的服务需安全评估与算法备案 [42]。《互联网信息服务深度合成管理规定》定义包含文本生成 [43]。《人工智能生成合成内容标识办法》2025-09-01 施行 [44]。
- 对录音助手的适用性：本地模型只为用户本人在本机生成内容、不对外提供服务，字面上不易落入"向公众提供服务"；在线 AI 只是调用用户自己选择的第三方 API。但官方条文没有直接回答"客户端调用第三方 API"的情形，阿里云的指引认为调用第三方模型的开发者仍算服务提供者 [45]。结论：未核实，建议上架前咨询律师。
- 保守做法（2.1 已做或可做）：界面标注 AI 生成内容需核对；发送到外部 API 前单独同意；不向第三方发送原始录音；若将来提供自己的在线整理服务，再做算法备案评估。
- 声纹：网信办 2026 年问答引用 GB/T 45574-2025，声纹属敏感个人信息 [46]。需单独同意、说明必要性，隐私政策写明只存本机。

## 六、鸿蒙 NEXT

- 账号：华为开发者联盟，个人可用人脸 / 银行卡 / 身份证实名；企业需营业执照 [22]。
- 中国销售的 HarmonyOS NEXT 设备只运行 HAP 原生应用，不能运行 APK [23]。需要用 DevEco Studio + ArkTS / ArkUI 重写 [53]。
- 麦克风权限 `ohos.permission.MICROPHONE` [54]；后台录音依赖长时任务，官方配置细节未核实。
- 上架资质与华为 AppGallery 一致：软著建议、个人承诺函、备案或单机 App [35]。
- 技术前置：语音模型与大模型要在 ArkTS 侧重新集成（鸿蒙有 NNRt / MindSpore Lite 推理框架），工作量约等于重做一个客户端。

## 七、提交前准备清单

| 项目 | 要求 |
| --- | --- |
| 隐私政策页面 | 公开 URL，应用内可达；写明录音、转写、声纹、在线 AI、同步各自的数据去向 |
| 用户协议 | 覆盖录音合法性提示（录他人需取得同意）、AI 内容需核对 |
| 软著证书 | 名称与应用名、包名一致 |
| APP 备案号 | 经云厂商办理；应用内"关于"页展示并可跳转 |
| 权限用途文案 | 麦克风、读取音频、通知、前台服务；在线 AI 单独同意页（已有） |
| 前台服务演示视频 | 麦克风录音、数据同步各一段 |
| 审核说明 | 提供一段示例录音与操作路径，说明不录通话、不用无障碍 |
| 商店素材 | Google：图标 512×512、特色图 1024×500、截图 2–8 张 [55]；Apple：截图 1179×2556 等 [50]；国内各商店规格未核实 |
| 构建 | target API 36、AAB、16 KB 对齐、Play 上传密钥 |
| 联系方式 | 开发者邮箱与支持页面 |

## 参考链接

[1] https://support.google.com/googleplay/android-developer/answer/6112435
[2] https://support.google.com/googleplay/android-developer/answer/14151465
[3] https://developer.android.com/google/play/requirements/target-sdk
[4] https://developer.android.com/guide/practices/page-sizes
[5] https://support.google.com/googleplay/android-developer/answer/10467955
[6] https://support.google.com/googleplay/android-developer/answer/10787469
[7] https://developer.android.com/develop/background-work/services/fgs/service-types
[8] https://support.google.com/googleplay/android-developer/answer/10144311
[9] https://appinchina.co/blog/google-play-store-in-china-everything-you-need-to-know/
[10] https://developer.huawei.com/consumer/cn/doc/start/itrna-0000001076878172
[11] https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1731
[12] https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1322
[13] https://open.oppomobile.com/new/developmentDoc/info?id=10914
[14] https://dev.vivo.com.cn/documentCenter/doc/12
[15] https://app.open.qq.com/p/developer/reg
[16] https://developer.honor.com/cn/doc/guides/101152
[17] https://developer.samsung.com/dev-support
[18] https://developer.apple.com/programs/enroll/
[19] https://www.medianama.com/2023/10/223-apple-china-app-store-government-license/
[20] https://developer.apple.com/news/?id=pvszzano
[21] https://developer.apple.com/app-store/review/guidelines/
[22] https://developer.huawei.com/consumer/cn/doc/start/itrna-0000001076878172
[23] https://technode.com/2024/01/19/huawei-launches-harmonyos-next-with-ambitious-plans-to-break-away-from-android/
[24] https://support.google.com/googleplay/android-developer/answer/9198012
[25] https://support.google.com/googleplay/android-developer/answer/10841920
[26] https://developer.android.com/guide/app-bundle
[27] https://support.google.com/googleplay/android-developer/answer/9842756
[28] https://support.google.com/googleplay/android-developer/answer/9859655
[30] https://support.google.com/googleplay/android-developer/answer/13392821
[31] https://support.google.com/googleplay/android-developer/answer/10964491
[32] https://www.techradar.com/news/new-google-play-store-policy-cracks-down-on-call-recording-apps
[34] https://www.gov.cn/zhengce/zhengceku/202308/content_6897341.htm
[35] https://developer.huawei.com/consumer/cn/doc/app/80301
[36] https://www.ithome.com/0/759/797.htm
[37] https://www.ccopyright.com.cn/index.php?optionid=1079
[38] https://www.ccopyright.com.cn/index.php?optionid=1033
[40] https://developer.aliyun.com/article/1466572
[41] https://chinajusticeobserver.com/law/x/measures-for-determining-the-illegal-collection-and-use-of-personal-information-by-apps-20191128
[42] https://www.cac.gov.cn/2023-07/13/c_1690898327029107.htm
[43] https://www.cac.gov.cn/2022-12/11/c_1672221949354811.htm
[44] https://www.cac.gov.cn/2025-03/14/c_1743654684782215.htm
[45] https://help.aliyun.com/document_detail/2667824.html
[46] https://www.cac.gov.cn/2026-01/09/c_1769688003183197.htm
[48] https://developer.apple.com/distribute/app-review/
[49] https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview/
[50] https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications
[53] https://developer.huawei.com/consumer/en/harmonyos/develop/
[54] https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/audio-call-development-0000001774120330
[55] https://support.google.com/googleplay/android-developer/answer/9866151
