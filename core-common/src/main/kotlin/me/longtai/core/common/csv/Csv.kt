package me.longtai.core.common.csv

/** RFC 4180 CSV reading and writing. */
object Csv {

    class ParseException(message: String, val line: Int) : RuntimeException("line $line: $message")

    /**
     * Parses CSV text into rows of fields. Handles quoted fields, escaped quotes,
     * embedded newlines, CRLF/LF line endings and a leading UTF-8 BOM.
     * Completely empty lines are skipped.
     */
    fun parse(text: String, delimiter: Char = ','): List<List<String>> {
        val input = text.removePrefix("﻿")
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var fieldWasQuoted = false
        var line = 1
        var i = 0

        fun endField() {
            row.add(field.toString())
            field.setLength(0)
            fieldWasQuoted = false
        }

        fun endRow() {
            endField()
            if (!(row.size == 1 && row[0].isEmpty())) rows.add(row)
            row = mutableListOf()
        }

        while (i < input.length) {
            val c = input[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < input.length && input[i + 1] == '"' -> {
                        field.append('"')
                        i++
                    }
                    c == '"' -> inQuotes = false
                    else -> {
                        if (c == '\n') line++
                        field.append(c)
                    }
                }
            } else {
                when (c) {
                    '"' -> {
                        if (field.isNotEmpty() || fieldWasQuoted) throw ParseException("unexpected quote", line)
                        inQuotes = true
                        fieldWasQuoted = true
                    }
                    delimiter -> endField()
                    '\r' -> {
                        if (i + 1 < input.length && input[i + 1] == '\n') i++
                        endRow()
                        line++
                    }
                    '\n' -> {
                        endRow()
                        line++
                    }
                    else -> {
                        if (fieldWasQuoted) throw ParseException("characters after closing quote", line)
                        field.append(c)
                    }
                }
            }
            i++
        }
        if (inQuotes) throw ParseException("unterminated quoted field", line)
        if (field.isNotEmpty() || row.isNotEmpty() || fieldWasQuoted) endRow()
        return rows
    }

    /**
     * Writes rows as CSV with CRLF line endings. Text cells that a spreadsheet would
     * interpret as a formula are prefixed with an apostrophe (CSV injection guard).
     */
    fun write(rows: List<List<String>>, delimiter: Char = ','): String {
        val sb = StringBuilder()
        for (row in rows) {
            row.forEachIndexed { index, value ->
                if (index > 0) sb.append(delimiter)
                sb.append(escape(sanitize(value), delimiter))
            }
            sb.append("\r\n")
        }
        return sb.toString()
    }

    internal fun sanitize(value: String): String {
        if (value.isEmpty()) return value
        val first = value[0]
        val dangerous = first == '=' || first == '+' || first == '@' || first == '\t' || first == '\r' ||
            (first == '-' && value.drop(1).toBigDecimalOrNull() == null)
        return if (dangerous) "'$value" else value
    }

    private fun escape(value: String, delimiter: Char): String {
        val needsQuotes = value.any { it == delimiter || it == '"' || it == '\n' || it == '\r' } ||
            value.startsWith(' ') || value.endsWith(' ')
        return if (needsQuotes) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }
}

/**
 * Header-aware view over parsed CSV rows. Column lookup is case-insensitive and ignores
 * surrounding whitespace, so exported spreadsheets that were edited by hand still import.
 */
class CsvTable(rows: List<List<String>>) {
    val header: List<String> = rows.firstOrNull()?.map { it.trim().lowercase() } ?: emptyList()
    val records: List<Record> = rows.drop(1).mapIndexed { index, cells -> Record(index + 2, cells) }

    fun hasColumn(name: String) = header.contains(name.lowercase())

    fun missingColumns(required: List<String>): List<String> = required.filterNot { hasColumn(it) }

    inner class Record(val lineNumber: Int, private val cells: List<String>) {
        operator fun get(column: String): String? {
            val index = header.indexOf(column.lowercase())
            if (index < 0 || index >= cells.size) return null
            return cells[index].trim().removePrefix("'").ifEmpty { null }
        }
    }
}
