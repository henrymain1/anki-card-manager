package com.borderless.ankicards.ui.decks

import com.borderless.ankicards.data.anki.AnkiDroidRepository.DeckInfo

/**
 * One node in the deck hierarchy. AnkiDroid encodes nesting in the deck name
 * with `::` separators — e.g. `cantonese::Animals::Birds` is three nested
 * levels. We parse that here so the UI can render a proper tree instead of
 * a flat list of mangled names.
 *
 * @param displayName  Just the last `::` segment (the name shown at this level).
 * @param fullName     The original `::`-joined path. This is what AnkiDroid
 *                     stores and what gets written to settings when selected.
 * @param id           The deck id, or null if this node is a synthetic parent
 *                     (an intermediate path that doesn't itself exist as a
 *                     real deck — uncommon but possible). Synthetic parents
 *                     can be expanded but not selected.
 * @param depth        How deep we are (0 = top-level). Used by the UI to
 *                     indent rows; precomputed here so the renderer is dumb.
 * @param children     Direct children, sorted by display name.
 */
data class DeckNode(
    val displayName: String,
    val fullName: String,
    val id: Long?,
    val depth: Int,
    val children: List<DeckNode>
) {
    val isLeaf: Boolean get() = children.isEmpty()
    val isSynthetic: Boolean get() = id == null
}

/**
 * Build a [DeckNode] forest from a flat list of decks. Nodes are sorted
 * case-insensitively by their display name at every level. Intermediate paths
 * that don't correspond to a real [DeckInfo] are inserted as synthetic
 * parents so deeply-nested subdecks still render under something.
 */
fun buildDeckTree(decks: List<DeckInfo>): List<DeckNode> {
    if (decks.isEmpty()) return emptyList()

    // Walk every deck and accumulate path -> info, creating intermediate
    // entries as we go.
    val pathToId = HashMap<String, Long>()
    val allPaths = LinkedHashSet<String>()
    decks.forEach { d ->
        val parts = d.name.split("::")
        // Register every prefix path so synthetic parents get created later.
        val acc = StringBuilder()
        parts.forEachIndexed { i, part ->
            if (i > 0) acc.append("::")
            acc.append(part)
            allPaths += acc.toString()
        }
        pathToId[d.name] = d.id
    }

    // Group paths by parent.
    val childrenOf = HashMap<String, MutableList<String>>()
    val roots = mutableListOf<String>()
    allPaths.forEach { path ->
        val sep = path.lastIndexOf("::")
        if (sep < 0) {
            roots += path
        } else {
            val parent = path.substring(0, sep)
            childrenOf.getOrPut(parent) { mutableListOf() } += path
        }
    }

    fun toNode(path: String, depth: Int): DeckNode {
        val display = path.substringAfterLast("::")
        val rawKids = childrenOf[path].orEmpty()
            .sortedBy { it.substringAfterLast("::").lowercase() }
        return DeckNode(
            displayName = display,
            fullName = path,
            id = pathToId[path],
            depth = depth,
            children = rawKids.map { toNode(it, depth + 1) }
        )
    }

    return roots
        .sortedBy { it.lowercase() }
        .map { toNode(it, depth = 0) }
}

/**
 * Flatten a [DeckNode] forest into the rows the LazyColumn actually renders,
 * respecting which nodes the user has expanded. A node's children only appear
 * if its [DeckNode.fullName] is in [expanded].
 */
fun flattenVisible(tree: List<DeckNode>, expanded: Set<String>): List<DeckNode> {
    val out = mutableListOf<DeckNode>()
    fun walk(node: DeckNode) {
        out += node
        if (node.fullName in expanded) {
            node.children.forEach(::walk)
        }
    }
    tree.forEach(::walk)
    return out
}
