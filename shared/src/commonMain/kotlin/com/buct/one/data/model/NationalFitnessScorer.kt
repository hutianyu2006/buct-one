package com.buct.one.data.model

/**
 * 《国家学生体质健康标准（2014年修订）》普通高校本科组离线评分计算器。
 * 支持男生 / 女生、大一/大二 与 大三/大四两组标准及附加分计算。
 * 严禁新增任何 import，保持 model 包零依赖门禁。
 */
enum class FitnessGender(val label: String) {
    MALE("男生"),
    FEMALE("女生")
}

enum class FitnessGradeGroup(val label: String) {
    LOWER("大一/大二"),
    UPPER("大三/大四")
}

data class FitnessSimulationInput(
    val gender: FitnessGender = FitnessGender.MALE,
    val gradeGroup: FitnessGradeGroup = FitnessGradeGroup.LOWER,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val lungMl: Int? = null,
    val sprint50Sec: Double? = null,
    val sitReachCm: Double? = null,
    val jumpCm: Int? = null,
    val strengthCount: Int? = null,
    val enduranceSec: Int? = null
)

data class FitnessItemEval(
    val key: String,
    val name: String,
    val weightPercent: Int,
    val score: Int,
    val bonus: Int,
    val level: String,
    val nextTargetHint: String
)

data class FitnessSimulationResult(
    val bmi: Double?,
    val standardTotal: Double,
    val bonusTotal: Int,
    val finalTotal: Double,
    val level: String,
    val items: List<FitnessItemEval>
)

object NationalFitnessScorer {

    private val NUMBER_REGEX = Regex("[-+]?\\d+(?:\\.\\d+)?")
    private val COLON_TIME_REGEX = Regex("^(\\d+)\\s*[:'分：′]\\s*(\\d{1,2})[\"秒”]?$")
    private val DOT_TIME_REGEX = Regex("^(\\d+)\\.(\\d{1,2})$")
    private val PURE_MIN_REGEX = Regex("^(\\d+)\\s*分$")

    private val maleLungLower = listOf(
        100 to 5040, 95 to 4920, 90 to 4800, 85 to 4550, 80 to 4300,
        78 to 4180, 76 to 4060, 74 to 3940, 72 to 3820, 70 to 3700,
        68 to 3580, 66 to 3460, 64 to 3340, 62 to 3220, 60 to 3100,
        50 to 2940, 40 to 2780, 30 to 2620, 20 to 2460, 10 to 2300
    )
    private val maleLungUpper = listOf(
        100 to 5140, 95 to 5020, 90 to 4900, 85 to 4650, 80 to 4400,
        78 to 4280, 76 to 4160, 74 to 4040, 72 to 3920, 70 to 3800,
        68 to 3680, 66 to 3560, 64 to 3440, 62 to 3320, 60 to 3200,
        50 to 3030, 40 to 2860, 30 to 2690, 20 to 2520, 10 to 2350
    )
    private val femaleLungLower = listOf(
        100 to 3400, 95 to 3350, 90 to 3300, 85 to 3150, 80 to 3000,
        78 to 2900, 76 to 2800, 74 to 2700, 72 to 2600, 70 to 2500,
        68 to 2400, 66 to 2300, 64 to 2200, 62 to 2100, 60 to 2000,
        50 to 1960, 40 to 1920, 30 to 1880, 20 to 1840, 10 to 1800
    )
    private val femaleLungUpper = femaleLungLower.map { it.first to (it.second + 50) }

