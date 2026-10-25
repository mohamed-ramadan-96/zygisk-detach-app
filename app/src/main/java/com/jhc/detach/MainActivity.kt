package com.jhc.detach

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import com.jhc.detach.ui.theme.ZygiskdetachTheme
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.launch

data class DetachedApp(
    val packageName: String,
    val label: String,
    var detached: Boolean = false,
    val installed: Boolean = true,
    val isSystem: Boolean = false
)

private enum class AppFilter(val title: String) {
    ALL("All apps"), USER("User apps"), SYSTEM("System apps"), DETACHED("Detached only")
}
private enum class AppSort(val title: String) {
    NAME_ASC("Name (A–Z)"), NAME_DESC("Name (Z–A)"),
    SELECTED_FIRST("Selected first"), PACKAGE_NAME("Package name")
}

class Toaster(private val context: Context) {
    fun toast(msg: String, delay: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(context, msg, delay).show()
    }
}
fun runShell(cmd: String, toaster: Toaster): String {
    val op = Shell.cmd(cmd).exec()
    if (op.code != 0) {
        toaster.toast("ERROR: " + op.err.joinToString("\n"), Toast.LENGTH_LONG)
        return ""
    }
    return op.out.joinToString("\n")
}
fun String.splitn() = if (isEmpty()) emptyList() else split('\n')

@Composable
private fun AppRow(
    app: DetachedApp,
    packageManager: PackageManager,
    uninstalledAppBitmap: ImageBitmap,
    onDetachedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (app.detached) {
                MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 82.dp)
                .toggleable(value = app.detached, role = Role.Checkbox, onValueChange = onDetachedChange)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon = remember(app.packageName, app.installed) {
                if (app.installed) try {
                    packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap()
                } catch (_: Exception) { uninstalledAppBitmap } else uninstalledAppBitmap
            }
            Image(icon, contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!app.installed) Text("Not installed", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary)
            }
            Checkbox(checked = app.detached, onCheckedChange = null)
        }
    }
}


