package com.sr.niftyseller.data

import java.io.BufferedReader
import java.io.Reader

/** RFC-4180-style streaming reader; handles escaped quotes, commas and CRLF without loading a whole Bhavcopy. */
object CsvReader {
    fun rows(reader: Reader): Sequence<List<String>> = sequence {
        val input = BufferedReader(reader)
        val row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var pending = -1
        while (true) {
            val code = if (pending >= 0) pending.also { pending = -1 } else input.read()
            if (code == -1) break
            val char = code.toChar()
            when {
                quoted && char == '"' -> {
                    val next = input.read()
                    if (next == '"'.code) field.append('"') else {
                        quoted = false
                        if (next >= 0) pending = next
                    }
                }
                char == '"' && field.isEmpty() -> quoted = true
                char == ',' && !quoted -> { row.add(field.toString()); field.setLength(0) }
                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r') {
                        val next = input.read()
                        if (next != '\n'.code && next >= 0) pending = next
                    }
                    row.add(field.toString())
                    field.setLength(0)
                    yield(row.toList())
                    row.clear()
                }
                else -> field.append(char)
            }
        }
        if (quoted) throw IllegalArgumentException("CSV ends inside a quoted field")
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            yield(row.toList())
        }
    }

    fun headerIndex(header: List<String>): Map<String, Int> = header
        .mapIndexed { index, value -> normalize(value) to index }
        .toMap()

    fun normalize(value: String): String = value.trim().removePrefix("\uFEFF")
        .lowercase().filter(Char::isLetterOrDigit)
}
