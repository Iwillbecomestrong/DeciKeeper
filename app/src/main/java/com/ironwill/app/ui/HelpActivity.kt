/**
 * =================================================================================
 * [Context Header]
 * * @file: HelpActivity.kt
 * * @role: UI
 * * @description: 用户手册页面，支持搜索和目录跳转。
 * * @responsibilities:
 * 1. 构建并渲染帮助文本内容。
 * 2. 提供目录导航（可点击跳转）。
 * 3. 提供实时搜索过滤。
 * * @context:
 * - 上游: HomeFragment / 设置入口
 * - 下游: 无
 * * @constraints (必读):
 * 1. 文案必须与实际功能保持一致。
 * 2. 目录标题从文案中自动解析。
 * =================================================================================
 */
package com.ironwill.app.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout

class HelpActivity : AppCompatActivity() {

    private data class HelpSection(
        val title: String,
        val content: String,
        val level: Int           // 1=一级标题（一、二、三）, 2=二级标题（2.1, 2.2）
    )

    private lateinit var contentContainer: LinearLayout
    private lateinit var tocContainer: LinearLayout
    private lateinit var tocToggle: TextView
    private var allSections: List<HelpSection> = emptyList()
    private var searchQuery: String = ""

    companion object {
        fun buildHelpText(): String = """
一、需求

你是不是也有这些困扰？

· 打开抖音/B站/游戏就停不下来，明明想学习却管不住手
· 早上闹钟响了随手关掉继续睡，永远起不来
· 说好只玩 10 分钟，结果刷了 2 小时
· QQ/微信一聊就是一下午，正事全耽误了
· 靠意志力根本控制不了自己——你需要一个替你执行规则、不给你讨价还价机会的工具

这就是 IronWill。

―

二、快速入门

IronWill 的核心逻辑只有一句话：你事先定好规则 → 到了时间 IronWill 自动锁机 → 必须消耗额度才能解锁。

它不跟你商量，不给"就这一次"的机会。

2.1 理解两类会打扰你的 App

IronWill 把 App 分成两类来管：

· 社交软件（QQ、微信）—— 只在勿扰时段内受限。白天正常用，但每次打开消耗休息额度。
· 几乎纯娱乐软件（B站、小红书、浏览器、游戏等）—— 双重限制。勿扰时段内锁机，时间窗外直接打不开。

为什么这样分？社交软件是必须用的，不限制打开。但你在它们上面花的时间越多，消耗的额度就越多，晚上留给娱乐应用的时间就越少。这是一个自我调节的激励系统。

2.2 设勿扰时段

把一天划分成几个时段（比如上午、下午、晚上），每个时段给出一定的休息额度。在时段内打开黑名单 App 会消耗额度，额度用完自动锁机。没用完的额度 100% 滚入下一时段。

IronWill 会按"开始时间"来排这些时段：最早开始的那个时段算作一天的起点，深夜那种跨到第二天的时段会排在最后。这样白天、晚上、深夜的顺序会更直观，也方便系统把一天算清楚。
统计页图例顺序也按开始时间排列，和勿扰时段列表保持一致。

操作：打开 IronWill → 「专注规则」 → 编辑勿扰时段。

2.3 设黑名单

所有需要限制的 App 放进黑名单。首次进入黑名单设置时，系统会请求"查看所有已安装应用"的权限——这是为了读取你手机上的 App 列表，让你能按名称搜索和勾选。权限仅用于展示 App 名称，不读取任何应用内容，数据完全在本地处理。

黑名单 App 共享同一个额度池。打开就被锁，回桌面再点进去也会重新锁。

如果开启了无障碍服务，黑名单 App 即使以小窗（悬浮窗）模式运行也能被精准拦截。检测到小窗时，无障碍服务会自动执行返回操作来关闭它。

操作：打开 IronWill → 「设置」 → 「屏蔽应用」。

快速导入：黑名单页面提供"快速导入"按钮，可按类别一键导入（短视频、大型手游、浏览器、社交软件等），省去逐个查找的麻烦。

2.4 设应用时间窗

仅针对娱乐 App。定义它们可以用的时间段。时间窗外直接打不开——即使在勿扰时段之外也不行。QQ/微信不加入时间窗。

操作：打开 IronWill → 「专注规则」 → 「应用时间窗」。

2.5 闹钟挑战

闹钟系统独立于主锁屏，解决的是"起不来床"的问题。

触发方式：闹钟到点触发全屏悬浮窗挑战，必须完成才能关闭。
如果闹钟先在息屏状态响起，后来你解锁回到桌面，悬浮窗会自动尝试重新拉起，避免挑战界面被系统临时吃掉后找不到入口。

挑战类型：
· 数学题 —— 算出乘法结果
· 诗词对接 —— 补全上下句
· 单词拼写 —— 根据释义拼写单词
· 扫码 —— 扫描任意二维码即可关闭闹钟

两阶段解锁时，第二关可以重复第一关的题型。只要第二关勾选了某题型（含扫码），就会显示为可选项；第二关从已勾选题型中任选其一完成即可。

挑战过程中可选择收藏题目。收藏夹支持按跳过次数（跳过最多的排最前）或收藏时间排序，并可按近7天/30天/90天过滤，方便集中复习。扫码挑战需要相机权限，仅在使用时才申请。

闹钟同步备份：每个闹钟卡片上有"同步"按钮，可将闹钟备份到系统闹钟。点击后 IronWill 退到后台，留在系统闹钟中确认保存即可。

2.6 开启防卸载保护

阻止任何人（包括你自己）一时冲动把 IronWill 卸载了。关闭防卸载保护需延迟到次日才生效。

操作：IronWill「设置」中开启。

防卸载保护依靠无障碍服务实现精确拦截。开启无障碍后，系统应用信息页、权限页、设置页等任何含卸载/停用意图的界面，都会被精准识别并拦截回桌面。

切换系统语言时，"系统正在初始化"覆盖层也会弹出。这是为了防止有人通过切换系统语言绕过无障碍服务，再进入设置卸载。切换语言后系统会重启启动器，覆盖层在这一过渡期内提供保护，待无障碍服务就绪后自动消失。

―

三、全局参数配置

IronWill 提供了一整套可自定义的参数，让你根据自己的实际情况调整防护强度。所有参数都在「设置」→「全局参数」中配置。

3.1 次周生效（每周特权）

利好立即生效，不利修改（增加次数/时长）下周生效。

· 每周特权次数 —— 本周可使用的特权总次数（上限 3 次）
· 每周特权总时长 —— 本周可使用的特权总分钟数（上限 600 分钟 = 10 小时）

操作：全局参数页面 → 上方绿色卡片区域。

3.2 次日生效（核心规则）

利好立即生效，不利修改次日生效。

· 特权可用时间范围 —— 限制特权只能在指定时段内使用，防止睡眠/凌晨时段滥用。
· 借用比例 —— 允许从下一时段透支额度的百分比。额度不够时可借用，但最多借 50%，借 1 扣 2。
· 临时使用受限应用时长上限 —— 超级临时放行时，单次可输入的最大分钟数（0.1~3.0 分钟，默认 0.5 分钟，可带 1 位小数）。冷却不是固定翻倍，而是按放行时长分段递增：1 分钟内按 1 倍，2 分钟内按 2 倍，3 分钟内按 3 倍。比如放行 3 分钟，冷却就是 6 分钟。适合应急扫码、回一条消息。
· 空档冷却时长 —— 从空档期归来或空档期违规后的强制冷静时间（默认 300 秒）。
· 休息冷却时长 —— 从休息/工作锁定状态归来后的强制冷静时间（默认 60 秒）。

操作：全局参数页面 → 中间白色卡片区域。

3.3 立即生效（预警设置）

· 每日生效时间 —— 新一天的开始时间。可以手动指定，也可以开启"跟随最早结束时段"自动同步。
· 当你开启自动同步时，系统会扫描所有启用时段，取结束时间在 06:00–10:00 内最早的时段作为生效时间；若窗口内无匹配时段，默认 08:00。
· 监管远期预警 —— 监管时段开始前多久弹出第一次提醒（默认 20 分钟）。
· 监管近期预警 —— 监管时段开始前多久发出震动+弹窗最后通牒（默认 5 分钟）。

操作：全局参数页面 → 下方预警卡片区域。

3.4 设置时间与业务日

设置不再在深夜锁定，任意时间都可以调整。
统计里的"一天"按"今天第一个勿扰时段开始 → 明天第一个勿扰时段开始"来切；如果首段开始时间调整，统计日边界会随之同步。
导入设置：适应期内立即刷新待生效规则与统计日；适应期后导入的设置将延期至下一刷新时间生效，保护现有约束不被意外放松。
深夜跨天段会归到前一天的尾部，不会提前算到今天。
开启自动同步后，生效时间跟随 06:00–10:00 内最早结束的时段，统计日与生效日保持同步。

―

四、解锁方式

被锁了，有急事怎么办？三个渠道：

4.1 申请休息

分钟级解锁，适合"刷一会儿"的场景。消耗当前时段额度。1 分钟扣 1 分钟，冷却期内加权扣费（冷却重叠部分×2）。额度不够时可借用下一时段的额度（最多借 50%，借 1 扣 2）。

操作：锁屏界面点击"申请休息" → 输入时长 → 确认。

4.2 超级临时

分钟小数级应急放行（0.1~3.0 分钟，上限可在全局参数中自定义），适合扫码、回一条消息、看一眼信息等瞬间操作。同样消耗休息额度，但胜在快——用多少分钟扣对应的分钟数。

和休息的关键区别：即使 App 在应用时间窗外（平时打不开的时段），超级临时也能短暂放行。比如晚上 11 点过了娱乐时间窗，突然需要扫个二维码，就可以用超级临时解决。冷却会按放行分钟数分段变强，不是统一固定 2 倍。

用后进入强制冷却期，冷却期内仅可申请 10 秒二级应急。

操作：锁屏界面点击"超级临时使用受限应用" → 输入分钟数 → 确认。

4.3 每周特权

一键进入无限制模式，临时绕过所有规则，完全自由。每周可用次数、总时长、可用时间段均可在全局参数中自定义。仅在配置的时段内可用，开启后到时间自动结束（不会跨午夜）。

操作：主页底部特权卡片 → 点击启用。

额度实在用完了？等下一时段自动重置，或提前预支下一时段额度（借 1 扣 2）。

―

五、关键规则

5.1 生效策略

你可以在「设置」中选择两种生效策略（仅影响勿扰时段和应用时间窗）：
· 「宽严有别」（推荐）—— 放宽限制马上生效，加严限制次日生效
· 「全部次日」—— 所有修改都等次日，适合需要彻底防冲动的用户

注意：黑名单和全局参数固定为「宽严有别」，不受生效策略影响。

5.2 「宽严有别」策略

「宽严有别」是本系统最具特色的生效策略——任何让你"更轻松"的修改（减少锁机时间、移除黑名单 App 等），都延迟到次日生效时间才生效（默认 08:00，可在全局参数中调整），防止一时冲动给自己放松。让你"更严格"的修改则立即生效。

每周特权的不利修改（增加次数/时长）则延迟到次周生效。

5.3 额度流转

当前时段没用完的额度，100% 自动滚入下一时段。白天少聊 QQ/微信 → 额度剩得多 → 晚上娱乐 App 多玩会儿。

5.4 冷却期

以下两种情况都会进入冷却期：

· 休息结束后 → 休息冷却期（时长可在全局参数中自定义）
· 临时解锁结束后 → 临时解锁冷却期（时长随放行分钟数递增）

冷却期内的扣费规则（加权扣费）：

· 冷却期内申请临时解锁，冷却重叠部分按×2加权扣费
· 休息中申请临时解锁，如果解锁时段跨越休息结束进入冷却期，冷却重叠部分按×2加权，其余正常
· 冷却期内再次申请休息，冷却重叠部分同样×2加权

简单来说：只要和冷却期有重叠，重叠部分就按×2加权扣费，不重叠的部分正常扣费。

―

六、日志与反馈

遇到问题需要反馈？打开 IronWill → 「设置」 → 「快捷发送日志」，可将运行日志导出发送给维护者（收件地址在代码 LogExporter.DEFAULT_RECIPIENT 中配置）。

描述你做了什么、期望发生什么、实际发生了什么，附上导出的日志文件即可。

―

七、权限说明

IronWill 的权限按场景分批申请，不会一股脑全要。所有权限仅在本地使用，不上传任何数据。

7.1 首次打开：主页引导

主页会引导你开启以下权限，这是 IronWill 正常运行的基础：

· 通知权限 —— 显示常驻通知，防止系统杀死后台服务
· 使用情况访问 —— 检测当前前台运行的 App，精准拦截
· 悬浮窗 —— 显示锁屏覆盖层和闹钟挑战界面
· 自启动 —— 手机重启后自动恢复服务（部分手机需手动确认）
· 后台弹出界面 —— 锁屏界面能正常弹出（国产手机专属）
· 关联启动 —— 允许系统或其他应用唤醒 IronWill 的后台进程。

7.2 进入黑名单时

· 查询所有应用 —— 读取已安装 App 列表，生成黑名单选项。仅用于展示应用名称，不读取应用内容，不联网上传。

7.3 开启防卸载保护时

· 无障碍服务 —— 拦截黑名单应用、锁屏监控。不会读取密码/支付信息等敏感内容。开启后会先回到 IronWill，系统无障碍服务列表页不会被拦截，只有进入 IronWill 的服务详情并尝试关闭时才会保护。
· 关联启动 —— 允许系统或其他应用唤醒 IronWill 的后台进程。如果不开启，无障碍服务被系统关停后无法自动重新拉起。
· 允许高耗电（电池优化豁免）—— 防止系统在后台休眠时杀死服务。部分国产 ROM 会激进清理后台，以上三项缺一不可。

7.4 按需申请

· 相机 —— 闹钟挑战中扫码功能用，仅在启用扫码挑战时才申请。

IronWill 所有数据处理均在设备本地完成，无需网络权限，不会向任何服务器发送你的数据。

八、寄语

有所不为，而后可以有为。

―

九、配置示例

下面是一位用户的真实配置。他的需求很典型——白天用社交软件不限制，但每次打开都在消耗额度；娱乐 App 严格限时，只在特定时间窗内才能打开。

9.1 勿扰时段

一天切成四块，额度随注意力曲线分配：

· 上午 08:30–12:00（35 分钟）—— 精力最好，额度不用太多，专注为主
· 下午 12:45–17:00（35 分钟）—— 午后容易分心，和上午保持一致
· 晚上 17:30–22:00（30 分钟）—— 临近休息，稍微收紧
· 深夜 23:00–07:00（20 分钟）—— 最低额度，仅够应急

9.2 应用时间窗

娱乐 App 不是完全不让用，而是限定在合理时段：

· 健身时间窗 07:00–08:30 —— 早上边运动边看 B 站，只放行了这一个 App
· 工作日娱乐 21:00–23:00 —— 下班后有 2 小时集中娱乐，覆盖视频、游戏、浏览器等
· 周末娱乐 18:30–23:00 —— 周末放宽到傍晚开始，时长也更充裕

9.3 闹钟

闹钟设在 07:44，两阶段解锁，中间间隔 60 秒。第一关做完，正好起身去刷牙；刷完回来再完成第二关，闹钟才关闭。这样设计是为了防止一睁眼就躺床上刷手机——起床、走动、洗漱的间隙，大脑已经清醒了一半。

9.4 关键参数

· 借用比例 50% —— 额度不够时可预支下一时段的一半，借 1 分钟扣 2 分钟，给自己留条后路但要付出代价
· 空档冷却 120 秒 —— 从非监管时段回来，先冷静 2 分钟，防止一进来就刷
· 休息冷却 120 秒 —— 每次申请休息后强制冷静 2 分钟，避免连续解锁
· 超级临时上限 1.5 分钟 —— 应急扫码够用，但不够刷一条视频
· 每周特权 1 次、480 分钟、07:00–23:00 可用 —— 一张底牌，但只能在清醒时段用
· 生效时间跟随最早时段自动同步 —— 不用手动算，系统自动对齐

9.5 为什么这样设

白天社交软件不限制打开次数，但每次打开都消耗额度——聊 QQ/微信越多，晚上留给娱乐的时间就越少。这是一个自我调节的循环：白天克制→额度剩余多→晚上娱乐尽兴；白天放纵→额度耗尽→晚上自动锁机。

时间窗确保娱乐 App 只在指定时段可用，时间窗外即使额度没用完也打不开。双重约束之下，你不必跟意志力较劲——规则替你做了选择。

―

十、闹钟详解

闹钟系统独立于主锁屏，解决的是"起不来床"和"醒了又刷手机"两个问题。它的设计有一个核心思路：第一关负责叫醒你，第二关负责拦住你。

10.1 两阶段设计

闹钟触发后，你需要连续完成两个挑战才能关闭闹钟。两个阶段之间有一个可配置的冷却间隔（比如 60 秒）。这个间隔是关键——刷完第一关，闹钟暂停，你可以起身去刷牙、走动一下；60 秒后第二关弹出，这时你已经离开被窝，大脑清醒了不少。

为什么需要第二关？因为很多人做完第一关挑战后，人还在床上，手指下意识就滑去刷朋友圈、看视频了。第二关是一个轻量级的阻拦：它难度很低、强度最小，纯粹是一个"你确定你醒了？"的确认步骤。刷完牙回来顺手做完，闹钟关闭，新的一天才算正式开始。

10.2 渐进式振动

只有第一关会使用渐进式振动。开启后，振动强度从最低逐步爬升，如果选了"5 分钟突破最大值"，第一关会在 5 分钟内把振动拉到系统上限——确保你一定被叫醒。

第二关不会渐进。它始终保持在最低强度，几乎室友都听不到。这样设计的目的很明确：第二关不是用来叫醒你的，只是用来轻轻确认一下你已经清醒了。激进的手段全放在第一关，第二关保持克制。

10.3 防关机与防重启

半梦半醒的时候，人的本能是长按电源键——关机、重启，怎么把闹钟弄没怎么来。IronWill 在闹钟响起期间会拦截常规关机和重启操作。你可以在闹钟设置里开启这项保护，确保除了完成挑战之外没有其他捷径。

10.4 配置建议

· 第一关 —— 选你必须下床才能完成的。扫码最推荐（把二维码贴在客厅或洗手间，必须走过去），数学可以设难一点（下床拿笔或计算器才能算）。振动开启渐进并勾上"突破最大值"，确保叫醒能力。如果你有更好的题型建议，欢迎联系我。
· 第二关 —— 题型选你擅长的就好，强度调到最小，振动关掉或保持最低，不要加码
· 间隔时间 —— 60 秒起步，够刷个牙、走几步。太短等于没有间隔，太长可能又躺回去了

总的来说：第一关是铁腕手段，必须让你离开被窝；第二关是温柔提醒，确认你不再需要闹钟。一个唱红脸，一个唱白脸。

十一、常见问题

问：为什么我的黑名单 App 以小窗模式运行时没有被拦截？

答：小窗拦截需要开启无障碍服务才能实现精确识别。无障碍开启后，即使 App 以悬浮小窗运行，也能被精准检测到，无障碍服务会自动执行返回操作。

        """.trimIndent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 边缘到边缘布局，手动处理状态栏避让（确保前置摄像头区域正确留白）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }

