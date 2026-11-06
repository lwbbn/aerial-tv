package cn.quizlock.aerial.quizlock

import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Fresh questions instead of a fixed deck.
 *
 * The 32 hand-written questions in [QuizBank] are the reviewed seed of the closed loop, but a
 * child sees the same 32 stems after a few days. This factory expands that seed into a local
 * question pack: for each syllabus knowledge point (PEP grade 5, first term) it lays out a batch
 * of variants with fresh numbers, poem lines, sentence subjects, nouns and word pairs drawn from
 * small syllabus-bounded tables. Every answer is computed, never guessed, so an expanded question
 * is as checkable as a hand-written one; the correct option is always written first and shuffled
 * by [Question.shuffled] at ask time.
 *
 * Expansion happens once when the picker starts, NOT while the quiz is up: from then on the
 * picker only draws from the bank and mixes knowledge points (see [QuestionPicker]). All
 * questions keep the same tags as the seed bank (ADR-016/017), including the same
 * `variantGroup`, so a wrong answer can swap in a different question from the same point.
 * Nothing here needs a network connection (I1): expansion is pure local code.
 */
object QuestionFactory {

    private data class Kind(
        val subject: String, val unit: String, val kp: String, val group: String,
    )

    private val kinds = listOf(
        Kind("数学", "小数乘法", "decimal_multiplication", "vg_decimal_mul"),
        Kind("数学", "小数除法", "decimal_division", "vg_decimal_div"),
        Kind("数学", "简易方程", "simple_equation", "vg_equation"),
        Kind("数学", "多边形的面积", "polygon_area", "vg_area"),
        Kind("数学", "位置", "position_pair", "vg_position"),
        Kind("数学", "植树问题", "planting_trees", "vg_planting"),
        Kind("语文", "古诗三首", "classical_poetry", "vg_poetry"),
        Kind("语文", "句子乐园", "figure_of_speech", "vg_rhetoric"),
        Kind("语文", "词语积累", "word_meaning", "vg_word"),
        Kind("语文", "字词辨析", "spelling", "vg_spelling"),
        Kind("英语", "There be", "there_be", "vg_there_be"),
        Kind("英语", "What can you do", "modal_can", "vg_can"),
        Kind("英语", "What would you like", "food_preference", "vg_food"),
        Kind("英语", "词汇", "vocabulary", "vg_vocab"),
    )

    private val seq = AtomicInteger(0)
    private val rnd = Random.Default

    /**
     * Expands the local question pack: for every knowledge point we lay out [perKind] reviewed
     * template variants with fresh numbers / lines / words. Called once when the picker starts,
     * so from then on every quiz is **drawn from the bank** — the picker never invents a stem at
     * ask time. A fresh process expands a fresh batch, and the picker randomises knowledge points
     * on top of it, so the same few questions do not keep coming back.
     */
    fun expand(policy: PickerPolicy, perKind: Int): List<Question> =
        kinds.flatMap { kind -> List(perKind) { build(kind, policy) } }

    private fun build(kind: Kind, policy: PickerPolicy): Question {
        val (stem, right, wrongs, explanation) = when (kind.kp) {
            "decimal_multiplication" -> decimalMultiplication()
            "decimal_division" -> decimalDivision()
            "simple_equation" -> equation()
            "polygon_area" -> polygonArea()
            "position_pair" -> positionPair()
            "planting_trees" -> plantingTrees()
            "classical_poetry" -> poetry()
            "figure_of_speech" -> rhetoric()
            "word_meaning" -> wordMeaning()
            "spelling" -> spelling()
            "there_be" -> thereBe()
            "modal_can" -> modalCan()
            "food_preference" -> foodPreference()
            else -> vocabulary()
        }
        val term = QuestionPicker.resolveTerm(policy)
        return Question(
            id = "gen_${seq.incrementAndGet()}_${kind.kp}",
            grade = policy.grade, term = term, textbook = policy.textbook,
            subject = kind.subject, unit = kind.unit, knowledgePoint = kind.kp,
            variantGroup = kind.group, stem = stem, options = options(right, wrongs),
            correctOptionId = "A", explanation = explanation, fresh = true,
        )
    }