    private val maleSprint50Lower = listOf(
        100 to 6.7, 95 to 6.8, 90 to 6.9, 85 to 7.0, 80 to 7.1,
        78 to 7.3, 76 to 7.5, 74 to 7.7, 72 to 7.9, 70 to 8.1,
        68 to 8.3, 66 to 8.5, 64 to 8.7, 62 to 8.9, 60 to 9.1,
        50 to 9.3, 40 to 9.5, 30 to 9.7, 20 to 9.9, 10 to 10.1
    )
    private val maleSprint50Upper = listOf(
        100 to 6.6, 95 to 6.7, 90 to 6.8, 85 to 6.9, 80 to 7.0,
        78 to 7.2, 76 to 7.4, 74 to 7.6, 72 to 7.8, 70 to 8.0,
        68 to 8.2, 66 to 8.4, 64 to 8.6, 62 to 8.8, 60 to 9.0,
        50 to 9.2, 40 to 9.4, 30 to 9.6, 20 to 9.8, 10 to 10.0
    )
    private val femaleSprint50Lower = listOf(
        100 to 7.5, 95 to 7.6, 90 to 7.7, 85 to 8.0, 80 to 8.3,
        78 to 8.5, 76 to 8.7, 74 to 8.9, 72 to 9.1, 70 to 9.3,
        68 to 9.5, 66 to 9.7, 64 to 9.9, 62 to 10.1, 60 to 10.3,
        50 to 10.5, 40 to 10.7, 30 to 10.9, 20 to 11.1, 10 to 11.3
    )
    private val femaleSprint50Upper = listOf(
        100 to 7.4, 95 to 7.5, 90 to 7.6, 85 to 7.9, 80 to 8.2,
        78 to 8.4, 76 to 8.6, 74 to 8.8, 72 to 9.0, 70 to 9.2,
        68 to 9.4, 66 to 9.6, 64 to 9.8, 62 to 10.0, 60 to 10.2,
        50 to 10.4, 40 to 10.6, 30 to 10.8, 20 to 11.0, 10 to 11.2
    )

    private val maleSitReachLower = listOf(
        100 to 24.9, 95 to 23.1, 90 to 21.3, 85 to 19.5, 80 to 17.7,
        78 to 16.3, 76 to 14.9, 74 to 13.5, 72 to 12.1, 70 to 10.7,
        68 to 9.3, 66 to 7.9, 64 to 6.5, 62 to 5.1, 60 to 3.7,
        50 to 2.7, 40 to 1.7, 30 to 0.7, 20 to -0.3, 10 to -1.3
    )
    private val maleSitReachUpper = listOf(
        100 to 25.1, 95 to 23.3, 90 to 21.5, 85 to 19.9, 80 to 18.2,
        78 to 16.8, 76 to 15.4, 74 to 14.0, 72 to 12.6, 70 to 11.2,
        68 to 9.8, 66 to 8.4, 64 to 7.0, 62 to 5.6, 60 to 4.2,
        50 to 3.2, 40 to 2.2, 30 to 1.2, 20 to 0.2, 10 to -0.8
    )
    private val femaleSitReachLower = listOf(
        100 to 25.8, 95 to 24.0, 90 to 22.2, 85 to 20.6, 80 to 19.0,
        78 to 17.7, 76 to 16.4, 74 to 15.1, 72 to 13.8, 70 to 12.5,
        68 to 11.2, 66 to 9.9, 64 to 8.6, 62 to 7.3, 60 to 6.0,
        50 to 5.2, 40 to 4.4, 30 to 3.6, 20 to 2.8, 10 to 2.0
    )
    private val femaleSitReachUpper = listOf(
        100 to 26.3, 95 to 24.4, 90 to 22.4, 85 to 21.0, 80 to 19.5,
        78 to 18.2, 76 to 16.9, 74 to 15.6, 72 to 14.3, 70 to 13.0,
        68 to 11.7, 66 to 10.4, 64 to 9.1, 62 to 7.8, 60 to 6.5,
        50 to 5.7, 40 to 4.9, 30 to 4.1, 20 to 3.3, 10 to 2.5
    )

    private val maleJumpLower = listOf(
        100 to 273, 95 to 268, 90 to 263, 85 to 256, 80 to 248,
        78 to 244, 76 to 240, 74 to 236, 72 to 232, 70 to 228,
        68 to 224, 66 to 220, 64 to 216, 62 to 212, 60 to 208,
        50 to 203, 40 to 198, 30 to 193, 20 to 188, 10 to 183
    )
    private val maleJumpUpper = maleJumpLower.map { it.first to (it.second + 2) }

