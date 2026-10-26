package me.lvca.alba.engine.model

/**
 * Every burst in the library: its photos and its key photo, found over the whole library so a burst's size and key
 * photo stay the same whichever view or search shows it.
 */
data class BurstIndex(
    /** The ids of each burst's photos, by burst id, in library order. Only bursts of two or more are listed. */
    val members: Map<String, List<String>>,
    /** Each burst's key photo: the one iCloud lists in its burst index ([SmartCollection.BURSTS]), else the first. */
    val keys: Map<String, String>,
) {
    fun size(burstId: String?): Int = burstId?.let(members::get)?.size ?: 0

    companion object {
        val EMPTY = BurstIndex(emptyMap(), emptyMap())

        /** Indexes the bursts in [library], leaving out photos in the trash. */
        fun of(library: List<GalleryMedia>): BurstIndex {
            val members = LinkedHashMap<String, MutableList<String>>()
            val indexed = HashMap<String, String>()
            for (item in library) {
                val burst = item.details.burstId?.takeUnless { item.isTrashed } ?: continue
                members.getOrPut(burst, ::mutableListOf) += item.id
                if (SmartCollection.BURSTS.id in item.collectionIds) indexed.putIfAbsent(burst, item.id)
            }
            members.values.removeAll { it.size < 2 }
            if (members.isEmpty()) return EMPTY
            return BurstIndex(members, members.mapValues { (burst, ids) -> indexed[burst]?.takeIf { it in ids } ?: ids.first() })
        }
    }
}

/**
 * Bursts shown the way Photos shows them: one photo stands for the whole burst, and the others stay out of the grid
 * until the user opens the burst.
 */
object BurstStacks {
    /**
     * Collapses each burst in [items] to one photo, except the bursts in [expanded]: the key photo when [items] holds
     * it, else the first of the burst's photos that [items] holds. That photo carries the burst's size in
     * [GalleryMedia.burstCount]. Order is kept.
     */
    fun stack(items: List<GalleryMedia>, index: BurstIndex, expanded: Set<String> = emptySet()): List<GalleryMedia> {
        if (index.members.isEmpty()) return items
        val present = HashSet<String>(items.size).apply { items.forEach { add(it.id) } }
        val shown = HashMap<String, String>()
        for ((burst, key) in index.keys) {
            if (burst in expanded) continue
            (key.takeIf { it in present } ?: index.members.getValue(burst).firstOrNull { it in present })?.let { shown[burst] = it }
        }
        return items.mapNotNull { item ->
            val burst = item.details.burstId?.takeIf { it in index.members && it !in expanded } ?: return@mapNotNull item
            if (shown[burst] == item.id) item.copy(burstCount = index.size(burst)) else null
        }
    }
}
