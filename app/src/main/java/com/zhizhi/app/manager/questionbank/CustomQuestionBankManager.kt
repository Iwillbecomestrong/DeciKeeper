package com.zhizhi.app.manager.questionbank

import android.content.Context
import android.util.Log
import com.zhizhi.app.data.questionbank.CustomQuestion
import com.zhizhi.app.data.questionbank.CustomQuestionBank
import com.zhizhi.app.manager.AutoBackupManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * 自定义题库管理器（仓储层）。
 *
 * 职责：
 * 1. 题库持久化存储（存放在应用私有存储 files/custom_question_banks.json）。
 * 2. 题库的增删改查与参与闹钟出题启停状态（enabled）管理。
 * 3. 闹钟挑战抽题（从启用的题库池中随机抽题，优先覆盖错题与未做题目）。
 * 4. 作答记录更新与错题本归档。
 * 5. 提供标准 Markdown 模板与一键 AI 出题提示词（Prompt）。
 */
object CustomQuestionBankManager {

    private const val TAG = "CustomQuestionBankMgr"
    private const val FILE_NAME = "custom_question_banks.json"

    // 内存缓存
    private val memoryBanks = mutableListOf<CustomQuestionBank>()
    private var isLoaded = false

    private fun getFile(context: Context): File = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun getBanks(context: Context, forceReload: Boolean = false): List<CustomQuestionBank> {
        if (!isLoaded || forceReload) {
            loadFromDisk(context)
        }
        return memoryBanks.toList()
    }

    @Synchronized
    fun getBank(context: Context, bankId: String): CustomQuestionBank? {
        return getBanks(context).find { it.id == bankId }
    }

    @Synchronized
    fun saveBank(context: Context, bank: CustomQuestionBank) {
        val list = getBanks(context).toMutableList()
        val index = list.indexOfFirst { it.id == bank.id }
        if (index >= 0) {
            list[index] = bank.copy(updatedAt = System.currentTimeMillis())
        } else {
            list.add(bank)
        }
        memoryBanks.clear()
        memoryBanks.addAll(list)
        persistToDisk(context)
    }

    @Synchronized
    fun deleteBank(context: Context, bankId: String) {
        val list = getBanks(context).toMutableList()
        list.removeAll { it.id == bankId }
        memoryBanks.clear()
        memoryBanks.addAll(list)
        persistToDisk(context)
    }

    @Synchronized
    fun setBankEnabled(context: Context, bankId: String, enabled: Boolean) {
        val bank = getBank(context, bankId) ?: return
        saveBank(context, bank.copy(enabled = enabled))
    }

    /**
     * 从启用的题库或指定的特定题库中随机抽取一道题目。
     * @param bankId 指定题库ID；为 null 时从所有启用的题库中抽题。
     */
    @Synchronized
    fun getRandomQuestion(context: Context, bankId: String? = null): CustomQuestion? {
        val targetBanks = if (!bankId.isNullOrBlank()) {
            val b = getBank(context, bankId)
            if (b != null && b.questions.isNotEmpty()) listOf(b) else emptyList()
        } else {
            getBanks(context).filter { it.enabled && it.questions.isNotEmpty() }
        }
        if (targetBanks.isEmpty()) return null

        val candidateQuestions = targetBanks.flatMap { it.questions }
        if (candidateQuestions.isEmpty()) return null

        // 优先抽样：如果存在错题或未做题目，有 70% 概率从中抽取
        val mistakesOrUnseen = candidateQuestions.filter { it.wrongCount > 0 || it.lastAttemptStatus == null }
        return if (mistakesOrUnseen.isNotEmpty() && Random.nextFloat() < 0.7f) {
            mistakesOrUnseen.random()
        } else {
            candidateQuestions.random()
        }
    }

    /**
     * 记录作答结果
     */
    @Synchronized
    fun recordAttempt(
        context: Context,
        bankId: String,
        questionId: String,
        isCorrect: Boolean,
        pickedOption: String
    ) {
        val bank = getBank(context, bankId) ?: return
        val updatedQuestions = bank.questions.map { q ->
            if (q.id == questionId) {
                val newWrongCount = if (isCorrect) q.wrongCount else q.wrongCount + 1
                q.copy(
                    wrongCount = newWrongCount,
                    lastAttemptStatus = if (isCorrect) "correct" else "wrong",
                    lastAttemptAt = System.currentTimeMillis()
                )
            } else {
                q
            }
        }
        saveBank(context, bank.copy(questions = updatedQuestions))
    }

    /**
     * 重置单题错题标记（已掌握）
     */
    @Synchronized
    fun resetMistake(context: Context, bankId: String, questionId: String) {
        val bank = getBank(context, bankId) ?: return
        val updatedQuestions = bank.questions.map { q ->
            if (q.id == questionId) {
                q.copy(wrongCount = 0)
            } else {
                q
            }
        }
        saveBank(context, bank.copy(questions = updatedQuestions))
    }

