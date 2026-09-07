package com.zhizhi.app.manager

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

object AlarmChallengeManager {
    private const val TAG = "AlarmChallengeManager"
    private const val PREFS_BOOKMARKS = "alarm_bookmarks"
    private const val KEY_BOOKMARK_POEMS = "bookmarked_poems"
    private const val KEY_BOOKMARK_WORDS = "bookmarked_words"

    data class Poem(val up: String, val down: String)
    data class Word(
        val text: String,
        val uk: String,
        val us: String,
        val translation: String,
        val shortTranslation: String = ""
    )

    val poems = mutableListOf<Poem>()
    val words = mutableListOf<Word>()

    private var loaded = false
    private var poemsLoaded = false
    private var wordsLoaded = false

    // ===== 选词填空 =====

    data class ClozeQuestion(
        val sentence: String,              // 三句节选短文，目标空位为 __________
        val answer: String,                // 正确答案
        val options: List<String>,         // 4个选项（A/B/C/D）
        val optionMeanings: List<String> = emptyList(),  // 4 个选项的中文释义
        val hint: String = "",             // 目标词简短释义（旧兼容）
        val passageTranslation: String = "", // 短文中文翻译
        val answerTranslation: String = "", // 目标词完整中文释义
        val grammarExplanation: String = "", // 语法/考点解析
        val sourceYear: Int = 0,            // 题目来源年份
        val sourceQuestionNo: Int = 0,     // 题目来源题号
        val fullPassage: String = "",      // 完整短文（仅回顾时显示）
        val sourceRepo: String = ""        // 仓库来源链接
    )

    // 选词填空真题池（启动时从 assets/cloze_pool.json 加载）
    private val clozePool = mutableListOf<ClozeQuestion>()
    private var clozePoolLoaded = false

    // 单测可见快照
    internal val clozePoolSnapshot: List<ClozeQuestion> get() = clozePool.toList()
    internal val isClozePoolLoaded: Boolean get() = clozePoolLoaded

    // 单测可重置,保证每个用例从干净状态出发
    internal fun resetClozePoolForTest() {
        clozePool.clear()
        clozePoolLoaded = false
    }

