package zio.bdd.intellij.lang

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

/**
 * Line-oriented lexer for Gherkin .feature files.
 *
 * Processes text line by line. Within each line, tokenises step keywords,
 * structural keywords, tags, placeholders, table pipes, and doc strings.
 *
 * The lexer is stateful: it tracks whether it is inside a doc string block.
 */
class ZioBddLexer : LexerBase() {

    private var buffer: CharSequence = ""
    private var startOffset: Int = 0
    private var endOffset: Int = 0
    private var position: Int = 0
    private var tokenStart: Int = 0
    private var tokenEnd: Int = 0
    private var tokenType: IElementType? = null
    private var inDocString: Boolean = false
    private var docDelimiter: String = "\"\"\""

    // Lines still to process — each element is a (lineStart, lineText) pair
    private val lineQueue = ArrayDeque<Pair<Int, String>>()

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.startOffset = startOffset
        this.endOffset = endOffset
        this.position = startOffset
        this.tokenStart = startOffset
        this.tokenEnd = startOffset
        this.tokenType = null
        this.inDocString = false
        lineQueue.clear()
        advance()
    }

    override fun getState(): Int = if (inDocString) 1 else 0
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = endOffset

    override fun advance() {
        if (position >= endOffset) {
            tokenType = null
            return
        }
        tokenStart = position
        val line = readLine()
        tokenEnd = position
        tokenType = classifyLine(line)
    }

    /** Read from current position to end of line (inclusive of \n). */
    private fun readLine(): String {
        val start = position
        while (position < endOffset && buffer[position] != '\n') position++
        if (position < endOffset) position++ // consume the \n
        return buffer.subSequence(start, position).toString()
    }

    private val structuralKeywords = listOf(
        "Feature:", "Background:", "Scenario Outline:", "Scenario Template:",
        "Scenario:", "Example:", "Examples:", "Scenarios:", "Rule:"
    )
    private val stepKeywords = listOf("Given", "When", "Then", "And", "But", "*")
    private val docDelimiters = listOf("\"\"\"", "```")

    private fun classifyLine(rawLine: String): IElementType {
        val line = rawLine.trimStart()

        // Doc string handling — everything between delimiters is DOC_STRING
        if (inDocString) {
            if (docDelimiters.any { line.trimEnd().endsWith(it) || line.startsWith(it) }) {
                inDocString = false
            }
            return ZioBddTokenTypes.DOC_STRING
        }

        // Opening doc string delimiter
        if (docDelimiters.any { line.startsWith(it) }) {
            inDocString = true
            return ZioBddTokenTypes.DOC_STRING
        }

        // Comment
        if (line.startsWith("#")) return ZioBddTokenTypes.COMMENT

        // Tags line — @flags(...) or regular @tag
        if (line.startsWith("@")) {
            return if (line.contains("@flags(")) ZioBddTokenTypes.FLAGS_TAG
            else ZioBddTokenTypes.TAG
        }

        // Structural keywords
        structuralKeywords.forEach { kw ->
            if (line.startsWith(kw)) {
                return if (kw == "Feature:" || kw == "Rule:") ZioBddTokenTypes.KEYWORD
                else ZioBddTokenTypes.KEYWORD
            }
        }

        // Step keywords
        stepKeywords.forEach { kw ->
            if (line.startsWith("$kw ") || line == kw) return ZioBddTokenTypes.STEP_KEYWORD
        }

        // Table row
        if (line.startsWith("|")) return ZioBddTokenTypes.TABLE_PIPE

        // Placeholder line (inside Scenario Outline)
        if (line.contains("<") && line.contains(">")) return ZioBddTokenTypes.STEP_TEXT

        return ZioBddTokenTypes.TEXT
    }
}
