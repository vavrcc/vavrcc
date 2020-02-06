package org.javacc.fuzzer

import com.grosner.kpoet.*
import com.squareup.javapoet.*
import org.javacc.fuzzer.tree.MinExpansionFinder
import org.javacc.parser.*
import java.io.Reader
import java.util.*
import java.util.concurrent.Callable
import java.util.function.Consumer
import javax.tools.JavaFileObject

class FuzzParserGenerator(
    val inputFiles: List<JavaFileObject>,
    val outputFiles: MutableList<JavaFileObject>,
    val config: JavaCCConfig,
    val lexer: FuzzLexGenerator
) : Callable<Boolean> {
    private val fuzzParserClassName = config.parserClassName + "FuzzyParser"

    private val jj_depth = "jj_depth"

    lateinit var token_source: FieldSpec
    lateinit var jj_random: FieldSpec
    lateinit var token: FieldSpec
    lateinit var tokenOutput: FieldSpec
    lateinit var jj_tokenCount: FieldSpec
    lateinit var jj_depthVar: FieldSpec
    lateinit var jj_consumeToken: MethodSpec
    lateinit var getToken: MethodSpec

    private val nameAllocator = NameAllocator()

    private val depthVars = mutableMapOf<MethodSpec.Builder, FieldSpec>()

    private val minWeights = MinExpansionFinder(config.bnfproductions)

    private fun MethodSpec.Builder.phase1ExpansionGen(sequence: Sequence) {
        for (i in 1 until sequence.units.size) {
            phase1ExpansionGen(sequence.units[i] as Expansion)
        }
    }

    private fun MethodSpec.Builder.phase1ExpansionGen(action: Action) {
        addCode("$[$L\n$]", action.actionTokens.asString())
    }

    private fun MethodSpec.Builder.phase1ExpansionGen(regex: RegularExpression) {
        if (regex.ordinal == 0) {
            comment("EOF")
            return
        }
        addCode("$[")
        regex.lhsTokens.takeIf { it.isNotEmpty() }?.let {
            addCode("$L = ", it.asString())
        }
        val label = regex.label.ifEmpty {
            JavaCCGlobals.names_of_tokens[regex.ordinal] ?: regex.ordinal.toString()
        }
        addCode(
            "$N($N)$L;\n$]",
            jj_consumeToken,
            label,
            regex.rhsToken?.let { "." + it.asString } ?: ""
        )
    }

    private fun MethodSpec.Builder.phase1ExpansionGenLoop(
        nested: Expansion,
        min: Int,
        max: Int
    ) {
        val lookahead = if (nested is Sequence) {
            nested.lookahead
        } else {
            Lookahead().apply {
                amount = Options.getLookahead()
                laExpansion = nested
            }
        }
        nameAllocator.allocate("jj_cnt") { jj_cnt ->
            val body: MethodSpec.Builder.() -> Unit = {
                phase1ExpansionGen(nested)
                // TODO: implement Lookahead check for $L", lookahead
            }
            if (min == 0 && max == 0) {
                // ignore
            } else if (min == 1 && max == 1) {
                // exactly once
                body()
            } else if (min == 0 && max == 1) {
                // zero or one
                `if`({ "${jj_random.N}.nextBoolean()" }, body).end()
            } else {
                `for`({
                    val i = jj_cnt.N
                    "int $i = ${min.L} + ${jj_random.N}.nextInt(${(max - min + 1).L})/${jj_depth.N}; $i > 0 && ${jj_tokenCount.N} < 1000; $i--"
                }, body)
            }
        }
    }

    /**
     * See [org.javacc.parser.ParseEngine.phase1ExpansionGen]
     */
    private fun MethodSpec.Builder.phase1ExpansionGen(p: NonTerminal) {
        addCode("$[")
        p.lhsTokens.takeIf { it.isNotEmpty() }?.let {
            addCode("$L = ", it.asString())
        }
        addCode(
            "$N($L);\n$]",
            p.name,
            p.argumentTokens.asString()
        )
    }

    /**
     * See [org.javacc.parser.ParseEngine.phase1ExpansionGen]
     */
    private fun MethodSpec.Builder.phase1ExpansionGen(p: Choice) {
        if (p.choices.isEmpty()) {
            comment("Empty choice")
            return
        }
        if (p.choices.size == 1) {
            phase1ExpansionGen(p.choices.first() as Expansion)
            return
        }
        // TODO: support lookahead
        nameAllocator.allocate("idx") { idx ->
            addCode("{$>")
            statement("int $N", idx)
            val nonRecursiveChoices = p.choices.withIndex()
                .filter { minWeights.weight(it.value as Expansion) != Int.MAX_VALUE }
            if (nonRecursiveChoices.size == p.choices.size) {
                statement { "${idx.N} = ${jj_random.N}.nextInt(${p.choices.size.L})" }
            } else {
                `if`("$N < 1000", jj_tokenCount) {
                    statement { "${idx.N} = ${jj_random.N}.nextInt(${p.choices.size.L})" }
                } `else` {
                    switch("$N.nextInt($L)", jj_random, nonRecursiveChoices.size) {
                        for ((index, choice) in nonRecursiveChoices.map { it.index }.withIndex()) {
                            case(L, index) {
                                statement { "${idx.N} = ${choice.L}" }
                                `break`
                            }
                        }
                        default {
                            statement { "${idx.N} = 0" }
                        }
                    }
                }
            }
            switch(N, idx) {
                // switch("$N.nextInt($L)", jj_random, p.choices.size) {
                for ((index, choice) in p.choices.withIndex()) {
                    if (index == p.choices.size - 1) {
                        default {
                            phase1ExpansionGen(choice as Expansion)
                        }
                    } else {
                        case(L, index) {
                            phase1ExpansionGen(choice as Expansion)
                            `break`
                        }
                    }
                }
            }
            addCode("$<}\n")
        }
    }

    /**
     * See [org.javacc.parser.ParseEngine.phase1ExpansionGen]
     */
    private fun MethodSpec.Builder.phase1ExpansionGen(expansion: Expansion) {
        when (expansion) {
            is RegularExpression -> phase1ExpansionGen(expansion)
            is NonTerminal -> phase1ExpansionGen(expansion)
            is Action -> phase1ExpansionGen(expansion)
            is Choice -> phase1ExpansionGen(expansion)
            is Sequence -> phase1ExpansionGen(expansion)
            is OneOrMore -> phase1ExpansionGenLoop(expansion.expansion, 1, 1)
            is ZeroOrOne -> phase1ExpansionGenLoop(expansion.expansion, 0, 1)
            is ZeroOrMore -> phase1ExpansionGenLoop(expansion.expansion, 0, 1)
            // is TryBlock -> phase1ExpansionGenLoop(expansion)
            else -> TODO(expansion.toString())
        }
    }

    /**
     * See [org.javacc.parser.ParseEngine.buildPhase1Routine]
     */
    private fun TypeSpec.Builder.buildPhase1Routine(p: BNFProduction) {
        val returnType = p.returnTypeTokens.asString()
        val params = CodeBlock.of(L, p.parameterListTokens.asString())
        val depth = `private field`(Int::class, "${jj_depth}_${p.lhs}")
        `public`(returnType.T, name = p.lhs, opaqueParams = params) {
            addException(config.parseExceptionTypeName)
            depthVars[this] = depth
            p.declarationTokens.takeIf { it.isNotEmpty() }?.also {
                addCode("$L\n", it.asString())
            }
            statement { "int ${jj_depth.N} = ++${depth.N}" }
            `try` {
                phase1ExpansionGen(p.expansion)
            } `finally` {
                addStatement("$N--", depth)
            }
            if (p.returnTypeTokens.first().kind != JavaCCParserConstants.VOID) {
                `throw new`(Error::class, S, "Missing return statement")
            }
            depthVars.remove(this)
        }
    }

    private fun TypeSpec.Builder.copyJavaCode(p: JavaCodeProduction) {
        val returnType = p.returnTypeTokens.asString()
        val params = CodeBlock.of(L, p.parameterListTokens.asString())
        `public`(returnType.T, name = p.lhs, opaqueParams = params) {
            addException(config.parseExceptionTypeName)
            addCode(L, p.codeTokens.asString())
        }
    }

    override fun call(): Boolean {
        val fuzzerFile = javaFile(
            config.packageName,
            imports = {
                for (import in config.parserImports) {
                    addImport(import.asString())
                }
                for (import in config.parserStaticImports) {
                    addStaticImport(import.asString())
                }
                addStaticImport(config.parserConstantsClassName, "*")
            }
        ) {
            `class`(fuzzParserClassName) {
                modifiers(public)
                val extends = JavaCCGlobals.cu_to_insertion_point_1
                    .asSequence()
                    .dropWhile { it.kind != JavaCCParserConstants.EXTENDS && it.kind != JavaCCParserConstants.IMPLEMENTS }
                    .asIterable()
                    .asString()
                if (extends.isNotEmpty()) {
                    opaqueSuper(codeBlock { " " + extends.L })
                }
                val parserBody = JavaCCGlobals.cu_to_insertion_point_2
                    .asSequence()
                    .dropWhile { it.image != "{" }
                    .drop(1)
                    .asIterable()
                    .asString()
                addBody("$L\n", parserBody)
                addBody("SimpleCharStream jj_input_stream = new SimpleCharStream((java.io.Reader) null); // unused\n")
                jj_random = `private field`(Random::class, "jj_random")
                token_source = `private field`(lexer.typeName, "token_source") // name is API
                token = `public field`(config.tokenTypeName, "token") {
                    `=`("new $T()", config.tokenTypeName)
                }
                jj_tokenCount = `private field`(Int::class, "jj_tokenCount")
                tokenOutput = `private field`(Consumer::class.parameterized(config.tokenTypeName), "tokenOutput")

                constructor(param(Random::class, "random")) {
                    addStatement("this.$N = random", jj_random)
                    addStatement("this.$N = new $T(random)", token_source, lexer.typeName)
                }

                public(TypeName.VOID, "ReInit", param(Reader::class, "reader")) {
                    `throw new`(UnsupportedOperationException::class, S, "Not implemented yet")
                }

                public(TypeName.VOID, "set${tokenOutput.name.capitalize()}", param(tokenOutput.type, "value")) {
                    addStatement("this.$N = value", tokenOutput)
                }

                public(tokenOutput.type, "get${tokenOutput.name.capitalize()}") {
                    `return`(N, tokenOutput)
                }

                jj_consumeToken = protected(config.tokenTypeName, "jj_consumeToken", param(config.tokenKindTypeName, "kind")) {
                    addJavadoc(
                        """
                            Generates the next token with given kind.
                            @throws IllegalArgumentException when the current token kind does not match the provided argument
                        """.trimIndent()
                    )
                    code {
                        """
                        ${config.tokenTypeName.T} oldToken = ${token.N};
                        ${config.tokenTypeName.T} nextToken = oldToken.next;
                        if (nextToken == null) {
                          nextToken = ${token_source.N}.${lexer.generateMethodName.N}(kind);
                          ${jj_tokenCount.N}++;
                          if (${tokenOutput.N} != null) {
                              ${tokenOutput.N}.accept(nextToken);
                          }
                        }
                        ${token.N} = nextToken;
                        oldToken.next = null; // Stop nepotism
                        return nextToken;

                        """.trimIndent()
                    }

//                    statement("$T oldToken = $N", config.tokenTypeName, token)
//                    statement { "${config.tokenTypeName.T} oldToken = ${token.N}" }
//                    addStatement("$T nextToken = oldToken.next", config.tokenTypeName)
//                    `if`("nextToken == null") {
//                        addStatement("nextToken = $N.$N(kind)", tokenSource, lexer.generateMethodName)
//                        `if`("$N != null", tokenOutput) {
//                            addStatement("$N.accept(nextToken)", tokenOutput)
//                        }.`end if`()
//                    }.`end if`()
//                    addStatement("$N = nextToken", token)
//                    addStatement("oldToken.next = null; // Stop nepotism")
//                    `return`("nextToken")
                }

                getToken = public(config.tokenTypeName, "getToken", param(Int::class, "offset")) {
                    addModifiers(final)
                    code {
                        """
                        ${config.tokenTypeName.T} t = token;
                        for(int i = 0; i < offset; i++) {
                          if (t.next != null) {
                            t = t.next;
                          } else {
                            throw new IllegalArgumentException("Lookahead is not implemented yet");
                          }
                        }
                        return t;
                        """.trimIndent()
                    }
                }

                for (production in config.bnfproductions) {
                    when (production) {
                        is BNFProduction -> buildPhase1Routine(production)
                        is JavaCodeProduction -> copyJavaCode(production)
                        else -> throw IllegalArgumentException("Unsupported production: $production")
                    }
                }
            }
        }

        outputFiles += fuzzerFile.toJavaFileObject()

        return true
    }
}
