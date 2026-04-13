package com.druk.lmplayground.tools

interface Tool {
    val name: String
    val description: String
    val parametersSchema: String
    fun execute(arguments: String): String
}
