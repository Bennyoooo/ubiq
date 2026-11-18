package com.agentos.agent

/** TASK: a card for a background task this conversation started (start_task); [ChatItem.taskId] is its session. */
enum class Role { USER, ASSISTANT, ACTION, SYSTEM, TASK }

/**
 * Model replies as plain text (one-line summaries, quotes, copy): drop markdown emphasis, headings and
 * code fences, and turn table rows into "a · b · c". The chat itself renders markdown (ui/MarkdownText).
 */
fun plainText(s: String): String = s
    .replace(Regex("(?m)^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$\n?"), "")
    .replace(Regex("(?m)^\\s*\\|(.*)\\|\\s*$")) { m -> m.groupValues[1].split("|").joinToString(" · ") { it.trim() } }
    .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
    .replace(Regex("(?m)^#{1,6}\\s+"), "")
    .replace("```", "")
    .replace(Regex("(?<![\\w*])\\*(\\S[^*]*?)\\*(?![\\w*])"), "$1")

data class ChatItem(
    val role: Role,
    val text: String,
    val id: Long = System.nanoTime(),
    val attachments: List<Attachment> = emptyList(),
    val taskId: String? = null,
    /** Short answers the user can tap under a question (ask_user's options). */
    val options: List<String> = emptyList(),
)

sealed class AgentStatus {
    data object Idle : AgentStatus()
    data class Running(val step: Int, val note: String) : AgentStatus()
    data class WaitingForUser(val question: String) : AgentStatus()
}
