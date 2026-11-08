package io.legado.app.data.repository

import io.legado.app.domain.model.CacheMaintenanceRules
import io.legado.app.utils.FileDoc

/**
 * 书架「按字数」「按大小」排序的公共取值逻辑。
 *
 * Book.wordCount 是展示文本（如 "12.34万字"、"5000字"，也可能是书源原样文本），
 * 不是数值，需解析后再比较；无法解析或无数据的书统一视为缺失。
 * 大小排序只针对本地书：books 表没有大小列，排序时取本地原文件的字节数。
 * 网络书没有本地文件，一律视为缺失并沉底。
 */
object BookSort {

    const val WORD_COUNT = 6
    const val SIZE = 7

    /**
     * 复用缓存维护里已有的解析器，避免两套口径漂移。
     * 本地书和网络书只要写入了字数字段，都能参与排序。
     */
    fun wordCountOf(text: String?): Long? =
        CacheMaintenanceRules.parseStoredWordCount(text)?.toLong()

    /**
     * 本地书的原文件字节数；网络书返回 null（排序时统一沉底）。
     */
    fun sizeBytes(bookUrl: String, isLocal: Boolean): Long? {
        if (!isLocal) return null
        val size = try {
            FileDoc.fromFile(bookUrl).size
        } catch (e: Exception) {
            0L
        }
        return size.takeIf { it > 0 }
    }

    /**
     * 缺失值（null）始终排在末尾，与升序降序无关。
     * selector 在比较前按元素预计算一次，避免比较器里做文件 IO。
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