    private val femaleJumpLower = listOf(
        100 to 207, 95 to 201, 90 to 195, 85 to 188, 80 to 181,
        78 to 178, 76 to 175, 74 to 172, 72 to 169, 70 to 166,
        68 to 163, 66 to 160, 64 to 157, 62 to 154, 60 to 151,
        50 to 146, 40 to 141, 30 to 136, 20 to 131, 10 to 126
    )
    private val femaleJumpUpper = femaleJumpLower.map { it.first to (it.second + 1) }

    private val malePullUpLower = listOf(
        100 to 19, 95 to 18, 90 to 17, 85 to 16, 80 to 15,
        76 to 14, 72 to 13, 68 to 12, 64 to 11, 60 to 10,
        50 to 9, 40 to 8, 30 to 7, 20 to 6, 10 to 5
    )
    private val malePullUpUpper = malePullUpLower.map { it.first to (it.second + 1) }

    private val femaleSitUpLower = listOf(
        100 to 56, 95 to 54, 90 to 52, 85 to 49, 80 to 46,
        78 to 44, 76 to 42, 74 to 40, 72 to 38, 70 to 36,
        68 to 34, 66 to 32, 64 to 30, 62 to 28, 60 to 26,
        50 to 24, 40 to 22, 30 to 20, 20 to 18, 10 to 16
    )
    private val femaleSitUpUpper = femaleSitUpLower.map { it.first to (it.second + 1) }

    private val femaleSitUpBonusAbove100 = listOf(
        10 to 13, 9 to 12, 8 to 11, 7 to 10, 6 to 9,
        5 to 8, 4 to 7, 3 to 6, 2 to 4, 1 to 2
    )

    private val male1000mLowerSec = listOf(
        100 to 197, 95 to 202, 90 to 207, 85 to 214, 80 to 222,
        78 to 227, 76 to 232, 74 to 237, 72 to 242, 70 to 247,
        68 to 252, 66 to 257, 64 to 262, 62 to 267, 60 to 272,
        50 to 292, 40 to 312, 30 to 332, 20 to 352, 10 to 372
    )
    private val male1000mUpperSec = male1000mLowerSec.map { it.first to (it.second - 2) }

    private val male1000mBonusFasterSec = listOf(
        10 to 35, 9 to 32, 8 to 29, 7 to 26, 6 to 23,
        5 to 20, 4 to 16, 3 to 12, 2 to 8, 1 to 4
    )

    private val female800mLowerSec = listOf(
        100 to 198, 95 to 204, 90 to 210, 85 to 217, 80 to 224,
        78 to 229, 76 to 234, 74 to 239, 72 to 244, 70 to 249,
        68 to 254, 66 to 259, 64 to 264, 62 to 269, 60 to 274,
        50 to 284, 40 to 294, 30 to 304, 20 to 314, 10 to 324
    )
    private val female800mUpperSec = female800mLowerSec.map { it.first to (it.second - 2) }

    private val female800mBonusFasterSec = listOf(
        10 to 50, 9 to 45, 8 to 40, 7 to 35, 6 to 30,
        5 to 25, 4 to 20, 3 to 15, 2 to 10, 1 to 5
    )

    fun extractDouble(raw: String): Double? {
        val m = NUMBER_REGEX.find(raw.trim()) ?: return null
        return m.value.toDoubleOrNull()
    }

    fun extractHeightCm(raw: String): Double? {
        val trimmed = raw.trim()
        val v = extractDouble(trimmed) ?: return null
        return if (trimmed.contains("米") && !trimmed.contains("厘米") && v < 3.0) {
            v * 100.0
        } else {
            v
        }
    }