    /** Four distinct options with the correct one first; the picker shuffles them at ask time. */
    private fun options(right: String, wrongs: List<String>): List<QuestionOption> {
        val texts = ArrayList<String>(4)
        texts.add(right)
        for (w in wrongs) if (w !in texts && texts.size < 4) texts.add(w)
        val fillers = listOf("以上都不对", "条件不足，无法判断", "需要更多信息")
        for (f in fillers) if (texts.size < 4 && f !in texts) texts.add(f)
        return texts.mapIndexed { i, t -> QuestionOption(('A' + i).toString(), t) }
    }

    private fun num(v: Double): String =
        BigDecimal(v).setScale(2, BigDecimal.ROUND_HALF_UP)
            .stripTrailingZeros().toPlainString()

    private fun pick(pool: List<String>) = pool.random(rnd)

    // ------------------------------ 数学 ------------------------------

    private fun decimalMultiplication(): Quiz {
        val a = rnd.nextInt(2, 10)      // 0.a
        val b = rnd.nextInt(2, 10)      // 0.b
        val prod = a * b                // 0.a × 0.b = prod / 100
        val ans = num(prod / 100.0)
        return Quiz(
            stem = "${num(a / 10.0)} × ${num(b / 10.0)} = ?",
            right = ans,
            wrongs = listOf(num(prod / 10.0), num(prod / 1000.0), num((a + b) / 10.0)),
            explanation = "先算 ${a}×${b}=${prod}，两个因数共两位小数，所以积是 ${ans}。",
        )
    }

    private fun decimalDivision(): Quiz {
        val d = rnd.nextInt(2, 10)      // 除数 0.d
        val q = rnd.nextInt(2, 10)      // 商
        val dividend = num(d * q / 10.0)
        return Quiz(
            stem = "${dividend} ÷ ${num(d / 10.0)} = ?",
            right = q.toString(),
            wrongs = listOf(num(q / 10.0), (q * 10).toString(), d.toString(), (q + 1).toString()),
            explanation = "被除数和除数同时扩大 10 倍，变成 ${d * q}÷${d}=${q}。",
        )
    }

    private fun equation(): Quiz {
        val a = rnd.nextInt(2, 10)
        return when (rnd.nextInt(3)) {
            0 -> {
                var b = rnd.nextInt(a + 2, a + 16)
                if (b == 2 * a) b++   // keep "x = b - a" distinct from the distractor "a"
                Quiz("x + ${a} = ${b}，x 等于多少？", (b - a).toString(),
                    listOf((b + a).toString(), b.toString(), a.toString()),
                    "两边同时减 ${a}：x = ${b} - ${a} = ${b - a}。")
            }
            1 -> {
                val b = rnd.nextInt(2, 16)
                Quiz("x - ${a} = ${b}，x 等于多少？", (b + a).toString(),
                    listOf((b - a).toString(), b.toString(), a.toString()),
                    "两边同时加 ${a}：x = ${b} + ${a} = ${b + a}。")
            }
            else -> {
                val q = rnd.nextInt(2, 10)
                Quiz("${a}x = ${a * q}，x 等于多少？", q.toString(),
                    listOf((a * q - a).toString(), (a + q).toString(), (a * q).toString()),
                    "两边同时除以 ${a}：x = ${q}。")
            }
        }
    }

    private fun polygonArea(): Quiz {
        val b = rnd.nextInt(3, 13)
        val h = rnd.nextInt(2, 11)
        return when (rnd.nextInt(3)) {
            0 -> {
                val ans = num(b * h / 2.0)
                Quiz("一个三角形，底 ${b} 厘米、高 ${h} 厘米，面积是多少？", "${ans} 平方厘米",
                    listOf("${b * h} 平方厘米", "${b + h} 平方厘米", "${num(b * h / 2.0 + 2)} 平方厘米"),
                    "三角形面积 = 底×高÷2 = ${b}×${h}÷2 = ${ans}（平方厘米）。")
            }
            1 -> Quiz("一个平行四边形，底 ${b} 厘米、高 ${h} 厘米，面积是多少？", "${b * h} 平方厘米",
                listOf("${num(b * h / 2.0)} 平方厘米", "${b + h} 平方厘米", "${b * h + h} 平方厘米"),
                "平行四边形面积 = 底×高 = ${b}×${h} = ${b * h}（平方厘米）。")
            else -> {
                val top = rnd.nextInt(2, b)
                val ans = num((top + b) * h / 2.0)
                Quiz("一个梯形，上底 ${top} 厘米、下底 ${b} 厘米、高 ${h} 厘米，面积是多少？",
                    "${ans} 平方厘米",
                    listOf("${(top + b) * h} 平方厘米", "${top * b} 平方厘米",
                        "${(top + b) * h / 2 + h} 平方厘米"),
                    "梯形面积 = (上底+下底)×高÷2 = (${top}+${b})×${h}÷2 = ${ans}（平方厘米）。")
            }
        }
    }

