package org.javacc.test.calcite

import org.apache.calcite.avatica.util.Casing
import org.apache.calcite.sql.validate.SqlConformanceEnum
import org.junit.jupiter.api.Test
import java.lang.AssertionError
import java.util.*
import java.util.function.Consumer

class CalciteFuzzer {
    @Test
    internal fun name() {
        val r = Random()
        val res = mutableListOf<String>()
        var bestLen = 0
        for (i in 1..1000000) {
            try {
                res.clear()
                val parser = SqlParserImplFuzzyParser(r)
                parser.setQuotedCasing(Casing.UNCHANGED)
                parser.setUnquotedCasing(Casing.TO_UPPER)
                parser.setConformance(SqlConformanceEnum.ORACLE_12)
                parser.setIdentifierMaxLength(64)
                parser.tokenOutput = Consumer<Token> { res.add(it.image) }
                parser.SqlSelect()
                if (bestLen<res.size) {
                    bestLen = res.size
                    println(bestLen)
                    println(res.joinToString(" "))
                    println()
                }
            } catch(e: AssertionError) {
                println(e.message)
                println(e.stackTrace.take(2))
            } catch (e: Exception) {
                println(e.message)
                println(e.stackTrace
                    .asSequence()
                    .take(35)
                    .filter { it.className.startsWith("org.javacc") }
                    .take(8)
                    .joinToString("\n\tat "))
            }
//            println(fuzzer.jj_generate_S_IDENTIFIER_144())
        }
    }
}
