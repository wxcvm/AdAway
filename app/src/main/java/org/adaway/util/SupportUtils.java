package org.adaway.util;

import static android.content.Intent.ACTION_VIEW;
import static android.net.Uri.parse;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.ImageView;

/**
 * Support links and helpers formerly hosted on SupportActivity
 * (classic UI removed). Kept for UpdateActivity and WelcomeSupportFragment.
 */
public final class SupportUtils {
    public static final Uri SUPPORT_LINK = parse("https://github.com/wxcvm/AdAway");
    public static final Uri SPONSORSHIP_LINK = parse("https://github.com/wxcvm/AdAway/issues");

    private SupportUtils() {
    }

    public static void animateHeart(ImageView heartImageView) {
        PropertyValuesHolder growScaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1F, 1.2F);
        PropertyValuesHolder growScaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1F, 1.2F);
        Animator growAnimator = ObjectAnimator.ofPropertyValuesHolder(heartImageView, growScaleX, growScaleY);
        growAnimator.setDuration(200);
        growAnimator.setStartDelay(2000);

        PropertyValuesHolder shrinkScaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.2F, 1F);
        PropertyValuesHolder shrinkScaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.2F, 1F);
        Animator shrinkAnimator = ObjectAnimator.ofPropertyValuesHolder(heartImageView, shrinkScaleX, shrinkScaleY);
        growAnimator.setDuration(400);

        AnimatorSet animationSet = new AnimatorSet();
        animationSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                animationSet.start();
            }
        });
        animationSet.playSequentially(growAnimator, shrinkAnimator);
        animationSet.start();
    }

    public static void bindLink(Context context, View view, Uri uri) {
        view.setOnClickListener(v -> {
            Intent browserIntent = new Intent(ACTION_VIEW, uri);
            context.startActivity(browserIntent);
        });
    }
}
