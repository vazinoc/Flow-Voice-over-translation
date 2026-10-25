package com.flow.vot

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VotOverlayButton(
    isEnabled: Boolean,
    isLoading: Boolean,
    onToggleClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .combinedClickable(
                onClick = onToggleClick,
                onLongClick = onOpenSettingsClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(
                imageVector = Icons.Default.Translate,
                contentDescription = "Закадровый перевод (VOT)",
                tint = if (isEnabled) MaterialTheme.colorScheme.primary else Color.White
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VotSettingsBottomSheet(
    settings: VotSettings,
    errorMessage: String?,
    onSettingsChanged: (VotSettings) -> Unit,
    onRequestRetranslate: () -> Unit,
    onDismiss: () -> Unit
) {
    val languages = listOf(
        "en" to "Английский",
        "de" to "Немецкий",
        "fr" to "Французский",
        "es" to "Испанский",
        "it" to "Итальянский",
        "zh" to "Китайский",
        "ja" to "Японский",
        "ko" to "Корейский"
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Закадровый перевод (Яндекс VOT)",
                style = MaterialTheme.typography.titleMedium
            )

            if (!errorMessage.isNullOrEmpty()) {
                Text(
                    text = "Ошибка: $errorMessage",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Переключатель включения
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Включить закадровый перевод")
                Switch(
                    checked = settings.isEnabled,
                    onCheckedChange = { checked ->
                        if (checked) onRequestRetranslate()
                        else onSettingsChanged(settings.copy(isEnabled = false))
                    }
                )
            }

            // Ползунок громкости перевода
            Column {
                Text("Громкость перевода: ${(settings.translationVolume * 100).roundToInt()}%")
                Slider(
                    value = settings.translationVolume,
                    onValueChange = { onSettingsChanged(settings.copy(translationVolume = it)) },
                    valueRange = 0f..1f
                )
            }

            // Ползунок громкости оригинала (Audio Ducking)
            Column {
                Text("Громкость оригинала: ${(settings.originalVolume * 100).roundToInt()}%")
                Slider(
                    value = settings.originalVolume,
                    onValueChange = { onSettingsChanged(settings.copy(originalVolume = it)) },
                    valueRange = 0f..1f
                )
            }

            // Выбор исходного языка видео
            Text("Язык оригинала:", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                languages.take(4).forEach { (code, title) ->
                    FilterChip(
                        selected = settings.sourceLang == code,
                        onClick = {
                            onSettingsChanged(settings.copy(sourceLang = code))
                            if (settings.isEnabled) onRequestRetranslate()
                        },
                        label = { Text(title) }
                    )
                }
            }
        }
    }
}
