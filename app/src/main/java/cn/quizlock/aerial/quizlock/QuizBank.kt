package cn.quizlock.aerial.quizlock

/**
 * The built-in question bank: PEP (人教版) grade 5, first term (上册) — carried over as-is from
 * QuizLock's Dart demo bank (12 math, 10 Chinese, 10 English). Original demo content for the
 * closed loop; the real bank ships as reviewed, versioned Question Packs (docs/08).
 *
 * Subject labels are Chinese ("数学" / "语文" / "英语") and are data, not an enum (ADR-017);
 * they must match the keys of QuizPolicy.subjectWeights. Every question's correct option is
 * written first and shuffled at ask time, so no child can memorize positions.
 */
object QuizBank {
    val all: List<Question> by lazy { build() }

    private fun q(
        id: String, subject: String, unit: String, kp: String, group: String,
        stem: String, options: List<String>, explanation: String,
    ): Question {
        val opts = options.mapIndexed { i, t -> QuestionOption(('A' + i).toString(), t) }
        return Question(
            id = id, subject = subject, unit = unit, knowledgePoint = kp,
            variantGroup = group, stem = stem, options = opts,
            correctOptionId = "A", explanation = explanation,
        )
    }

    private fun build(): List<Question> = listOf(
        // ---------------- 数学 · 小数乘法 ----------------
        q("m01", "数学", "小数乘法", "decimal_multiplication", "vg_decimal_mul",
            "0.8 × 0.7 = ?", listOf("0.56", "5.6", "0.056", "56"),
            "先算 8×7=56，两个因数共两位小数，所以是 0.56。"),
        q("m02", "数学", "小数乘法", "decimal_multiplication", "vg_decimal_mul",
            "1.2 × 0.4 = ?", listOf("0.48", "4.8", "0.048", "48"),
            "12×4=48，共两位小数，得 0.48。"),
        q("m03", "数学", "小数乘法", "decimal_multiplication", "vg_decimal_mul",
            "0.25 × 4 = ?", listOf("1", "10", "0.1", "100"),
            "0.25 是四分之一，乘 4 正好等于 1。"),
        // ---------------- 数学 · 小数除法 ----------------
        q("m04", "数学", "小数除法", "decimal_division", "vg_decimal_div",
            "3.6 ÷ 0.6 = ?", listOf("6", "0.6", "60", "0.06"),
            "被除数和除数同时扩大 10 倍，变成 36÷6=6。"),
        q("m05", "数学", "小数除法", "decimal_division", "vg_decimal_div",
            "7.2 ÷ 0.8 = ?", listOf("9", "0.9", "90", "8"),
            "同时扩大 10 倍：72÷8=9。"),
        q("m06", "数学", "小数除法", "decimal_division", "vg_decimal_div",
            "4.5 ÷ 0.5 = ?", listOf("9", "0.9", "90", "0.09"),
            "同时扩大 10 倍：45÷5=9。"),
        // ---------------- 数学 · 简易方程 ----------------
        q("m07", "数学", "简易方程", "simple_equation", "vg_equation",
            "x + 7 = 15，x 等于多少？", listOf("8", "22", "7", "15"),
            "两边同时减 7：x = 15 - 7 = 8。"),
        q("m08", "数学", "简易方程", "simple_equation", "vg_equation",
            "3x = 21，x 等于多少？", listOf("7", "63", "18", "24"),
            "两边同时除以 3：x = 7。"),
        q("m09", "数学", "简易方程", "simple_equation", "vg_equation",
            "x - 5 = 12，x 等于多少？", listOf("17", "7", "12", "5"),
            "两边同时加 5：x = 17。"),
        // ---------------- 数学 · 多边形的面积 ----------------
        q("m10", "数学", "多边形的面积", "polygon_area", "vg_area",
            "一个三角形，底 6 厘米、高 4 厘米，面积是多少？",
            listOf("12 平方厘米", "24 平方厘米", "10 平方厘米", "6 平方厘米"),
            "三角形面积 = 底×高÷2 = 6×4÷2 = 12。"),
        q("m11", "数学", "多边形的面积", "polygon_area", "vg_area",
            "一个平行四边形，底 5 厘米、高 3 厘米，面积是多少？",
            listOf("15 平方厘米", "8 平方厘米", "30 平方厘米", "7.5 平方厘米"),
            "平行四边形面积 = 底×高 = 5×3 = 15。"),
        q("m12", "数学", "多边形的面积", "polygon_area", "vg_area",
            "一个梯形，上底 3 厘米、下底 5 厘米、高 4 厘米，面积是多少？",
            listOf("16 平方厘米", "32 平方厘米", "12 平方厘米", "20 平方厘米"),
            "梯形面积 = (上底+下底)×高÷2 = (3+5)×4÷2 = 16。"),
        // ---------------- 语文 · 古诗 ----------------
        q("c01", "语文", "古诗三首", "classical_poetry", "vg_poetry",
            "「王师北定中原日，家祭无忘告乃翁」出自哪首诗？",
            listOf("《示儿》", "《题临安邸》", "《己亥杂诗》", "《静夜思》"),
            "这是陆游临终写给儿子的《示儿》。"),
        q("c02", "语文", "古诗三首", "classical_poetry", "vg_poetry",
            "《示儿》的作者是谁？", listOf("陆游", "林升", "龚自珍", "李白"),
            "南宋诗人陆游。"),
        q("c03", "语文", "古诗三首", "classical_poetry", "vg_poetry",
            "「我劝天公重抖擞，不拘一格降人才」出自哪首诗？",
            listOf("《己亥杂诗》", "《示儿》", "《题临安邸》", "《春望》"),
            "清代龚自珍《己亥杂诗》。"),
        // ---------------- 语文 · 修辞 ----------------
        q("c04", "语文", "句子乐园", "figure_of_speech", "vg_rhetoric",
            "「弯弯的月亮像一只小船」用了什么修辞？",
            listOf("比喻", "拟人", "夸张", "反问"),
            "把月亮比作小船，是比喻。"),
        q("c05", "语文", "句子乐园", "figure_of_speech", "vg_rhetoric",
            "「小鸟在枝头欢快地唱歌」用了什么修辞？",
            listOf("拟人", "比喻", "排比", "设问"),
            "把小鸟当作人来写，是拟人。"),
        q("c06", "语文", "句子乐园", "figure_of_speech", "vg_rhetoric",
            "「飞流直下三千尺」用了什么修辞？",
            listOf("夸张", "比喻", "拟人", "对偶"),
            "故意说成三千尺，是夸张。"),
        // ---------------- 语文 · 词义 ----------------
        q("c07", "语文", "词语积累", "word_meaning", "vg_word",
            "「居高临下」中「临」的意思是什么？",
            listOf("面对、靠近", "来到", "临时", "照着写"),
            "「临」在这里是「从高处往下看、面对」的意思。"),
        q("c08", "语文", "词语积累", "word_meaning", "vg_word",
            "「慷慨」的近义词是？",
            listOf("大方", "小气", "犹豫", "安静"),
            "慷慨 = 大方、不吝啬。"),
        q("c09", "语文", "词语积累", "word_meaning", "vg_word",
            "「斩钉截铁」形容的是什么？",
            listOf("说话办事坚决果断", "走路很慢", "心里很害怕", "声音很轻"),
            "像砍断钉子、切断铁一样，形容果断。"),
        q("c10", "语文", "词语积累", "word_meaning", "vg_word",
            "下列词语中，没有错别字的一组是？",
            listOf("毫不犹豫", "豪不犹豫", "毫不犹预", "豪不犹预"),
            "「毫」是一点儿，「豫」是迟疑，写作「毫不犹豫」。"),
        // ---------------- 英语 · There be ----------------
        q("e01", "英语", "There be", "there_be", "vg_there_be",
            "There ___ a book on the desk.", listOf("is", "are", "am", "be"),
            "a book 是单数，用 is。"),
        q("e02", "英语", "There be", "there_be", "vg_there_be",
            "There ___ some apples in the bag.", listOf("are", "is", "am", "be"),
            "apples 是复数，用 are。"),
        q("e03", "英语", "There be", "there_be", "vg_there_be",
            "— Is there a river in the park?  — Yes, ___.",
            listOf("there is", "there are", "it is", "they are"),
            "Is there...? 的肯定回答是 Yes, there is."),
        // ---------------- 英语 · can ----------------
        q("e04", "英语", "What can you do", "modal_can", "vg_can",
            "I can ___ English songs.", listOf("sing", "sings", "singing", "to sing"),
            "情态动词 can 后面接动词原形。"),
        q("e05", "英语", "What can you do", "modal_can", "vg_can",
            "— Can you play basketball?  — No, I ___.",
            listOf("can't", "don't", "isn't", "am not"),
            "Can you...? 的否定回答是 No, I can't."),
        q("e06", "英语", "What can you do", "modal_can", "vg_can",
            "She can ___ the pipa.", listOf("play", "plays", "playing", "to play"),
            "can 后接动词原形 play。"),
        // ---------------- 英语 · 饮食偏好 ----------------
        q("e07", "英语", "What would you like", "food_preference", "vg_food",
            "— What's your favourite food?  — ___",
            listOf("My favourite food is noodles.", "I am fine.", "It is Monday.", "Yes, I can."),
            "问最喜欢的食物，回答要说食物。"),
        q("e08", "英语", "What would you like", "food_preference", "vg_food",
            "I'd like some ___. I'm thirsty.",
            listOf("water", "rice", "bread", "apples"),
            "thirsty 是渴了，应该喝水。"),
        q("e09", "英语", "What would you like", "food_preference", "vg_food",
            "— What would you like to ___?  — Some milk, please.",
            listOf("drink", "eat", "play", "read"),
            "milk 是喝的，用 drink。"),
        q("e10", "英语", "What would you like", "food_preference", "vg_food",
            "下列哪个单词是「蔬菜」？",
            listOf("vegetable", "sandwich", "hamburger", "ice cream"),
            "vegetable = 蔬菜。"),
    )
}
