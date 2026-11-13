package org.telegram.mattermost;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MainTabsActivity;

import java.util.ArrayList;

/**
 * Mattermost sign-in screen: server URL, username/email, password and (when required) MFA code.
 */
public class MMLoginActivity extends BaseFragment {

    private EditText serverField;
    private EditText loginField;
    private EditText passwordField;
    private EditText mfaField;
    private TextView errorView;
    private TextView signInButton;
    private ProgressBar progress;
    private boolean busy;

    public MMLoginActivity() {
        super();
    }

    public MMLoginActivity(int account) {
        super();
        currentAccount = account;
    }

    @Override
    public boolean isSwipeBackEnabled(android.view.MotionEvent event) {
        return false;
    }

    private EditText field(Context context, String hint, int inputType) {
        EditText e = new EditText(context);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        e.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        e.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        e.setPadding(dp(4), dp(12), dp(4), dp(12));
        return e;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(28), AndroidUtilities.statusBarHeight + dp(48), dp(28), dp(28));
        scroll.addView(col, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView logo = new ImageView(context);
        logo.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(96), dp(96));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(logo, lp);

        TextView title = new TextView(context);
        title.setText("mm-tg");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(16);
        col.addView(title, tlp);

        TextView sub = new TextView(context);
        sub.setText("Sign in to your Mattermost server");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        sub.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(6);
        slp.bottomMargin = dp(28);
        col.addView(sub, slp);

        serverField = field(context, "Server URL (e.g. chat.example.com)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        serverField.setText(MMConfig.lastServer());
        col.addView(serverField, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        loginField = field(context, "Email or username", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        col.addView(loginField, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        passwordField = field(context, "Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        passwordField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit();
                return true;
            }
            return false;
        });
        col.addView(passwordField, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mfaField = field(context, "MFA code", InputType.TYPE_CLASS_NUMBER);
        mfaField.setVisibility(View.GONE);
        col.addView(mfaField, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        errorView = new TextView(context);
        errorView.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        errorView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        errorView.setVisibility(View.GONE);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(12);
        col.addView(errorView, elp);

        FrameLayout buttonFrame = new FrameLayout(context);
        signInButton = new TextView(context);
        signInButton.setText("Sign in");
        signInButton.setGravity(Gravity.CENTER);
        signInButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        signInButton.setTypeface(AndroidUtilities.bold());
        signInButton.setTextColor(0xffffffff);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        signInButton.setBackground(bg);
        signInButton.setOnClickListener(v -> submit());
        buttonFrame.addView(signInButton, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        progress = new ProgressBar(context);
        progress.setVisibility(View.GONE);
        buttonFrame.addView(progress, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        blp.topMargin = dp(24);
        col.addView(buttonFrame, blp);

        fragmentView = root;
        return fragmentView;
    }

    private void setBusy(boolean b) {
        busy = b;
        signInButton.setText(b ? "" : "Sign in");
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        serverField.setEnabled(!b);
        loginField.setEnabled(!b);
        passwordField.setEnabled(!b);
        mfaField.setEnabled(!b);
    }

    private void showError(String text) {
        errorView.setText(text);
        errorView.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
    }

    private void submit() {
        if (busy) {
            return;
        }
        String server = serverField.getText().toString().trim();
        String login = loginField.getText().toString().trim();
        String password = passwordField.getText().toString();
        String mfa = mfaField.getText().toString().trim();
        if (server.isEmpty() || login.isEmpty() || password.isEmpty()) {
            showError("Enter the server, your username or email, and your password.");
            return;
        }
        AndroidUtilities.hideKeyboard(fragmentView);
        showError(null);
        setBusy(true);
        MMBackend.getInstance(currentAccount).login(server, login, password, mfa, (user, error, needMfa) -> {
            setBusy(false);
            if (user != null) {
                onAuthSuccess(user);
            } else if (needMfa && mfaField.getVisibility() != View.VISIBLE) {
                mfaField.setVisibility(View.VISIBLE);
                mfaField.requestFocus();
                showError("Enter the code from your authenticator app.");
            } else {
                showError(error == null ? "Sign in failed" : error);
            }
        });
    }

    private void onAuthSuccess(TLRPC.User user) {
        MessagesController.getInstance(currentAccount).cleanup();
        ConnectionsManager.getInstance(currentAccount).setUserId(user.id);
        UserConfig.getInstance(currentAccount).clearConfig();
        MessagesController.getInstance(currentAccount).cleanup();
        UserConfig.getInstance(currentAccount).syncContacts = false;
        UserConfig.getInstance(currentAccount).setCurrentUser(user);
        UserConfig.getInstance(currentAccount).saveConfig(true);
        MessagesStorage.getInstance(currentAccount).cleanup(true);
        ArrayList<TLRPC.User> users = new ArrayList<>();
        users.add(user);
        MessagesStorage.getInstance(currentAccount).putUsersAndChats(users, null, true, true);
        MessagesController.getInstance(currentAccount).putUser(user, false);
        ContactsController.getInstance(currentAccount).checkAppAccount();

        if (getParentActivity() != null) {
            AndroidUtilities.setLightStatusBar(getParentActivity(), false);
        }
        if (getParentActivity() instanceof LaunchActivity) {
            MainTabsActivity mainTabsActivity = new MainTabsActivity();
            mainTabsActivity.prepareDialogsActivity(new android.os.Bundle());
            presentFragment(mainTabsActivity, true);
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
        }
    }
}