    private fun positionPair(): Quiz {
        var c = rnd.nextInt(1, 9)
        var r = rnd.nextInt(1, 9)
        if (c == r) r = (r % 8) + 1
        val name = pick(listOf("A", "B", "P", "Q"))
        return Quiz("点 ${name} 在第 ${c} 列、第 ${r} 行，用数对表示是？", "(${c}, ${r})",
            listOf("(${r}, ${c})", "(${c + 1}, ${r})", "(${c}, ${r + 1})"),
            "数对先写列、再写行，所以是 (${c}, ${r})。")
    }

    private fun plantingTrees(): Quiz {
        val gap = rnd.nextInt(2, 9)
        val sections = rnd.nextInt(3, 13)
        val length = gap * sections
        return Quiz("一条 ${length} 米长的小路，每隔 ${gap} 米栽一棵树（两端都栽），一共要栽多少棵？",
            "${sections + 1} 棵",
            listOf("${sections} 棵", "${sections - 1} 棵", "${sections + 2} 棵"),
            "间隔数 = ${length}÷${gap} = ${sections}，两端都栽时棵数 = 间隔数 + 1 = ${sections + 1}。")
    }

    // ------------------------------ 语文 ------------------------------

    private data class Poem(val line: String, val title: String, val author: String)

    private val poems = listOf(
        Poem("王师北定中原日，家祭无忘告乃翁", "《示儿》", "陆游"),
        Poem("山外青山楼外楼，西湖歌舞几时休", "《题临安邸》", "林升"),
        Poem("我劝天公重抖擞，不拘一格降人才", "《己亥杂诗》", "龚自珍"),
        Poem("举头望明月，低头思故乡", "《静夜思》", "李白"),
        Poem("春眠不觉晓，处处闻啼鸟", "《春晓》", "孟浩然"),
        Poem("欲穷千里目，更上一层楼", "《登鹳雀楼》", "王之涣"),
        Poem("谁知盘中餐，粒粒皆辛苦", "《悯农》", "李绅"),
        Poem("两个黄鹂鸣翠柳，一行白鹭上青天", "《绝句》", "杜甫"),
        Poem("飞流直下三千尺，疑是银河落九天", "《望庐山瀑布》", "李白"),
        Poem("孤舟蓑笠翁，独钓寒江雪", "《江雪》", "柳宗元"),
        Poem("儿童相见不相识，笑问客从何处来", "《回乡偶书》", "贺知章"),
        Poem("生当作人杰，死亦为鬼雄", "《夏日绝句》", "李清照"),
        Poem("但使龙城飞将在，不教胡马度阴山", "《出塞》", "王昌龄"),
        Poem("羌笛何须怨杨柳，春风不度玉门关", "《凉州词》", "王之涣"),
        Poem("两岸猿声啼不住，轻舟已过万重山", "《早发白帝城》", "李白"),
        Poem("白毛浮绿水，红掌拨清波", "《咏鹅》", "骆宾王"),
    )

    private fun poetry(): Quiz {
        val p = poems.random(rnd)
        return if (rnd.nextBoolean()) {
            val others = poems.map { it.title }.filter { it != p.title }.distinct().shuffled(rnd).take(3)
            Quiz("「${p.line}」出自哪首诗？", p.title, others,
                "这句出自${p.author}的${p.title}。")
        } else {
            val others = poems.map { it.author }.filter { it != p.author }.distinct().shuffled(rnd).take(3)
            Quiz("${p.title}的作者是谁？", p.author, others,
                "${p.title}的作者是${p.author}。")
        }
    }

