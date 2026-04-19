package com.tyrnguard.client.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.tyrnguard.client.DeployManager
import com.tyrnguard.client.SettingsStore
import com.tyrnguard.client.TunnelManager
import com.tyrnguard.client.TunnelService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import kotlin.math.PI
import kotlin.math.sin

private const val CMD_TIMEOUT = 900000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeployTab(snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) { DeployManager.init(context) }

    val savedIp by settingsStore.deployIp.collectAsStateWithLifecycle("")
    val savedLogin by settingsStore.deployLogin.collectAsStateWithLifecycle("")
    val savedPassword by settingsStore.deployPassword.collectAsStateWithLifecycle("")
    val savedMainPass by settingsStore.deployMainPassword.collectAsStateWithLifecycle("")
    val savedAdminId by settingsStore.deployAdminId.collectAsStateWithLifecycle("")
    val savedBotToken by settingsStore.deployBotToken.collectAsStateWithLifecycle("")
    val savedSshPort by settingsStore.deploySshPort.collectAsStateWithLifecycle("22")

    var ip by remember { mutableStateOf("") }
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showSecretsDialog by remember { mutableStateOf(false) }
    var showUninstallDialog by remember { mutableStateOf(false) }

    val isDeploying by DeployManager.isDeploying.collectAsStateWithLifecycle()
    val deployProgress by DeployManager.deployProgress.collectAsStateWithLifecycle()
    val currentStep by DeployManager.currentStep.collectAsStateWithLifecycle()

    LaunchedEffect(savedIp) { if (savedIp.isNotEmpty()) ip = savedIp }
    LaunchedEffect(savedLogin) { if (savedLogin.isNotEmpty()) login = savedLogin }
    LaunchedEffect(savedPassword) { if (savedPassword.isNotEmpty()) password = savedPassword }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Деплой сервера",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(12.dp))
                Text("Установка VPN сервера в 1 клик на VPS. Укажите IP и пароль от root.", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it.filter { c -> !c.isWhitespace() }; scope.launch { settingsStore.saveDeploy(ip, login, password, savedSshPort) } },
                    label = { Text("IP сервера") },
                    placeholder = { Text("1.2.3.4") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !isDeploying,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = login,
                        onValueChange = { login = it.filter { c -> !c.isWhitespace() }; scope.launch { settingsStore.saveDeploy(ip, login, password, savedSshPort) } },
                        label = { Text("Логин") },
                        placeholder = { Text("root") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        enabled = !isDeploying,
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it.filter { c -> !c.isWhitespace() }; scope.launch { settingsStore.saveDeploy(ip, login, password, savedSshPort) } },
                        label = { Text("Пароль SSH") },
                        placeholder = { Text("password") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        enabled = !isDeploying,
                    )
                }
            }
        }

        OutlinedButton(
            onClick = { showSecretsDialog = true },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Icon(Icons.Default.Key, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text("Секреты (Бот и Пароль)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        AnimatedVisibility(visible = isDeploying, enter = expandVertically(spring(stiffness = Spring.StiffnessLow)) + fadeIn(), exit = shrinkVertically(spring(stiffness = Spring.StiffnessLow)) + fadeOut()) {
            val animatedProgress by animateFloatAsState(targetValue = deployProgress, animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy), label = "")

            Surface(
                modifier = Modifier.fillMaxWidth(), 
                shape = RoundedCornerShape(24.dp), 
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 0.dp
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        AnimatedContent(targetState = currentStep, transitionSpec = { slideInVertically { it } + fadeIn() togetherWith slideOutVertically { -it } + fadeOut() }, modifier = Modifier.weight(1f), label = "") { step ->
                            Text(step, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("${(animatedProgress * 100).toInt()}%", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Наш кастомный плавный волнистый прогресс-бар
                    CustomWavyProgressIndicator(
                        progress = animatedProgress,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    if (ip.isBlank() || password.isBlank() || savedMainPass.isBlank()) return@Button
                    val effectiveLogin = if (login.isBlank()) "root" else login
                    val appContext = context.applicationContext
                    DeployManager.scope.launch {
                        try {
                            DeployManager.startDeploy()
                            val intent = Intent(appContext, TunnelService::class.java).apply { action = "DEPLOY_START" }
                            if (Build.VERSION.SDK_INT >= 26) appContext.startForegroundService(intent) else appContext.startService(intent)
                            val success = performDeploy(appContext, ip, effectiveLogin, password, savedSshPort.toIntOrNull() ?: 22, savedMainPass, savedAdminId, savedBotToken) { p, s -> DeployManager.updateProgress(p, s) }
                            if (success) snackbarHostState.showSnackbar("Деплой успешно завершен. Сервер готов к работе!") else snackbarHostState.showSnackbar("Ошибка деплоя. Подробности в логах.")
                        } finally {
                            try { appContext.startService(Intent(appContext, TunnelService::class.java).apply { action = "DEPLOY_STOP" }) } catch (_: Exception) {}
                        }
                    }
                },
                modifier = Modifier.weight(1f).height(64.dp),
                shape = RoundedCornerShape(24.dp),
                enabled = !isDeploying && ip.isNotBlank() && password.isNotBlank() && savedMainPass.isNotBlank()
            ) {
                if (isDeploying) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 3.dp)
                else { Icon(Icons.Default.CloudUpload, null, Modifier.size(24.dp)); Spacer(Modifier.width(8.dp)); Text("Установить", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }

            FilledTonalButton(
                onClick = { if (ip.isBlank() || password.isBlank()) return@FilledTonalButton; showUninstallDialog = true },
                modifier = Modifier.weight(1f).height(64.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                enabled = !isDeploying && ip.isNotBlank() && password.isNotBlank()
            ) {
                Icon(Icons.Default.Delete, null, Modifier.size(24.dp)); Spacer(Modifier.width(8.dp)); Text("Удалить", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        if (showSecretsDialog) {
            DeploySecretsDialog(
                initialMainPass = savedMainPass,
                initialAdminId = savedAdminId,
                initialBotToken = savedBotToken,
                initialSshPort = savedSshPort,
                onDismiss = { showSecretsDialog = false },
                onSave = { pass, adminId, botToken, sshPort ->
                    scope.launch { 
                        settingsStore.saveDeploySecrets(pass, adminId, botToken, sshPort) 
                    }
                    showSecretsDialog = false
                }
            )
        }

        if (showUninstallDialog) UninstallConfirmDialog(
            onDismiss = { showUninstallDialog = false },
            onConfirm = {
                showUninstallDialog = false
                val effectiveLogin = if (login.isBlank()) "root" else login
                DeployManager.scope.launch {
                    try {
                        DeployManager.startDeploy()
                        performUninstall(ip, effectiveLogin, password, savedSshPort.toIntOrNull() ?: 22) { p, s -> DeployManager.updateProgress(p, s) }
                        snackbarHostState.showSnackbar("Сервер успешно очищен.")
                    } catch (_: Exception) {}
                }
            }
        )
    }
}

// Кастомный компонент Wavy Indicator, который работает всегда и везде!
@Composable
fun CustomWavyProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val phaseShift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(modifier = modifier.height(14.dp)) {
        val waveLength = 32.dp.toPx()
        val amplitude = size.height / 2 - 2.dp.toPx()
        val centerY = size.height / 2
        val progressWidth = size.width * progress.coerceIn(0f, 1f)

        // Рисуем задний фон (track)
        val trackPath = Path()
        var x = progressWidth
        var first = true
        while (x <= size.width) {
            val y = centerY + sin((x / waveLength) * 2 * PI - phaseShift).toFloat() * amplitude
            if (first) {
                trackPath.moveTo(x, y)
                first = false
            } else trackPath.lineTo(x, y)
            x += 2f
        }
        if (!first) {
            drawPath(
                path = trackPath,
                color = trackColor,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // Рисуем заполненный прогресс
        val progressPath = Path()
        x = 0f
        first = true
        while (x <= progressWidth) {
            val y = centerY + sin((x / waveLength) * 2 * PI - phaseShift).toFloat() * amplitude
            if (first) {
                progressPath.moveTo(x, y)
                first = false
            } else progressPath.lineTo(x, y)
            x += 2f
        }
        if (!first) {
            drawPath(
                path = progressPath,
                color = color,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

private class SSHClient(private val session: Session, private val pass: String, private val user: String) {
    fun exec(command: String, timeout: Long = CMD_TIMEOUT): String {
        if (!session.isConnected) { DeployManager.writeError("SSH exec: сессия разорвана"); return "error: session is down" }
        var channel: ChannelExec? = null
        val result = StringBuilder()
        return try {
            channel = session.openChannel("exec") as ChannelExec
            
            val sudoRegex = Regex("(^|&&|\\|\\||;)\\s*sudo\\s+")
            val cmd = if (user == "root") {
                command.replace(sudoRegex, "$1 ").trim()
            } else {
                command.replace(sudoRegex, "$1 sudo -S ").trim()
            }
            
            channel.setCommand(cmd)
            val outStream = channel.outputStream
            val input = channel.inputStream
            val err = channel.errStream
            channel.connect(15000)
            if (cmd.contains("sudo -S")) { outStream.write("$pass\n".toByteArray()); outStream.flush() }
            val reader = input.bufferedReader()
            val errReader = err.bufferedReader()
            val startTime = System.currentTimeMillis()
            val progressRegex = Regex("^WDTT_PROGRESS\\|(\\d+\\.?\\d*)\\|(.+)$")
            while (!channel.isClosed || reader.ready() || errReader.ready()) {
                if (System.currentTimeMillis() - startTime > timeout) {
                    DeployManager.writeError("SSH timeout"); try { channel.disconnect() } catch (_: Exception) {}; return "error: timeout"
                }
                if (reader.ready()) {
                    reader.readLine()?.let { line ->
                        progressRegex.find(line.trim())?.let { match ->
                            DeployManager.updateProgress(match.groupValues[1].toFloatOrNull() ?: 0f, match.groupValues[2])
                        } ?: run {
                            if (!line.contains("WDTT_PROGRESS")) {
                                val clean = line.replace(Regex("\u001B\\[[;\\d]*m"), "")
                                result.appendLine(clean)
                                if (clean.contains("[✗]") || clean.contains("FAIL") || (clean.contains("error", true) && !clean.contains("2>/dev/null"))) {
                                    DeployManager.writeError("REMOTE: $clean"); TunnelManager.addDeployErrorLog("REMOTE: $clean")
                                }
                            }
                        }
                    }
                }
                if (errReader.ready()) {
                    errReader.readLine()?.let { line ->
                        if (!line.contains("password for")) {
                            val clean = line.replace(Regex("\u001B\\[[;\\d]*m"), "")
                            result.appendLine(clean)
                            if (clean.isNotBlank() && !clean.startsWith("Warning:")) {
                                DeployManager.writeError("STDERR: $clean"); TunnelManager.addDeployErrorLog("STDERR: $clean")
                            }
                        }
                    }
                }
                if (!reader.ready() && !errReader.ready()) Thread.sleep(100)
            }
            result.toString()
        } catch (e: Exception) {
            val msg = "SSH exec error: ${e.message}"
            DeployManager.writeError(msg); TunnelManager.addDeployErrorLog(msg); "error: ${e.message}"
        } finally {
            try { channel?.disconnect() } catch (_: Exception) {}
        }
    }

    fun upload(localFile: File, remotePath: String) {
        if (!session.isConnected) { DeployManager.writeError("SSH upload: сессия разорвана"); throw Exception("Session is down") }
        var sftp: ChannelSftp? = null
        try {
            sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(15000)
            sftp.put(localFile.absolutePath, remotePath)
        } catch (e: Exception) {
            val msg = "SFTP upload error: ${e.message}"
            DeployManager.writeError(msg); TunnelManager.addDeployErrorLog(msg); throw e
        } finally {
            try { sftp?.disconnect() } catch (_: Exception) {}
        }
    }
}

private fun createSSHSession(host: String, user: String, pass: String, port: Int = 22): Session {
    val jsch = JSch()
    val session = jsch.getSession(user, host, port)
    session.setPassword(pass)
    session.setConfig(Properties().apply {
        put("StrictHostKeyChecking", "no"); put("ServerAliveInterval", "10"); put("ServerAliveCountMax", "6"); put("ConnectTimeout", "15000"); put("PreferredAuthentications", "password,keyboard-interactive")
    })
    session.connect(20000)
    return session
}

private suspend fun performDeploy(context: Context, host: String, user: String, pass: String, port: Int, mainPass: String, adminId: String, botToken: String, onProgress: (Float, String) -> Unit): Boolean = withContext(Dispatchers.IO) {
    var session: Session? = null
    try {
        onProgress(0.02f, "Подключение...")
        session = createSSHSession(host, user, pass, port)
        DeployManager.activeSession = session
        val ssh = SSHClient(session, pass, user)

        if (user == "root") {
            onProgress(0.04f, "Установка базовых пакетов...")
            ssh.exec("apt-get update && apt-get install -y sudo curl || yum install -y sudo curl", timeout = 120000L)
        }

        onProgress(0.05f, "Подготовка файлов...")
        val args = "${if (mainPass.isNotBlank()) "-password \"$mainPass\" " else ""}${if (adminId.isNotBlank()) "-admin \"$adminId\" " else ""}${if (botToken.isNotBlank()) "-bot-token \"$botToken\" " else ""}".trim()

        val scriptFile = File(context.cacheDir, "deploy.sh")
        val serverFile = File(context.cacheDir, "server")
        try {
            context.assets.open("deploy.sh").use { inp -> FileOutputStream(scriptFile).use { out -> inp.copyTo(out) } }
            context.assets.open("server").use { inp -> FileOutputStream(serverFile).use { out -> inp.copyTo(out) } }
        } catch (e: Exception) {
            val msg = "Ошибка: файлы не найдены в assets (${e.message})"
            DeployManager.stopDeploy(msg)
            DeployManager.writeError(msg)
            TunnelManager.addDeployErrorLog(msg)
            return@withContext false
        }

        onProgress(0.06f, "Загрузка на сервер...")
        ssh.upload(scriptFile, "/tmp/deploy.sh")
        ssh.upload(serverFile, "/tmp/wdtt-server")
        scriptFile.delete()
        serverFile.delete()

        onProgress(0.08f, "Установка...")
        val output = ssh.exec("sudo env WDTT_ARGS='$args' bash /tmp/deploy.sh", timeout = CMD_TIMEOUT)

        if (output.contains("✅") || output.contains("Деплой успешно") || output.contains("active")) {
            DeployManager.stopDeploy("success")
            TunnelManager.addDeploySuccessLog("Деплой успешно завершен.")
            return@withContext true
        } else if (output.contains("error:")) {
            val msg = "Ошибка выполнения скрипта: $output"
            DeployManager.stopDeploy("Ошибка выполнения скрипта")
            DeployManager.writeError(msg)
            TunnelManager.addDeployErrorLog(msg)
            return@withContext false
        } else {
            DeployManager.stopDeploy("success")
            TunnelManager.addDeploySuccessLog("Деплой завершён.")
            return@withContext true
        }
    } catch (e: Exception) {
        val msg = "Критическая ошибка деплоя: ${e.message ?: e.javaClass.simpleName}"
        DeployManager.stopDeploy(msg)
        DeployManager.writeError(msg)
        TunnelManager.addDeployErrorLog(msg)
        return@withContext false
    } finally {
        try { session?.disconnect() } catch (_: Exception) {}
        DeployManager.activeSession = null
    }
}

private suspend fun performUninstall(host: String, user: String, pass: String, port: Int, onProgress: (Float, String) -> Unit) = withContext(Dispatchers.IO) {
    var session: Session? = null
    try {
        onProgress(0.05f, "Подключение...")
        session = createSSHSession(host, user, pass, port)
        DeployManager.activeSession = session
        val ssh = SSHClient(session, pass, user)

        onProgress(0.15f, "Остановка сервиса...")
        ssh.exec("sudo systemctl unmask wdtt 2>/dev/null || true", timeout = 10000L)
        ssh.exec("sudo systemctl stop wdtt 2>/dev/null || true", timeout = 15000L)
        ssh.exec("sudo systemctl disable wdtt 2>/dev/null || true", timeout = 15000L)
        ssh.exec("sudo rm -f /etc/systemd/system/wdtt.service", timeout = 10000L)
        ssh.exec("sudo systemctl daemon-reload", timeout = 10000L)

        onProgress(0.30f, "Удаление бинарника...")
        ssh.exec("sudo pkill -9 -f wdtt-server 2>/dev/null || true", timeout = 10000L)
        ssh.exec("sudo rm -f /usr/local/bin/wdtt-server", timeout = 10000L)

        onProgress(0.45f, "Очистка iptables...")
        ssh.exec("sudo bash -c 'for i in 1 2 3 4 5; do iptables -t nat -D POSTROUTING -s 10.66.66.0/24 -j MASQUERADE 2>/dev/null || true; iptables -D INPUT -p udp --dport 56000 -j ACCEPT 2>/dev/null || true; iptables -D INPUT -p udp --dport 56001 -j ACCEPT 2>/dev/null || true; iptables -D INPUT -p udp --dport 1024:65535 -j ACCEPT 2>/dev/null || true; iptables -D FORWARD -j ACCEPT 2>/dev/null || true; done'", timeout = 15000L)

        onProgress(0.60f, "Удаление WireGuard...")
        ssh.exec("sudo ip link del wg0 2>/dev/null || true", timeout = 10000L)
        ssh.exec("sudo rm -rf /etc/wireguard/wg-keys.dat /etc/wireguard/passwords.json /etc/wireguard/server.log", timeout = 10000L)
        ssh.exec("sudo fuser -k 56001/udp 56000/udp 2>/dev/null || true", timeout = 10000L)

        onProgress(0.75f, "Удаление Full Cone NAT...")
        ssh.exec("sudo bash /tmp/deploy.sh uninstall 2>/dev/null || true", timeout = 30000L)

        onProgress(0.90f, "Очистка sysctl...")
        ssh.exec("sudo rm -f /etc/sysctl.d/99-wdtt.conf /etc/sysctl.d/99-vpn.conf", timeout = 10000L)
        ssh.exec("sudo sysctl --system >/dev/null 2>&1 || true", timeout = 15000L)

        onProgress(1.0f, "Готово!")
        DeployManager.stopDeploy("success")
    } catch (e: Exception) {
        val msg = "Ошибка очистки сервера: ${e.message ?: e.javaClass.simpleName}"
        DeployManager.stopDeploy(msg)
        DeployManager.writeError(msg)
        TunnelManager.addDeployErrorLog(msg)
    } finally {
        try { session?.disconnect() } catch (_: Exception) {}
        DeployManager.activeSession = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeploySecretsDialog(
    initialMainPass: String,
    initialAdminId: String,
    initialBotToken: String,
    initialSshPort: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit
) {
    var passInput by remember { mutableStateOf(initialMainPass) }
    var adminIdInput by remember { mutableStateOf(initialAdminId) }
    var botTokenInput by remember { mutableStateOf(initialBotToken) }
    var sshPortInput by remember { mutableStateOf(if (initialSshPort.isBlank()) "22" else initialSshPort) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(24.dp).fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Секреты Деплоя", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(value = passInput, onValueChange = { passInput = it }, label = { Text("Пароль туннеля (любой)") }, placeholder = { Text("Придумайте надежный пароль") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                Text("Телеграм бот", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = adminIdInput, onValueChange = { adminIdInput = it }, label = { Text("ID Админа (Опционально)") }, placeholder = { Text("ID из @getmyid_bot") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = botTokenInput, onValueChange = { botTokenInput = it }, label = { Text("Токен Бота (Опционально)") }, placeholder = { Text("Токен от BotFather") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                Text("Настройки SSH", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = sshPortInput, onValueChange = { sshPortInput = it }, label = { Text("SSH Порт") }, placeholder = { Text("22") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { 
                        onSave(passInput, adminIdInput, botTokenInput, if (sshPortInput.isBlank()) "22" else sshPortInput)
                    }, 
                    modifier = Modifier.fillMaxWidth().height(52.dp), 
                    shape = RoundedCornerShape(20.dp), 
                    enabled = passInput.isNotBlank()
                ) {
                    Text("Сохранить", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UninstallConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var confirmText by remember { mutableStateOf("") }
    val isConfirmed = confirmText.trim().lowercase() == "да"

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Удаление WDTT с сервера", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                Text("Будут удалены: бинарник, systemd-сервис, бот, конфигурация WireGuard, правила iptables и модуль Full Cone NAT.\n\nЭто действие необратимо.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = confirmText, onValueChange = { confirmText = it }, label = { Text("Введите «да» для подтверждения") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.error))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(20.dp)) { Text("Отмена") }
                    Button(onClick = onConfirm, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(20.dp), enabled = isConfirmed, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Default.Delete, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("Удалить", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}