    fun parseEnduranceToSeconds(raw: String): Int? {
        val normalized = raw.trim()
            .replace("′", "'")
            .replace("”", "\"")
            .replace("：", ":")
            .trim()
        if (normalized.isEmpty()) return null

        val dotCandidate = if (normalized.endsWith("分")) normalized.dropLast(1).trim() else normalized
        DOT_TIME_REGEX.matchEntire(dotCandidate)?.let { m ->
            val min = m.groupValues[1].toIntOrNull() ?: return null
            val frac = m.groupValues[2]
            val sec = if (frac.length == 1) frac.toInt() * 10 else frac.toInt()
            if (sec >= 60) return null
            return min * 60 + sec
        }

        PURE_MIN_REGEX.matchEntire(normalized)?.let { m ->
            val min = m.groupValues[1].toIntOrNull() ?: return null
            return min * 60
        }

        val colonCandidate = normalized
            .replace("分", ":")
            .replace("秒", "")
            .trim()
        COLON_TIME_REGEX.matchEntire(colonCandidate)?.let { m ->
            val min = m.groupValues[1].toIntOrNull() ?: return null
            val sec = m.groupValues[2].toIntOrNull() ?: return null
            if (sec >= 60) return null
            return min * 60 + sec
        }

        val asInt = normalized.toIntOrNull()
        if (asInt != null && asInt >= 100) return asInt
        return null
    }

    /** 模型层保持零 import：数字补零与取整全部用 stdlib 全限定调用完成。 */
    private fun padTwo(value: Int): String = if (value < 10) "0$value" else value.toString()

    fun formatSecondsToDot(sec: Int): String {
        val s = sec.coerceAtLeast(0)
        return "${s / 60}.${padTwo(s % 60)}"
    }

    fun formatSecondsToClock(sec: Int): String {
        val s = sec.coerceAtLeast(0)
        return "${s / 60}'${padTwo(s % 60)}\""
    }

    fun detectGender(genderText: String): FitnessGender =
        if (genderText.contains("女")) FitnessGender.FEMALE else FitnessGender.MALE

    fun detectGradeGroup(gradeText: String): FitnessGradeGroup {
        val g = gradeText.trim()
        return if (g.contains("大三") || g.contains("大四") || g.contains("三") || g.contains("四") || g.contains("研")) {
            FitnessGradeGroup.UPPER
        } else {
            FitnessGradeGroup.LOWER
        }
    }

    fun inputFromRecord(profile: TyglProfile, record: TyglFitnessRecord): FitnessSimulationInput {
        val gender = detectGender(profile.gender)
        val grade = detectGradeGroup(record.gradeName.ifBlank { profile.gradeName })
        val heightCm = extractHeightCm(record.heightText)
        val weightKg = extractDouble(record.weightText)

        fun findItem(vararg keys: String): TyglFitnessItem? =
            record.items.firstOrNull { item -> keys.any { item.name.contains(it) } }

        val lungMl = findItem("肺活量")?.let { extractDouble(it.rawResult)?.let { v -> kotlin.math.round(v).toInt() } }
        val sprint50 = findItem("50米")?.let { extractDouble(it.rawResult) }
        val sitReach = findItem("体前屈")?.let { extractDouble(it.rawResult) }
        val jumpCm = findItem("立定跳远")?.let { extractDouble(it.rawResult)?.let { v -> kotlin.math.round(v).toInt() } }
        val strength = findItem("引体", "仰卧起坐")?.let { extractDouble(it.rawResult)?.let { v -> kotlin.math.round(v).toInt() } }
        val enduranceSec = findItem("1000米", "800米")?.let { parseEnduranceToSeconds(it.rawResult) }

        return FitnessSimulationInput(
            gender = gender,
            gradeGroup = grade,
            heightCm = heightCm,
            weightKg = weightKg,
            lungMl = lungMl,
            sprint50Sec = sprint50,
            sitReachCm = sitReach,
            jumpCm = jumpCm,
            strengthCount = strength,
            enduranceSec = enduranceSec
        )
    }

