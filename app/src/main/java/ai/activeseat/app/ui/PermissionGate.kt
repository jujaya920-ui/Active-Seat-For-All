package ai.activeseat.app.ui

import ai.activeseat.app.vehicle.Gear
import ai.activeseat.app.vehicle.SPEED_PERMISSION
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// Same tokens as NavPanel, so the gate reads as part of the navigation side.
private val GateInk = Color(0xFF111111)
private val SubInk = Color(0xFF6B7280)
private val Line = Color(0xFFD5D9E0)
private val Dark = Color(0xFF1A1A1A)
private val Divider = Color(0xFFF0F1F4)
private val Warning = Color(0xFFFF8A00)
private val ButtonOff = Color(0xFFE4E6EA)
private val TextOff = Color(0xFFB0B0B0)
private val Inset = 42.dp

/** What the gate shows. An empty [missing] means every runtime permission is granted. */
data class PermissionGateState(
    val missing: List<String> = emptyList(),
    /** The system no longer shows its dialog for one of [missing], so only Settings can grant it. */
    val needsSettings: Boolean = false,
)

/**
 * The runtime (dangerous) permissions this app declares and has not been granted. Install-time
 * permissions are left out: the system grants them or an ordinary app can never get them, and
 * names the device does not define are skipped.
 */
fun missingRuntimePermissions(context: Context): List<String> {
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    val requested = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
    return requested.filter { name ->
        val info = runCatching { pm.getPermissionInfo(name, 0) }.getOrNull() ?: return@filter false
        info.protection == PermissionInfo.PROTECTION_DANGEROUS &&
            context.checkSelfPermission(name) != PackageManager.PERMISSION_GRANTED
    }
}

private class PermissionText(val name: String, val use: String)

private fun describe(context: Context, permission: String): PermissionText = when (permission) {
    SPEED_PERMISSION -> PermissionText("차량 속도", "가속과 감속을 미리 알아채는 데 씁니다")
    else -> PermissionText(
        runCatching { context.packageManager.getPermissionInfo(permission, 0).loadLabel(context.packageManager).toString() }
            .getOrDefault(permission),
        "앱이 동작하는 데 필요합니다",
    )
}

/** Covers the whole app until every runtime permission is granted; it cannot be dismissed. */
@Composable
fun PermissionGate(
    context: Context,
    state: PermissionGateState,
    gear: Gear,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (state.missing.isEmpty()) return
    // The system blocks its permission dialog unless the car is parked; an unknown gear may still be.
    val blocked = !state.needsSettings && gear != Gear.PARK && gear != Gear.UNKNOWN
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Column(modifier = Modifier.width(980.dp).background(Color.White).border(1.dp, Line)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(124.dp).padding(horizontal = Inset),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                LockIcon(Modifier.size(52.dp))
                Text("권한을 허용해 주세요", color = GateInk, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            Column(modifier = Modifier.padding(start = Inset, end = Inset, top = 34.dp, bottom = 24.dp)) {
                Text("차의 움직임을 미리 알려 주려면 아래 권한이 필요합니다.", color = GateInk, fontSize = 27.sp, lineHeight = 40.sp)
                Text("모두 허용해야 앱을 쓸 수 있습니다.", color = GateInk, fontSize = 27.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
            }
            state.missing.forEach { PermissionRow(describe(context, it)) }
            when {
                blocked -> Notice("주행 중에는 권한 창이 열리지 않습니다.\n기어를 P로 바꿔 주세요.", Warning, Color.White, bold = true)
                state.needsSettings -> Notice("권한 창이 더 이상 열리지 않습니다.\n설정 > 권한에서 허용한 뒤 돌아오세요.", Divider, GateInk, bold = false, bar = true)
            }
            Spacer(Modifier.height(36.dp))
            when {
                blocked -> GateButton("P단에서 허용할 수 있습니다", enabled = false) {}
                state.needsSettings -> GateButton("설정 열기", enabled = true, onClick = onOpenSettings)
                else -> GateButton("권한 허용", enabled = true, onClick = onRequest)
            }
        }
    }
}

@Composable
private fun PermissionRow(text: PermissionText) {
    Column(modifier = Modifier.padding(horizontal = Inset)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            Box(modifier = Modifier.size(84.dp).background(Divider), contentAlignment = Alignment.Center) {
                GaugeIcon(Modifier.size(46.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text.name, color = GateInk, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(text.use, color = SubInk, fontSize = 23.sp)
            }
            Text(
                "허용 필요",
                color = Warning,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.border(2.dp, Warning).padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }
}

@Composable
private fun Notice(text: String, background: Color, color: Color, bold: Boolean, bar: Boolean = false) {
    Row(modifier = Modifier.padding(start = Inset, end = Inset, top = 26.dp).fillMaxWidth().height(IntrinsicSize.Min).background(background)) {
        if (bar) Box(Modifier.width(10.dp).fillMaxHeight().background(GateInk))
        Text(
            text,
            color = color,
            fontSize = 26.sp,
            lineHeight = 39.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
        )
    }
}

@Composable
private fun GateButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().height(124.dp)
            .background(if (enabled) Dark else ButtonOff)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Color.White else TextOff, fontSize = 36.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LockIcon(modifier: Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = size.width * 0.09f)
        val bodyTop = size.height * 0.45f
        drawRect(GateInk, Offset(size.width * 0.2f, bodyTop), Size(size.width * 0.6f, size.height * 0.45f), style = stroke)
        val shackle = size.width * 0.3f
        drawArc(GateInk, 180f, 180f, false, Offset(size.width / 2 - shackle / 2, size.height * 0.12f), Size(shackle, shackle * 1.1f), style = stroke)
        drawLine(GateInk, Offset(size.width / 2 - shackle / 2, size.height * 0.12f + shackle * 0.55f), Offset(size.width / 2 - shackle / 2, bodyTop), stroke.width)
        drawLine(GateInk, Offset(size.width / 2 + shackle / 2, size.height * 0.12f + shackle * 0.55f), Offset(size.width / 2 + shackle / 2, bodyTop), stroke.width)
    }
}

@Composable
private fun GaugeIcon(modifier: Modifier) {
    Canvas(modifier) {
        val line = size.width * 0.1f
        val inset = size.width * 0.12f
        val diameter = size.width - inset * 2
        drawArc(GateInk, 180f, 180f, false, Offset(inset, size.height * 0.3f), Size(diameter, diameter), style = Stroke(line, cap = StrokeCap.Round))
        val center = Offset(size.width / 2, size.height * 0.3f + diameter / 2)
        drawLine(color = GateInk, start = center, end = Offset(size.width * 0.7f, size.height * 0.42f), strokeWidth = line, cap = StrokeCap.Round)
    }
}