    private fun rhetoric(): Quiz {
        val others = { answer: String ->
            listOf("比喻", "拟人", "夸张", "排比", "反问").filter { it != answer }.shuffled(rnd).take(3)
        }
        return when (rnd.nextInt(3)) {
            0 -> {
                val a = pick(listOf("弯弯的月亮", "天上的云朵", "平静的湖面", "闪闪的星星", "冬天的雪花"))
                val b = pick(listOf("一只小船", "一团棉花", "一面镜子", "明亮的眼睛", "洁白的羽毛"))
                Quiz("「${a} 像 ${b}」用了什么修辞？", "比喻", others("比喻"),
                    "把${a}比作${b}，是比喻。")
            }
            1 -> {
                val s = pick(listOf(
                    "小鸟在枝头欢快地唱歌", "花儿在风中点头微笑", "小溪一路唱着歌跑向远方",
                    "向日葵仰起脸庞望着太阳", "柳树姑娘在河边梳理长发",
                ))
                Quiz("「${s}」用了什么修辞？", "拟人", others("拟人"),
                    "把事物当作人来写，是拟人。")
            }
            else -> {
                val s = pick(listOf(
                    "飞流直下三千尺", "他的喊声把整栋楼都震得晃起来", "教室里静得连针掉在地上都听得见",
                    "这书包重得像装了一座山", "他一口气跑了十万八千里",
                ))
                Quiz("「${s}」用了什么修辞？", "夸张", others("夸张"),
                    "故意说得远超事实，是夸张。")
            }
        }
    }

    private val words = listOf(
        "慷慨" to "大方、不吝啬", "犹豫" to "拿不定主意", "斩钉截铁" to "说话办事坚决果断",
        "居高临下" to "从高处往下看", "聚精会神" to "注意力非常集中", "恍然大悟" to "一下子明白过来",
        "爱不释手" to "喜欢得舍不得放下", "兴高采烈" to "非常高兴", "目不转睛" to "眼睛一动不动地盯着看",
        "无微不至" to "照顾得非常周到", "栩栩如生" to "像活的一样逼真", "络绎不绝" to "来来往往连续不断",
        "驰名中外" to "国内外都很有名", "迫不及待" to "急得不能再等", "一丝不苟" to "做事非常认真细致",
        "自言自语" to "自己跟自己说话", "应接不暇" to "东西太多看不过来", "不计其数" to "数量多得数不清",
        "毫不犹豫" to "一点儿也不迟疑", "心旷神怡" to "心情舒畅、精神愉快",
    )

    private fun wordMeaning(): Quiz {
        val (word, meaning) = words.random(rnd)
        val others = words.map { it.second }.filter { it != meaning }.shuffled(rnd).take(3)
        return Quiz("「${word}」的意思最接近哪一个？", meaning, others,
            "${word} 的意思是「${meaning}」。")
    }

    private val spellings = listOf(
        "毫不犹豫" to listOf("豪不犹豫", "毫不犹预", "豪不犹预"),
        "聚精会神" to listOf("聚精汇神", "聚经会神", "聚精会申"),
        "再接再厉" to listOf("再接再励", "在接再厉", "再接再历"),
        "兴高采烈" to listOf("兴高彩烈", "兴高采列", "兴高采冽"),
        "目不转睛" to listOf("目不转晴", "目不传睛", "目不转精"),
        "迫不及待" to listOf("迫不急待", "迫不极待", "迫不急带"),
        "川流不息" to listOf("穿流不息", "川流不熄", "穿流不熄"),
        "一丝不苟" to listOf("一丝不勾", "一思不苟", "一丝不枸"),
        "恍然大悟" to listOf("晃然大悟", "恍然大误", "恍然大物"),
        "爱不释手" to listOf("爱不择手", "爱不释首", "爱不失手"),
    )

    private fun spelling(): Quiz {
        val (right, wrong) = spellings.random(rnd)
        return Quiz("下列词语中，没有错别字的一组是？", right, wrong,
            "正确写法是「${right}」。")
    }

    // ------------------------------ 英语 ------------------------------

    private data class Noun(val phrase: String, val plural: Boolean)
    private val nouns = listOf(
        Noun("a book", false), Noun("some books", true), Noun("a pen", false), Noun("some pens", true),
        Noun("an apple", false), Noun("some apples", true), Noun("a cat", false), Noun("two cats", true),
        Noun("a river", false), Noun("some rivers", true), Noun("a desk", false), Noun("two desks", true),
        Noun("some water", false), Noun("some milk", false), Noun("a bird", false), Noun("three birds", true),
    )
    private val places = listOf(
        "on the desk", "in the bag", "in the park", "near the window", "under the tree",
        "on the wall", "in the classroom", "behind the door",
    )

