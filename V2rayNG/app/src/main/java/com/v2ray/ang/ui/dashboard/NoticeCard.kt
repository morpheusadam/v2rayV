package com.v2ray.ang.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.notice.Notice

/**
 * The slot below the Auto Mode card.
 *
 * It draws nothing at all when there is no notice — not an empty card, not a placeholder,
 * nothing — so the normal state of the screen is exactly what it was before this existed.
 * That is the whole design: the space is reserved for something to be said later, and
 * saying nothing has to cost no pixels.
 */
@Composable
fun NoticeCard(
    notice: Notice?,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (notice == null) {
        return
    }

    val accent = when (notice.accent.lowercase()) {
        "violet" -> Securo.Violet
        "red" -> Securo.Red
        else -> Securo.Green
    }

    SecuroCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                if (notice.title.isNotBlank()) {
                    SecuroLabel(text = notice.title, color = accent)
                    Spacer(Modifier.height(6.dp))
                }
                if (notice.body.isNotBlank()) {
                    Text(
                        text = notice.body,
                        style = Securo.Unit,
                        color = Securo.TextSecondary,
                        // Long enough for a release note, short enough that a remote file
                        // cannot push the rest of the screen off the bottom.
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                val action = notice.action
                if (action != null && action.isUsable) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onAction) {
                            Text(text = action.label, style = Securo.Label, color = accent)
                        }
                    }
                }
            }

            if (notice.dismissible) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete_24dp),
                        contentDescription = stringResource(R.string.notice_dismiss),
                        tint = Securo.TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