@Composable
private fun FilterPill(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    val foreground = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick),
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, color = foreground, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun DetachScreen(
    packageManager: PackageManager,
    apps: MutableState<List<DetachedApp>>,
    uninstalledAppBitmap: ImageBitmap,
    appLogo: ImageBitmap,
    onApply: (List<DetachedApp>) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(AppFilter.ALL) }
    var selectedSort by remember { mutableStateOf(AppSort.SELECTED_FIRST) }
    var filterMenuExpanded by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var moreMenuExpanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val detachedCount = apps.value.count { it.detached }
    // Keep "Selected first" order stable while the user is editing; refresh it only after Apply.
    var appliedSelection by remember { mutableStateOf(apps.value.associate { it.packageName to it.detached }) }

    val visibleApps = apps.value.filter { app ->
        val matchesFilter = when (selectedFilter) {
            AppFilter.ALL -> true
            AppFilter.USER -> app.installed && !app.isSystem
            AppFilter.SYSTEM -> app.installed && app.isSystem
            AppFilter.DETACHED -> app.detached
        }
        matchesFilter && (app.label.contains(query, true) || app.packageName.contains(query, true))
    }.let { filtered ->
        when (selectedSort) {
            AppSort.NAME_ASC -> filtered.sortedBy { it.label.lowercase() }
            AppSort.NAME_DESC -> filtered.sortedByDescending { it.label.lowercase() }
            AppSort.SELECTED_FIRST -> filtered.sortedWith(
                compareByDescending<DetachedApp> { appliedSelection[it.packageName] == true }
                    .thenBy { it.label.lowercase() }
            )
            AppSort.PACKAGE_NAME -> filtered.sortedBy { it.packageName.lowercase() }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(shadowElevation = 10.dp, color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(54.dp),
                        shape = RoundedCornerShape(17.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Check, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(29.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("$detachedCount apps selected", style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("Will be detached from Zygisk", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = {
                            val current = apps.value
                            // Preserve the viewport, not the selected app key: selected-first sorting
                            // can move a deselected row far down the list after Apply.
                            val visibleIndex = listState.firstVisibleItemIndex
                            val visibleOffset = listState.firstVisibleItemScrollOffset
                            onApply(current)
                            appliedSelection = current.associate { it.packageName to it.detached }
                            coroutineScope.launch {
                                withFrameNanos { }
                                if (visibleApps.isNotEmpty()) {
                                    listState.scrollToItem(
                                        visibleIndex.coerceIn(0, visibleApps.lastIndex),
                                        visibleOffset
                                    )
                                }
                            }
                        },
                        modifier = Modifier.height(58.dp),
                        shape = RoundedCornerShape(22.dp),
                        contentPadding = PaddingValues(horizontal = 17.dp)
                    ) {
                        Icon(Icons.Filled.Sort, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Apply changes", fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Image(bitmap = appLogo, contentDescription = "Zygisk Detach logo", modifier = Modifier.size(56.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Zygisk Detach", style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Detach selected apps from Zygisk", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    Box {
                        Surface(Modifier.size(44.dp).clip(CircleShape).clickable { sortMenuExpanded = true },
                            color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = CircleShape) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Sort, "Sort apps") }
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false },
                            shape = RoundedCornerShape(24.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            tonalElevation = 6.dp,
                            shadowElevation = 8.dp
                        ) {
                            AppSort.values().forEach { sort ->
                                DropdownMenuItem(text = { Text(sort.title) },
                                    leadingIcon = { Checkbox(checked = selectedSort == sort, onCheckedChange = null) },
                                    onClick = { selectedSort = sort; sortMenuExpanded = false })
                            }
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Box {
                        Surface(Modifier.size(44.dp).clip(CircleShape).clickable { moreMenuExpanded = true },
                            color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = CircleShape) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.MoreVert, "More options") }
                        }
                        DropdownMenu(
                            expanded = moreMenuExpanded,
                            onDismissRequest = { moreMenuExpanded = false },
                            shape = RoundedCornerShape(24.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            tonalElevation = 6.dp,
                            shadowElevation = 8.dp
                        ) {
                            DropdownMenuItem(
                                text = { Text("Select All") },
                                leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null) },
                                onClick = {
                                    val visiblePackages = visibleApps.map { it.packageName }.toSet()
                                    apps.value = apps.value.map { app ->
                                        if (app.packageName in visiblePackages) app.copy(detached = true) else app
                                    }
                                    moreMenuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear Selection") },
                                leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                                onClick = {
                                    val visiblePackages = visibleApps.map { it.packageName }.toSet()
                                    apps.value = apps.value.map { app ->
                                        if (app.packageName in visiblePackages) app.copy(detached = false) else app
                                    }
                                    moreMenuExpanded = false
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Reset filter and sorting") },
                                onClick = {
                                    selectedFilter = AppFilter.ALL
                                    selectedSort = AppSort.SELECTED_FIRST
                                    query = ""
                                    moreMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Search apps or package names") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search apps") },
                    trailingIcon = {
                        if (query.isNotEmpty()) Icon(Icons.Filled.Close, contentDescription = "Clear search",
                            modifier = Modifier.clip(CircleShape).clickable { query = ""; focusManager.clearFocus() }.padding(4.dp))
                    },
                    shape = RoundedCornerShape(18.dp)
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterPill("All apps", selectedFilter == AppFilter.ALL, { selectedFilter = AppFilter.ALL }, Icons.Filled.Android)
                    FilterPill("User apps", selectedFilter == AppFilter.USER, { selectedFilter = AppFilter.USER }, Icons.Filled.Android)
                    FilterPill("Detached only", selectedFilter == AppFilter.DETACHED, { selectedFilter = AppFilter.DETACHED }, Icons.Filled.Check)
                    FilterPill("System apps", selectedFilter == AppFilter.SYSTEM, { selectedFilter = AppFilter.SYSTEM }, Icons.Filled.Android)
                }
                Spacer(Modifier.height(7.dp))
                Text("${visibleApps.size} apps",
                    modifier = Modifier.align(Alignment.End),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (visibleApps.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Text(if (query.isNotBlank()) "No matching apps" else "No apps in this filter",
                            style = MaterialTheme.typography.titleMedium)
                        Text("Try another search or change the filter.", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp)
                ) {
                    items(visibleApps, key = { it.packageName }) { app ->
                        AppRow(app, packageManager, uninstalledAppBitmap) { selected ->
                            apps.value = apps.value.map { current ->
                                if (current.packageName == app.packageName) current.copy(detached = selected)
                                else current
                            }
                        }
                    }
                }
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val toaster = Toaster(applicationContext)
        Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER))
        if (!Shell.getShell().isRoot) {
            toaster.toast("Not root")
            finishAndRemoveTask()
            return
        }
        if (Shell.cmd("test -f /data/adb/modules/zygisk-detach/detach").exec().code != 0) {
            toaster.toast("zygisk-detach is not installed")
            finishAndRemoveTask()
            return
        }

        val apps = packageManager.getInstalledPackages(0).mapNotNull { info ->
            info.applicationInfo?.let { applicationInfo ->
                DetachedApp(
                    packageName = info.packageName,
                    label = packageManager.getApplicationLabel(applicationInfo).toString(),
                    isSystem = applicationInfo.flags and
                        (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                )
            }
        }.sortedBy { it.label }.toMutableList()
        val detachedFromModule = try {
            runShell("/data/adb/modules/zygisk-detach/detach list", toaster).splitn()
        } catch (_: IndexOutOfBoundsException) {
            runShell("/data/adb/modules/zygisk-detach/detach reset", toaster)
            emptyList()
        }
        detachedFromModule.forEach { pkg ->
            if (apps.none { it.packageName == pkg }) apps.add(
                DetachedApp(pkg, pkg, detached = true, installed = false)
            )
        }
        apps.forEach { it.detached = detachedFromModule.contains(it.packageName) }
        val initialApps = apps.sortedBy { it.label }.sortedBy { !it.detached }.toList()

        setContent {
            val appState = remember { mutableStateOf(initialApps) }
            ZygiskdetachTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DetachScreen(
                        packageManager = packageManager,
                        apps = appState,
                        uninstalledAppBitmap = getDrawable(R.mipmap.unistalled_app)!!.toBitmap(96, 96).asImageBitmap(),
                        appLogo = getDrawable(R.mipmap.ic_launcher)!!.toBitmap(192, 192).asImageBitmap(),
                        onApply = { currentApps ->
                            val packages = currentApps.filter { it.detached }.map { it.packageName }
                            if (packages.isEmpty()) {
                                runShell("/data/adb/modules/zygisk-detach/detach reset", toaster)
                                toaster.toast("Emptied the detach list")
                            } else {
                                runShell("/data/adb/modules/zygisk-detach/detach detachall ${packages.joinToString(" ")}", toaster)
                                toaster.toast("Detached")
                            }
                        }
                    )
                }
            }
        }
    }
}
