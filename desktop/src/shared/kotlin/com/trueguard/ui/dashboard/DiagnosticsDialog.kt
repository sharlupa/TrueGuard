package com.trueguard.ui.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.trueguard.core.CheckResult
import com.trueguard.core.CheckState
import com.trueguard.core.Diagnostics
import com.trueguard.ui.components.PrimaryButton

/** «Диагностика» (b5/a3): проверки идут по очереди, у каждой — свой индикатор. */
@Composable
fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val results = remember { mutableStateMapOf<String, CheckResult>() }
    LaunchedEffect(Unit) { Diagnostics.run().collect { results[it.title] = it } }
    val cs = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerHigh, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp).widthIn(max = 480.dp)) {
                Text("Диагностика", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Diagnostics.titles.forEach { title ->
                        val r = results[title]
                        Row(verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                                AnimatedContent(r?.state ?: CheckState.Pending, transitionSpec = { scaleIn() + fadeIn() togetherWith fadeOut() }) { st ->
                                    when (st) {
                                        CheckState.Pending -> LoadingIndicator(Modifier.size(28.dp))
                                        CheckState.Ok -> Icon(Icons.Filled.CheckCircle, null, tint = cs.primary)
                                        CheckState.Warn -> Icon(Icons.Filled.Error, null, tint = cs.tertiary)
                                        CheckState.Fail -> Icon(Icons.Filled.Cancel, null, tint = cs.error)
                                    }
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(title, fontWeight = FontWeight.Bold)
                                Text(r?.detail ?: "Проверка...", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                PrimaryButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Закрыть", fontWeight = FontWeight.Bold) }
            }
        }
    }
}
