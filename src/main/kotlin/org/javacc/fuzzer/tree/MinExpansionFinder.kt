package org.javacc.fuzzer.tree

import org.javacc.parser.*

class MinExpansionFinder(productions: Iterable<NormalProduction>) {
    private val productionWeight = mutableMapOf<NormalProduction, Int>()
    private val expansionWeight = mutableMapOf<Expansion, Int>()

    private val activeProductions = mutableSetOf<NormalProduction>()

    init {
        productions.forEach { visit(it) }
    }

    fun weight(p: NormalProduction) = productionWeight[p]
    fun weight(e: Expansion) = expansionWeight[e]

    private fun visit(e: Expansion): Int {
        return expansionWeight.getOrPut(e) {
            when (e) {
                is NonTerminal -> visit(e.prod)
                is RegularExpression -> 1
                is Action -> 1
                is Choice -> e.choices.map { visit(it as Expansion) }.min() ?: 0
                is Sequence -> e.units.drop(1).map { visit(it as Expansion) }.sum()
                is OneOrMore -> visit(e.expansion)
                is ZeroOrOne -> 0
                is ZeroOrMore -> 0
                else -> TODO("Unsupported expansion $e")
            }
        }
    }

    private fun visit(p: NormalProduction): Int {
        if (!activeProductions.add(p)) {
            return Int.MAX_VALUE
        }
        productionWeight[p]?.let { return it }
        val res = when (p) {
            is BNFProduction -> visit(p.expansion)
            is JavaCodeProduction -> 1
            else -> TODO("Unsupported production $p")
        }

        activeProductions -= p
        productionWeight[p] = res
        return res
    }


}
