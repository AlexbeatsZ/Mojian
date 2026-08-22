package com.meta.inklab

import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

object XlsxPreviewParser {
    private const val MAX_ROWS = 12
    private const val MAX_COLUMNS = 8
    private const val MAX_SHARED_STRINGS = 20_000

    fun preview(file: File): MutableList<MutableList<String>> {
        return when (file.extension.lowercase()) {
            "csv", "tsv" -> previewDelimited(file, if (file.extension.equals("tsv", true)) '\t' else ',')
            "xlsx", "xlsm" -> previewXlsx(file)
            else -> mutableListOf(mutableListOf("此格式由外部表格应用打开"))
        }
    }

    private fun previewDelimited(file: File, separator: Char): MutableList<MutableList<String>> =
        file.useLines(Charsets.UTF_8) { lines ->
            lines.take(MAX_ROWS).map { line ->
                parseDelimitedLine(line, separator).take(MAX_COLUMNS).map(::compact).toMutableList()
            }.toMutableList()
        }

    private fun previewXlsx(file: File): MutableList<MutableList<String>> =
        ZipFile(file).use { zip ->
            val strings =
                zip.getEntry("xl/sharedStrings.xml")?.let { entry ->
                    zip.getInputStream(entry).use { input ->
                        val document = documentBuilderFactory().newDocumentBuilder().parse(input)
                        val nodes = document.getElementsByTagName("si")
                        MutableList(minOf(nodes.length, MAX_SHARED_STRINGS)) { index ->
                            val element = nodes.item(index) as Element
                            compact(element.getElementsByTagName("t").let { texts ->
                                buildString { for (textIndex in 0 until texts.length) append(texts.item(textIndex).textContent) }
                            })
                        }
                    }
                }.orEmpty()
            val sheetEntry = zip.getEntry("xl/worksheets/sheet1.xml") ?: return@use mutableListOf(mutableListOf("工作簿没有可预览的首张工作表"))
            zip.getInputStream(sheetEntry).use { input ->
                val document = documentBuilderFactory().newDocumentBuilder().parse(input)
                val rowNodes = document.getElementsByTagName("row")
                val result = mutableListOf<MutableList<String>>()
                for (rowIndex in 0 until minOf(rowNodes.length, MAX_ROWS)) {
                    val row = MutableList(MAX_COLUMNS) { "" }
                    val cells = (rowNodes.item(rowIndex) as Element).getElementsByTagName("c")
                    for (cellIndex in 0 until cells.length) {
                        val cell = cells.item(cellIndex) as Element
                        val column = columnIndex(cell.getAttribute("r"))
                        if (column !in 0 until MAX_COLUMNS) continue
                        val type = cell.getAttribute("t")
                        val raw =
                            when (type) {
                                "inlineStr" -> cell.getElementsByTagName("t").item(0)?.textContent.orEmpty()
                                else -> cell.getElementsByTagName("v").item(0)?.textContent.orEmpty()
                            }
                        row[column] =
                            if (type == "s") strings.getOrNull(raw.toIntOrNull() ?: -1).orEmpty()
                            else compact(raw)
                    }
                    result += row
                }
                if (result.isEmpty()) mutableListOf(mutableListOf("首张工作表为空")) else trimTrailingEmptyColumns(result)
            }
        }

    private fun documentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            setExpandEntityReferences(false)
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }

    private fun columnIndex(reference: String): Int {
        var result = 0
        var found = false
        for (character in reference) {
            if (!character.isLetter()) break
            found = true
            result = result * 26 + (character.uppercaseChar() - 'A' + 1)
        }
        return if (found) result - 1 else -1
    }

    private fun trimTrailingEmptyColumns(rows: MutableList<MutableList<String>>): MutableList<MutableList<String>> {
        var last = rows.maxOfOrNull { row -> row.indexOfLast(String::isNotEmpty) } ?: 0
        last = last.coerceAtLeast(0)
        return rows.map { it.take(last + 1).toMutableList() }.toMutableList()
    }

    private fun parseDelimitedLine(line: String, separator: Char): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val character = line[index]
            when {
                character == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                character == '"' -> quoted = !quoted
                character == separator && !quoted -> {
                    cells += current.toString()
                    current.clear()
                }
                else -> current.append(character)
            }
            index++
        }
        cells += current.toString()
        return cells
    }

    private fun compact(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(80)
}