    fun loadClozePool(context: Context) {
        if (clozePoolLoaded) return
        val jsonStr = try {
            context.assets.open("cloze_pool.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "加载 cloze_pool.json 失败", e)
            return
        }
        loadClozePoolFromJsonString(jsonStr)
        // 顺带加载短文词库（一次 assets 读取），失败也不抛
        try {
            loadPassageWords(context)
        } catch (e: Exception) {
            Log.w(TAG, "loadPassageWords 调用失败", e)
        }
    }

    /**
     * 从 JSON 字符串解析 cloze 题库,原子替换 clozePool. 解析失败或结果为空都不修改 clozePool.
     * 暴露 internal 供单测直接喂数据.
     * @return 成功加载的题目数(0 表示加载失败/题库为空,保留 legacy 兜底)
     */
    internal fun loadClozePoolFromJsonString(json: String): Int {
        return try {
            val root = JSONObject(json)
            val items = root.optJSONArray("items") ?: run {
                Log.e(TAG, "cloze_pool.json 缺少 items 数组")
                return 0
            }

            // 原子加载: 先解析到临时列表,异常/空都不会污染 clozePool
            val tempPool = mutableListOf<ClozeQuestion>()
            var minYear = Int.MAX_VALUE
            var maxYear = Int.MIN_VALUE
            var skipped = 0

            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue
                try {
                    val q = parseOneClozeItem(obj) ?: continue
                    tempPool.add(q)
                    if (q.sourceYear in 1..9999) {
                        if (q.sourceYear < minYear) minYear = q.sourceYear
                        if (q.sourceYear > maxYear) maxYear = q.sourceYear
                    }
                } catch (e: Exception) {
                    skipped++
                    Log.w(TAG, "跳过第 ${i + 1} 项 (id=${obj.optString("id", "?")}): ${e.message}")
                }
            }

            if (tempPool.isNotEmpty()) {
                clozePool.clear()
                clozePool.addAll(tempPool)
                clozePoolLoaded = true
                val yearRange = if (minYear <= maxYear && minYear != Int.MAX_VALUE) " ($minYear-$maxYear)" else ""
                Log.d(TAG, "成功加载选词填空真题池: ${clozePool.size} 题$yearRange, 跳过 $skipped 项")
                tempPool.size
            } else {
                // 题库解析后为空 → 保留 legacyGenerateCloze() 兜底,但必须显式 log
                Log.e(TAG, "题库解析后为空, 走 legacyGenerateCloze() 兜底; 跳过 $skipped 项")
                0
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析 cloze_pool.json 失败", e)
            0
        }
    }

    /**
     * 解析单条 Cloze 题. 支持 v1 (sentence/options 数组/option_meanings 数组) 和
     * v3-simple (context_three_sentences/options 对象 {A,B,C,D}/option_meanings_zh 对象) 两种 schema.
     * 任何字段缺失或解析失败返回 null, 由调用方决定是否跳过.
     */
    private fun parseOneClozeItem(obj: JSONObject): ClozeQuestion? {
        // 题面: 新格式 context_three_sentences -> passage_with_only_target_blank_other_answers_filled -> 旧 sentence
        val sentence = obj.optString("context_three_sentences", "").ifEmpty {
            obj.optString("passage_with_only_target_blank_other_answers_filled", "")
        }.ifEmpty {
            obj.optString("sentence", "")
        }
        if (sentence.isEmpty()) return null

        // 选项: 显式按 A,B,C,D 取值(支持对象/数组两种形态). JSONObject 不保证遍历顺序.
        val opts = parseFourField(obj, "options")
        if (opts == null) return null

        // 释义: 新格式 option_meanings_zh, 旧格式 option_meanings 数组
        val means = parseFourField(obj, "option_meanings_zh", "option_meanings")
            ?.map { it.ifEmpty { "" } }
            ?: List(4) { "" }

        val answer = obj.optString("answer_text", "").ifEmpty { obj.optString("answer", "") }
        if (answer.isEmpty()) return null

        // 答案释义回填: 把正确项的 option_meanings_zh 复制到 answerTranslation
        // 优先用 answer_label, 旧格式可能用 correct_letter
        val answerLabel = obj.optString("answer_label", "").ifEmpty { obj.optString("correct_letter", "") }
        val backfillIdx = when {
            answerLabel in "ABCD" -> "ABCD".indexOf(answerLabel)
            else -> opts.indexOf(answer).takeIf { it in 0..3 } ?: -1
        }
        val backfillTrans = if (backfillIdx in 0..3) means.getOrNull(backfillIdx) ?: "" else ""
        val answerTransFinal = obj.optString("answerTranslation", "").ifEmpty {
            obj.optString("answer_translation", backfillTrans)
        }

        val srcObj = obj.optJSONObject("source")
        return ClozeQuestion(
            sentence = sentence,
            answer = answer,
            options = opts,
            optionMeanings = means,
            hint = obj.optString("hint", ""),
            passageTranslation = obj.optString("passageTranslation", "").ifEmpty {
                obj.optString("passage_translation", "")
            }.ifEmpty {
                // v3.1 Bug-3 修复: passage.zh 兜底 (cloze_pool.json 545 条翻译都在 passage.zh 字段)
                obj.optJSONObject("passage")?.optString("zh", "") ?: ""
            },
            answerTranslation = answerTransFinal,
            grammarExplanation = obj.optString("review_explanation", "").ifEmpty {
                obj.optString("grammarExplanation", "")
            }.ifEmpty {
                obj.optString("grammar_explanation", "")
            }.ifEmpty {
                obj.optString("jiexi", "")
            },
            sourceYear = obj.optInt("year", 0).takeIf { it > 0 }
                ?: obj.optInt("exam_year", 0),
            sourceQuestionNo = obj.optInt("blank_index", 0).takeIf { it > 0 }
                ?: obj.optInt("printed_question_no", 0).takeIf { it > 0 }
                ?: obj.optInt("blank_num", 0).takeIf { it > 0 }
                ?: obj.optInt("question_no", 0),
            fullPassage = obj.optString("raw_passage", "").ifEmpty {
                obj.optString("full_passage", "")
            }.ifEmpty {
                obj.optString("sentence_full", "")
            },
            sourceRepo = (srcObj?.optString("repo", ""))?.ifEmpty { "" }
                ?: obj.optString("source_file", "")
        )
    }

    /**
     * 从 JSONObject 读取 4 个固定键(A/B/C/D)的字段. 支持两种 JSON 形态:
     * - 对象: {"A": "...", "B": "...", "C": "...", "D": "..."}
     * - 数组: ["...", "...", "...", "..."]
     * 显式按 A,B,C,D 顺序, 不依赖 JSONObject 遍历顺序. 返回 List<String> 或 null(都缺失/类型不对).
     */
    private fun parseFourField(obj: JSONObject, vararg keys: String): List<String>? {
        for (key in keys) {
            if (!obj.has(key)) continue
            // 对象形态
            val o = obj.optJSONObject(key)
            if (o != null) {
                return listOf(
                    o.optString("A", ""),
                    o.optString("B", ""),
                    o.optString("C", ""),
                    o.optString("D", "")
                )
            }
            // 数组形态
            val arr = obj.optJSONArray(key)
            if (arr != null && arr.length() >= 4) {
                return (0 until 4).map { idx ->
                    if (arr.isNull(idx)) "" else arr.optString(idx, "")
                }
            }
        }
        return null
    }

    /** 简短释义：取第一个义项，中文，去掉英文括号，限制10字 */
    fun shortTranslation(full: String): String {
        val first = full.split("；", "；", ";")[0].trim()
        val noEn = first.replace(Regex("\\(.*?\\)"), "").replace(Regex("[a-zA-Z]+"), "")
            .replace(".", "").trim()
        return if (noEn.length > 12) noEn.take(12) + "…" else noEn
    }

    /**
     * 生成 4 个近形干扰选项（同首字母分组，随机取）
     * 返回 (答案 + 3个干扰项) 的洗牌列表
     */
    private fun buildClozeOptions(target: Word): List<String> {
        val firstChar = target.text.firstOrNull()?.lowercaseChar() ?: 'a'
        val sameLetter = words.filter {
            (it.text.firstOrNull()?.lowercaseChar() ?: 'a') == firstChar && it.text != target.text
        }.shuffled().take(3).map { it.text }
        val fillers = if (sameLetter.size < 3) {
            words.filter { it.text != target.text }
                .shuffled().take(3 - sameLetter.size).map { it.text }
        } else emptyList()
        return (listOf(target.text) + sameLetter + fillers).shuffled()
    }

    /**
     * 生成选词填空题（真题池驱动）
     * 从 clozePool 随机抽一题。空位为 __________，4 选 1。
     */
    fun generateCloze(): ClozeQuestion? {
        // 优先从真题池抽题
        if (clozePool.isNotEmpty()) {
            return clozePool.random()
        }
        // 兜底：旧硬编码逻辑（兼容旧 clozePool 加载失败时）
        return legacyGenerateCloze()
    }

    /** 旧版 Cloze 兜底（保留兼容 ClozeQuestion 字段） */
    private fun legacyGenerateCloze(): ClozeQuestion? {
        if (words.size < 5) return null
        val target = words.filter { it.text.length >= 5 }.randomOrNull() ?: return null
        val options = buildClozeOptions(target)

        // 只显示目标词本身的简短释义作为提示，不泄露其他选项
        val hint = shortTranslation(target.translation).ifEmpty { target.text }

        // 16组英文短文 + 中文翻译，每组2-3句，只有1句包含 [    ]
        data class Passage(val en: List<String>, val zh: String)
        val passages = listOf(
            Passage(
                listOf(
                    "She faced many difficulties but never gave up.",
                    "Her strong [    ] helped her overcome every challenge.",
                    "Everyone admired her for this quality."
                ),
                "她面对许多困难但从未放弃。她坚强的毅力帮助她克服了每一个挑战。每个人都钦佩她的这一品质。"
            ),
            Passage(
                listOf(
                    "Learning a new language requires daily practice.",
                    "With enough [    ], anyone can become fluent eventually.",
                    "The key is to keep trying every day."
                ),
                "学习一门新语言需要每天练习。有了足够的毅力，任何人都能最终变得流利。关键是每天坚持尝试。"
            ),
            Passage(
                listOf(
                    "The team worked together on a difficult project.",
                    "Good [    ] helped them solve problems quickly.",
                    "They shared ideas and supported each other."
                ),
                "团队一起合作完成了一个困难的项目。良好的协作帮助他们快速解决了问题。他们分享想法、互相支持。"
            ),
            Passage(
                listOf(
                    "He always tells the truth even when it's hard.",
                    "His [    ] is respected by everyone around him.",
                    "People know they can trust his words."
                ),
                "他总是说真话，即使很难。他的诚实受到周围每个人的尊重。人们知道可以相信他的话。"
            ),
            Passage(
                listOf(
                    "The scientist spent years researching the disease.",
                    "Her great [    ] led to a medical breakthrough.",
                    "Many lives were saved because of her work."
                ),
                "这位科学家花了多年研究这种疾病。她伟大的奉献精神带来了医学突破。许多生命因她的工作而得救。"
            ),
            Passage(
                listOf(
                    "She trained every morning for the big competition.",
                    "Her [    ] and discipline brought her victory.",
                    "She proved that hard work pays off."
                ),
                "她每天早上为大赛训练。她的毅力和自律为她带来了胜利。她证明了努力终有回报。"
            ),
            Passage(
                listOf(
                    "The leader listened to every team member's opinion.",
                    "Her wise [    ] helped the company grow.",
                    "Everyone felt valued and motivated."
                ),
                "这位领导听取了每个团队成员的意见。她明智的领导力帮助公司成长。每个人都感到被重视和激励。"
            ),
            Passage(
                listOf(
                    "After the earthquake the community rebuilt together.",
                    "Their [    ] and hope never faded.",
                    "They supported each other through the hard times."
                ),
                "地震后，社区一起重建。他们的韧性和希望从未消退。他们在艰难时期互相支持。"
            ),
            Passage(
                listOf(
                    "The artist painted the same scene many times.",
                    "His [    ] to his craft was truly remarkable.",
                    "Each painting was better than the last."
                ),
                "这位艺术家多次画同一个场景。他对技艺的专注投入令人赞叹。每幅画都比上一幅更好。"
            ),
            Passage(
                listOf(
                    "He always finds creative ways to fix old problems.",
                    "His [    ] has saved the company a lot of money.",
                    "He thinks in ways others don't."
                ),
                "他总是能找到创造性的方法解决老问题。他的创造力为公司节省了大量资金。他以别人想不到的方式思考。"
            ),
            Passage(
                listOf(
                    "The teacher explained the topic with great patience.",
                    "Her [    ] made students feel comfortable asking questions.",
                    "Everyone learned something new."
                ),
                "老师非常耐心地解释了这个话题。她的耐心让学生们敢于提问。每个人都学到了新东西。"
            ),
            Passage(
                listOf(
                    "She donated her time to help those in need.",
                    "Her [    ] inspired many others to volunteer too.",
                    "The community grew stronger together."
                ),
                "她贡献自己的时间帮助有需要的人。她的慷慨激励了许多人也去当志愿者。社区因此变得更加强大。"
            ),
            Passage(
                listOf(
                    "The old tree has stood in the village for generations.",
                    "Its [    ] and strength are admired by everyone.",
                    "It has survived many storms."
                ),
                "这棵老树在村子里屹立了几代人。它的坚韧和力量受到每个人的钦佩。它经历了许多风暴依然挺立。"
            ),
            Passage(
                listOf(
                    "The athlete pushed through pain to finish the race.",
                    "His [    ] and determination inspired the crowd.",
                    "He crossed the finish line with a smile."
                ),
                "这位运动员忍着疼痛完成了比赛。他的毅力和决心激励了观众。他微笑着冲过终点线。"
            ),
            Passage(
                listOf(
                    "The writer revised the manuscript many times.",
                    "Her [    ] to producing quality work was evident.",
                    "The final book became a bestseller."
                ),
                "这位作家多次修改手稿。她对产出高质量作品的执着显而易见。最终这本书成了畅销书。"
            ),
            Passage(
                listOf(
                    "They explored the cave with caution and wonder.",
                    "Their sense of [    ] drove them to discover new things.",
                    "The adventure was unforgettable."
                ),
                "他们谨慎而好奇地探索了洞穴。他们的冒险精神驱使他们发现新事物。这次冒险令人难忘。"
            )
        )

        // 每篇短文已经硬编码了唯一的 [    ]，直接拼接即可
        val passage = passages.random()
        val clozePassage = passage.en.joinToString(" ")

        return ClozeQuestion(
            sentence = clozePassage,
            answer = target.text,
            options = options,
            hint = hint,
            passageTranslation = passage.zh,
            answerTranslation = target.translation
        )
    }

    fun init(context: Context) {
        if (loaded) return

        if (!poemsLoaded) {
            try {
                loadPoems(context)
                poemsLoaded = poems.isNotEmpty()
                if (!poemsLoaded) {
                    Log.w(TAG, "诗词题库解析完成但条目为 0，请检查 duilian_single.json 字段")
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载唐诗失败", e)
            }
        }

        if (!wordsLoaded) {
            try {
                loadWords(context)
                wordsLoaded = words.isNotEmpty()
                if (!wordsLoaded) {
                    Log.w(TAG, "单词题库解析完成但条目为 0，请检查 vocabulary_kaoyan.json 字段")
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载单词失败", e)
            }
        }

        // 加载选词填空真题池（cloze_pool.json）
        try {
            loadClozePool(context)
        } catch (e: Exception) {
            Log.e(TAG, "加载 cloze_pool.json 失败", e)
        }

        loaded = poemsLoaded && wordsLoaded
    }

    private fun loadPoems(context: Context) {
        poems.clear()
        // 使用清洗后的 duilian_single.json
        val jsonStr = context.assets.open("duilian_single.json").bufferedReader().use { it.readText() }
        val array = JSONArray(jsonStr)
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            var up = obj.optString("display_text", "")
            if (up.isEmpty()) up = obj.optString("up", "")
            var down = obj.optString("expected_answer", "")
            if (down.isEmpty()) down = obj.optString("down", "")
            if (up.isNotEmpty() && down.isNotEmpty()) {
                poems.add(Poem(up, down))
            }
        }
        Log.d(TAG, "成功加载唐诗: ${poems.size} 首")
    }

    private fun loadWords(context: Context) {
        words.clear()
        // 1. 加载原有考研词汇
        loadVocabularyKaoyan(context)
        // 2. 加载 AI 生成的高频词库 (高考+考研+雅思高频)
        loadAIWordlist(context)
        Log.d(TAG, "成功加载单词: ${words.size} 个")
    }

    private fun loadVocabularyKaoyan(context: Context) {
        try {
            val jsonStr = context.assets.open("vocabulary_kaoyan.json").bufferedReader().use { it.readText() }
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val word = obj.optString("word", "")
                val uk = obj.optString("ukPhonetic", "")
                val us = obj.optString("usPhonetic", "")
                val trans = obj.optString("translation", "")
                if (word.isNotEmpty() && trans.isNotEmpty()) {
                    words.add(Word(word, uk, us, trans))
                }
            }
            Log.d(TAG, "考研词汇: ${words.size} 个")
        } catch (e: Exception) {
            Log.e(TAG, "加载考研词汇失败", e)
        }
    }

    private fun loadAIWordlist(context: Context) {
        try {
            val jsonStr = context.assets.open("wordlist.json").bufferedReader().use { it.readText() }
            val array = JSONArray(jsonStr)
            var count = 0
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val text = obj.optString("text", "")
                val uk = obj.optString("uk", "")
                val us = obj.optString("us", "")
                val trans = obj.optString("translation", "")
                if (text.isNotEmpty() && trans.isNotEmpty()) {
                    words.add(Word(text, uk, us, trans))
                    count++
                }
            }
            Log.d(TAG, "AI 词库: $count 个")
        } catch (e: Exception) {
            Log.e(TAG, "加载 AI 词库失败", e)
        }
    }

