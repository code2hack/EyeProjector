package android.util
class SparseArray<T>(map: Map<Int, T>) {
    private val entries = map.entries.toList()
    fun size() = entries.size
    fun keyAt(index: Int) = entries[index].key
    fun valueAt(index: Int) = entries[index].value
}
