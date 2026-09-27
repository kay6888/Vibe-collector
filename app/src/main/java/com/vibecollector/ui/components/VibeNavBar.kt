package com.vibecollector.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.vibecollector.VibeTab

@Composable
fun VibeNavBar(current: VibeTab, pendingCount: Int, onSelect: (VibeTab) -> Unit) {
    NavigationBar {
        VibeTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = current == tab,
                onClick = { onSelect(tab) },
                icon = {
                    if (tab == VibeTab.INBOX && pendingCount > 0) {
                        BadgedBox(badge = { Badge { Text(pendingCount.coerceAtMost(99).toString()) } }) {
                            Icon(tab.icon(), contentDescription = tab.label)
                        }
                    } else {
                        Icon(tab.icon(), contentDescription = tab.label)
                    }
                },
                label = { Text(tab.label) },
            )
        }
    }
}

private fun VibeTab.icon() = when (this) {
    VibeTab.FILES -> Icons.Filled.Folder
    VibeTab.INBOX -> Icons.Filled.Inbox
    VibeTab.NEW -> Icons.Filled.Add
    VibeTab.SETTINGS -> Icons.Filled.Settings
}
