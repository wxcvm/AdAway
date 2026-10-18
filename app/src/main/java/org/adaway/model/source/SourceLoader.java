package org.adaway.model.source;

import org.adaway.db.AppDatabase;
import org.adaway.db.dao.HostListItemDao;
import org.adaway.db.entity.HostListItem;
import org.adaway.db.entity.HostsSource;
import org.adaway.db.entity.ListType;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Clear, complete parse and batched insert either all commit or all roll back. */
class SourceLoader {
    private static final int BATCH_SIZE = 100, MAX_COSMETIC_CHARACTERS = 4 * 1024 * 1024;
    private final HostsSource source;
    SourceLoader(HostsSource source) { this.source = source; }
    private static final class ImportFailure extends RuntimeException {
        ImportFailure(IOException cause) { super(cause); }
    }
    void parse(BufferedReader reader, HostListItemDao dao, AppDatabase database) throws IOException {
        List<String> cosmetics = new ArrayList<>();
        try {
            database.runInTransaction(() -> {
                dao.clearSourceHosts(source.getId());
                List<HostListItem> batch = new ArrayList<>(BATCH_SIZE);
                try {
                    BoundedSourceImport.read(reader, source.isAllowEnabled(), source.isRedirectEnabled(),
                            new BoundedSourceImport.Sink() {
                        private int cosmeticCharacters;
                        private long order;
                        @Override public void entry(DomainSourceParser.Entry entry) {
                            HostListItem item = new HostListItem();
                            item.setMatchScope(entry.scope.ordinal()); item.setSourceOrder(++order);
                            item.setHost(entry.domain); item.setEnabled(true); item.setSourceId(source.getId());
                            item.setType(entry.kind == DomainSourceParser.Kind.ALLOW ? ListType.ALLOWED
                                    : entry.kind == DomainSourceParser.Kind.REPLACE ? ListType.REDIRECTED : ListType.BLOCKED);
                            item.setRedirection(entry.address); batch.add(item);
                            if (batch.size() == BATCH_SIZE) { dao.insert(batch); batch.clear(); }
                        }
                        @Override public void cosmetic(String line) throws IOException {
                            cosmeticCharacters += line.length();
                            if (cosmetics.size() >= 20000 || cosmeticCharacters > MAX_COSMETIC_CHARACTERS)
                                throw new IOException("Cosmetic import exceeds limit");
                            cosmetics.add(line);
                        }
                    });
                    if (!batch.isEmpty()) dao.insert(batch);
                } catch (IOException e) { throw new ImportFailure(e); }
            });
        } catch (ImportFailure e) { throw (IOException) e.getCause(); }
        catch (RuntimeException e) { throw new IOException("Source transaction failed", e); }
        // Non-database side effects are deferred until a successful commit.
        for (String cosmetic : cosmetics) CosmeticRules.collect(cosmetic);
    }
}