    /**
     * 获取所有题库中的全部错题
     */
    @Synchronized
    fun getAllMistakes(context: Context): List<Pair<CustomQuestionBank, CustomQuestion>> {
        val banks = getBanks(context)
        val result = mutableListOf<Pair<CustomQuestionBank, CustomQuestion>>()
        for (b in banks) {
            for (q in b.questions) {
                if (q.wrongCount > 0) {
                    result.add(b to q)
                }
            }
        }
        return result
    }

    /**
     * 切换题目的收藏状态
     * @return 切换后的最新收藏状态
     */
    @Synchronized
    fun toggleFavorite(context: Context, bankId: String, questionId: String): Boolean {
        val bank = getBank(context, bankId) ?: return false
        var newFavState = false
        val updatedQuestions = bank.questions.map { q ->
            if (q.id == questionId) {
                newFavState = !q.isFavorite
                q.copy(isFavorite = newFavState)
            } else {
                q
            }
        }
        saveBank(context, bank.copy(questions = updatedQuestions))
        return newFavState
    }

    /**
     * 显式设置题目的收藏状态
     */
    @Synchronized
    fun setFavorite(context: Context, bankId: String, questionId: String, isFavorite: Boolean) {
        val bank = getBank(context, bankId) ?: return
        val updatedQuestions = bank.questions.map { q ->
            if (q.id == questionId) q.copy(isFavorite = isFavorite) else q
        }
        saveBank(context, bank.copy(questions = updatedQuestions))
    }

    /**
     * 获取所有题库中已收藏的全部题目
     */
    @Synchronized
    fun getAllFavorites(context: Context): List<Pair<CustomQuestionBank, CustomQuestion>> {
        val banks = getBanks(context)
        val result = mutableListOf<Pair<CustomQuestionBank, CustomQuestion>>()
        for (b in banks) {
            for (q in b.questions) {
                if (q.isFavorite) {
                    result.add(b to q)
                }
            }
        }
        return result
    }

    /**
     * 清空内存与磁盘（测试用）
     */
    @Synchronized
    internal fun clearForTest(context: Context) {
        memoryBanks.clear()
        isLoaded = false
        val file = getFile(context)
        if (file.exists()) file.delete()
    }

    // ===== 持久化 JSON 转换 =====

