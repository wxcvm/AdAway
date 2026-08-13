package org.adaway;

import android.app.Application;

import org.adaway.helper.NotificationHelper;
import org.adaway.helper.PreferenceHelper;
import org.adaway.model.adblocking.AdBlockMethod;
import org.adaway.model.adblocking.AdBlockModel;
import org.adaway.model.source.SourceModel;
import org.adaway.model.update.UpdateModel;
import org.adaway.util.log.ApplicationLog;

/**
 * This class is a custom {@link Application} for AdAway app.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class AdAwayApplication extends Application {
    /**
     * The common source model for the whole application.
     */
    private SourceModel sourceModel;
    /**
     * The common ad block model for the whole application.
     */
    private volatile AdBlockModel adBlockModel;
    /**
     * The common update model for the whole application.
     */
    private UpdateModel updateModel;

    @Override
    public void onCreate() {
        // Delegate application creation
        super.onCreate();
        // Initialize logging
        ApplicationLog.init(this);
        // Sync web server port cache (getStats has no Context).
        org.adaway.util.WebServerUtils.initPortCache(this);
        // Create notification channels
        NotificationHelper.createNotificationChannels(this);
        // Create models
        this.sourceModel = new SourceModel(this);
        this.updateModel = new UpdateModel(this);
    }

    /**
     * Get the source model.
     *
     * @return The common source model for the whole application.
     */
    public SourceModel getSourceModel() {
        return this.sourceModel;
    }

    /**
     * Get the ad block model.
     *
     * @return The common ad block model for the whole application.
     */
    public AdBlockModel getAdBlockModel() {
        // Check cached model (double-checked locking: this getter is
        // called from multiple threads - UI, quick-settings tile,
        // broadcast receiver - so building two RootModel instances would
        // both kick off duplicate work such as starting the web server).
        AdBlockMethod method = PreferenceHelper.getAdBlockMethod(this);
        AdBlockModel model = this.adBlockModel;
        if (model == null || model.getMethod() != method) {
            synchronized (this) {
                model = this.adBlockModel;
                if (model == null || model.getMethod() != method) {
                    model = AdBlockModel.build(this, method);
                    this.adBlockModel = model;
                }
            }
        }
        return model;
    }

    /**
     * Get the update model.
     *
     * @return Teh common update model for the whole application.
     */
    public UpdateModel getUpdateModel() {
        return this.updateModel;
    }
}
