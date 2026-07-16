package ru.asop.common.util

object InnValidator {
    private val WEIGHTS_10 = intArrayOf(2, 4, 10, 3, 5, 9, 4, 6, 8)
    private val WEIGHTS_12_1 = intArrayOf(7, 2, 4, 10, 3, 5, 9, 4, 6, 8)
    private val WEIGHTS_12_2 = intArrayOf(3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8)

    fun isValid(inn: String?): Boolean {
        if (inn == null) return false
        if (!inn.matches(Regex("^\\d{10}$|^\\d{12}$"))) return false

        return when (inn.length) {
            10 -> validate10(inn)
            12 -> validate12(inn)
            else -> false
        }
    }

    private fun validate10(inn: String): Boolean {
        val checkSum = WEIGHTS_10.mapIndexed { i, w -> w * inn[i].digitToInt() }
            .sum() % 11 % 10
        return checkSum == inn[9].digitToInt()
    }

    private fun validate12(inn: String): Boolean {
        val checkSum1 = WEIGHTS_12_1.mapIndexed { i, w -> w * inn[i].digitToInt() }
            .sum() % 11 % 10
        val checkSum2 = WEIGHTS_12_2.mapIndexed { i, w -> w * inn[i].digitToInt() }
            .sum() % 11 % 10
        return checkSum1 == inn[10].digitToInt() && checkSum2 == inn[11].digitToInt()
    }
}