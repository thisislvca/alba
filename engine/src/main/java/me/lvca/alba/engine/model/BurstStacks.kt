package me.lvca.alba.engine.model

/**
 * Bursts shown the way Photos shows them: one photo stands for the whole burst, and the others stay out of the grid
 * until the user opens the burst.
 */
data class BurstStacks(
    /** The items to show, with each collapsed burst reduced to its key photo, in the original order. */
    val items: List<GalleryMedia>,
    /** How many of the given items belong to each burst, by burst id. Only bursts of two or more are listed. */
    val sizes: Map<String, Int>,
) {
    companion object {
        /**
         * Collapses each burst in [items] to one key photo, except the bursts in [expanded]. The key photo is the one
         * iCloud lists in its burst index ([SmartCollection.BURSTS]), or else the first one in [items].
         */
        fun stack(items: List<GalleryMedia>, expanded: Set<String> = emptySet()): BurstStacks {
            val sizes = HashMap<String, Int>()
            for (item in items) item.details.burstId?.let { sizes[it] = (sizes[it] ?: 0) + 1 }
            sizes.values.removeAll { it < 2 }
            if (sizes.isEmpty()) return BurstStacks(items, emptyMap())
            val keys = HashMap<String, String>()
            val indexed = HashMap<String, String>()
            for (item in items) {
                val burst = item.details.burstId?.takeIf { it in sizes } ?: continue
                keys.putIfAbsent(burst, item.id)
                if (SmartCollection.BURSTS.id in item.collectionIds) indexed.putIfAbsent(burst, item.id)
            }
            keys.putAll(indexed)
            val shown = items.filter { item ->
                val burst = item.details.burstId
                burst == null || burst !in sizes || burst in expanded || keys[burst] == item.id
            }
            return BurstStacks(shown, sizes)
        }
    }
}
