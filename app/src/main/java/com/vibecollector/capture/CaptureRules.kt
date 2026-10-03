package com.vibecollector.capture

import com.vibecollector.data.VibeSettings
import com.vibecollector.parse.ParseResult
import com.vibecollector.parse.TreeParser

/**
 * Decides whether a clipboard payload is worth capturing.
 *
 * Kept free of Android types so the rules can be unit tested directly.
 */
object CaptureRules {

    /** Known assistants, used when "chat apps only" is on. */
    val CHAT_PACKAGES: Set<String> = setOf(
        "com.openai.chatgpt",
        "com.openai.chatgpt.android",
        "com.deepseek.chat",
        "ai.google.labs.gemini",
        "com.google.android.apps.bard",
        "com.anthropic.claude",
        "com.microsoft.officecopilot",
        "com.copilotapp",
        "com.perplexity",
        "com.poe.android",
        "com.you.com",
        "com.replika",
        "dev.morphcodes.line",
    )

    private val CODE_SIGNALS = listOf(
        Regex("(?m)^```"),                                   // markdown fence
        Regex("""(?m)^\s*(?:import|from|package|using|require)\s+[^\n]{1,80}"""),
        Regex("""(?m)^\s*(?:def|class|function|const|let|var|public|private|fun|fn|func|struct|impl|interface|type)\s+\w+"""),
        Regex("""[{};]\s*$""", RegexOption.MULTILINE),
        Regex("""=>\s*[{(]?"""),
        Regex("""(?m)^\s*(?:SELECT|INSERT\s+INTO|CREATE\s+TABLE)\b""", RegexOption.IGNORE_CASE),
        Regex("""<\/?[a-z][a-z0-9-]*(?:\s[^>]*)?>""", RegexOption.IGNORE_CASE),
        Regex("""(?m)^\s*[.#][a-zA-Z][\w-]*\s*\{"""),
        Regex("""^\s*[<][!?]?[A-Za-z]"""),
    )

    private val JUST_A_URL = Regex("""^\s*(?:https?|ftp)://\S+\s*$""")

    data class Verdict(
        val capture: Boolean,
        val reason: String = "",
    )

    fun evaluate(
        text: String,
        settings: VibeSettings,
        sourcePackage: String?,
        ownPackages: Set<String> = emptySet(),
    ): Verdict {
        if (!settings.captureEnabled) return Verdict(false, "capture is off")
        if (settings.capturePaused) return Verdict(false, "capture is paused")
        if (!settings.copyCollectEnabled) return Verdict(false, "copy collect is off")

        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Verdict(false, "empty clipboard")
        if (trimmed.length < settings.minChars) return Verdict(false, "too short (${trimmed.length} chars)")

        // Never react to the user copying out of vibe-collector itself.
        if (sourcePackage != null && sourcePackage in ownPackages) {
            return Verdict(false, "copied from vibe-collector")
        }
        if (sourcePackage != null && sourcePackage in settings.excludedPackages) {
            return Verdict(false, "$sourcePackage is excluded")
        }

        if (settings.chatAppsOnly) {
            if (sourcePackage == null) return Verdict(false, "unknown source app")
            if (sourcePackage !in CHAT_PACKAGES) return Verdict(false, "$sourcePackage is not a known chat app")
        }

        if (JUST_A_URL.matches(trimmed)) return Verdict(false, "just a URL")

        val parsed = com.vibecollector.parse.CodeBlockParser.parse(trimmed)
        if (parsed.isEmpty) return Verdict(false, "nothing code-like found")

        val codeish = parsed.sawFence || TreeParser.looksLikeStructure(trimmed) || hasCodeSignals(trimmed)
        if (!codeish) return Verdict(false, "does not look like code")

        return Verdict(true, "${parsed.files.size} file(s) detected")
    }

    fun hasCodeSignals(text: String): Boolean {
        val head = text.take(2000)
        return CODE_SIGNALS.count { it.containsMatchIn(head) } >= 2
    }

    /**
     * A payload can be captured either because it contains code blocks, or
     * because it is a project structure with nothing in it yet (the
     * scaffold-first flow).
     */
    fun isStructureOnly(result: ParseResult, text: String): Boolean =
        result.files.size == 1 &&
            result.files.first().needsNameConfirm &&
            TreeParser.looksLikeStructure(text)
}
