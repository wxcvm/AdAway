package org.adaway.model.adblocking;

import android.content.Context;

import androidx.annotation.StringRes;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.adaway.model.error.HostErrorException;
import org.adaway.model.root.RootModel;

import java.util.List;

import timber.log.Timber;

/**
 * This class is the base model for all ad block model.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public abstract class AdBlockModel {
    protected final Context context;
    protected final MutableLiveData<Boolean> applied;
    private final MutableLiveData<String> state;

    protected AdBlockModel(Context context) {
        this.context = context;
        this.state = new MutableLiveData<>();
        this.applied = new MutableLiveData<>();
    }

    public static AdBlockModel build(Context context, AdBlockMethod method) {
        if (method == AdBlockMethod.ROOT) {
            return new RootModel(context);
        }
        return new UndefinedBlockModel(context);
    }

    public abstract AdBlockMethod getMethod();

    public LiveData<Boolean> isApplied() {
        return this.applied;
    }

    public abstract void apply() throws HostErrorException;

    public abstract void revert() throws HostErrorException;

    public LiveData<String> getState() {
        return this.state;
    }

    protected void setState(@StringRes int stateResId, Object... details) {
        String state = this.context.getString(stateResId, details);
        Timber.d(state);
        this.state.postValue(state);
    }

    public abstract boolean isRecordingLogs();

    public abstract void setRecordingLogs(boolean recording);

    public abstract List<String> getLogs();

    public abstract void clearLogs();
}
