package com.noteflowai.app.data.search

import java.util.concurrent.ConcurrentHashMap

/**
 * Manages in-memory dismissed suggestions to ensure user-dismissed notes and recommendations
 * are not re-surfaced during the active editing session.
 */
class SuggestionDismissalManager {

    private val dismissedSet = ConcurrentHashMap.newKeySet<String>()

    fun dismiss(id: String) {
        dismissedSet.add(id)
    }

    fun isDismissed(id: String): Boolean {
        return dismissedSet.contains(id)
    }

    fun <T> filterActive(items: List<T>, idSelector: (T) -> String): List<T> {
        return items.filterNot { isDismissed(idSelector(it)) }
    }

    fun clear() {
        dismissedSet.clear()
    }
}