    fun evaluate(input: FitnessSimulationInput): FitnessSimulationResult {
        val isMale = input.gender == FitnessGender.MALE
        val isUpper = input.gradeGroup == FitnessGradeGroup.UPPER
        val eps = 1e-9

        // 1. BMI (15%)
        val rawBmi = if (input.heightCm != null && input.heightCm > 30.0 && input.weightKg != null && input.weightKg > 10.0) {
            val hM = input.heightCm / 100.0
            round1(input.weightKg / (hM * hM))
        } else {
            null
        }
        val bmiNormalLow = if (isMale) 17.9 else 17.2
        val (bmiScore, bmiLevel, bmiHint) = if (rawBmi == null) {
            Triple(0, "未填", "填写身高和体重自动计算")
        } else {
            when {
                rawBmi in bmiNormalLow..23.9 -> Triple(100, "正常", "保持在 $bmiNormalLow~23.9 即可满分")
                rawBmi < bmiNormalLow -> Triple(80, "低体重", "增至 $bmiNormalLow 以上可达满分")
                rawBmi <= 27.9 -> Triple(80, "超重", "降至 23.9 以下可达满分")
                else -> Triple(60, "肥胖", "降至 27.9 以下可达 80 分")
            }
        }

        // 2. Lung capacity (15%)
        val lungTable = when {
            isMale && !isUpper -> maleLungLower
            isMale && isUpper -> maleLungUpper
            !isMale && !isUpper -> femaleLungLower
            else -> femaleLungUpper
        }
        val (lungScore, lungHint) = evalHigherInt(input.lungMl, lungTable, "ml")

        // 3. 50m sprint (20%)
        val sprintTable = when {
            isMale && !isUpper -> maleSprint50Lower
            isMale && isUpper -> maleSprint50Upper
            !isMale && !isUpper -> femaleSprint50Lower
            else -> femaleSprint50Upper
        }
        val (sprintScore, sprintHint) = evalLowerDouble(input.sprint50Sec, sprintTable, "秒", eps)

        // 4. Sit and reach (10%)
        val sitReachTable = when {
            isMale && !isUpper -> maleSitReachLower
            isMale && isUpper -> maleSitReachUpper
            !isMale && !isUpper -> femaleSitReachLower
            else -> femaleSitReachUpper
        }
        val (sitReachScore, sitReachHint) = evalHigherDouble(input.sitReachCm, sitReachTable, "cm", eps)

        // 5. Standing long jump (10%)
        val jumpTable = when {
            isMale && !isUpper -> maleJumpLower
            isMale && isUpper -> maleJumpUpper
            !isMale && !isUpper -> femaleJumpLower
            else -> femaleJumpUpper
        }
        val (jumpScore, jumpHint) = evalHigherInt(input.jumpCm, jumpTable, "cm")

        // 6. Strength (10% + max 10 bonus)
        val strengthName = if (isMale) "引体向上" else "1分钟仰卧起坐"
        val strengthTable = when {
            isMale && !isUpper -> malePullUpLower
            isMale && isUpper -> malePullUpUpper
            !isMale && !isUpper -> femaleSitUpLower
            else -> femaleSitUpUpper
        }
        val (strengthScore, baseStrengthHint) = evalHigherInt(input.strengthCount, strengthTable, "个")
        val strengthBase100 = strengthTable.first().second
        val strengthBonus = when {
            input.strengthCount == null -> 0
            isMale -> (input.strengthCount - strengthBase100).coerceIn(0, 10)
            else -> {
                val extra = input.strengthCount - strengthBase100
                if (extra <= 0) 0 else femaleSitUpBonusAbove100.firstOrNull { extra >= it.second }?.first ?: 0
            }
        }
        val strengthHint = if (input.strengthCount != null && strengthScore >= 100 && strengthBonus < 10) {
            if (isMale) {
                "已获加分 $strengthBonus/10 · 再多 1 个加 1 分"
            } else {
                val nextBonus = femaleSitUpBonusAbove100.lastOrNull { it.first > strengthBonus }
                if (nextBonus != null) {
                    val need = strengthBase100 + nextBonus.second
                    "已获加分 $strengthBonus/10 · $need 个可加 ${nextBonus.first} 分"
                } else {
                    "已获加分 $strengthBonus/10"
                }
            }
        } else if (strengthBonus >= 10) {
            "标准满分 + 加分封顶 10 分"
        } else {
            baseStrengthHint
        }

        // 7. Endurance run (20% + max 10 bonus)
        val enduranceName = if (isMale) "1000米跑" else "800米跑"
        val enduranceTable = when {
            isMale && !isUpper -> male1000mLowerSec
            isMale && isUpper -> male1000mUpperSec
            !isMale && !isUpper -> female800mLowerSec
            else -> female800mUpperSec
        }
        val enduranceBonusTable = if (isMale) male1000mBonusFasterSec else female800mBonusFasterSec
        val (enduranceScore, baseEnduranceHint) = evalLowerSeconds(input.enduranceSec, enduranceTable)
        val enduranceBase100 = enduranceTable.first().second
        val enduranceBonus = if (input.enduranceSec != null && input.enduranceSec < enduranceBase100) {
            val faster = enduranceBase100 - input.enduranceSec
            enduranceBonusTable.firstOrNull { faster >= it.second }?.first ?: 0
        } else {
            0
        }
        val enduranceHint = if (input.enduranceSec != null && enduranceScore >= 100 && enduranceBonus < 10) {
            val nextBonus = enduranceBonusTable.lastOrNull { it.first > enduranceBonus }
            if (nextBonus != null) {
                val targetSec = enduranceBase100 - nextBonus.second
                "已获加分 $enduranceBonus/10 · 跑进 ${formatSecondsToClock(targetSec)} 可加 ${nextBonus.first} 分"
            } else {
                "已获加分 $enduranceBonus/10"
            }
        } else if (enduranceBonus >= 10) {
            "标准满分 + 加分封顶 10 分"
        } else {
            baseEnduranceHint
        }

        val items = listOf(
            FitnessItemEval("bmi", "体重指数(BMI)", 15, bmiScore, 0, bmiLevel, bmiHint),
            FitnessItemEval("lung", "肺活量", 15, lungScore, 0, if (input.lungMl == null) "未填" else scoreToLevel(lungScore), lungHint),
            FitnessItemEval("sprint50", "50米跑", 20, sprintScore, 0, if (input.sprint50Sec == null) "未填" else scoreToLevel(sprintScore), sprintHint),
            FitnessItemEval("sitReach", "坐位体前屈", 10, sitReachScore, 0, if (input.sitReachCm == null) "未填" else scoreToLevel(sitReachScore), sitReachHint),
            FitnessItemEval("jump", "立定跳远", 10, jumpScore, 0, if (input.jumpCm == null) "未填" else scoreToLevel(jumpScore), jumpHint),
            FitnessItemEval("strength", strengthName, 10, strengthScore, strengthBonus, if (input.strengthCount == null) "未填" else scoreToLevel(strengthScore), strengthHint),
            FitnessItemEval("endurance", enduranceName, 20, enduranceScore, enduranceBonus, if (input.enduranceSec == null) "未填" else scoreToLevel(enduranceScore), enduranceHint)
        )

        val standardTotal = round1(items.sumOf { it.score * it.weightPercent / 100.0 })
        val bonusTotal = strengthBonus + enduranceBonus
        val finalTotal = round1(standardTotal + bonusTotal)
        val totalLevel = scoreToLevel(finalTotal)

        return FitnessSimulationResult(
            bmi = rawBmi,
            standardTotal = standardTotal,
            bonusTotal = bonusTotal,
            finalTotal = finalTotal,
            level = totalLevel,
            items = items
        )
    }