    // ===== 选词填空：词形还原 / 释义去重 / 查词 / 收藏 (R8/R6/R7) =====

    // 140+ 常用不规则映射：把过去式/过去分词/复数/比较级还原为词形原型，方便命中词汇库
    private val IRREGULAR_LEMMA: Map<String, String> = mapOf(
        // be 动词
        "was" to "be", "were" to "be", "been" to "be", "being" to "be", "am" to "be", "is" to "be", "are" to "be",
        // have
        "had" to "have", "has" to "have", "having" to "have",
        // do
        "did" to "do", "done" to "do", "does" to "do", "doing" to "do",
        // go
        "went" to "go", "gone" to "go", "going" to "go", "goes" to "go",
        // 常见不规则过去式/过去分词 -> 现在时
        "took" to "take", "taken" to "take", "taking" to "take", "takes" to "take",
        "made" to "make", "making" to "make", "makes" to "make",
        "said" to "say", "saying" to "say", "says" to "say",
        "got" to "get", "gotten" to "get", "getting" to "get", "gets" to "get",
        "gave" to "give", "given" to "give", "giving" to "give", "gives" to "give",
        "came" to "come", "coming" to "come", "comes" to "come",
        "saw" to "see", "seen" to "see", "seeing" to "see", "sees" to "see",
        "knew" to "know", "known" to "know", "knowing" to "know", "knows" to "know",
        "thought" to "think", "thinking" to "think", "thinks" to "think",
        "found" to "find", "finding" to "find", "finds" to "find",
        "told" to "tell", "telling" to "tell", "tells" to "tell",
        "felt" to "feel", "feeling" to "feel", "feels" to "feel",
        "brought" to "bring", "bringing" to "bring", "brings" to "bring",
        "bought" to "buy", "buying" to "buy", "buys" to "buy",
        "caught" to "catch", "catching" to "catch", "catches" to "catch",
        "taught" to "teach", "teaching" to "teach", "teaches" to "teach",
        "left" to "leave", "leaving" to "leave", "leaves" to "leave",
        "kept" to "keep", "keeping" to "keep", "keeps" to "keep",
        "held" to "hold", "holding" to "hold", "holds" to "hold",
        "wrote" to "write", "written" to "write", "writing" to "write", "writes" to "write",
        "spoke" to "speak", "spoken" to "speak", "speaking" to "speak", "speaks" to "speak",
        "broke" to "break", "broken" to "break", "breaking" to "break", "breaks" to "break",
        "chose" to "choose", "chosen" to "choose", "choosing" to "choose", "chooses" to "choose",
        "drove" to "drive", "driven" to "drive", "driving" to "drive", "drives" to "drive",
        "rose" to "rise", "risen" to "rise", "rising" to "rise", "rises" to "rise",
        "froze" to "freeze", "frozen" to "freeze", "freezing" to "freeze", "freezes" to "freeze",
        "stole" to "steal", "stolen" to "steal", "stealing" to "steal", "steals" to "steal",
        "wore" to "wear", "worn" to "wear", "wearing" to "wear", "wears" to "wear",
        "tore" to "tear", "torn" to "tear", "tearing" to "tear", "tears" to "tear",
        "bore" to "bear", "born" to "bear", "borne" to "bear", "bearing" to "bear", "bears" to "bear",
        "swore" to "swear", "sworn" to "swear", "swearing" to "swear", "swears" to "swear",
        "hid" to "hide", "hidden" to "hide", "hiding" to "hide", "hides" to "hide",
        "bit" to "bite", "bitten" to "bite", "biting" to "bite", "bites" to "bite",
        "sang" to "sing", "sung" to "sing", "singing" to "sing", "sings" to "sing",
        "sank" to "sink", "sunk" to "sink", "sinking" to "sink", "sinks" to "sink",
        "drank" to "drink", "drunk" to "drink", "drinking" to "drink", "drinks" to "drink",
        "swam" to "swim", "swum" to "swim", "swimming" to "swim", "swims" to "swim",
        "ran" to "run", "running" to "run", "runs" to "run",
        "won" to "win", "winning" to "win", "wins" to "win",
        "began" to "begin", "begun" to "begin", "beginning" to "begin", "begins" to "begin",
        "grew" to "grow", "grown" to "grow", "growing" to "grow", "grows" to "grow",
        "drew" to "draw", "drawn" to "draw", "drawing" to "draw", "draws" to "draw",
        "threw" to "throw", "thrown" to "throw", "throwing" to "throw", "throws" to "throw",
        "blew" to "blow", "blown" to "blow", "blowing" to "blow", "blows" to "blow",
        "flew" to "fly", "flown" to "fly", "flying" to "fly", "flies" to "fly",
        "fell" to "fall", "fallen" to "fall", "falling" to "fall", "falls" to "fall",
        "sent" to "send", "sending" to "send", "sends" to "send",
        "spent" to "spend", "spending" to "spend", "spends" to "spend",
        "lent" to "lend", "lending" to "lend", "lends" to "lend",
        "built" to "build", "building" to "build", "builds" to "build",
        "lost" to "lose", "losing" to "lose", "loses" to "lose",
        "met" to "meet", "meeting" to "meet", "meets" to "meet",
        "led" to "lead", "leading" to "lead", "leads" to "lead",
        "read" to "read", "reading" to "read", "reads" to "read",
        "fed" to "feed", "feeding" to "feed", "feeds" to "feed",
        "slept" to "sleep", "sleeping" to "sleep", "sleeps" to "sleep",
        "wept" to "weep", "weeping" to "weep", "weeps" to "weep",
        "crept" to "creep", "creeping" to "creep", "creeps" to "creep",
        "swept" to "sweep", "sweeping" to "sweep", "sweeps" to "sweep",
        "leapt" to "leap", "leaping" to "leap", "leaps" to "leap",
        "dealt" to "deal", "dealing" to "deal", "deals" to "deal",
        "meant" to "mean", "meaning" to "mean", "means" to "mean",
        "heard" to "hear", "hearing" to "hear", "hears" to "hear",
        "sold" to "sell", "selling" to "sell", "sells" to "sell",
        "paid" to "pay", "paying" to "pay", "pays" to "pay",
        "sat" to "sit", "sitting" to "sit", "sits" to "sit",
        "stood" to "stand", "standing" to "stand", "stands" to "stand",
        "understood" to "understand", "understanding" to "understand", "understands" to "understand",
        "stuck" to "stick", "sticking" to "stick", "sticks" to "stick",
        "struck" to "strike", "striking" to "strike", "strikes" to "strike",
        "shook" to "shake", "shaken" to "shake", "shaking" to "shake", "shakes" to "shake",
        // 不规则复数 -> 单数
        "children" to "child", "men" to "man", "women" to "woman", "people" to "person",
        "mice" to "mouse", "geese" to "goose", "feet" to "foot", "teeth" to "tooth",
        "oxen" to "ox", "lice" to "louse", "dice" to "die",
        "data" to "datum", "media" to "medium", "criteria" to "criterion",
        "phenomena" to "phenomenon", "analyses" to "analysis",
        "indices" to "index", "vertices" to "vertex", "matrices" to "matrix",
        // 不规则比较级/最高级
        "better" to "good", "best" to "good",
        "worse" to "bad", "worst" to "bad",
        "further" to "far", "furthest" to "far", "farther" to "far", "farthest" to "far",
        // 常见名词/形容词复数 -s/-es (规则) 也会在 lookupWordWithLemma 中处理
        "capabilities" to "capable", "abilities" to "ability",
        "universities" to "university", "activities" to "activity"
    )