    private fun thereBe(): Quiz {
        val n = nouns.random(rnd)
        return if (rnd.nextBoolean()) {
            val right = if (n.plural) "are" else "is"
            Quiz("There ___ ${n.phrase} ${pick(places)}.", right,
                listOf("is", "are", "am", "be").filter { it != right },
                "there be 句型看后面的名词：${n.phrase} 是${if (n.plural) "复数" else "单数/不可数"}，用 ${right}。")
        } else {
            val q = "— ${if (n.plural) "Are" else "Is"} there ${n.phrase} ${pick(places)}?  — Yes, ___."
            val right = if (n.plural) "there are" else "there is"
            Quiz(q, right, listOf("there is", "there are", "it is", "they are").filter { it != right },
                "Is/Are there...? 的肯定回答是 Yes, ${right}.")
        }
    }

    private data class Verb(val base: String, val third: String, val ing: String)
    private val verbs = listOf(
        Verb("sing", "sings", "singing"), Verb("dance", "dances", "dancing"),
        Verb("swim", "swims", "swimming"), Verb("draw", "draws", "drawing"),
        Verb("cook", "cooks", "cooking"), Verb("play", "plays", "playing"),
        Verb("ride", "rides", "riding"), Verb("read", "reads", "reading"),
        Verb("write", "writes", "writing"), Verb("clean", "cleans", "cleaning"),
    )
    private val subjects = listOf("I", "She", "He", "They", "My sister", "Tom")
    private val objects = listOf(
        "English songs", "cartoons", "the pipa", "basketball", "a bike", "a story", "the room",
    )

    private fun modalCan(): Quiz {
        val v = verbs.random(rnd)
        return if (rnd.nextBoolean()) {
            Quiz("${pick(subjects)} can ___ ${pick(objects)}.", v.base,
                listOf(v.third, v.ing, "to ${v.base}"),
                "情态动词 can 后面接动词原形 ${v.base}。")
        } else {
            Quiz("— Can you ${v.base}?  — No, I ___.", "can't",
                listOf("don't", "isn't", "am not"),
                "Can you...? 的否定回答是 No, I can't.")
        }
    }

    private val drinks = listOf("water", "milk", "juice", "tea")
    private val foods = listOf("noodles", "rice", "bread", "chicken", "fish", "sandwiches")

    private fun foodPreference(): Quiz {
        return when (rnd.nextInt(3)) {
            0 -> Quiz("I'd like some ___. I'm thirsty.", drinks.random(rnd),
                foods.shuffled(rnd).take(3), "thirsty 是渴了，要选喝的东西。")
            1 -> Quiz("— What's your favourite food?  — ___",
                "My favourite food is ${foods.random(rnd)}.",
                listOf("I am fine.", "It is Monday.", "Yes, I can."),
                "问最喜欢的食物，回答要说食物。")
            else -> Quiz("— What would you like to ___?  — Some milk, please.", "drink",
                listOf("eat", "play", "read"), "milk 是喝的，用 drink。")
        }
    }

    private val vocab = listOf(
        "vegetable" to "蔬菜", "sandwich" to "三明治", "hamburger" to "汉堡包", "ice cream" to "冰激凌",
        "noodles" to "面条", "polite" to "有礼貌的", "helpful" to "乐于助人的", "clever" to "聪明的",
        "shy" to "害羞的", "weekend" to "周末", "homework" to "作业", "forest" to "森林",
        "river" to "河流", "bridge" to "桥", "building" to "建筑物", "bottle" to "瓶子",
        "clock" to "时钟", "plant" to "植物", "photo" to "照片", "bike" to "自行车",
        "teacher" to "老师", "doctor" to "医生", "Monday" to "星期一", "young" to "年轻的",
    )

    private fun vocabulary(): Quiz {
        val (en, cn) = vocab.random(rnd)
        return if (rnd.nextBoolean()) {
            Quiz("下列哪个单词是「${cn}」？", en,
                vocab.map { it.first }.filter { it != en }.shuffled(rnd).take(3),
                "${en} = ${cn}。")
        } else {
            Quiz("「${en}」的中文意思是？", cn,
                vocab.map { it.second }.filter { it != cn }.shuffled(rnd).take(3),
                "${en} = ${cn}。")
        }
    }

    private data class Quiz(
        val stem: String, val right: String, val wrongs: List<String>, val explanation: String,
    )
}