    private fun evalHigherInt(value: Int?, table: List<Pair<Int, Int>>, unit: String): Pair<Int, String> {
        if (value == null) return 0 to "待填写"
        val score = table.firstOrNull { value >= it.second }?.first ?: 0
        if (score >= 100) return 100 to "已达标准满分 (${table.first().second}$unit)"
        val passThreshold = table.firstOrNull { it.first == 60 }?.second
        if (score < 60 && passThreshold != null) {
            return score to "距及格(60分)还差 ${passThreshold - value}$unit"
        }
        val next = table.lastOrNull { it.first > score }
        return if (next != null) {
            score to "距 ${next.first}分(${next.second}$unit) 还差 ${next.second - value}$unit"
        } else {
            score to "标准分满分"
        }
    }

    private fun evalHigherDouble(value: Double?, table: List<Pair<Int, Double>>, unit: String, eps: Double): Pair<Int, String> {
        if (value == null) return 0 to "待填写"
        val score = table.firstOrNull { value + eps >= it.second }?.first ?: 0
        if (score >= 100) return 100 to "已达标准满分 (${formatOneDecimal(table.first().second)}$unit)"
        val passThreshold = table.firstOrNull { it.first == 60 }?.second
        if (score < 60 && passThreshold != null) {
            val diff = formatOneDecimal(passThreshold - value)
            return score to "距及格(60分)还差 ${diff}$unit"
        }
        val next = table.lastOrNull { it.first > score }
        return if (next != null) {
            val diff = formatOneDecimal(next.second - value)
            score to "距 ${next.first}分(${formatOneDecimal(next.second)}$unit) 还差 ${diff}$unit"
        } else {
            score to "标准分满分"
        }
    }

