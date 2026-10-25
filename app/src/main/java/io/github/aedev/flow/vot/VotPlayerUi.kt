package io.github.aedev.flow.vot

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Компактная панель закадрового перевода под видеоплеером
 */
@Composable
fun VotActionBar(
    isEnabled: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    onToggleClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (isEnabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = "VOT",
                        tint = if (isEnabled) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Column {
                    Text(
                        text = when {
                            isLoading -> "Загрузка перевода Яндекс..."
                            isEnabled -> "Закадровый перевод включён"
                            else -> "Закадровый перевод (Яндекс)"
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                    if (!errorMessage.isNullOrEmpty()) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilledTonalButton(
                    onClick = onToggleClick,
                    enabled = !isLoading,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(if (isEnabled) "Выкл" else "Перевести")
                }

                IconButton(onClick = onOpenSettingsClick) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Настройки перевода"
                    )
                }
            }
        }
    }
}

/**
 * Иконка для оверлея поверх видео
 */
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
        "es" to "Испанский"
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
                text = "Настройки перевода (Яндекс VOT)",
                style = MaterialTheme.typography.titleMedium
            )

            if (!errorMessage.isNullOrEmpty()) {
                Text(
                    text = "Ошибка: $errorMessage",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

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

            Column {
                Text("Громкость перевода: ${(settings.translationVolume * 100).roundToInt()}%")
                Slider(
                    value = settings.translationVolume,
                    onValueChange = { onSettingsChanged(settings.copy(translationVolume = it)) },
                    valueRange = 0f..1f
                )
            }

            Column {
                Text("Громкость оригинала: ${(settings.originalVolume * 100).roundToInt()}%")
                Slider(
                    value = settings.originalVolume,
                    onValueChange = { onSettingsChanged(settings.copy(originalVolume = it)) },
                    valueRange = 0f..1f
                )
            }

            Text("Язык оригинала:", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                languages.forEach { (code, title) ->
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
