package com.tyrnguard.client

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    
    companion object {
        private val Context.dataStore by preferencesDataStore("settings")
        private val PEER = stringPreferencesKey("peer")
        private val VK_HASHES = stringPreferencesKey("vk_hashes")
        private val SECONDARY_VK_HASH = stringPreferencesKey("secondary_vk_hash")
        private val WORKERS_PER_HASH = intPreferencesKey("workers_per_hash")
        private val PROTOCOL = stringPreferencesKey("protocol")
        private val LISTEN_PORT = intPreferencesKey("listen_port")
        private val SNI = stringPreferencesKey("sni")
        private val USER_AGENT = stringPreferencesKey("user_agent")
        private val DEPLOY_IP = stringPreferencesKey("deploy_ip")
        private val DEPLOY_LOGIN = stringPreferencesKey("deploy_login")
        private val DEPLOY_PASSWORD = stringPreferencesKey("deploy_password")
        private val DEPLOY_SSH_PORT = stringPreferencesKey("deploy_ssh_port")
        private val EXCLUDED_APPS = stringPreferencesKey("excluded_apps")
        private val CONNECTION_PASSWORD = stringPreferencesKey("connection_password")
        private val DEPLOY_MAIN_PASSWORD = stringPreferencesKey("deploy_main_password")
        private val DEPLOY_ADMIN_ID = stringPreferencesKey("deploy_admin_id")
        private val DEPLOY_BOT_TOKEN = stringPreferencesKey("deploy_bot_token")
        private val CAPTCHA_MODE = stringPreferencesKey("captcha_mode") 
        private val CAPTCHA_SOLVE_METHOD = stringPreferencesKey("captcha_solve_method") 
        private val IS_WHITELIST = booleanPreferencesKey("is_whitelist")
        private val THEME_MODE = stringPreferencesKey("theme_mode") 
        private val USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color") 
        private val SAVED_SERVERS = stringPreferencesKey("saved_servers_list")
        private val AUTO_CONNECT_ON_BOOT = booleanPreferencesKey("auto_connect_boot")
        private val CUSTOM_DNS = stringPreferencesKey("custom_dns")
        
        // НОВЫЙ КЛЮЧ ДЛЯ MTU
        private val CUSTOM_MTU = intPreferencesKey("custom_mtu")
		private val CUSTOM_DNS_IP = stringPreferencesKey("custom_dns_ip")
    }

    private val dataStore = appContext.dataStore

    val peer: Flow<String> = dataStore.data.map { it[PEER] ?: "" }
    val vkHashes: Flow<String> = dataStore.data.map { it[VK_HASHES] ?: "" }
    val secondaryVkHash: Flow<String> = dataStore.data.map { it[SECONDARY_VK_HASH] ?: "" }
    val workersPerHash: Flow<Int> = dataStore.data.map { it[WORKERS_PER_HASH] ?: 16 }
    val protocol: Flow<String> = dataStore.data.map { it[PROTOCOL] ?: "udp" }
    val listenPort: Flow<Int> = dataStore.data.map { it[LISTEN_PORT] ?: 9000 }
    val sni: Flow<String> = dataStore.data.map { it[SNI] ?: "" }
    val userAgent: Flow<String> = dataStore.data.map { it[USER_AGENT] ?: "" }
    val deployIp: Flow<String> = dataStore.data.map { it[DEPLOY_IP] ?: "" }
    val deployLogin: Flow<String> = dataStore.data.map { it[DEPLOY_LOGIN] ?: "" }
    val deployPassword: Flow<String> = dataStore.data.map { it[DEPLOY_PASSWORD] ?: "" }
    val deploySshPort: Flow<String> = dataStore.data.map { it[DEPLOY_SSH_PORT] ?: "" }
    val excludedApps: Flow<String> = dataStore.data.map { it[EXCLUDED_APPS] ?: "" }
    val connectionPassword: Flow<String> = dataStore.data.map { it[CONNECTION_PASSWORD] ?: "" }
    val deployMainPassword: Flow<String> = dataStore.data.map { it[DEPLOY_MAIN_PASSWORD] ?: "" }
    val deployAdminId: Flow<String> = dataStore.data.map { it[DEPLOY_ADMIN_ID] ?: "" }
    val deployBotToken: Flow<String> = dataStore.data.map { it[DEPLOY_BOT_TOKEN] ?: "" }
    val captchaMode: Flow<String> = dataStore.data.map { it[CAPTCHA_MODE] ?: "wv" }
    val captchaSolveMethod: Flow<String> = dataStore.data.map { it[CAPTCHA_SOLVE_METHOD] ?: "manual" }
    val isWhitelist: Flow<Boolean> = dataStore.data.map { it[IS_WHITELIST] ?: false }
    val themeMode: Flow<String> = dataStore.data.map { it[THEME_MODE] ?: "system" }
    val useDynamicColor: Flow<Boolean> = dataStore.data.map { it[USE_DYNAMIC_COLOR] ?: true }
    val savedServersJson: Flow<String> = dataStore.data.map { it[SAVED_SERVERS] ?: "[]" }
    val autoConnectOnBoot: Flow<Boolean> = dataStore.data.map { it[AUTO_CONNECT_ON_BOOT] ?: false }
    val customDns: Flow<String> = dataStore.data.map { it[CUSTOM_DNS] ?: "default" }
    
    // НОВЫЙ FLOW ДЛЯ MTU (0 = Авто)
    val customMtu: Flow<Int> = dataStore.data.map { it[CUSTOM_MTU] ?: 0 }
  
    val customDnsIp: Flow<String> = dataStore.data.map { it[CUSTOM_DNS_IP] ?: "1.1.1.1" }
    suspend fun saveCustomDnsIp(ip: String) { dataStore.edit { prefs -> prefs[CUSTOM_DNS_IP] = ip } }    
    suspend fun saveThemeMode(mode: String) { dataStore.edit { prefs -> prefs[THEME_MODE] = mode } }
    suspend fun saveDynamicColor(enabled: Boolean) { dataStore.edit { prefs -> prefs[USE_DYNAMIC_COLOR] = enabled } }
    suspend fun saveServersList(jsonArrayString: String) { dataStore.edit { prefs -> prefs[SAVED_SERVERS] = jsonArrayString } }
    suspend fun save(peer: String, vkHashes: String, secondaryVkHash: String, workersPerHash: Int, protocol: String, listenPort: Int, sni: String = "") {
        dataStore.edit { prefs -> prefs[PEER] = peer; prefs[VK_HASHES] = vkHashes; prefs[SECONDARY_VK_HASH] = secondaryVkHash; prefs[WORKERS_PER_HASH] = workersPerHash; prefs[PROTOCOL] = protocol; prefs[LISTEN_PORT] = listenPort; prefs[SNI] = sni }
    }
    suspend fun saveUserAgent(ua: String) { dataStore.edit { prefs -> prefs[USER_AGENT] = ua } }
    suspend fun saveDeploy(ip: String, login: String, pass: String, sshPort: String) { dataStore.edit { prefs -> prefs[DEPLOY_IP] = ip; prefs[DEPLOY_LOGIN] = login; prefs[DEPLOY_PASSWORD] = pass; prefs[DEPLOY_SSH_PORT] = sshPort } }
    suspend fun saveExcludedApps(packages: String) { dataStore.edit { prefs -> prefs[EXCLUDED_APPS] = packages } }
    suspend fun saveConnectionPassword(password: String) { dataStore.edit { prefs -> prefs[CONNECTION_PASSWORD] = password } }
    suspend fun saveDeploySecrets(mainPass: String, adminId: String, botToken: String, sshPort: String) { dataStore.edit { prefs -> prefs[DEPLOY_MAIN_PASSWORD] = mainPass; prefs[DEPLOY_ADMIN_ID] = adminId; prefs[DEPLOY_BOT_TOKEN] = botToken; prefs[DEPLOY_SSH_PORT] = sshPort } }
    suspend fun saveCaptchaMode(mode: String) { dataStore.edit { prefs -> prefs[CAPTCHA_MODE] = mode } }
    suspend fun saveCaptchaSolveMethod(method: String) { dataStore.edit { prefs -> prefs[CAPTCHA_SOLVE_METHOD] = method } }
    suspend fun saveIsWhitelist(enabled: Boolean) { dataStore.edit { prefs -> prefs[IS_WHITELIST] = enabled } }
    suspend fun saveExceptionsMode(packages: String, isWhitelist: Boolean) { dataStore.edit { prefs -> prefs[EXCLUDED_APPS] = packages; prefs[IS_WHITELIST] = isWhitelist } }
    suspend fun saveAutoConnect(enabled: Boolean) { dataStore.edit { prefs -> prefs[AUTO_CONNECT_ON_BOOT] = enabled } }
    suspend fun saveCustomDns(dns: String) { dataStore.edit { prefs -> prefs[CUSTOM_DNS] = dns } }
    
    // НОВАЯ ФУНКЦИЯ СОХРАНЕНИЯ MTU
    suspend fun saveCustomMtu(mtu: Int) { dataStore.edit { prefs -> prefs[CUSTOM_MTU] = mtu } }
}