    // 短文词库（覆盖真题阅读词汇），从 assets/passage_words.json 加载
    private val passageWords = mutableMapOf<String, String>()
    @Volatile private var passageWordsLoaded = false

    /**
     * 加载短文词库（passage_words.json）。失败不抛异常，最多打 log。
     * 与 loadClozePool 一样幂等：重复调用不会重复加载。
     */
    fun loadPassageWords(context: Context) {
        if (passageWordsLoaded) return
        try {
            val jsonStr = context.assets.open("passage_words.json").bufferedReader().use { it.readText() }
            val root = JSONObject(jsonStr)
            val keys = root.keys()
            var count = 0
            while (keys.hasNext()) {
                val k = keys.next()
                val v = root.optString(k, "")
                if (k.isNotEmpty() && v.isNotEmpty()) {
                    passageWords[k.lowercase()] = v
                    count++
                }
            }
            passageWordsLoaded = true
            Log.d(TAG, "成功加载短文词库: $count 个")
        } catch (e: Exception) {
            Log.e(TAG, "加载 passage_words.json 失败", e)
        }
    }

    /**
     * 4 级查词链：1) 词库原样 -> 2) IRREGULAR_LEMMA 还原 -> 3) 规则后缀还原
     * (e.g. abilities->ability, going->go) -> 4) passageWords
     * @return 命中词库或 passageWords 的 Word/null
     */
    fun lookupWordWithLemma(raw: String): Word? {
        val w = raw.trim().lowercase()
        if (w.isEmpty()) return null
        // 1) 直接
        words.find { it.text.equals(w, ignoreCase = true) }?.let { return it }
        // 2) 不规则还原
        IRREGULAR_LEMMA[w]?.let { lemma ->
            words.find { it.text.equals(lemma, ignoreCase = true) }?.let { return it }
        }
        // 3) 规则后缀: -ies -> -y, -es/-s -> 空, -ing/-ed 去尾
        val rules = listOf(
            w.removeSuffix("ies").let { if (it != w && it.isNotEmpty()) "${it}y" else null },
            w.removeSuffix("ing").takeIf { it.isNotEmpty() && it != w },
            w.removeSuffix("ed").takeIf { it.isNotEmpty() && it != w },
            w.removeSuffix("es").takeIf { it.isNotEmpty() && it != w },
            w.removeSuffix("s").takeIf { it.isNotEmpty() && it != w }
        )
        for (lemma in rules) {
            if (lemma.isNullOrEmpty()) continue
            words.find { it.text.equals(lemma, ignoreCase = true) }?.let { return it }
        }
        // 4) passageWords 兜底（拼回短文里的释义）
        return passageWords[w]?.let { Word(text = w, uk = "", us = "", translation = it) }
    }

    /**
     * 异步查有道词典（短文长按查词时使用）。
     * 同步实现：在 IO 线程调用方自行包一层 withContext(Dispatchers.IO)。
     * 返回 ec.word.trs[0].tr[0].l.i 数组第一个翻译；失败返回 null。
     */
    fun queryYoudaoDict(word: String): String? {
        val w = word.trim()
        if (w.isEmpty()) return null
        return try {
            val urlStr = "https://dict.youdao.com/jsonapi?q=" + URLEncoder.encode(w, "UTF-8")
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (IronWill/1.0)")
            try {
                val code = conn.responseCode
                if (code !in 200..299) return null
                val stream: InputStream = conn.inputStream
                val text = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
                val root = JSONObject(text)
                val ec = root.optJSONObject("ec") ?: return null
                val wordObj = ec.optJSONObject("word") ?: return null
                val trs = wordObj.optJSONArray("trs") ?: return null
                if (trs.length() == 0) return null
                val first = trs.optJSONObject(0) ?: return null
                val trArr = first.optJSONArray("tr") ?: return null
                if (trArr.length() == 0) return null
                val lObj = trArr.optJSONObject(0)?.optJSONObject("l") ?: return null
                lObj.optJSONArray("i")?.let { iArr ->
                    if (iArr.length() > 0) return iArr.optString(0, "")
                }
                null
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryYoudaoDict failed: $w", e)
            null
        }
    }

    /**
     * 安全获取选项释义：
     *  - 索引越界 -> "（无释义）"
     *  - 释义为空/纯"null"字符串/与 word 完全相同(忽略大小写) -> "（无释义）"
     *  - 释义以 word 开头(忽略大小写)且 word 之后去除前后空白后非空 -> 去掉 word 前缀
     *  - 释义以 word 开头但 word 之后只有空白 -> 返回原文（避免空释义）
     *  - 其他 -> 返回原文
     */
    fun getSafeOptionMeaning(optionMeanings: List<String>, idx: Int, word: String): String {
        if (idx < 0 || idx >= optionMeanings.size) return "（无释义）"
        val raw = optionMeanings[idx]
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) return "（无释义）"
        if (raw.equals(word, ignoreCase = true)) return "（无释义）"
        // 释义以 word 开头 -> 去 word 前缀（word 之后允许任意非空字符,忽略大小写）
        if (raw.length > word.length && raw.startsWith(word, ignoreCase = true)) {
            val remainder = raw.substring(word.length).trim()
            if (remainder.isNotEmpty()) return remainder
            // word 之后只有空白 -> 返回原文
            return raw
        }
        return raw
    }

