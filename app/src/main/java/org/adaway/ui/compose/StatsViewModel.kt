package org.adaway.ui.compose

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adaway.db.AppDatabase
import org.adaway.db.entity.ListType
import org.adaway.model.adblocking.AdBlockModel
import org.adaway.util.WebServerUtils
import timber.log.Timber

/**
 * ViewModel for the new Compose UI: hosts-list statistics (Room) plus
 * one retained native statistics observer (10-second polling plus optional push).
 */
class StatsViewModel @JvmOverloads constructor(
    application: Application,
    private val observeInBackground: Boolean = true,
) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)

    // Hosts-list statistics (Room LiveData)
    val blockedHostCount: LiveData<Int> = database.hostsListItemDao().getBlockedHostCount()
    val allowedHostCount: LiveData<Int> = database.hostsListItemDao().getAllowedHostCount()
    val redirectHostCount: LiveData<Int> = database.hostsListItemDao().getRedirectHostCount()

    // Host entries DAO (for log type lookup)
    val hostEntryDao = database.hostEntryDao()

    // Hosts list DAO (for user-defined rules)
    val hostsListItemDao = database.hostsListItemDao()

    // Ad block model (state, web server enabled, etc.)
    val adBlockModel: AdBlockModel = (application as org.adaway.AdAwayApplication).getAdBlockModel()

    private val statsObserver = StatsObservationCoordinator(viewModelScope, AndroidStatsTransport(application))
    val statsObservation: StateFlow<StatsObservationState> = statsObserver.state
    // Read-only compatibility views; all decisions are made by statsObservation.
    val serverStats = statsObservation.map { it.stats }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val topHostsObservation = statsObservation.map { it.ranking }.stateIn(viewModelScope, SharingStarted.Eagerly, TopHostsObservation())
    val statsUpdatedAt = statsObservation.map { it.observedAtMillis }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val statsError = statsObservation.map { it.legacyError }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    internal val statsRefreshTick = statsObserver.refreshTick
    internal val queryLogBrowser = QueryLogBrowser(viewModelScope, ::loadQueryLogPage)
    val certificateObservation = CertificateObservation(viewModelScope,
        { CertificateProbe.read(application.applicationContext) })
    val certificateAction = CertificateAction(viewModelScope, certificateObservation)
    internal val contentResponseCache = ContentResponseCache()

    private val lifecycleObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
        when (event) {
            androidx.lifecycle.Lifecycle.Event.ON_START -> statsObserver.setForeground(true, isRealtimeEnabled(application))
            androidx.lifecycle.Lifecycle.Event.ON_STOP -> statsObserver.setForeground(false)
            else -> Unit
        }
    }
    init {
        if (observeInBackground) {
            // An already-started lifecycle replays ON_START; there is no duplicate eager start.
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
            if (org.adaway.helper.PreferenceHelper.getUpdateCheckAppStartup(application)) {
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    (application as org.adaway.AdAwayApplication).getUpdateModel().checkForUpdate(false)
                }
            }
        }
    }
    fun startRealtime() = statsObserver.setRealtime(true)
    fun stopRealtime() = statsObserver.setRealtime(false)
    fun refreshServerStats() = statsObserver.refresh()

    /** Cursor pins the original history; expired generations fail without clearing the UI. */
    suspend fun loadQueryLogPage(offset: Int, limit: Int, cursor: QueryLogCursor?, filter: QueryLogFilter): QueryLogPage =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            filter.requireValid()
            val json = org.adaway.util.WebServerStats.getFilteredQueryLogPage(offset, limit, cursor?.generation,
                cursor?.anchor, cursor?.queryKey, filter.action, filter.family, filter.type, filter.uid, filter.query)
            QueryLogPage.parse(json, offset, cursor, filter)
        }

    /**
     * Load user-defined rule entries (source_id == 1: manual whitelist,
     * blacklist and redirect rules) on a background thread.
     */
    fun loadUserRules(onResult: (List<org.adaway.db.entity.HostListItem>) -> Unit) {
        viewModelScope.launch {
            val items = withContext(kotlinx.coroutines.Dispatchers.IO) {
                hostsListItemDao.getUserList()
            }
            onResult(items)
        }
    }

    /** A bounded page and its count share one committed database view. */
    suspend fun loadRulePage(type: Int, query: String, filter: Int, requestedPage: Int): RuleBrowserPage =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            require(filter in 0..3 && requestedPage >= 0)
            database.runInTransaction(java.util.concurrent.Callable {
                val total = hostsListItemDao.countBrowserRules(type, query, filter)
                val page = requestedPage.coerceAtMost(if (total == 0) 0 else (total - 1) / RULES_PAGE_SIZE)
                RuleBrowserPage(hostsListItemDao.browseRules(type, query, filter, RULES_PAGE_SIZE,
                    page * RULES_PAGE_SIZE), total, page)
            })
        }

    /**
     * Load hosts-list entries of the given type (0=BLOCKED, 1=ALLOWED,
     * 2=REDIRECTED), capped at [limit] rows, plus the total count.
     */
    fun loadRulesByType(
        type: Int,
        limit: Int,
        onResult: (items: List<org.adaway.db.entity.HostListItem>, total: Int) -> Unit,
    ) {
        viewModelScope.launch {
            val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                hostsListItemDao.getListByType(type, limit) to hostsListItemDao.getCountByType(type)
            }
            onResult(result.first, result.second)
        }
    }

    /**
     * Search hosts-list entries of a type by substring (case-insensitive LIKE),
     * capped at [limit] rows plus the number of matches.
     */
    fun searchRules(
        type: Int,
        query: String,
        limit: Int,
        onResult: (items: List<org.adaway.db.entity.HostListItem>, total: Int) -> Unit,
    ) {
        val pattern = "%" + query.trim() + "%"
        viewModelScope.launch {
            val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                hostsListItemDao.searchByType(type, pattern, limit) to
                    hostsListItemDao.countSearchByType(type, pattern)
            }
            onResult(result.first, result.second)
        }
    }

    /**
     * Load a map sourceId -> label for rule rows (1 = "user").
     */
    fun loadSourceLabels(onResult: (Map<Int, String>) -> Unit) {
        viewModelScope.launch {
            val labels = withContext(kotlinx.coroutines.Dispatchers.IO) {
                database.hostsSourceDao().getAll().associate { it.id to it.label }
            }
            onResult(labels)
        }
    }

    /** True while a hosts sync (sources + apply) is in progress. */
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing
    private val _syncStatus = MutableStateFlow(HostsSyncStatus.IDLE)
    val syncStatus: StateFlow<HostsSyncStatus> = _syncStatus

    /** Load all rule sources (id != 1, i.e. everything but the user list). */
    fun loadSources(onResult: (List<org.adaway.db.entity.HostsSource>) -> Unit) {
        viewModelScope.launch {
            val sources = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    migrateSourceNames()
                    database.hostsSourceDao().getAll()
                } catch (failure: Exception) {
                    Timber.w("Source list unavailable (%s)", failure.javaClass.simpleName)
                    null
                }
            }
            if (sources != null) onResult(sources)
        }
    }

    /** One-time local label repair; later user choices such as "raw" stay verbatim. */
    private fun migrateSourceNames() = synchronized(org.adaway.model.source.SourcePresentation::class.java) {
        val preferences = getApplication<Application>().getSharedPreferences("source-presentation", android.content.Context.MODE_PRIVATE)
        if (!preferences.getBoolean("legacy-labels-repaired", false)) {
            database.runInTransaction {
                val dao = database.hostsSourceDao()
                for (source in dao.getAll()) if (source.id != 1) {
                    val label = org.adaway.model.source.SourcePresentation.legacyName(source.label, source.url)
                    if (label != source.label) dao.rename(source.id, label)
                }
            }
            check(preferences.edit().putBoolean("legacy-labels-repaired", true).commit())
        }
    }

    /** Local edits rebuild the active snapshot without requiring a source download. */
    fun setSourceEnabled(id: Int, enabled: Boolean, onDone: (Boolean) -> Unit) = editLocalRules({
        val dao = database.hostsSourceDao()
        dao.setSourceEnabled(id, enabled)
        dao.setSourceItemsEnabled(id, enabled)
    }, onDone)

    fun removeSource(source: org.adaway.db.entity.HostsSource, onDone: (Boolean) -> Unit) = editLocalRules({
        require(source.id != 1)
        database.hostsSourceDao().delete(source)
    }, onDone)

    fun removeRule(host: String, onDone: (Boolean) -> Unit) = editLocalRules({
        hostsListItemDao.deleteUserFromHost(host)
    }, onDone)

    private fun editLocalRules(edit: () -> Unit, onDone: (Boolean) -> Unit) {
        if (!_syncing.compareAndSet(false, true)) { onDone(false); return }
        _syncStatus.value = HostsSyncStatus.RUNNING
        viewModelScope.launch {
            var committed = false
            var failure = HostsSyncStatus.SAVE_FAILED
            try {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val app = getApplication<org.adaway.AdAwayApplication>()
                    app.getSourceModel().editRules { edit() }
                    committed = true
                    failure = HostsSyncStatus.APPLY_FAILED
                    adBlockModel.apply()
                    failure = HostsSyncStatus.SERVER_REFRESH_FAILED
                    check(WebServerUtils.exportBlockList(app) >= 0)
                    exportExemptions(app)
                    if (WebServerUtils.isWebServerReachable(app)) check(WebServerUtils.reloadConfig())
                }
                _syncStatus.value = HostsSyncStatus.LOCAL_SUCCESS
            } catch (e: kotlinx.coroutines.CancellationException) {
                _syncStatus.value = HostsSyncStatus.IDLE
                throw e
            } catch (e: Exception) {
                _syncStatus.value = failure
                Timber.w(e, "Local rule edit or application failed")
            } finally {
                _syncing.value = false
                onDone(committed)
            }
        }
    }

    /**
     * 添加一条用户自定义规则（source_id == 1）。
     * @param host    域名（不带通配符）
     * @param type    0=拦截 1=放行 2=重定向
     * @param redirection 重定向目标（type==2 时使用）
     */
    /**
     * 放行整个应用：与设置页"应用监控"里的放行开关共用同一状态，写进
     * allowlist.txt，服务器随后不再拦截该 uid。
     */
    fun allowAppForever(uid: Int) {
        setAppAllowed(getApplication<Application>(), uid, true)
    }

    /**
     * 把规则库里的 ALLOWED 条目导出成原生读取的 `domain_allowlist.txt`。
     *
     * <p>放在 ViewModel 而不是 WebServerUtils：后者是纯 JVM 工具类，一旦在那里引用
     * Room 的 DAO，JVM 单元测试环境里没有生成的实现，抛出的往往是 `Error` 而不是
     * `Exception`（普通 catch 兜不住），会把与导出无关的测试一起带崩。这里在协程的
     * IO 上下文里调用，并显式兜住 `Throwable`：导出失败只是豁免名单不刷新，不该影响
     * 规则本身的写入。</p>
     */
    private suspend fun exportExemptions(app: Application) {
        try {
            val allowed = hostsListItemDao.getListByType(
                org.adaway.db.entity.ListType.ALLOWED.value,
                10000,
            )
            val file = java.io.File(
                org.adaway.util.WebServerUtils.getResourcePath(app).toFile(),
                "domain_allowlist.txt",
            )
            file.parentFile?.mkdirs()
            org.adaway.model.source.AtomicRuleFile.write(file) { writer ->
                writer.write("# 由 ADBlock 生成：按域名解除过滤（原生服务器每次请求都会重读）\n")
                for (item in allowed) {
                    val host = org.adaway.model.rules.ExemptionRules.normalize(item.host.orEmpty()) ?: continue
                    if (item.isEnabled && host.isNotEmpty() && !host.startsWith("#")) {
                        writer.write(host)
                        writer.write("\n")
                    }
                }
            }
            Timber.i("Exported %d exemption(s) to %s", allowed.size, file.absolutePath)
        } catch (throwable: Throwable) {
            Timber.w(throwable, "Failed to export the domain allow list")
        }
    }

    /**
     * 只放行一个域：写规则库（ALLOWED，单一事实来源），随后导出豁免名单。
     * 原生服务器每请求重读 domain_allowlist.txt，所以立即生效。
     */
    fun allowHost(host: String, onDone: () -> Unit = {}) {
        addExemptionRules(listOf(host)) { onDone() }
    }

    /**
     * 批量加入"解除过滤"规则（多语法输入已由 ExemptionRules.parseAll 归一化）。
     *
     * @param hosts  规范化后的域列表。
     * @param onDone 实际写入的条数。
     */
    fun addExemptionRules(hosts: List<String>, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val added = withContext(kotlinx.coroutines.Dispatchers.IO) {
                var count = 0
                for (host in hosts) {
                    try {
                        val item = org.adaway.db.entity.HostListItem()
                        item.host = host
                        item.type = org.adaway.db.entity.ListType.ALLOWED
                        item.sourceId = 1
                        hostsListItemDao.insert(item)
                        hostEntryDao.allowHost(host)
                        count++
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to add exemption %s", host)
                    }
                }
                if (count > 0) {
                    val app = getApplication<Application>()
                    org.adaway.util.WebServerUtils.exportBlockList(app)
                    exportExemptions(app)
                }
                count
            }
            onDone(added)
        }
    }

    /**
     * 应用自定义重定向地址（hosts 档）：写偏好 → 重新生成 hosts → 导出规则 → 重载
     * 服务器配置，并撤销劫持规则（自定义地址不依赖劫持，也就不会占用整机流量）。
     */
    fun applyCustomAddresses(ipv4: String, ipv6: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val app = getApplication<Application>()
                    org.adaway.util.BlockMode.applyCustom(app, ipv4.trim(), ipv6.trim())
                    adBlockModel.apply()
                    org.adaway.util.WebServerUtils.exportBlockList(app)
                    org.adaway.util.WebServerUtils.reloadConfig()
                    org.adaway.model.root.HijackModel.disable()
                    true
                } catch (e: Exception) {
                    Timber.w(e, "Failed to apply the custom redirection target")
                    false
                }
            }
            onDone(ok)
        }
    }

    /**
     * 新增用户规则，并把成功/失败交给调用方。
     *
     * <p>此前回调不带结果：重复规则、域名格式非法或数据库写入失败时，界面只会看到对话框
     * 关闭（用户以为添加成功）。改签名而不是复制一份实现 —— Kotlin 允许把无参 lambda 传给
     * 单参函数类型（忽略 it 即可），所以既有调用点不需要改动。</p>
     */
    fun addUserRule(host: String, type: Int, redirection: String?, onDone: (Boolean) -> Unit) = editLocalRules({
        val normalized = host.trim().lowercase(java.util.Locale.ROOT).removeSuffix(".")
        val wildcard = normalized.contains('*') || normalized.contains('?')
        require(if (wildcard) type != ListType.BLOCKED.value && org.adaway.util.RegexUtils.isValidWildcardHostname(normalized)
            else org.adaway.util.RegexUtils.isValidHostname(normalized))
        val item = org.adaway.db.entity.HostListItem().apply {
            this.host = normalized
            this.type = ListType.fromValue(type)
            sourceId = 1
            isEnabled = true
            if (type == ListType.REDIRECTED.value) {
                require(!redirection.isNullOrBlank() && org.adaway.util.RegexUtils.isValidIP(redirection.trim()))
                this.redirection = redirection.trim()
            }
        }
        // One personal action per name: replacing it retains the documented later-write priority.
        hostsListItemDao.deleteUserFromHost(normalized)
        hostsListItemDao.insert(item)
    }, onDone)

    /**
     * 添加一个规则源：远程订阅链接（https://…）或本地规则文件（content://…）。
     *
     * 本地文件通过 SAF 选择，调用方必须已调用 takePersistableUriPermission()
     * 获得持久化读权限，否则同步时会读取失败。
     *
     * @param url   订阅链接或本地文件 URI
     * @param label 显示名称（本地文件使用文件名；为空时按 URL 推导）
     * @param onDone 结果回调（false = 链接非法、已存在或未能写入）
     */
    fun addSource(url: String, label: String? = null, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    migrateSourceNames()
                    val normalized = url.trim()
                    if (label != null && (label.trim().length > 80 || label.any { it.isISOControl() })) {
                        return@withContext false
                    }
                    if (!org.adaway.db.entity.HostsSource.isValidUrl(normalized)) {
                        Timber.w("Rejected hosts source: unsupported URL format")
                        return@withContext false
                    }
                    val dao = database.hostsSourceDao()
                    val source = org.adaway.db.entity.HostsSource()
                    source.setUrl(normalized)
                    source.setLabel(
                        label?.takeIf { it.isNotBlank() } ?: deriveSourceLabel(normalized),
                    )
                    dao.insert(source) > 0
                } catch (e: Exception) {
                    // 唯一索引冲突（源已存在）等：返回 false 由界面提示
                    Timber.w("Failed to add source (%s)", e.javaClass.simpleName)
                    false
                }
            }
            onDone(ok)
        }
    }

    private fun deriveSourceLabel(url: String) = org.adaway.model.source.SourcePresentation.name(null, url)

    fun renameSource(id: Int, label: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val name = label.trim()
            val ok = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    migrateSourceNames()
                    name.isNotEmpty() && name.length <= 80 && name.none { it.isISOControl() } &&
                        database.hostsSourceDao().rename(id, name) == 1
                } catch (failure: Exception) {
                    Timber.w("Source rename failed (%s)", failure.javaClass.simpleName)
                    false
                }
            }
            onDone(ok)
        }
    }

    /** Synchronize enabled sources and report the phase that failed to the UI. */
    fun syncHosts() {
        if (!_syncing.compareAndSet(false, true)) return
        _syncStatus.value = HostsSyncStatus.RUNNING
        viewModelScope.launch {
            var failure = HostsSyncStatus.SOURCE_FAILED
            try {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val application = getApplication<org.adaway.AdAwayApplication>()
                    val sourceModel = application.getSourceModel()
                    sourceModel.retrieveHostsSources()
                    failure = HostsSyncStatus.APPLY_FAILED
                    adBlockModel.apply()
                    failure = HostsSyncStatus.SERVER_REFRESH_FAILED
                    check(WebServerUtils.exportBlockList(application) >= 0)
                    // A stopped optional server does not make a Hosts sync fail.
                    if (WebServerUtils.isWebServerReachable(application)) {
                        check(WebServerUtils.reloadConfig())
                    }
                }
                _syncStatus.value = HostsSyncStatus.SUCCESS
                Timber.i("Hosts sync completed")
            } catch (e: kotlinx.coroutines.CancellationException) {
                _syncStatus.value = HostsSyncStatus.IDLE
                throw e
            } catch (e: Exception) {
                _syncStatus.value = failure
                Timber.w(e, "Hosts sync failed")
            } finally {
                _syncing.value = false
            }
        }
    }

    /**
     * 切换拦截模式（[org.adaway.util.BlockMode.LOCALHOST] = 127.0.0.1 本机拦截页，
     * [org.adaway.util.BlockMode.NULL_ROUTE] = 0.0.0.0 空路由），并立即重新生成
     * hosts 文件使其生效。
     */
    fun applyBlockMode(mode: Int, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val app = getApplication<Application>()
                    if (mode == org.adaway.util.BlockMode.HIJACK) {
                        /*
                         * Refuse a new transparent hijack before changing
                         * persisted block mode or rewriting the hosts file.
                         * VPN ownership is independent of whether AdGuard is
                         * merely installed. Unknown is not authorization.
                         */
                        val owner = org.adaway.model.traffic.AndroidVpnObserver.read(app, false)
                        if (owner != org.adaway.model.traffic.TrafficModePolicy.VpnOwner.NONE) {
                            Timber.w("Cannot enable hijack while VPN owner is %s", owner)
                            return@withContext false
                        }
                    }
                    org.adaway.util.BlockMode.apply(app, mode)
                    // 1) 先把新目标地址写进 hosts 并生效，再导出规则文件让
                    //    服务器重读（透明代理直接按导出规则过滤）
                    adBlockModel.apply()
                    org.adaway.util.WebServerUtils.exportBlockList(app)
                    org.adaway.util.WebServerUtils.reloadConfig()
                    if (mode == org.adaway.util.BlockMode.HIJACK) {
                        // 2) 劫持模式：确保服务器（--proxy-filter）在运行，
                        //    再挂上 iptables 重定向规则；服务器不在就不劫持，
                        //    避免把整机流量导到没人监听的口上。
                        if (!org.adaway.util.WebServerUtils.isWebServerRunning()) {
                            org.adaway.util.WebServerUtils.startWebServer(app)
                            kotlinx.coroutines.delay(2000)
                        }
                        if (!org.adaway.util.WebServerUtils.isWebServerRunning()) {
                            Timber.w("Hijack mode: web server is not running, rules not installed")
                            false
                        } else {
                            org.adaway.model.root.HijackModel.enable(app)
                        }
                    } else {
                        // 其它模式：立刻撤销劫持规则，恢复正常上网
                        org.adaway.model.root.HijackModel.disable()
                        true
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to apply blocking mode %d", mode)
                    false
                }
            }
            onDone(ok)
        }
    }

    /**
     * 本机规则统计（拦截/放行/重定向 条数）。
     *
     * 服务器没在运行时用它在统计页兜底显示，页面不会因为“拿不到服务器数据”
     * 而完全空白；数据直接来自 Room，不产生任何网络/进程开销。
     */
    fun loadLocalHostCounts(onResult: (Int, Int, Int) -> Unit) {
        viewModelScope.launch {
            val counts = withContext(kotlinx.coroutines.Dispatchers.IO) {
                Triple(
                    hostsListItemDao.getCountByType(org.adaway.db.entity.ListType.BLOCKED.value),
                    hostsListItemDao.getCountByType(org.adaway.db.entity.ListType.ALLOWED.value),
                    hostsListItemDao.getCountByType(org.adaway.db.entity.ListType.REDIRECTED.value),
                )
            }
            onResult(counts.first, counts.second, counts.third)
        }
    }

    /** 当前拦截模式：0=127.0.0.1 本机拦截，1=0.0.0.0 空路由，2=自定义。 */
    fun currentBlockMode(): Int = org.adaway.util.BlockMode.current(getApplication())

    /**
     * 检查应用更新（更新源：GitHub Releases / wxcvm/AdAway）。
     *
     * @param force 为 true 时忽略 30 分钟的结果缓存
     * @param onResult 回调（新版本对象, 失败原因）；失败时对象为 null
     */
    fun checkUpdate(
        force: Boolean,
        onResult: (org.adaway.model.update.Manifest?, String?) -> Unit,
    ) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val model = (app as org.adaway.AdAwayApplication).getUpdateModel()
            val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    model.checkForUpdate(force)
                } catch (e: Exception) {
                    Timber.w(e, "Update check failed")
                    null
                }
            }
            onResult(result, if (result == null) model.lastError else null)
        }
    }

    /** 下载最新版本 APK（校验签名/版本后由系统安装器安装）。 */
    fun downloadUpdate(): Long {
        val app = getApplication<Application>()
        return (app as org.adaway.AdAwayApplication).getUpdateModel().update()
    }

    override fun onCleared() {
        statsObserver.close()
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        super.onCleared()
    }
}
