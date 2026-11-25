package com.buct.one.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 极简 Markdown 正文渲染：只覆盖公告编辑器实际会用到的标题 / 列表 / 引用 / 分隔线 / 粗体 / 行内代码。 */
internal sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullet(val items: List<String>) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data object Rule : MarkdownBlock
}

private val HeadingRegex = Regex("^(#{1,6})\\s+(.+)$")
private val BulletRegex = Regex("^([-*+]|\\d+[.)])\\s+(.+)$")
private val RuleRegex = Regex("^(---+|\\*{3,}|_{3,})\\s*$")
private val InlineRegex = Regex("\\*\\*(.+?)\\*\\*|`(.+?)`|\\[(.+?)]\\((.+?)\\)")

internal fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = StringBuilder()
    val bullets = mutableListOf<String>()

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        if (text.isNotEmpty()) blocks += MarkdownBlock.Paragraph(text)
        paragraph.setLength(0)
    }

    fun flushBullets() {
        if (bullets.isNotEmpty()) {
            blocks += MarkdownBlock.Bullet(bullets.toList())
            bullets.clear()
        }
    }

    for (rawLine in markdown.replace("\r\n", "\n").split('\n')) {
        val line = rawLine.trim()
        val heading = HeadingRegex.find(line)
        val bullet = BulletRegex.find(line)
        when {
            line.isEmpty() -> {
                flushParagraph()
                flushBullets()
            }
            RuleRegex.matches(line) -> {
                flushParagraph()
                flushBullets()
                blocks += MarkdownBlock.Rule
            }
            line.startsWith(">") -> {
                flushParagraph()
                flushBullets()
                blocks += MarkdownBlock.Quote(line.removePrefix(">").trim())
            }
            heading != null -> {
                flushParagraph()
                flushBullets()
                blocks += MarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
            }
            bullet != null -> {
                flushParagraph()
                bullets += bullet.groupValues[2].trim()
            }
            else -> {
                flushBullets()
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
    }
    flushParagraph()
    flushBullets()
    return blocks
}

@Composable
fun MarkdownContent(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    val colors = MaterialTheme.colorScheme

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    text = inlineAnnotatedString(block.text),
                    style = when {
                        block.level <= 2 -> MaterialTheme.typography.headlineSmall
                        block.level == 3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                is MarkdownBlock.Paragraph -> Text(
                    text = inlineAnnotatedString(block.text),
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 24.sp,
                    color = colors.onBackground,
                )
                is MarkdownBlock.Bullet -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (item in block.items) {
                        Row {
                            Text("•", style = MaterialTheme.typography.bodyMedium, color = colors.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = inlineAnnotatedString(item),
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 22.sp,
                                color = colors.onBackground,
                            )
                        }
                    }
                }
                is MarkdownBlock.Quote -> Surface(
                    color = colors.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(
                            text = inlineAnnotatedString(block.text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                MarkdownBlock.Rule -> HorizontalDivider(color = colors.outline.copy(alpha = 0.7f))
            }
        }
    }
}

/** 行内样式：**粗体**、`代码`、[文字](链接)——链接仅保留文字。 */
internal fun inlineAnnotatedString(text: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in InlineRegex.findAll(text)) {
        if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
        val bold = match.groupValues[1]
        val code = match.groupValues[2]
        val linkLabel = match.groupValues[3]
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
            linkLabel.isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(linkLabel) }
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}