    // ===== 选词填空收藏 (R6/R7) =====

    data class BookmarkedCloze(
        val clozeId: String,           // kaoyan_cloze_<year>_<questionNo>
        val year: Int,
        val questionNo: Int,
        val sentence: String,          // 题目 sentence（已去圈号数字）
        val answer: String,
        val answerTranslation: String,
        val options: List<String>,
        val optionMeanings: List<String>, // 已经过 getSafeOptionMeaning 处理
        val excerptTranslation: String, // 从 grammarExplanation 提取的［篇章分析］
        val grammarExplanation: String,
        val sourceRepo: String,
        val bookmarkedWord: String?,    // 用户长按收藏的单词（可能为空）
        val bookmarkedAt: Long
    )

    private const val KEY_BOOKMARK_CLOZES = "bookmarked_clozes"

    /**
     * 把 [ClozeQuestion] 加入收藏列表。bw 是用户长按的 passage 单词（可空）。
     * 已存在同 clozeId 的条目：合并 bookmarkedWord（首次为空的允许覆盖为新值）。
     */
    fun bookmarkCloze(context: Context, cloze: AlarmChallengeManager.ClozeQuestion, bw: String?) {
        val prefs = context.getSharedPreferences(PREFS_BOOKMARKS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BOOKMARK_CLOZES, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val year = cloze.sourceYear
        val qno = cloze.sourceQuestionNo
        val clozeId = "kaoyan_cloze_${year}_${qno.toString().padStart(2, '0')}"

        // 选项释义经 getSafeOptionMeaning 处理（review 页用）
        val safeOptionMeanings = cloze.options.mapIndexed { idx, opt ->
            getSafeOptionMeaning(cloze.optionMeanings, idx, opt)
        }
        // 节选翻译: 从 grammarExplanation 解析 〔篇章分析〕... 段
        val excerptTrans = extractExcerptTranslation(cloze.grammarExplanation)
            .ifEmpty { cloze.passageTranslation }
        // 清理 sentence 中的圈号数字 (① ② ...)
        val cleanSentence = cleanClozeSentence(cloze.sentence)

        val newArr = JSONArray()
        var found = false
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("clozeId", "") == clozeId) {
                // 已存在: 把空 bookmarkedWord 用新的 bw 覆盖
                val prevBw = obj.optString("bw", "").ifEmpty { null }
                obj.put("bw", (bw?.takeIf { it.isNotEmpty() } ?: prevBw) ?: "")
                newArr.put(obj)
                found = true
            } else {
                newArr.put(obj)
            }
        }
        if (!found) {
            val obj = JSONObject().apply {
                put("clozeId", clozeId)
                put("year", year)
                put("questionNo", qno)
                put("sentence", cleanSentence)
                put("answer", cloze.answer)
                put("answerTranslation", cloze.answerTranslation)
                put("options", JSONArray(cloze.options))
                put("optionMeanings", JSONArray(safeOptionMeanings))
                put("excerptTranslation", excerptTrans)
                put("grammarExplanation", cloze.grammarExplanation)
                put("sourceRepo", cloze.sourceRepo)
                put("bw", bw?.takeIf { it.isNotEmpty() } ?: "")
                put("bookmarkedAt", System.currentTimeMillis())
            }
            newArr.put(obj)
        }
        prefs.edit().putString(KEY_BOOKMARK_CLOZES, newArr.toString()).apply()
        Log.d(TAG, "书签 选词填空: $clozeId | bw=$bw")
    }

    /**
     * 按单词返回所有收藏的 cloze 节选。R1 按题目紧凑分组时复用。
     * - 匹配规则: 大小写不敏感
     * - 返回值含 clozeId / year / questionNo, 便于分组到具体真题
     */
    fun getClozeExcerptsForWord(context: Context, word: String): List<BookmarkedCloze> {
        if (word.isBlank()) return emptyList()
        return getBookmarkedClozes(context).filter {
            it.bookmarkedWord?.equals(word, ignoreCase = true) == true
        }
    }

    /** 检查某道题（或题目的特定单词）是否已收藏 */
    fun isClozeBookmarked(context: Context, clozeId: String, word: String? = null): Boolean {
        val prefs = context.getSharedPreferences(PREFS_BOOKMARKS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BOOKMARK_CLOZES, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val bwKey = word?.takeIf { it.isNotEmpty() } ?: ""
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("clozeId", "") == clozeId) {
                if (bwKey.isEmpty()) return true
                if (obj.optString("bw", "").equals(bwKey, ignoreCase = true)) return true
            }
        }
        return false
    }

    /**
     * 删除某条 cloze 收藏（按 clozeId + bookmarkedWord 唯一定位）。
     * - 允许 bw="" 删除整题收藏
     * - 找不到条目时静默 no-op
     */
    fun removeBookmarkedCloze(context: Context, clozeId: String, word: String?) {
        val prefs = context.getSharedPreferences(PREFS_BOOKMARKS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BOOKMARK_CLOZES, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val bwKey = word?.takeIf { it.isNotEmpty() } ?: ""
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("clozeId", "") == clozeId && obj.optString("bw", "") == bwKey) {
                Log.d(TAG, "removeBookmarkedCloze: $clozeId | bw=$bwKey")
                continue
            }
            newArr.put(obj)
        }
        prefs.edit().putString(KEY_BOOKMARK_CLOZES, newArr.toString()).apply()
    }

    fun getBookmarkedClozes(context: Context): List<BookmarkedCloze> {
        val prefs = context.getSharedPreferences(PREFS_BOOKMARKS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BOOKMARK_CLOZES, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val result = mutableListOf<BookmarkedCloze>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val year = obj.optInt("year", 0)
            val qno = obj.optInt("questionNo", 0)
            val clozeId = obj.optString("clozeId", "kaoyan_cloze_${year}_${qno.toString().padStart(2, '0')}")
            val optionsArr = obj.optJSONArray("options")
            val meanArr = obj.optJSONArray("optionMeanings")
            val options = if (optionsArr != null) (0 until optionsArr.length()).map { optionsArr.optString(it, "") } else emptyList()
            val means = if (meanArr != null) (0 until meanArr.length()).map { meanArr.optString(it, "") } else emptyList()
            val bwStr = obj.optString("bw", "")
            result.add(
                BookmarkedCloze(
                    clozeId = clozeId,
                    year = year,
                    questionNo = qno,
                    sentence = obj.optString("sentence", ""),
                    answer = obj.optString("answer", ""),
                    answerTranslation = obj.optString("answerTranslation", ""),
                    options = options,
                    optionMeanings = means,
                    excerptTranslation = obj.optString("excerptTranslation", ""),
                    grammarExplanation = obj.optString("grammarExplanation", ""),
                    sourceRepo = obj.optString("sourceRepo", ""),
                    bookmarkedWord = bwStr.ifEmpty { null },
                    bookmarkedAt = obj.optLong("bookmarkedAt", 0L)
                )
            )
        }
        // 排序: 年份 asc, 题号 asc (与 test 期望一致)
        result.sortWith(compareBy<BookmarkedCloze> { it.year }.thenBy { it.questionNo })
        return result
    }

    /**
     * 从 grammarExplanation 文本中提取 〔篇章分析〕... 段（最常见格式）。
     * 兼容全角方括号 ［ ］ / ［篇章分析］ / 〔篇章分析〕。
     * 段尾遇到下一个 ［...］ / [xxx] / 〔xxx〕 标记则截止。
     * 如果没有 〔篇章分析〕, 尝试 〔答案精解〕:
     *  - 首行（去除 "1. " / "1、" 等编号前缀）> 20 字 -> 截前 200 字 + "…"
     *  - 首行 <= 20 字 -> 原样返回
     * 都没有则返回 ""。
     */
    internal fun extractExcerptTranslation(grammar: String): String {
        if (grammar.isEmpty()) return ""
        val sectionMarkers = listOf("［篇章分析］", "[篇章分析]", "〔篇章分析〕")
        for (marker in sectionMarkers) {
            val start = grammar.indexOf(marker)
            if (start >= 0) {
                val body = grammar.substring(start + marker.length)
                val endMarkers = listOf("［", "[", "〔")
                var end = body.length
                for (em in endMarkers) {
                    val next = body.indexOf(em)
                    if (next in 0 until end) end = next
                }
                val block = body.substring(0, end).trim()
                if (block.isNotEmpty()) return block
            }
        }
        // 兜底: 答案精解段
        val answerMarkers = listOf("［答案精解］", "[答案精解]", "〔答案精解〕")
        for (marker in answerMarkers) {
            val start = grammar.indexOf(marker)
            if (start >= 0) {
                val body = grammar.substring(start + marker.length).trim()
                // 取首行（处理跨行/换行）
                val firstLine = body.substringBefore('\n').trim()
                // 去掉 "1. " / "1、 " / "(1) " 之类编号前缀
                val noNumberPrefix = firstLine.replace(Regex("^\\d+[.、]\\s*"), "").trim()
                if (noNumberPrefix.isEmpty()) return ""
                return if (noNumberPrefix.length > 20) {
                    noNumberPrefix.take(200) + "…"
                } else {
                    noNumberPrefix
                }
            }
        }
        return ""
    }

    /**
     * 去掉 sentence 中的圈号数字（① ② ...），让 review 卡片更易读。
     */
    internal fun cleanClozeSentence(s: String): String {
        if (s.isEmpty()) return s
        return s.replace(Regex("[①-⑳]"), "").trim()
    }

    /**
     * 统一的题面清洗（供答题题面/复习题面/节选翻译共用，保证一致性）：
     * 1. 去掉圈号数字 ①-⑳；
     * 2. 安全清除"字母紧跟数字"的粘连编号（to9→to、They1that→They that、smell12→smell）；
     * 3. 折叠多余空白、去首尾。
     *
     * 不碰 \d+[A-Za-z]（保留 19th-century / 1960s / 1750 等合法序数与年代），也不动 ____ 空位。
     */
    internal fun cleanPassage(s: String): String {
        if (s.isEmpty()) return s
        var t = s.replace(Regex("[①-⑳]"), "")
        // 字蒂紧接数字：只在字母后补空格，避免两词被粘连（They1that→They that）
        t = Regex("([A-Za-z])\\d+").replace(t) { m -> m.groupValues[1] + " " }
        return t.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * 按圈号数字 ①-⑳ 切分句段；无圈号标记时返回整段单元素（由调用方决定是否再按标点兜底）。
     */
    internal fun splitNumberedSentences(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        return s.split(Regex("(?=[①-⑳])")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * 构建"含填空句的恰好 3 句"节选翻译对（en, zh），并保证与题面同一来源、一一对应、不冗余。
     * - en/zh 各自优先按圈号 ①-⑳ 切分（context_three_sentences 与 passage.zh 成对），按下标配对，最多 3 对；
     * - 若按圈号切不出多句，再用标点兜底；
     * - 句子 >3 时选取以空位 ____ 为中心的 3 句窗口（保证含填空句）；
     * - zh 的 passageTranslation 为空时，回退到 extractExcerptTranslation（并按同法切、仍 ≤3 对）。
     */
    fun buildExcerptPairs(cloze: ClozeQuestion): List<Pair<String, String>> {
        val rawEn = cloze.sentence
        val rawZh = cloze.passageTranslation.ifEmpty {
            extractExcerptTranslation(cloze.grammarExplanation)
        }
        // 先按圈号 ①②③ 切分（此时不能先 cleanPassage，否则圈号被剥了就切不出），
        // 切完再对每句做 cleanPassage（清粘连数字）。
        val enParts = splitNumberedSentences(rawEn)
        val zhParts = splitNumberedSentences(rawZh)
        val enFinal = pickWindow(if (enParts.size >= 2) enParts else splitBySentenceBoundary(rawEn))
            .map { cleanPassage(it) }.filter { it.isNotEmpty() }
        val zhFinal = pickWindow(if (zhParts.size >= 2) zhParts else splitBySentenceBoundary(rawZh))
            .map { cleanPassage(it) }.filter { it.isNotEmpty() }
        val count = minOf(enFinal.size, zhFinal.size, 3)
        if (count <= 0) return emptyList()
        return (0 until count).map { i -> enFinal[i] to zhFinal[i] }
    }

    /** 取以空位 ____ 为中心、最多 max 句的窗口（保证包含填空句）。 */
    private fun pickWindow(sentences: List<String>, max: Int = 3): List<String> {
        if (sentences.size <= max) return sentences
        val blankIdx = sentences.indexOfFirst { it.contains("____") || it.contains("___") || it.contains("[    ]") }
        val start = if (blankIdx >= 0) (blankIdx - 1).coerceIn(0, sentences.size - max) else 0
        return sentences.subList(start, minOf(start + max, sentences.size))
    }

    /**
     * v3.1 Bug-3: 清洗翻译字符串里的异常换行与多余空白。
     * - 把 `\s*\n\s*` 替换为单个空格 (例: "百分\n4\n分" → "百分 4 分")
     * - 把连续 2+ 空白合并为 1 个空格
     * - 去首尾空白
     * 保留 `\n\n` 段间分隔的方案已被简化: 全归一为单空格,避免渲染时出现意外换行
     */
    internal fun cleanTranslation(s: String): String {
        if (s.isEmpty()) return s
        return s
            .replace(Regex("""\s*\n\s*"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /**
     * v3.1 Bug-3: 把节选翻译按句拆分,英文 3 句对应中文 3 句。
     * - 句边界: `.` `!` `?` `。` `！` `？` `;` `；` 后接空白/字符串末尾
     * - 拆分后按索引配对,中英文数量不一致时取较少者
     * - 返回 List<Pair<英文, 中文>>
     */
    internal fun splitExcerptBySentence(english: String, chinese: String): List<Pair<String, String>> {
        if (english.isEmpty() || chinese.isEmpty()) return emptyList()
        val enSentences = splitBySentenceBoundary(english)
        val zhSentences = splitBySentenceBoundary(chinese)
        val count = minOf(enSentences.size, zhSentences.size)
        if (count == 0) return emptyList()
        return (0 until count).map { i -> enSentences[i] to zhSentences[i] }
    }

    /**
     * 按句边界拆分字符串. 保留末尾标点.
     * 例: "First. Second!" → ["First.", "Second!"]
     */
    private fun splitBySentenceBoundary(text: String): List<String> {
        val sb = StringBuilder()
        val out = mutableListOf<String>()
        for (ch in text) {
            sb.append(ch)
            if (ch in SENTENCE_DELIMITERS) {
                val piece = sb.toString().trim()
                if (piece.isNotEmpty()) out.add(piece)
                sb.clear()
            }
        }
        val tail = sb.toString().trim()
        if (tail.isNotEmpty()) out.add(tail)
        return out
    }

    private val SENTENCE_DELIMITERS = charArrayOf('.', '!', '?', '。', '！', '？', ';', '；')

    fun getRandomPoem(): Poem? {
        if (poems.isEmpty()) return null
        return poems[Random.nextInt(poems.size)]
    }

    fun getRandomWord(): Word? {
        if (words.isEmpty()) return null
        return words[Random.nextInt(words.size)]
    }

    fun generateMath(): Pair<Int, Int> {
        val a = Random.nextInt(100, 1000) // 3位数
        val b = Random.nextInt(10, 100)  // 2位数
        return a to b
    }

    // 格式化输入，去除标点和不可见字符
    fun normalizeText(input: String): String {
        return input.replace(Regex("[\\p{P}\\p{S}\\p{Z}\\s]"), "")
    }

    fun checkPoemMatch(input: String, target: String): Boolean {
        // 全盘去标点和空格后全等
        return normalizeText(input) == normalizeText(target)
    }

    // ===== 收藏功能 =====

    data class BookmarkedPoem(
        val display: String,
        val answer: String,
        val skipCount: Int,
        val nextReviewMs: Long = 0L,
        val easeFactor: Float = 2.5f,
        val intervalDays: Int = 0,
        val repetitions: Int = 0,
        val bookmarkedAtMs: Long = 0L
    )

    data class BookmarkedWord(
        val word: String,
        val translation: String,
        val phonetic: String,
        val skipCount: Int,
        val nextReviewMs: Long = 0L,
        val easeFactor: Float = 2.5f,
        val intervalDays: Int = 0,
        val repetitions: Int = 0,
        val bookmarkedAtMs: Long = 0L
    )

    /**
     * SM-2 评分：
     * 0 = 完全不记得，下次立即出现
     * 1 = 记得但非常困难
     * 2 = 有些困难
     * 3 = 一般
     * 4 = 简单
     * 5 = 非常简单
     *
     * 答对 → quality >= 3 → 延长间隔
     * 答错 → quality < 3 → 重置间隔
     */
    fun calcNextReview(
        quality: Int,           // 0-5
        prevEaseFactor: Float,  // 初始 2.5
        prevIntervalDays: Int,   // 初始 0
        prevRepetitions: Int    // 初始 0
    ): Triple<Float, Int, Int> {
        val ef = (prevEaseFactor + (0.1f - (5 - quality) * (0.08f + (5 - quality) * 0.02f)))
            .coerceAtLeast(1.3f)
        return if (quality < 3) {
            // 答错：间隔重置为 1 天，从头开始
            Triple(ef, 1, 0)
        } else {
            val newReps = prevRepetitions + 1
            val newInterval = when (newReps) {
                1 -> 1
                2 -> 6
                else -> (prevIntervalDays * ef).toInt()
            }
            Triple(ef, newInterval.coerceAtLeast(1), newReps)
        }
    }

    /** "答对了，下次什么时候" 格式化 */
    fun formatNextReview(intervalDays: Int): String {
        return when {
            intervalDays == 0 -> "下次闹钟见"
            intervalDays == 1 -> "明天闹钟见"
            intervalDays < 7 -> "${intervalDays}天后闹钟见"
            intervalDays < 30 -> "${intervalDays / 7}周后闹钟见"
            else -> "${intervalDays / 30}个月后闹钟见"
        }
    }

    /** "答错了，下次什么时候" 格式化（固定1天） */
    fun formatNextReviewRetry(): String = "明天闹钟见"

    fun bookmarkPoem(context: Context, display: String, answer: String, quality: Int = 3) {
        val prefs = context.getSharedPreferences(PREFS_BOOKMARKS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BOOKMARK_POEMS, "[]") ?: "[]"
        val arr = JSONArray(raw)

        var found = false
        var nextReviewMs = 0L
        var newIntervalDays = 0
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("display", "") == display &&
                obj.optString("answer", "") == answer) {
                // 已存在：skipCount+1，并更新 SM-2 参数
                val prevCount = obj.optInt("skipCount", 1)
                val prevEf = obj.optDouble("easeFactor", 2.5).toFloat()
                val prevInterval = obj.optInt("intervalDays", 0)
                val prevReps = obj.optInt("repetitions", 0)
                obj.put("skipCount", prevCount + 1)

                val (ef, intervalDays, reps) = calcNextReview(quality, prevEf, prevInterval, prevReps)
                obj.put("easeFactor", ef.toDouble())
                obj.put("intervalDays", intervalDays)
                obj.put("repetitions", reps)
                obj.put("nextReviewMs", if (quality >= 3) System.currentTimeMillis() + intervalDays * 86_400_000L else 0L)
                nextReviewMs = obj.optLong("nextReviewMs", 0L)
                newIntervalDays = intervalDays
                found = true
            }
            newArr.put(obj)
        }
        if (!found) {
            val (ef, intervalDays, reps) = calcNextReview(quality, 2.5f, 0, 0)
            val obj = JSONObject().apply {
                put("display", display)
                put("answer", answer)
                put("skipCount", 1)
                put("easeFactor", ef.toDouble())
                put("intervalDays", intervalDays)
                put("repetitions", reps)
                put("nextReviewMs", if (quality >= 3) System.currentTimeMillis() + intervalDays * 86_400_000L else 0L)
                put("bookmarkedAtMs", System.currentTimeMillis())
            }
            nextReviewMs = obj.optLong("nextReviewMs", 0L)
            newIntervalDays = intervalDays
            newArr.put(obj)
        }
        prefs.edit().putString(KEY_BOOKMARK_POEMS, newArr.toString()).apply()
        val nextLabel = if (quality >= 3) formatNextReview(newIntervalDays) else formatNextReviewRetry()
        Log.d(TAG, "书签 诗词: $display | quality=$quality | nextReview=$nextLabel")
    }

    fun bookmarkWord(context: Context, word: String, translation: String, phonetic: String, quality: Int = 3) {
        val prefs = context.getSharedPreferences(PREFS_BOOKMARKS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BOOKMARK_WORDS, "[]") ?: "[]"
        val arr = JSONArray(raw)

        var found = false
        var nextReviewMs = 0L
        var newIntervalDays = 0
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("word", "") == word) {
                val prevCount = obj.optInt("skipCount", 1)
                val prevEf = obj.optDouble("easeFactor", 2.5).toFloat()
                val prevInterval = obj.optInt("intervalDays", 0)
                val prevReps = obj.optInt("repetitions", 0)
                obj.put("skipCount", prevCount + 1)

                val (ef, intervalDays, reps) = calcNextReview(quality, prevEf, prevInterval, prevReps)
                obj.put("easeFactor", ef.toDouble())
                obj.put("intervalDays", intervalDays)
                obj.put("repetitions", reps)
                obj.put("nextReviewMs", if (quality >= 3) System.currentTimeMillis() + intervalDays * 86_400_000L else 0L)
                nextReviewMs = obj.optLong("nextReviewMs", 0L)
                newIntervalDays = intervalDays
                found = true
            }
            newArr.put(obj)
        }
        if (!found) {
            val (ef, intervalDays, reps) = calcNextReview(quality, 2.5f, 0, 0)
            val obj = JSONObject().apply {
                put("word", word)
                put("translation", translation)
                put("phonetic", phonetic)
                put("skipCount", 1)
                put("easeFactor", ef.toDouble())
                put("intervalDays", intervalDays)
                put("repetitions", reps)
                put("nextReviewMs", if (quality >= 3) System.currentTimeMillis() + intervalDays * 86_400_000L else 0L)
                put("bookmarkedAtMs", System.currentTimeMillis())
            }
            nextReviewMs = obj.optLong("nextReviewMs", 0L)
            newIntervalDays = intervalDays
            newArr.put(obj)
        }
        prefs.edit().putString(KEY_BOOKMARK_WORDS, newArr.toString()).apply()
        val nextLabel = if (quality >= 3) formatNextReview(newIntervalDays) else formatNextReviewRetry()
        Log.d(TAG, "书签 单词: $word | quality=$quality | nextReview=$nextLabel")
    }

    fun isPoemBookmarked(context: Context, display: String, answer: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_BOOKMARKS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BOOKMARK_POEMS, "[]") ?: "[]"
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("display", "") == display && obj.optString("answer", "") == answer) return true
        }
        return false
    }

    fun removeBookmarkedPoem(context: Context, display: String, answer: String) {
        val prefs = context.getSharedPreferences(PREFS_BOOKMARKS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BOOKMARK_POEMS, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("display", "") == display && obj.optString("answer", "") == answer) continue
            newArr.put(obj)
        }
        prefs.edit().putString(KEY_BOOKMARK_POEMS, newArr.toString()).apply()
    }

    fun isWordBookmarked(context: Context, word: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_BOOKMARKS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BOOKMARK_WORDS, "[]") ?: "[]"
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("word", "").equals(word, ignoreCase = true)) return true
        }
        return false
    }


    /**
     * 收藏时间过滤窗口：返回 -1 表示"全部"。
     * timeRange 取值: ""(全部) / "7d" / "30d" / "90d"。
     */
    private fun timeRangeWindowDays(timeRange: String): Long {
        return when (timeRange) {
            "7d" -> 7L
            "30d" -> 30L
            "90d" -> 90L
            else -> -1L
        }
    }

    /**
     * 读取收藏的诗词。
     *
     * @param query 文本搜索（匹配 display 或 answer），空串 = 不筛选
     * @param sortBy 排序方式："skip" = 按跳过次数降序（默认），"time" = 按收藏时间降序（最新在前）
     * @param timeRange 时间过滤：""(全部) / "7d" / "30d" / "90d"，只保留收藏时间在 now-range 内的条目
     */
    fun getBookmarkedPoems(
        context: Context,
        query: String = "",
        sortBy: String = "skip",
        timeRange: String = ""
    ): List<BookmarkedPoem> {
        val prefs = context.getSharedPreferences(PREFS_BOOKMARKS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BOOKMARK_POEMS, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val windowDays = timeRangeWindowDays(timeRange)
        val now = System.currentTimeMillis()
        val result = mutableListOf<BookmarkedPoem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val display = obj.optString("display", "")
            val answer = obj.optString("answer", "")
            val skipCount = obj.optInt("skipCount", 1)
            val bookmarkedAtMs = obj.optLong("bookmarkedAtMs", 0L)
            // 支持搜索：匹配 display 或 answer
            if (query.isNotEmpty() && !display.contains(query, true) && !answer.contains(query, true)) {
                continue
            }
            // 时间过滤：bookmarkedAtMs 落在 now - window 内（0 表示旧数据无收藏时间，放行）
            if (windowDays >= 0L && bookmarkedAtMs > 0L && bookmarkedAtMs < now - windowDays * 86_400_000L) {
                continue
            }
            result.add(BookmarkedPoem(display, answer, skipCount, bookmarkedAtMs = bookmarkedAtMs))
        }
        // "skip"：按 skipCount 降序（跳过最多的排最前）；"time"：按收藏时间降序（最新在前）
        if (sortBy == "time") {
            result.sortByDescending { it.bookmarkedAtMs }
        } else {
            result.sortByDescending { it.skipCount }
        }
        return result
    }

    /**
     * 读取收藏的单词。
     *
     * @param query 文本搜索（匹配 word 或 translation），空串 = 不筛选
     * @param sortBy 排序方式："skip" = 按跳过次数降序（默认），"time" = 按收藏时间降序（最新在前）
     * @param timeRange 时间过滤：""(全部) / "7d" / "30d" / "90d"，只保留收藏时间在 now-range 内的条目
     */
    fun getBookmarkedWords(
        context: Context,
        query: String = "",
        sortBy: String = "skip",
        timeRange: String = ""
    ): List<BookmarkedWord> {
        val prefs = context.getSharedPreferences(PREFS_BOOKMARKS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BOOKMARK_WORDS, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val windowDays = timeRangeWindowDays(timeRange)
        val now = System.currentTimeMillis()
        val result = mutableListOf<BookmarkedWord>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val word = obj.optString("word", "")
            val translation = obj.optString("translation", "")
            val phonetic = obj.optString("phonetic", "")
            val skipCount = obj.optInt("skipCount", 1)
            val bookmarkedAtMs = obj.optLong("bookmarkedAtMs", 0L)
            // 支持搜索：匹配 word 的英文拼写或翻译
            if (query.isNotEmpty() && !word.contains(query, true) && !translation.contains(query, true)) {
                continue
            }
            // 时间过滤：bookmarkedAtMs 落在 now - window 内（0 表示旧数据无收藏时间，放行）
            if (windowDays >= 0L && bookmarkedAtMs > 0L && bookmarkedAtMs < now - windowDays * 86_400_000L) {
                continue
            }
            result.add(BookmarkedWord(word, translation, phonetic, skipCount, bookmarkedAtMs = bookmarkedAtMs))
        }
        // "skip"：按 skipCount 降序（跳过最多的排最前）；"time"：按收藏时间降序（最新在前）
        if (sortBy == "time") {
            result.sortByDescending { it.bookmarkedAtMs }
        } else {
            result.sortByDescending { it.skipCount }
        }
        return result
    }

    fun clearAllBookmarks(context: Context) {
        context.getSharedPreferences(PREFS_BOOKMARKS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_BOOKMARK_POEMS)
            .remove(KEY_BOOKMARK_WORDS)
            .apply()
    }

    /**
     * Req-1: 取消某单词的收藏（按 word 忽略大小写匹配）。
     * 找不到条目时静默 no-op。用于单词详情弹窗里星星按钮的"取消收藏"。
     */
    fun removeBookmarkedWord(context: Context, word: String) {
        if (word.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS_BOOKMARKS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BOOKMARK_WORDS, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("word", "").equals(word, ignoreCase = true)) {
                Log.d(TAG, "removeBookmarkedWord: $word")
                continue
            }
            newArr.put(obj)
        }
        prefs.edit().putString(KEY_BOOKMARK_WORDS, newArr.toString()).apply()
    }

    /** 返回已到期需要复习的诗词（nextReviewMs <= now），null 表示无 */
    fun getReviewablePoem(context: Context): BookmarkedPoem? {
        val poems = getBookmarkedPoems(context)
        val now = System.currentTimeMillis()
        return poems.firstOrNull { it.nextReviewMs in 1 until now }
    }

    /** 返回已到期需要复习的单词，null 表示无 */
    fun getReviewableWord(context: Context): BookmarkedWord? {
        val words = getBookmarkedWords(context)
        val now = System.currentTimeMillis()
        return words.firstOrNull { it.nextReviewMs in 1 until now }
    }

    /**
     * 闹钟答题时判断"本次是否强制插入复习题"：
     * - 每答完 N 道题，检查是否有到期的复习项
     * - 有 → 返回 true，UI 调用 getReviewableXxx() 取题
     * - 没有 → 返回 false，正常随机抽题
     *
     * 策略：每 5 道中插 1 道复习题（有到期项时）
     */
    fun shouldInjectReviewQuestion(
        context: Context,
        totalAnswered: Int,
        injectEvery: Int = 5
    ): Boolean {
        if (totalAnswered <= 0) return false
        if (totalAnswered % injectEvery != 0) return false
        // 本次轮到插入复习题，检查是否有可用的
        return getReviewablePoem(context) != null || getReviewableWord(context) != null
    }

    /**
     * 生成收藏回顾的文本内容
     */
    fun buildBookmarkReviewText(context: Context): String {
        val poems = getBookmarkedPoems(context)
        val words = getBookmarkedWords(context)
        val sb = StringBuilder()

        if (poems.isEmpty() && words.isEmpty()) {
            return "暂无收藏的题目"
        }

        if (poems.isNotEmpty()) {
            sb.appendLine("📜 收藏的诗词")
            sb.appendLine("=" .repeat(30))
            poems.forEachIndexed { idx, p ->
                sb.appendLine("${idx + 1}. ${p.display} [跳过${p.skipCount}次]")
                sb.appendLine("   答案: ${p.answer}")
                sb.appendLine()
            }
        }

        if (words.isNotEmpty()) {
            sb.appendLine("📖 收藏的单词")
            sb.appendLine("=" .repeat(30))
            words.forEachIndexed { idx, w ->
                sb.appendLine("${idx + 1}. ${w.word} [跳过${w.skipCount}次]")
                sb.appendLine("   释义: ${w.translation}")
                if (w.phonetic.isNotEmpty()) sb.appendLine("   音标: ${w.phonetic}")
                sb.appendLine()
            }
        }

        return sb.toString()
    }
}
