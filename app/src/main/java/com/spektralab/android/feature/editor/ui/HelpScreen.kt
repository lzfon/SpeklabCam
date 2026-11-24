package com.spektralab.android.feature.editor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * 参数说明页：与「关于」页同一套写法——Scaffold + TopAppBar + 可滚动的正文，
 * 左上角返回箭头退出。正文是编译内置的全文（HelpText.kt），运行时不再读任何文件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ParamHelpScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val blocks = parseHelp(HELP_TEXT_FALLBACK)
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("参数说明", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回编辑器")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                expandedHeight = 56.dp,
            )
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                blocks.forEach { block ->
                    when (block.kind) {
                        HelpKind.TITLE -> {
                            Text(block.label, style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp).semantics { heading() })
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
                        }
                        HelpKind.PART -> Text(block.label, style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 18.dp, bottom = 4.dp).semantics { heading() })
                        HelpKind.SECTION -> Text(block.label, style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp).semantics { heading() })
                        HelpKind.PARAM -> Text(buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary)) { append(block.label) }
                            append(block.body)
                        }, style = MaterialTheme.typography.bodyMedium)
                        HelpKind.BULLET -> Text("· ${block.body}", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HelpKind.BODY -> Text(block.body, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Box(Modifier.size(24.dp))
            }
        }
    }
}

private enum class HelpKind { TITLE, PART, SECTION, PARAM, BULLET, BODY }

private data class HelpBlock(val kind: HelpKind, val label: String = "", val body: String = "")

/** 极简结构识别：# 总标题、后续 # 为一级（一、二、三）/ ## 二级（1、2）/ **参数名**正文 / - 列表。 */
private fun parseHelp(raw: String): List<HelpBlock> {
    if (raw.isBlank()) return emptyList()
    val blocks = mutableListOf<HelpBlock>()
    var firstHeading = true
    for (rawLine in raw.replace("\r", "").split('\n')) {
        val t = rawLine.trim()
        when {
            t.isEmpty() -> Unit
            t.startsWith("### ") -> blocks += HelpBlock(HelpKind.SECTION, t.removePrefix("### ").trim())
            t.startsWith("## ") -> blocks += HelpBlock(HelpKind.SECTION, t.removePrefix("## ").trim())
            t.startsWith("# ") -> {
                val label = t.removePrefix("# ").trim()
                blocks += HelpBlock(if (firstHeading) HelpKind.TITLE else HelpKind.PART, label)
                firstHeading = false
            }
            t.startsWith("- ") -> blocks += HelpBlock(HelpKind.BULLET, body = t.removePrefix("- ").trim())
            t.startsWith("**") && t.indexOf("**", 2) > 1 -> {
                val end = t.indexOf("**", 2)
                blocks += HelpBlock(HelpKind.PARAM,
                    label = t.substring(2, end).trim(),
                    body = t.substring(end + 2).trim().replace("`", ""))
            }
            else -> blocks += HelpBlock(HelpKind.BODY, body = t.replace("`", "").replace(Regex("\\*\\*"), ""))
        }
    }
    return blocks
}
