package org.adaway.ui.welcome;

import static org.adaway.model.adblocking.AdBlockMethod.ROOT;
import static org.adaway.model.adblocking.AdBlockMethod.UNDEFINED;
import static java.lang.Boolean.TRUE;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.topjohnwu.superuser.Shell;

import org.adaway.R;
import org.adaway.databinding.WelcomeMethodLayoutBinding;
import org.adaway.helper.PreferenceHelper;
import org.adaway.util.log.SentryLog;

/**
 * This class is a fragment to setup the ad blocking method (Root only).
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class WelcomeMethodFragment extends WelcomeFragment {
    private WelcomeMethodLayoutBinding binding;
    @ColorInt private int cardColor;
    @ColorInt private int cardEnabledColor;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        this.binding = WelcomeMethodLayoutBinding.inflate(inflater, container, false);
        this.binding.rootCardView.setOnClickListener(this::checkRoot);
        // Hide the VPN card entirely
        this.binding.vpnCardView.setVisibility(View.GONE);
        this.cardColor = getResources().getColor(R.color.cardBackground, null);
        this.cardEnabledColor = getResources().getColor(R.color.cardEnabledBackground, null);
        return this.binding.getRoot();
    }

    private void checkRoot(@Nullable View view) {
        Shell.getShell();
        if (TRUE.equals(Shell.isAppGrantedRoot())) {
            SentryLog.recordBreadcrumb("Enable root ad-blocking method");
            PreferenceHelper.setAbBlockMethod(requireContext(), ROOT);
            this.binding.rootCardView.setCardBackgroundColor(this.cardEnabledColor);
            allowNext();
        } else {
            PreferenceHelper.setAbBlockMethod(requireContext(), UNDEFINED);
            this.binding.rootCardView.setCardBackgroundColor(this.cardColor);
            blockNext();
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.welcome_root_missing_title)
                    .setMessage(R.string.welcome_root_missile_description)
                    .setPositiveButton(R.string.button_close, null)
                    .create()
                    .show();
        }
    }
}
