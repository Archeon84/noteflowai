package com.noteflowai.app.data

/**
 * Generic undo/redo manager with a bounded history stack.
 *
 * @param T The type of state being managed (e.g., String for note text, List<String> for file names).
 * @param maxHistory Maximum number of undo entries (default 50).
 */
class UndoManager<T>(
    private val maxHistory: Int = 50
) {
    private val undoStack = ArrayDeque<T>(maxHistory + 1)
    private val redoStack = ArrayDeque<T>(maxHistory)

    /** Push current state onto the undo stack and clear redo history. */
    fun record(state: T) {
        undoStack.addLast(state)
        if (undoStack.size > maxHistory) {
            undoStack.removeFirst()
        }
        redoStack.clear()
    }

    /** Undo: pop from undo stack, push current to redo, return previous state. Returns null if nothing to undo. */
    fun undo(currentState: T): T? {
        if (undoStack.isEmpty()) return null
        redoStack.addLast(currentState)
        return undoStack.removeLast()
    }

    /** Redo: pop from redo stack, push current to undo, return next state. Returns null if nothing to redo. */
    fun redo(currentState: T): T? {
        if (redoStack.isEmpty()) return null
        undoStack.addLast(currentState)
        return redoStack.removeLast()
    }

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
