package com.druk.lmplayground.tools

import org.json.JSONObject
import kotlin.math.pow

class CalculatorTool : Tool {
    override val name = "calculate"
    override val description = "Evaluate a mathematical expression and return the numeric result. Supports +, -, *, /, ^, %, and parentheses."
    override val parametersSchema = """{"type":"object","properties":{"expression":{"type":"string","description":"The mathematical expression to evaluate, e.g. '(2 + 3) * 4'"}},"required":["expression"]}"""

    override fun execute(arguments: String): String {
        return try {
            val json = JSONObject(arguments)
            val expression = json.getString("expression")
            val result = evaluate(expression)
            // Format: avoid trailing .0 for integer results
            val formatted = if (result == result.toLong().toDouble()) {
                result.toLong().toString()
            } else {
                result.toBigDecimal().stripTrailingZeros().toPlainString()
            }
            """{"result":$formatted,"expression":"$expression"}"""
        } catch (e: Exception) {
            """{"error":"${e.message?.replace("\"", "'")}"}"""
        }
    }

    private fun evaluate(expression: String): Double {
        return Parser(expression.trim()).parseExpression()
    }

    private class Parser(private val input: String) {
        private var pos = 0

        fun parseExpression(): Double {
            var result = parseTerm()
            while (pos < input.length) {
                skipSpaces()
                if (pos >= input.length) break
                when (input[pos]) {
                    '+' -> { pos++; result += parseTerm() }
                    '-' -> { pos++; result -= parseTerm() }
                    else -> break
                }
            }
            return result
        }

        private fun parseTerm(): Double {
            var result = parsePower()
            while (pos < input.length) {
                skipSpaces()
                if (pos >= input.length) break
                when (input[pos]) {
                    '*' -> { pos++; result *= parsePower() }
                    '/' -> { pos++; val d = parsePower(); result /= d }
                    '%' -> { pos++; result %= parsePower() }
                    else -> break
                }
            }
            return result
        }

        private fun parsePower(): Double {
            var result = parseUnary()
            while (pos < input.length) {
                skipSpaces()
                if (pos >= input.length || input[pos] != '^') break
                pos++
                result = result.pow(parseUnary())
            }
            return result
        }

        private fun parseUnary(): Double {
            skipSpaces()
            if (pos >= input.length) throw IllegalArgumentException("Unexpected end of expression")
            if (input[pos] == '-') {
                pos++
                return -parseUnary()
            }
            if (input[pos] == '+') {
                pos++
                return parseUnary()
            }
            return parsePrimary()
        }

        private fun parsePrimary(): Double {
            skipSpaces()
            if (pos >= input.length) throw IllegalArgumentException("Unexpected end of expression")
            if (input[pos] == '(') {
                pos++
                val result = parseExpression()
                skipSpaces()
                if (pos < input.length && input[pos] == ')') pos++
                return result
            }
            return parseNumber()
        }

        private fun parseNumber(): Double {
            skipSpaces()
            val start = pos
            if (pos < input.length && (input[pos] == '-' || input[pos] == '+')) pos++
            while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) pos++
            // Handle scientific notation
            if (pos < input.length && (input[pos] == 'e' || input[pos] == 'E')) {
                pos++
                if (pos < input.length && (input[pos] == '+' || input[pos] == '-')) pos++
                while (pos < input.length && input[pos].isDigit()) pos++
            }
            if (pos == start) throw IllegalArgumentException("Expected number at position $pos")
            return input.substring(start, pos).toDouble()
        }

        private fun skipSpaces() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }
    }
}
