package dev.droiduse.assistant

data class TargetApp(val packageName: String, val label: String)

/** Require an app name in the request so removing the picker does not silently reuse an old target. */
internal fun inferTargetPackage(task: String, apps: List<TargetApp>): String? {
    val text = task.trim()
    if (text.isBlank()) return null
    apps.filter { it.label.length >= 2 && text.contains(it.label, ignoreCase = true) }
        .maxByOrNull { it.label.length }?.let { return it.packageName }
    apps.firstOrNull { text.contains(it.packageName, ignoreCase = true) }
        ?.let { return it.packageName }
    for (keyword in listOf("番茄", "美团")) {
        if (text.contains(keyword))
            apps.firstOrNull { it.label.contains(keyword) }?.let { return it.packageName }
    }
    return null
}
