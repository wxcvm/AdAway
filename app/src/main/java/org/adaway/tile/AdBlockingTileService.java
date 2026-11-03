package org.adaway.tile;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import org.adaway.AdAwayApplication;
import org.adaway.model.adblocking.AdBlockModel;
import org.adaway.model.error.HostErrorException;
import org.adaway.util.AppExecutors;

import java.util.concurrent.atomic.AtomicBoolean;

import static android.service.quicksettings.Tile.STATE_ACTIVE;
import static android.service.quicksettings.Tile.STATE_INACTIVE;

import timber.log.Timber;

/**
 * This class is a {@link TileService} to toggle ad-blocking.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class AdBlockingTileService extends TileService {
    private final AtomicBoolean toggling = new AtomicBoolean(false);
    // observeForever/removeObserver must receive the exact same Observer object.
    private final Observer<Boolean> appliedObserver =
            value -> updateTile(Boolean.TRUE.equals(value));
    private LiveData<Boolean> observedApplied;

    @Override
    public void onTileAdded() {
        boolean adBlocked = Boolean.TRUE.equals(getModel().isApplied().getValue());
        updateTile(adBlocked);
    }

    @Override
    public void onStartListening() {
        LiveData<Boolean> applied = getModel().isApplied();
        if (this.observedApplied != applied) {
            stopObservingApplied();
            this.observedApplied = applied;
        }
        // Re-registering the same observer is idempotent if listening restarts.
        applied.observeForever(this.appliedObserver);
    }

    @Override
    public void onStopListening() {
        stopObservingApplied();
    }

    @Override
    public void onDestroy() {
        stopObservingApplied();
        super.onDestroy();
    }

    private void stopObservingApplied() {
        if (this.observedApplied != null) {
            this.observedApplied.removeObserver(this.appliedObserver);
            this.observedApplied = null;
        }
    }

    @Override
    public void onClick() {
        // Claim the toggle before queueing it: diskIO is serial, so claiming
        // inside the task would execute every rapid tap after the prior one.
        if (!this.toggling.compareAndSet(false, true)) return;
        try {
            AppExecutors.getInstance().diskIO().execute(this::toggleAdBlocking);
        } catch (RuntimeException exception) {
            this.toggling.set(false);
            Timber.w(exception, "Unable to queue quick-settings toggle");
        }
    }

    private void updateTile(boolean adBlocked) {
        Tile tile = getQsTile();
        // A queued LiveData callback may arrive after the tile was detached.
        if (tile == null) return;
        tile.setState(adBlocked ? STATE_ACTIVE : STATE_INACTIVE);
        tile.updateTile();
    }

    private void toggleAdBlocking() {
        try {
            AdBlockModel model = getModel();
            if (model.isApplied().getValue() == Boolean.TRUE) {
                model.revert();
            } else {
                model.apply();
            }
        } catch (HostErrorException e) {
            Timber.w(e, "Failed to toggle ad-blocking.");
        } catch (Throwable e) {
            // BUG FIX: don't let an unexpected exception crash the app from
            // a Quick Settings tile tap - see HomeViewModel for the same
            // fix and fuller explanation.
            Timber.e(e, "Unexpected error while toggling ad-blocking from tile.");
        } finally {
            this.toggling.set(false);
        }
    }

    private AdBlockModel getModel() {
        return ((AdAwayApplication) getApplication()).getAdBlockModel();
    }
}
