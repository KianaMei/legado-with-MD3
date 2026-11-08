package io.legado.app.data.repository

import io.legado.app.constant.AppPattern
import io.legado.app.help.book.BookHelp
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import java.io.File

/**
 * 书架「按字数」「按大小」排序的公共取值逻辑。
 *
 * Book.wordCount 是展示文本（如 "12.3万字"、"5000字"，也可能是书源原样文本），
 * 不是数值，因此需要解析后再比较；无法解析或无数据的书统一视为缺失。
 * books 表没有大小列，大小在排序时按本地原文件 / 已缓存章节目录实时计算。
 */
object BookSort {

    const val WORD_COUNT = 6
    const val SIZE = 7

    private val wordCountPattern = Regex(
        """^\s*([\d,]+(?:\.\d+)?)\s*(亿|万|千|百)?\s*字?\s*$"""
    )
    private val unitPattern = Regex("""([\d,]+(?:\.\d+)?)\s*(亿|万|千|百)""")
    private val groupedNumberPattern = Regex("""\d,\d{3}""")

    /**
     * 解析展示用字数文本，返回字数；缺失或无法解析返回 null。
     */
    fun parseWordCount(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()
        val normalized = if (groupedNumberPattern.containsMatchIn(trimmed)) {
            trimmed.replace(",", "")
        } else {
            trimmed
        }
        wordCountPattern.matchEntire(normalized)?.let { match ->
            return toWords(match.groupValues[1], match.groupValues[2])
        }
        // 兼容书源返回的 "共 12.3 万字" 等带前后缀文本
        return unitPattern.findAll(normalized)
            .mapNotNull { toWords(it.groupValues[1], it.groupValues[2]) }
            .maxOrNull()
    }

    private fun toWords(number: String, unit: String): Long? {
        val value = number.toDoubleOrNull() ?: return null
        val multiplier = when (unit) {
            "亿" -> 100_000_000.0
            "万" -> 10_000.0
            "千" -> 1_000.0
            "百" -> 100.0
            else -> 1.0
        }
        return (value * multiplier).toLong().takeIf { it > 0 }
    }

    /**
     * 书籍占用字节数：本地书取原文件大小，其余取已缓存章节目录大小。
     * 无数据返回 null（排序时统一沉底）。
     */
    fun sizeBytes(bookUrl: String, name: String, isLocal: Boolean): Long? {
        if (isLocal) {
            val size = try {
                FileDoc.fromFile(bookUrl).size
            } catch (e: Exception) {
                0L
            }
            return size.takeIf { it > 0 }
        }
        // 与 Book.getFolderNameNoCache() 保持一致
        val cleanName = name.replace(AppPattern.fileNameRegex, "")
        val folderName = cleanName.substring(0, minOf(9, cleanName.length)) +
                MD5Utils.md5Encode16(bookUrl)
        val cacheDir = File(FileUtils.getPath(BookHelp.cachePath, folderName))
        return directorySize(cacheDir).takeIf { it > 0 }
    }

    private fun directorySize(dir: File): Long {
        if (!dir.exists() || !dir.isDirectory) return 0L
        val children = dir.listFiles() ?: return 0L
        var total = 0L
        for (child in children) {
            total += if (child.isDirectory) directorySize(child) else child.length()
        }
        return total
    }

    /**
     * 缺失值（null）始终排在末尾，与升序降序无关。
     * selector 会在比较前按元素预计算一次（避免比较器里做文件 IO）。
     */
    fun <T : Any> compareByValue(
        list: List<T>,
        descending: Boolean,
        selector: (T) -> Long?
    ): List<T> {
        val values = list.map(selector)
        val indices = list.indices.sortedWith(Comparator { i1, i2 ->
            val v1 = values[i1]
            val v2 = values[i2]
            when {
                v1 == null && v2 == null -> i1.compareTo(i2)
                v1 == null -> 1
                v2 == null -> -1
                else -> {
                    val compare = if (descending) v2.compareTo(v1) else v1.compareTo(v2)
                    if (compare == 0) i1.compareTo(i2) else compare
                }
            }
        })
        return indices.map { list[it] }
    }
}
