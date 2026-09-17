package dev.droiduse.agent

/** Clipboard operations require an isolated per-session clipboard at the backend. */
enum class EditorOperation(val actionName: String) {
    SELECT("edit_select"), DELETE("edit_delete"), SELECT_ALL("edit_select_all"),
    COPY("edit_copy"), CUT("edit_cut"), PASTE("edit_paste"), UNDO("edit_undo"), REDO("edit_redo"),
    SEARCH("ime_search"), NEXT("ime_next"), DONE("ime_done"), SEND("ime_send"), HIDE("ime_hide");
    companion object {
        fun fromAction(name: String)=entries.firstOrNull { it.actionName==name }
        val actionNames get()=entries.map { it.actionName }.toSet()
    }
}
