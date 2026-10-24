package com.kienhoang.dualsubreplay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import com.kienhoang.dualsubreplay.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun AppNavigation(
    // "Ultratovush bo‘limlari": SHOKALON's first screen; null hides the item in the general builds.
    onHome: (() -> Unit)? = null,
    // Bumped by SHOKALON's sections screen to open this menu (its "Sozlamalar" button).
    menuRequests: Int = 0,
    onPractice: () -> Unit,
    onSettings: () -> Unit,
    onProgress: () -> Unit = {},
    onLesson: () -> Unit = {},
    // "Dasturni yangilash": only the Uzbek build is published where the dialog looks for updates.
    showUpdateItem: Boolean = appUpdateAvailable,
    onVisibilityChange: (Boolean) -> Unit = {},
    content: @Composable (menuButton: @Composable () -> Unit) -> Unit,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var showUpdate by remember { mutableStateOf(false) }
    if (showUpdate) AppUpdateDialog { showUpdate = false }
    val scope = rememberCoroutineScope()
    LaunchedEffect(drawer.currentValue, drawer.targetValue) {
        onVisibilityChange(drawer.currentValue == DrawerValue.Open || drawer.targetValue == DrawerValue.Open)
    }
    DisposableEffect(Unit) { onDispose { onVisibilityChange(false) } }
    LaunchedEffect(menuRequests) { if (menuRequests > 0) drawer.open() }
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                BoxWithConstraints(Modifier.fillMaxHeight()) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(12.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            DrawerHeader(onClose = { scope.launch { drawer.close() } })
                            Spacer(Modifier.height(8.dp))
                            HomeDrawerItem(onHome, drawer)
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
                                label = { Text("Dars: havola va subtitr") },
                                selected = false,
                                modifier = Modifier.testTag("open_lesson_tools"),
                                onClick = {
                                    scope.launch {
                                        drawer.close()
                                        onLesson()
                                    }
                                },
                            )
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.School, contentDescription = null) },
                                label = { Text("Practice") }, selected = false,
                                modifier = Modifier.testTag("open_saved_words"),
                                onClick = { scope.launch { drawer.close(); onPractice() } },
                            )
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Insights, contentDescription = null) },
                                label = { Text("Progress") },
                                selected = false,
                                modifier = Modifier.testTag("open_progress"),
                                onClick = {
                                    scope.launch {
                                        drawer.close()
                                        onProgress()
                                    }
                                },
                            )
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                label = { Text("Settings") }, selected = false,
                                onClick = { scope.launch { drawer.close(); onSettings() } },
                            )
                            if (showUpdateItem) {
                                NavigationDrawerItem(
                                    icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                                    label = { Text("Dasturni yangilash") },
                                    selected = false,
                                    modifier = Modifier.testTag("open_app_update"),
                                    onClick = {
                                        scope.launch {
                                            drawer.close()
                                            showUpdate = true
                                        }
                                    },
                                )
                            }
                        }
                        Column(Modifier.padding(horizontal = 4.dp)) {
                            HorizontalDivider(Modifier.padding(vertical = 16.dp))
                            SettingsRepositoryLink(beforeOpen = { drawer.close() })
                        }
                    }
                }
            }
        },
    ) {
        content { MenuButton(onHome) { scope.launch { drawer.open() } } }
    }
}

/**
 * The top-left button: in SHOKALON it goes back to the sections screen (the menu is opened from there),
 * elsewhere it opens the menu.
 */
@Composable
private fun MenuButton(
    onHome: (() -> Unit)?,
    openMenu: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(width = 72.dp, height = 48.dp)
                .testTag("navigation_menu_button")
                .clickable(role = Role.Button) { onHome?.invoke() ?: openMenu() },
        contentAlignment = Alignment.CenterStart,
    ) {
        Icon(
            if (onHome != null) Icons.Default.Apps else Icons.Default.Menu,
            contentDescription = if (onHome != null) "Ultratovush bo\u2018limlari" else "Open navigation menu",
            modifier = Modifier.padding(start = 12.dp).size(24.dp),
        )
    }
}

/** "Ultratovush bo‘limlari": back to SHOKALON's sections screen; nothing when [onHome] is null. */
@Composable
private fun HomeDrawerItem(
    onHome: (() -> Unit)?,
    drawer: DrawerState,
) {
    if (onHome == null) return
    val scope = rememberCoroutineScope()
    NavigationDrawerItem(
        icon = { Icon(Icons.Default.Home, contentDescription = null) },
        label = { Text("Ultratovush bo‘limlari") },
        selected = false,
        modifier = Modifier.testTag("open_shokalon_home"),
        onClick = {
            scope.launch {
                drawer.close()
                onHome()
            }
        },
    )
}

@Composable
private fun DrawerHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val brand = remember { appBrand() }
        if (brand.hasLogo) {
            Image(
                painter = painterResource(R.drawable.drawer_logo),
                contentDescription = null,
                modifier = Modifier.size(48.dp).clip(CircleShape),
            )
        } else {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ClosedCaption, contentDescription = null, modifier = Modifier.size(22.dp))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(brand.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("app_brand_name"))
            Text(
                brand.tagline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The installed version, so the doctor can tell at a glance whether an update arrived.
            Text(
                appVersionLabel(com.kienhoang.dualsubreplay.BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("app_version_label"),
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.testTag("close_navigation_menu")) {
            Icon(Icons.Default.Close, contentDescription = "Close navigation menu")
        }
    }
}
