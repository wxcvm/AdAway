package org.adaway.model.source;

import static org.adaway.db.entity.ListType.ALLOWED;
import static org.adaway.db.entity.ListType.BLOCKED;
import static org.adaway.db.entity.ListType.REDIRECTED;
import static org.adaway.util.Constants.BOGUS_IPV4;
import static org.adaway.util.Constants.LOCALHOST_HOSTNAME;
import static org.adaway.util.Constants.LOCALHOST_IPV4;
import static org.adaway.util.Constants.LOCALHOST_IPV6;

import org.adaway.db.AppDatabase;
import org.adaway.db.dao.HostListItemDao;
import org.adaway.db.entity.HostListItem;
import org.adaway.db.entity.HostsSource;
import org.adaway.db.entity.ListType;
import org.adaway.util.RegexUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import timber.log.Timber;

/**
 * This class is an {@link HostsSource} loader.<br>
 * It parses a source and loads it to database.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
class SourceLoader {
    private static final String TAG = "SourceLoader";
    private static final String END_OF_QUEUE_MARKER = "#EndOfQueueMarker";
    private static final int INSERT_BATCH_SIZE = 100;
    private static final String HOSTS_PARSER = "^\\s*([^#\\s]+)\\s+([^#\\s]+).*$";
    static final Pattern HOSTS_PARSER_PATTERN = Pattern.compile(HOSTS_PARSER);

    private final HostsSource source;

    SourceLoader(HostsSource hostsSource) {
        this.source = hostsSource;
    }

    /**
     * OPTIMIZATION: previously this only called clearSourceHosts() up
     * front, then let {@link ItemInserter} insert every INSERT_BATCH_SIZE
     * (100) items as its own separate, individually-committed transaction
     * (Room auto-wraps each array/list @Insert call in one). A 100k-300k+
     * line hosts source - not unusual for the popular community block
     * lists this app ships by default - meant well over a thousand
     * individual disk commits just for that one source, times however
     * many sources are configured, on literally every single sync. That
     * was by far the largest remaining cost in the whole hosts-update
     * pipeline, dwarfing the per-item parsing work itself.
     * <p>
     * clearSourceHosts() now runs as the very first step inside
     * {@link ItemInserter#call()} instead of here, and the whole thing -
     * the clear plus every insert batch - runs inside one
     * {@link AppDatabase#runInTransaction}, collapsing a source's total
     * commit count from "roughly (line count / 100) + 1" down to exactly
     * one. This is safe to do because ItemInserter already does every one
     * of its inserts sequentially on a single dedicated thread (the
     * reader/parser threads never touch the database - they only produce
     * HostListItem objects onto a queue), and because SourceModel already
     * serializes all of retrieveHostsSources() behind its own lock, so
     * there's never a second writer that a long-lived transaction here
     * could end up blocking.
     */
    void parse(BufferedReader reader, HostListItemDao hostListItemDao, AppDatabase database) throws IOException {
        // Create batch
        int parserCount = 3;
        // BUG FIX: these used to be unbounded (new LinkedBlockingQueue<>()
        // with no capacity). If the reader thread produces lines faster
        // than the parser/inserter threads can drain them - the normal
        // case, since DB inserts are the bottleneck - the queue has no
        // limit on how much of a 100k-300k+ line source it will hold in
        // memory at once as pending items. That's a real, reproducible
        // OutOfMemoryError risk on exactly the large-source-sync path this
        // was already known to run on, especially on lower-RAM devices.
        // A bounded capacity makes producers block (via put(), not add())
        // once it's full, naturally pacing the reader to however fast the
        // consumers can actually keep up - memory use is now capped
        // regardless of source size.
        LinkedBlockingQueue<String> hostsLineQueue = new LinkedBlockingQueue<>(4096);
        LinkedBlockingQueue<HostListItem> hostsListItemQueue = new LinkedBlockingQueue<>(4096);
        SourceReader sourceReader = new SourceReader(reader, hostsLineQueue, parserCount);
        ItemInserter inserter = new ItemInserter(hostsListItemQueue, hostListItemDao, parserCount, database, this.source.getId());
        ExecutorService executorService = Executors.newFixedThreadPool(
                parserCount + 2,
                r -> new Thread(r, TAG)
        );
        executorService.execute(sourceReader);
        for (int i = 0; i < parserCount; i++) {
            executorService.execute(new HostListItemParser(this.source, hostsLineQueue, hostsListItemQueue));
        }
        Future<Integer> inserterFuture = executorService.submit(inserter);
        try {
            Integer inserted = inserterFuture.get();
            Timber.i("%s host list items inserted.", inserted);
        } catch (ExecutionException e) {
            Timber.w(e, "Failed to parse hosts sources.");
        } catch (InterruptedException e) {
            Timber.w(e, "Interrupted while parsing sources.");
            Thread.currentThread().interrupt();
        } finally {
            executorService.shutdown();
        }
        /*
         * BUG FIX: SourceReader.run() used to catch every exception from
         * reading the stream (network timeout, connection reset partway
         * through a large source, etc.), log a warning nobody sees, and
         * then send the end-of-queue marker as if the read had finished
         * normally. Downstream, that looks exactly like a fully, cleanly
         * downloaded source — the parser/inserter threads just process
         * whatever lines arrived before the failure and finish "successfully".
         * The result: hosts sources silently truncate mid-file on any
         * network hiccup, with the app reporting a normal successful
         * update and no indication anything was cut short. Surface the
         * failure here so the caller's existing IOException handling
         * (SourceModel#downloadHostSource / #readSourceFile) can treat
         * this source as failed instead of silently accepting partial
         * data as a complete, successful import.
         */
        Throwable readError = sourceReader.getError();
        if (readError != null) {
            /*
             * Discard whatever partial data this failed read already
             * inserted. Keeping a truncated snapshot around gives false
             * confidence (some domains from this source silently missing,
             * with no indication) until the next successful sync overwrites
             * it. An explicit "this source currently contributes nothing"
             * is safer and more honest than an undetectably-incomplete one
             * — and since the failure propagates via the exception thrown
             * right after this, the next sync will retry this source
             * automatically anyway.
             */
            hostListItemDao.clearSourceHosts(this.source.getId());
            throw new IOException("Hosts source reading was interrupted before completion", readError);
        }
    }

    private static class SourceReader implements Runnable {
        private final BufferedReader reader;
        private final BlockingQueue<String> queue;
        private final int parserCount;
        private volatile Throwable error;

        private SourceReader(BufferedReader reader, BlockingQueue<String> queue, int parserCount) {
            this.reader = reader;
            this.queue = queue;
            this.parserCount = parserCount;
        }

        @Override
        public void run() {
            try {
                this.reader.lines().forEach(line -> {
                    try {
                        this.queue.put(line);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                });
            } catch (Throwable t) {
                Timber.w(t, "Failed to read hosts source.");
                this.error = t;
            } finally {
                // Send end of queue marker to parsers. Use a bounded
                // offer() instead of put()/add() here specifically: if
                // something's already gone wrong and nothing is draining
                // the queue anymore, we'd rather give up after a timeout
                // than block this thread forever trying to signal a
                // shutdown that's never going to be read.
                for (int i = 0; i < this.parserCount; i++) {
                    try {
                        this.queue.offer(END_OF_QUEUE_MARKER, 30, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        /**
         * @return the error that interrupted reading, or {@code null} if the
         * source was read to completion without issue.
         */
        Throwable getError() {
            return this.error;
        }
    }

    private static class HostListItemParser implements Runnable {
        private final HostsSource source;
        private final BlockingQueue<String> lineQueue;
        private final BlockingQueue<HostListItem> itemQueue;

        private HostListItemParser(HostsSource source, BlockingQueue<String> lineQueue, BlockingQueue<HostListItem> itemQueue) {
            this.source = source;
            this.lineQueue = lineQueue;
            this.itemQueue = itemQueue;
        }

        @Override
        public void run() {
            boolean allowedList = this.source.isAllowEnabled();
            boolean endOfSource = false;
            while (!endOfSource) {
                try {
                    String line = this.lineQueue.take();
                    // Check end of queue marker
                    //noinspection StringEquality
                    if (line == END_OF_QUEUE_MARKER) {
                        endOfSource = true;
                        // Send end of queue marker to inserter
                        HostListItem endItem = new HostListItem();
                        endItem.setHost(line);
                        this.itemQueue.put(endItem);
                    } // AdGuard / adblock cosmetic rules (##, #@#, #$#): not a
                    // hosts entry - collected for the hijack mode instead
                    else if (CosmeticRules.collect(line)) {
                        Timber.v("Cosmetic rule: %s.", line);
                    } // Check comments
                    else if (line.isEmpty() || line.charAt(0) == '#') {
                        Timber.d("Skip comment: %s.", line);
                    } else {
                        HostListItem item = allowedList ? parseAllowListItem(line) : parseHostListItem(line);
                        if (item != null && isRedirectionValid(item) && isHostValid(item)) {
                            this.itemQueue.put(item);
                        }
                    }
                } catch (InterruptedException e) {
                    Timber.w(e, "Interrupted while parsing hosts list item.");
                    endOfSource = true;
                    // BUG FIX: ensure the inserter still receives this parser's
                    // end-of-source marker. Otherwise workerStopped can never
                    // reach parserCount and ItemInserter#call() blocks forever
                    // on hostListItemQueue.take().
                    HostListItem endItem = new HostListItem();
                    endItem.setHost(END_OF_QUEUE_MARKER);
                    try {
                        this.itemQueue.put(endItem);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    Thread.currentThread().interrupt();
                }
            }
        }

        private HostListItem parseHostListItem(String line) {
            Matcher matcher = HOSTS_PARSER_PATTERN.matcher(line);
            if (!matcher.matches()) {
                /*
                 * AdGuard / Adblock Plus network rule: ||example.com^ blocks the
                 * whole domain, so an AdGuard-style rule list can be used as a
                 * source here as well. Only "whole domain" rules are taken;
                 * rules with a path, wildcard or option are left to the proxy
                 * engine of the hijack mode.
                 */
                String domain = abpDomainOf(line);
                if (domain != null) {
                    HostListItem item = new HostListItem();
                    item.setType(BLOCKED);
                    item.setHost(domain);
                    item.setEnabled(true);
                    item.setSourceId(this.source.getId());
                    return item;
                }
                Timber.d("Does not match: %s.", line);
                return null;
            }
            // Check IP address validity or while list entry (if allowed)
            String ip = matcher.group(1);
            String hostname = matcher.group(2);
            assert hostname != null;
            // Skip localhost name
            if (LOCALHOST_HOSTNAME.equals(hostname)) {
                return null;
            }
            // check if ip is 127.0.0.1 or 0.0.0.0
            ListType type;
            if (LOCALHOST_IPV4.equals(ip)
                    || BOGUS_IPV4.equals(ip)
                    || LOCALHOST_IPV6.equals(ip)) {
                type = BLOCKED;
            } else if (this.source.isRedirectEnabled()) {
                type = REDIRECTED;
            } else {
                return null;
            }
            HostListItem item = new HostListItem();
            item.setType(type);
            item.setHost(hostname);
            item.setEnabled(true);
            if (type == REDIRECTED) {
                item.setRedirection(ip);
            }
            item.setSourceId(this.source.getId());
            return item;
        }

        private HostListItem parseAllowListItem(String line) {
            // AdGuard / adblock exception rule (@@||example.com^): allow it
            if (line.startsWith("@@")) {
                String domain = abpDomainOf(line);
                if (domain != null) {
                    HostListItem item = new HostListItem();
                    item.setType(ALLOWED);
                    item.setHost(domain);
                    item.setEnabled(true);
                    item.setSourceId(this.source.getId());
                    return item;
                }
            }
            // Extract hostname
            int indexOf = line.indexOf('#');
            if (indexOf > 0) {
                line = line.substring(0, indexOf);
            }
            line = line.trim();
            // Create item
            HostListItem item = new HostListItem();
            item.setType(ALLOWED);
            item.setHost(line);
            item.setEnabled(true);
            item.setSourceId(this.source.getId());
            return item;
        }

        /**
         * Extract the domain of an AdGuard / Adblock Plus rule such as
         * <code>||ads.example.com^</code> or <code>@@||cdn.example.com^</code>.
         *
         * @param line The raw rule.
         * @return The domain, or {@code null} when the rule is not a plain
         * whole-domain rule.
         */
        private String abpDomainOf(String line) {
            String rule = line.trim();
            if (rule.startsWith("@@")) {
                rule = rule.substring(2);
            }
            if (!rule.startsWith("||")) {
                return null;
            }
            String rest = rule.substring(2);
            int end = rest.length();
            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c == '^' || c == '/' || c == '$' || c == '|' || c == '*' || c == '?') {
                    end = i;
                    break;
                }
            }
            char terminator = end < rest.length() ? rest.charAt(end) : '\0';
            if (terminator != '\0' && terminator != '^') {
                // Rule carries a path / wildcard / option: the hosts level
                // cannot express it, the hijack proxy engine can.
                return null;
            }
            String domain = rest.substring(0, end).trim();
            if (domain.isEmpty() || domain.indexOf('.') < 0
                    || !org.adaway.util.RegexUtils.isValidHostname(domain)) {
                return null;
            }
            return domain;
        }

        private boolean isRedirectionValid(HostListItem item) {
            return item.getType() != REDIRECTED || RegexUtils.isValidIP(item.getRedirection());
        }

        private boolean isHostValid(HostListItem item) {
            String hostname = item.getHost();
            if (item.getType() == BLOCKED) {
                if (hostname.indexOf('?') != -1 || hostname.indexOf('*') != -1) {
                    return false;
                }
                return RegexUtils.isValidHostname(hostname);
            }
            return RegexUtils.isValidWildcardHostname(hostname);
        }
    }

    private static class ItemInserter implements Callable<Integer> {
        private final BlockingQueue<HostListItem> hostListItemQueue;
        private final HostListItemDao hostListItemDao;
        private final int parserCount;
        private final AppDatabase database;
        private final int sourceId;

        private ItemInserter(
                BlockingQueue<HostListItem> itemQueue,
                HostListItemDao hostListItemDao,
                int parserCount,
                AppDatabase database,
                int sourceId
        ) {
            this.hostListItemQueue = itemQueue;
            this.hostListItemDao = hostListItemDao;
            this.parserCount = parserCount;
            this.database = database;
            this.sourceId = sourceId;
        }

        @Override
        public Integer call() {
            // See the OPTIMIZATION note on SourceLoader#parse(): both the
            // clear and every insert batch below run inside this one
            // transaction, and this method is the only place that writes
            // to the database for this source, all on this single thread -
            // so wrapping the whole thing here is what collapses this
            // source's total commit count down to one.
            int[] insertedHolder = {0};
            this.database.runInTransaction(() -> insertedHolder[0] = insertAll());
            return insertedHolder[0];
        }

        private int insertAll() {
            this.hostListItemDao.clearSourceHosts(this.sourceId);
            int inserted = 0;
            int workerStopped = 0;
            HostListItem[] batch = new HostListItem[INSERT_BATCH_SIZE];
            int cacheSize = 0;
            boolean queueEmptied = false;
            while (!queueEmptied) {
                try {
                    HostListItem item = this.hostListItemQueue.take();
                    // Check end of queue marker
                    //noinspection StringEquality
                    if (item.getHost() == END_OF_QUEUE_MARKER) {
                        workerStopped++;
                        if (workerStopped >= this.parserCount) {
                            queueEmptied = true;
                        }
                    } else {
                        batch[cacheSize++] = item;
                        if (cacheSize >= batch.length) {
                            this.hostListItemDao.insert(batch);
                            inserted += cacheSize;
                            cacheSize = 0;
                        }
                    }
                } catch (InterruptedException e) {
                    Timber.w(e, "Interrupted while inserted hosts list item.");
                    queueEmptied = true;
                    Thread.currentThread().interrupt();
                }
            }
            // Flush current batch
            HostListItem[] remaining = new HostListItem[cacheSize];
            System.arraycopy(batch, 0, remaining, 0, remaining.length);
            this.hostListItemDao.insert(remaining);
            inserted += cacheSize;
            // Return number of inserted items
            return inserted;
        }
    }
}
