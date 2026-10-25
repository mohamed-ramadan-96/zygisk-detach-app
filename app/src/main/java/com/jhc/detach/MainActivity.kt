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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.jhc.detach.ui.theme.ZygiskdetachTheme
import com.topjohnwu.superuser.Shell

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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(82.dp)
                .clickable { onDetachedChange(!app.detached) }
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
            Checkbox(checked = app.detached, onCheckedChange = onDetachedChange)
        }
    }
}

@Composable
private fun DetachScreen(
    packageManager: PackageManager,
    apps: MutableState<List<DetachedApp>>,
    uninstalledAppBitmap: ImageBitmap,
    onApply: (List<DetachedApp>) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(AppFilter.ALL) }
    var selectedSort by remember { mutableStateOf(AppSort.SELECTED_FIRST) }
    var filterMenuExpanded by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val detachedCount = apps.value.count { it.detached }

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
                compareByDescending<DetachedApp> { it.detached }.thenBy { it.label.lowercase() }
            )
            AppSort.PACKAGE_NAME -> filtered.sortedBy { it.packageName.lowercase() }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
                Button(
                    onClick = { onApply(apps.value) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(54.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.width(9.dp))
                    Text("Apply changes", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f)) {
                        Text(detachedCount.toString(), modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Zygisk Detach", style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(Modifier.height(4.dp))
                        Text("Choose which apps should stay out of Play Store updates.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton(onClick = { filterMenuExpanded = true }) {
                            Icon(Icons.Filled.FilterList, contentDescription = "Filter apps",
                                tint = if (selectedFilter != AppFilter.ALL) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface)
                        }
                        DropdownMenu(expanded = filterMenuExpanded, onDismissRequest = { filterMenuExpanded = false }) {
                            AppFilter.values().forEach { filter ->
                                DropdownMenuItem(
                                    text = { Text(filter.title) },
                                    leadingIcon = { if (selectedFilter == filter) Icon(Icons.Filled.Check, null) },
                                    onClick = { selectedFilter = filter; filterMenuExpanded = false }
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(Icons.Filled.Sort, contentDescription = "Sort apps",
                                tint = if (selectedSort != AppSort.SELECTED_FIRST) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface)
                        }
                        DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                            AppSort.values().forEach { sort ->
                                DropdownMenuItem(
                                    text = { Text(sort.title) },
                                    leadingIcon = { if (selectedSort == sort) Icon(Icons.Filled.Check, null) },
                                    onClick = { selectedSort = sort; sortMenuExpanded = false }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(15.dp))
                Card(shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Selected apps", style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(detachedCount.toString(), style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Text(if (detachedCount == 0) "No apps selected" else "Ready to apply",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Search apps or package names") },
                    trailingIcon = {
                        if (query.isNotEmpty()) Icon(Icons.Filled.Close, contentDescription = "Clear search",
                            modifier = Modifier.clip(CircleShape).clickable { query = ""; focusManager.clearFocus() }.padding(4.dp))
                    },
                    shape = RoundedCornerShape(18.dp)
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${selectedFilter.title} · ${selectedSort.title}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${visibleApps.size} apps", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
                LazyColumn(Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp)) {
                    items(visibleApps, key = { it.packageName }) { app ->
                        AppRow(app, packageManager, uninstalledAppBitmap) { selected ->
                            app.detached = selected
                            apps.value = apps.value.toList()
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