    private fun loadFromDisk(context: Context) {
        memoryBanks.clear()
        val file = getFile(context)
        if (!file.exists()) {
            isLoaded = true
            return
        }
        try {
            val jsonStr = file.readText()
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                memoryBanks.add(bankFromJson(obj))
            }
            isLoaded = true
        } catch (e: Exception) {
            Log.e(TAG, "从磁盘读取题库失败", e)
            isLoaded = true
        }
    }

    private fun persistToDisk(context: Context) {
        try {
            val array = JSONArray()
            for (bank in memoryBanks) {
                array.put(bankToJson(bank))
            }
            getFile(context).writeText(array.toString())
            AutoBackupManager.triggerDebouncedBackup(context)
        } catch (e: Exception) {
            Log.e(TAG, "保存题库到磁盘失败", e)
        }
    }

    @Synchronized
    fun exportToJson(context: Context): String? {
        val file = getFile(context)
        if (file.exists()) {
            return try {
                file.readText()
            } catch (e: Exception) {
                Log.e(TAG, "导出题库失败", e)
                null
            }
        }
        if (memoryBanks.isNotEmpty()) {
            val array = JSONArray()
            for (bank in memoryBanks) {
                array.put(bankToJson(bank))
            }
            return array.toString()
        }
        return null
    }

    @Synchronized
    fun importFromJson(context: Context, json: String): Boolean {
        return try {
            val array = JSONArray(json)
            val banks = mutableListOf<CustomQuestionBank>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                banks.add(bankFromJson(obj))
            }
            memoryBanks.clear()
            memoryBanks.addAll(banks)
            isLoaded = true
            persistToDisk(context)
            true
        } catch (e: Exception) {
            Log.e(TAG, "导入题库失败", e)
            false
        }
    }

    private fun bankToJson(bank: CustomQuestionBank): JSONObject {
        val obj = JSONObject()
        obj.put("id", bank.id)
        obj.put("title", bank.title)
        obj.put("description", bank.description)
        obj.put("enabled", bank.enabled)
        obj.put("createdAt", bank.createdAt)
        obj.put("updatedAt", bank.updatedAt)

        val qArr = JSONArray()
        for (q in bank.questions) {
            val qObj = JSONObject()
            qObj.put("id", q.id)
            qObj.put("bankId", q.bankId)
            qObj.put("stem", q.stem)
            qObj.put("options", JSONArray(q.options))
            qObj.put("answer", q.answer)
            qObj.put("explanation", q.explanation)
            qObj.put("hint", q.hint)
            qObj.put("wrongCount", q.wrongCount)
            qObj.put("lastAttemptStatus", q.lastAttemptStatus ?: "")
            qObj.put("lastAttemptAt", q.lastAttemptAt)
            qObj.put("isFavorite", q.isFavorite)
            qArr.put(qObj)
        }
        obj.put("questions", qArr)
        return obj
    }

    private fun bankFromJson(obj: JSONObject): CustomQuestionBank {
        val qArr = obj.optJSONArray("questions")
        val questions = mutableListOf<CustomQuestion>()
        if (qArr != null) {
            for (i in 0 until qArr.length()) {
                val qObj = qArr.optJSONObject(i) ?: continue
                val optArr = qObj.optJSONArray("options")
                val options = mutableListOf<String>()
                if (optArr != null) {
                    for (j in 0 until optArr.length()) {
                        options.add(optArr.optString(j, ""))
                    }
                }
                val statusStr = qObj.optString("lastAttemptStatus", "")
                questions.add(
                    CustomQuestion(
                        id = qObj.optString("id", ""),
                        bankId = qObj.optString("bankId", ""),
                        stem = qObj.optString("stem", ""),
                        options = options,
                        answer = qObj.optString("answer", "A"),
                        explanation = qObj.optString("explanation", ""),
                        hint = qObj.optString("hint", ""),
                        wrongCount = qObj.optInt("wrongCount", 0),
                        lastAttemptStatus = if (statusStr.isEmpty()) null else statusStr,
                        lastAttemptAt = qObj.optLong("lastAttemptAt", 0L),
                        isFavorite = qObj.optBoolean("isFavorite", false)
                    )
                )
            }
        }
        return CustomQuestionBank(
            id = obj.optString("id", ""),
            title = obj.optString("title", "未命名题库"),
            description = obj.optString("description", ""),
            enabled = obj.optBoolean("enabled", true),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
            questions = questions
        )
    }

    // ===== 模板与 AI Prompt 指南 =====

    /**
     * 获取标准 Markdown 题库模板文本
     */
    fun getStandardTemplate(): String = """
        ## 1. 唯物辩证法的实质和核心是？
        - A. 质量互变规律
        - B. 对立统一规律
        - C. 否定之否定规律
        - D. 联系与发展的观点
        答案: B
        解析: 对立统一规律揭示了事物普遍联系的根本内容和变化发展的内在动力，是唯物辩证法的实质和核心。

        ## 2. 认识的本质是？
        - A. 主体对客体的直观反映
        - B. 主体对客体的能动反映
        - C. 主体头脑中固有的观念
        - D. 客体对主体的决定作用
        答案: B
        解析: 辩证唯物主义认识论认为，认识是主体在实践基础上对客体的能动反映。
    """.trimIndent()

    /**
     * 获取精心优化的 AI 出题提示词（可直接一键复制发给 ChatGPT / DeepSeek / Claude / 豆包）
     */
    fun getAiPrompt(): String = """
        请帮我将以下学习内容（或针对[填入科目/主题，如：考研政治/公考常识/医学执考/四六级英语]），生成四选一单项选择题，供导入知止App进行早起闹钟挑战。

        【格式要求严格遵守如下 Markdown 结构】：
        ## 1. 题目题干内容（支持多行或背景段落）
        - A. 选项A内容
        - B. 选项B内容
        - C. 选项C内容
        - D. 选项D内容
        答案: A
        解析: 详细解析与知识点总结，帮助晨起后完整回顾。

        【约束条件】：
        1. 每道题必须严格包含 4 个选项（A/B/C/D）；
        2. 答案必须单起一行写“答案: X”；
        3. 解析必须单起一行写“解析: 内容”；
        4. 请一次性生成 10~20 道题，不要输出任何多余的客套话或代码块反引号，直接输出上述题目内容。
    """.trimIndent()

    /**
     * 获取专用的「已有题库/真题一键格式转换」提示词
     */
    fun getExistingBankConversionPrompt(): String = """
        请帮我将我接下来提供的题库/真题/练习题/单词表，完整重新整理为标准 Markdown 四选一格式，以便直接导入知止 App 进行早起闹钟挑战。

        【转换格式规范（每道题严格按如下结构输出）】：
        ## 1. 题目题干内容（支持多行或背景段落）
        - A. 选项A内容
        - B. 选项B内容
        - C. 选项C内容
        - D. 选项D内容
        答案: A
        解析: 详细解析或回顾（选填，原题有解析请保留）
        提示: 助记线索或解题技巧（选填，绝不要出现正确答案选项）

        【约束条件】：
        1. 每道题必须有且仅有 4 个选项（A/B/C/D）；若原题选项不足4个，请补充合理干扰项；
        2. 答案必须单起一行写“答案: X”；
        3. 若原题有解析请保留在“解析: 内容”；
        4. 提示（选填）只能提供思路或关键词，严禁出现答案选项；
        5. 不要输出任何客套问候语或 Markdown 代码块包裹，直接输出转换后的题目列表。

        待转换的原题库内容如下：
        [请在此粘贴你的原题库文本]
    """.trimIndent()
}