        // 解析帮助内容为结构化章节
        allSections = parseSections(buildHelpText())

        // 构建页面
        buildUI()
    }

    // ========== 章节解析 ==========

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun statusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else dp(24)
    }

    private fun parseSections(fullText: String): List<HelpSection> {
        val sections = mutableListOf<HelpSection>()
        val lines = fullText.split("\n")
        var currentTitle = ""
        var currentContent = StringBuilder()
        var currentLevel = 1

        fun flush() {
            val trimmed = currentContent.toString().trim('\n', ' ')
            if (currentTitle.isNotEmpty()) {
                sections.add(HelpSection(currentTitle, trimmed, currentLevel))
            }
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // 匹配一级标题：一、需求  二、快速入门  等
            val h1 = Regex("^[一二三四五六七八九十]+、.+$").find(trimmed)
            // 匹配二级标题：2.1 理解两类 App  等
            val h2 = Regex("^\\d+\\.\\d+\\s.+$").find(trimmed)

            when {
                h1 != null -> {
                    flush()
                    currentTitle = trimmed
                    currentContent = StringBuilder()
                    currentLevel = 1
                }
                h2 != null -> {
                    flush()
                    currentTitle = trimmed
                    currentContent = StringBuilder()
                    currentLevel = 2
                }
                else -> {
                    if (currentContent.isNotEmpty()) currentContent.append("\n")
                    currentContent.append(trimmed)
                }
            }
        }
        flush()
        return sections
    }

    // ========== UI 构建 ==========

    private fun buildUI() {
        val sbh = statusBarHeight()

        // === DrawerLayout（侧边栏容器） ===
        val drawerLayout = DrawerLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ===================== 主内容 =====================
        val mainContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        // ---- 顶部栏（导航按钮 + 标题） ----
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // sbh = 实际状态栏高度(含刘海)，+ dp(16) 额外呼吸空间
            setPadding(dp(24), sbh + dp(16), dp(24), dp(16))
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnToc = TextView(this).apply {
            text = "☰"
            textSize = 24f
            setTextColor(Color.parseColor("#00838F"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { drawerLayout.openDrawer(Gravity.START) }
        }
        topBar.addView(btnToc)

        val tvTitle = TextView(this).apply {
            text = "📘 IronWill 用户手册"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00838F"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMargins(0, 0, 48, 0) }
        }
        topBar.addView(tvTitle)
        mainContent.addView(topBar)

        // ---- 搜索栏 ----
        val searchFrame = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(32), dp(12), dp(32), dp(8))
        }
        val editText = EditText(this).apply {
            hint = "🔍 搜索手册内容..."
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            setHintTextColor(Color.parseColor("#9E9E9E"))
            setBackgroundResource(android.R.drawable.editbox_background)
            setPadding(dp(36), dp(16), dp(36), dp(16))
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) {
                    searchQuery = text.toString().trim()
                    filterAndRender()
                    true
                } else false
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString()?.trim() ?: ""
                    filterAndRender()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        searchFrame.addView(editText)
        mainContent.addView(searchFrame)

        // ---- 可滚动内容区域 ----
        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
        }

        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(32), dp(8), dp(32), dp(48))
        }
        scrollView.addView(contentContainer)
        mainContent.addView(scrollView)

        drawerLayout.addView(mainContent)

        // ===================== 侧边栏（目录） =====================
        val drawerContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = DrawerLayout.LayoutParams(
                dp(280),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            (layoutParams as DrawerLayout.LayoutParams).gravity = Gravity.START
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            setPadding(dp(24), sbh + dp(24), dp(24), dp(24))
        }

        val drawerTitle = TextView(this).apply {
            text = "📑 目录"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#006064"))
            setPadding(0, dp(24), 0, dp(20))
        }
        drawerContent.addView(drawerTitle)

        // 可滚动的目录列表
        val tocScroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val tocList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 生成目录项
        var sectionIndex = 0
        for (sec in allSections) {
            val isMajor = sec.level == 1
            val indent = if (isMajor) 0 else 32

            val tvItem = TextView(this).apply {
                text = sec.title
                textSize = if (isMajor) 15f else 13f
                setTypeface(null, if (isMajor) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(Color.parseColor(if (isMajor) "#006064" else "#00838F"))
                setPadding(dp(indent), dp(10), dp(16), dp(10))
                setOnClickListener {
                    drawerLayout.closeDrawer(Gravity.START)
                    searchQuery = ""
                    filterAndRender()
                    // 滚动到该章节（延迟等渲染完成）
                    scrollView.post {
                        scrollToSectionInContainer(sec.title)
                    }
                }
            }
            tocList.addView(tvItem)
        }
        tocScroll.addView(tocList)
        drawerContent.addView(tocScroll)

        // 底部提示
        val tvFooter = TextView(this).apply {
            text = "共 ${allSections.size} 个章节"
            textSize = 11f
            setTextColor(Color.parseColor("#BDBDBD"))
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        }
        drawerContent.addView(tvFooter)

        drawerLayout.addView(drawerContent)

        // 设置根视图
        setContentView(drawerLayout)

        // 初始渲染
        filterAndRender()
    }

    /**
     * 在 contentContainer 中滚动到匹配标题的第一个 View
     */
    private fun scrollToSectionInContainer(title: String) {
        for (i in 0 until contentContainer.childCount) {
            val child = contentContainer.getChildAt(i)
            if (child is TextView) {
                val text = child.text.toString()
                if (text == title || text.startsWith(title)) {
                    // 找到该 View 在 ScrollView 中的位置
                    val location = IntArray(2)
                    child.getLocationInWindow(location)
                    val parent = child.parent
                    if (parent is ScrollView) {
                        parent.smoothScrollTo(0, child.top - dp(80))
                    } else if (parent is ViewGroup) {
                        // 向上找 ScrollView
                        var p: ViewParent? = parent.parent
                        while (p != null) {
                            if (p is ScrollView) {
                                p.smoothScrollTo(0, child.top - 80)
                                break
                            }
                            p = p.parent
                        }
                    }
                    return
                }
            }
        }
    }

    // ========== 过滤与渲染 ==========

    private fun filterAndRender() {
        contentContainer.removeAllViews()
        contentContainer.visibility = View.VISIBLE

        val query = searchQuery.lowercase().trim()
        val matched = if (query.isEmpty()) {
            allSections
        } else {
            allSections.filter { sec ->
                sec.title.lowercase().contains(query) ||
                sec.content.lowercase().contains(query)
            }
        }

        if (query.isNotEmpty()) {
            // 搜索模式下：显示匹配数量
            addSearchResultHeader(contentContainer, "找到 ${matched.size} 个相关章节")
        }
        // 普通模式：直接渲染所有章节，目录在侧边栏中

        if (matched.isEmpty() && query.isNotEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "未找到匹配内容"
                textSize = 16f
                setTextColor(Color.parseColor("#9E9E9E"))
                gravity = Gravity.CENTER
                setPadding(0, dp(80), 0, dp(80))
            }
            contentContainer.addView(tvEmpty)
            return
        }

        // 渲染章节
        for ((i, section) in matched.withIndex()) {
            // 搜索高亮色
            val highlightColor = Color.parseColor("#FFFFEB3B")

            // ---- 标题 ----
            val tvTitle = if (section.level == 1) {
                buildSectionTitle(section.title, 18f, "#006064", Typeface.BOLD)
            } else {
                buildSectionTitle(section.title, 15f, "#00838F", Typeface.NORMAL)
            }

            // 搜索高亮
            if (query.isNotEmpty() && section.title.lowercase().contains(query)) {
                val spannable = SpannableString(section.title)
                val idx = section.title.lowercase().indexOf(query)
                if (idx >= 0) {
                    spannable.setSpan(BackgroundColorSpan(highlightColor), idx, idx + query.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                tvTitle.text = spannable
            }

            contentContainer.addView(tvTitle)

            // ---- 内容 ----
            val tvBody = buildContentText(section.content, query, highlightColor)
            contentContainer.addView(tvBody)

            // 章节间间距
            if (i < matched.size - 1) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1
                    ).apply { setMargins(0, dp(12), 0, dp(12)) }
                    setBackgroundColor(Color.parseColor("#1A000000"))
                }
                contentContainer.addView(divider)
            }
        }
    }

    // ========== 组件构建 ==========

    private fun addSearchResultHeader(container: LinearLayout, text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.parseColor("#757575"))
            setPadding(0, 0, 0, dp(16))
        }
        container.addView(tv)
    }

    private fun buildSectionTitle(text: String, size: Float, color: String, style: Int): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTypeface(null, style)
            setTextColor(Color.parseColor(color))
            setPadding(0, dp(4), 0, dp(8))
        }
    }

    private fun buildContentText(text: String, query: String, highlightColor: Int): TextView {
        val tv = TextView(this).apply {
            setTextSize(15f)
            setTextColor(Color.parseColor("#424242"))
            setLineSpacing(8f, 1f)
            setPadding(0, dp(4), 0, dp(4))
        }

        if (query.isNotEmpty() && text.lowercase().contains(query)) {
            // 只高亮第一次出现的匹配
            val lower = text.lowercase()
            var searchFrom = 0
            val sb = SpannableString(text)
            // 最多高亮前 5 处匹配，避免过度高亮
            var count = 0
            while (count < 5) {
                val idx = lower.indexOf(query, searchFrom)
                if (idx < 0) break
                sb.setSpan(BackgroundColorSpan(highlightColor), idx, idx + query.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                searchFrom = idx + query.length
                count++
            }
            tv.text = sb
        } else {
            tv.text = text
        }

        return tv
    }
}
