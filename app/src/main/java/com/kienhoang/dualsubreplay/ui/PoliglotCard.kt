package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.poliglot.PoliglotActivity

/** On the home screen: opens Poliglot, the 30-day English and Arabic speaking course, on its own screen. */
@Composable
internal fun PoliglotCard() {
    val context = LocalContext.current
    Card(
        onClick = { PoliglotActivity.open(context) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("poliglot_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(32.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("Poliglot", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Ingliz tili B1 speaking va arab tili noldan: 30 kunlik dastur", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