    private fun evalLowerDouble(value: Double?, table: List<Pair<Int, Double>>, unit: String, eps: Double): Pair<Int, String> {
        if (value == null || value <= 0.0) return 0 to "待填写"
        val score = table.firstOrNull { value <= it.second + eps }?.first ?: 0
        if (score >= 100) return 100 to "已达标准满分 (${formatOneDecimal(table.first().second)}$unit)"
        val passThreshold = table.firstOrNull { it.first == 60 }?.second
        if (score < 60 && passThreshold != null) {
            val diff = formatOneDecimal(value - passThreshold)
            return score to "距及格(60分)需快 ${diff}$unit (≤${formatOneDecimal(passThreshold)}$unit)"
        }
        val next = table.lastOrNull { it.first > score }
        return if (next != null) {
            val diff = formatOneDecimal(value - next.second)
            score to "距 ${next.first}分(${formatOneDecimal(next.second)}$unit) 需快 ${diff}$unit"
        } else {
            score to "标准分满分"
        }
    }

    private fun evalLowerSeconds(sec: Int?, table: List<Pair<Int, Int>>): Pair<Int, String> {
        if (sec == null || sec <= 0) return 0 to "按 M.SS 或 M'SS\" 输入"
        val score = table.firstOrNull { sec <= it.second }?.first ?: 0
        if (score >= 100) return 100 to "已达标准满分 (${formatSecondsToClock(table.first().second)})"
        val passSec = table.firstOrNull { it.first == 60 }?.second
        if (score < 60 && passSec != null) {
            return score to "距及格(60分 ${formatSecondsToClock(passSec)})需快 ${sec - passSec}秒"
        }
        val next = table.lastOrNull { it.first > score }
        return if (next != null) {
            score to "距 ${next.first}分(${formatSecondsToClock(next.second)}) 需快 ${sec - next.second}秒"
        } else {
            score to "标准分满分"
        }
    }

    private fun scoreToLevel(score: Int): String = when {
        score >= 90 -> "优秀"
        score >= 80 -> "良好"
        score >= 60 -> "及格"
        else -> "不及格"
    }

    private fun scoreToLevel(score: Double): String = when {
        score + 1e-9 >= 90.0 -> "优秀"
        score + 1e-9 >= 80.0 -> "良好"
        score + 1e-9 >= 60.0 -> "及格"
        else -> "不及格"
    }

    private fun round1(v: Double): Double = kotlin.math.round(v * 10.0) / 10.0

    private fun formatOneDecimal(v: Double): String {
        val scaled = kotlin.math.round(round1(v) * 10.0).toLong()
        val whole = scaled / 10L
        val fraction = kotlin.math.abs(scaled % 10L)
        return "$whole.$fraction"
    }
}
