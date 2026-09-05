# AdAway fork — bug fixes & optimizations

Repository: wxcvm/AdAway (private fork, commit 13cdcdf).
Method: read-only static audit (Java / Kotlin / Compose / native) + targeted fixes.
No Android SDK or Gradle network was available, so changes are source-level only and were not compiled. Each is a minimal, high-confidence correction.

## Applied fixes (12)

### Model / loader
1. model/source/SourceModel.java — NPE when a content:// hosts source returns a null InputStream (file providers can do that). new InputStreamReader(null) throws an NPE that the surrounding catch(IOException) could not catch, aborting the whole sync/apply loop. Added an explicit null check that throws an IOException.
2. model/source/SourceLoader.java — allowlist inline comment stripping: 'if (indexOf == 1) line = substring(0, indexOf)' only stripped (and truncated to one char) when # was at index 1, so real inline comments (host.example # note) corrupted or dropped allow entries. Changed to indexOf > 0.
3. model/source/SourceLoader.java — HostListItemParser on InterruptedException exited without emitting its end-of-source marker, so ItemInserter (which waits for workerStopped >= parserCount) could block forever on hostListItemQueue.take(). Now emits the marker on interrupt.
4. model/source/SourceModel.java — cachedHttpClient lazy init was a data race (non-volatile write read by other threads during a concurrent sync/update check). Marked volatile.

### Web server / resources
5. util/WebServerUtils.java — ensureCaptivePortalAllowlist closed the BufferedReader explicitly (not in finally), leaking the descriptor if a read threw. Moved to try-with-resources.
6. util/WebServerUtils.java — same code closed the FileWriter explicitly; an IOException mid-write leaked it. Moved to try-with-resources.

### Compose UI
7. ui/compose/StatsViewModel.kt — onCleared() only cancelled pollingJob, leaving the process-scope LifecycleObserver registered (retains the cleared ViewModel) and wsJob running. Now also removes the observer and cancels wsJob.
8. ui/compose/RulesScreen.kt — LazyColumn used key = 'host:sourceId', which collides for multiple user rules sharing sourceId = 1 (e.g. the same host added as block + allow), risking dropped rows or IllegalArgumentException. Switched to the auto-increment id PK.

### Database / lists UI
9. db/dao/HostsSourceDao.java — toggleEnabled() issued two UPDATEs (hosts_sources, hosts_lists) without a transaction; an interruption between them left the two enabled flags inconsistent. Annotated @Transaction.
10. ui/lists/ListsFilter.java — convertToLikeQuery dereferenced a possibly-null query (from a malformed ACTION_SEARCH intent), crashing the Lists screen. Null-guarded to empty string.
11. ui/hosts/HostsSourcesAdapter.java — DiffUtil.areContentsTheSame relied on HostsSource.equals(), which omits size and label, so after a hosts sync changed a source's host count the row never rebind and the shown count stayed stale. Now also compares size and label.
12. ui/lists/type/AbstractListFragment.java — getData().observe(getViewLifecycleOwner(), ...) was called inside onCreateView, before the Fragment's view lifecycle owner exists (throws IllegalStateException on modern AndroidX). Moved to onViewCreated (adapter kept as a field).

## Documented recommendations (not applied — higher risk / needs a device build)

- SourceLoader + SourceModel: ItemInserter holds one long runInTransaction for the whole source parse while checkForUpdate() writes to the DB without the same lock — a concurrent update check can block for the download duration. Consider locking both or scoping the transaction to the insert phase only.
- SourceModel.retrieveHostsSources: sources whose server sends no Last-Modified/ETag are treated as 'now', so the 'no update' skip never triggers and they are re-downloaded/re-parsed on every sync. Consider persisting the last-fetched online date.
- model/update/UpdateModel.java: update() registers an ApkDownloadReceiver with RECEIVER_EXPORTED and only unregisters a previous one; it is never unregistered on teardown. Prefer RECEIVER_NOT_EXPORTED plus a cleanup path.
- broadcast/BootReceiver.java: the goAsync() work (delay + retries + verify ~55 s) may exceed the receiver window the comment assumes; on slow root init boot work can be aborted. Consider a foreground service / WorkManager.
- Compose: observeAsStateCompat uses observeForever (not lifecycle-aware) in several screens; webServerRunning is read on the main thread during composition; RulesScreen shows the previous tab's rows until a reload; StatisticsScreen 'all' chart mode mixes hourly + daily series.
- db/Migrations.java: MIGRATION_1_2 inserts the user source with the current content://org.adaway/user/hosts URL, but MIGRATION_5_6 only remaps WHERE url = 'file://app/user/hosts', so a 1→8 migrated DB may leave the user source with allowEnabled/redirectEnabled = 0. Confirm intended migration behavior before changing frozen migration scripts.

## Notes
Native code (webserver/jni/webserver.c, tcpdump, libpcap, mongoose) was reviewed; it is already heavily hardened (SELinux staging, SNI cache, cert rotation, per-app uid tracing). No single clear defect found to change without a device test.