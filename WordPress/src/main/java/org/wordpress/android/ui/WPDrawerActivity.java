
package org.wordpress.android.ui;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import org.wordpress.android.Constants;
import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.analytics.AnalyticsTracker;
import org.wordpress.android.datasets.CommentTable;
import org.wordpress.android.models.Blog;
import org.wordpress.android.networking.SelfSignedSSLCertsManager;
import org.wordpress.android.ui.accounts.SignInActivity;
import org.wordpress.android.ui.comments.CommentsActivity;
import org.wordpress.android.ui.media.MediaBrowserActivity;
import org.wordpress.android.ui.notifications.NotificationsActivity;
import org.wordpress.android.ui.notifications.utils.SimperiumUtils;
import org.wordpress.android.ui.posts.EditPostActivity;
import org.wordpress.android.ui.posts.PagesActivity;
import org.wordpress.android.ui.posts.PostsActivity;
import org.wordpress.android.ui.prefs.SettingsActivity;
import org.wordpress.android.ui.reader.ReaderAnim;
import org.wordpress.android.ui.reader.ReaderPostListActivity;
import org.wordpress.android.ui.stats.StatsActivity;
import org.wordpress.android.ui.themes.ThemeBrowserActivity;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;
import org.wordpress.android.util.AuthenticationDialogUtils;
import org.wordpress.android.util.BlogUtils;
import org.wordpress.android.util.DeviceUtils;
import org.wordpress.android.util.ToastUtils;
import org.wordpress.android.util.ToastUtils.Duration;
import org.xmlrpc.android.ApiHelper;
import org.xmlrpc.android.ApiHelper.ErrorType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Base class for Activities that include a standard action bar and menu drawer.
 */
public abstract class WPDrawerActivity extends ActionBarActivity {
    public static final int NEW_BLOG_CANCELED = 10;

    /**
     * AuthenticatorRequest code used when no accounts exist, and user is prompted to add an
     * account.
     */
    private static final int ADD_ACCOUNT_REQUEST = 100;
    /**
     * AuthenticatorRequest code for reloading menu after returning from  the PreferencesActivity.
     */
    private static final int SETTINGS_REQUEST = 200;
    /**
     * AuthenticatorRequest code for re-authentication
     */
    private static final int AUTHENTICATE_REQUEST = 300;

    private static int[] blogIDs;
    private boolean isAnimatingRefreshButton;
    private boolean mShouldFinish;
    private boolean mBlogSpinnerInitialized;

    private Toolbar mToolbar;
    private DrawerLayout mDrawerLayout;
    private ActionBarDrawerToggle mDrawerToggle;
    private MenuDrawerAdapter mDrawerAdapter;
    protected final List<MenuDrawerItem> mMenuItems = new ArrayList<MenuDrawerItem>();
    private ListView mDrawerListView;
    private Spinner mBlogSpinner;
    protected boolean mFirstLaunch = false;

    private static final String OPENED_FROM_DRAWER = "opened_from_drawer";
    private static final int OPENED_FROM_DRAWER_DELAY = 250;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_drawer);
        setSupportActionBar(getToolbar());

        // configure all the available menu items
        mMenuItems.add(new ReaderMenuItem());
        mMenuItems.add(new NotificationsMenuItem());
        mMenuItems.add(new PostsMenuItem());
        mMenuItems.add(new MediaMenuItem());
        mMenuItems.add(new PagesMenuItem());
        mMenuItems.add(new CommentsMenuItem());
        mMenuItems.add(new ThemesMenuItem());
        mMenuItems.add(new StatsMenuItem());
        mMenuItems.add(new QuickPhotoMenuItem());
        mMenuItems.add(new QuickVideoMenuItem());
        mMenuItems.add(new ViewSiteMenuItem());

        // if this activity was opened from the drawer (ie: via startDrawerIntent() from another
        // drawer activity), hide the activity view then fade it in after a short delay
        if (getIntent() != null && getIntent().getBooleanExtra(OPENED_FROM_DRAWER, false)) {
            hideActivityContainer(false);
            getIntent().putExtra(OPENED_FROM_DRAWER, false);
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing()) {
                        showActivityContainer(true);
                    }
                }
            }, OPENED_FROM_DRAWER_DELAY);
        }
    }

    /*
     * returns the view containing the activity
     */
    private ViewGroup getActivityContainer() {
        return (ViewGroup) findViewById(R.id.activity_container);
    }

    private void hideActivityContainer(boolean animate) {
        View activityView = getActivityContainer();
        if (activityView == null || activityView.getVisibility() != View.VISIBLE) {
            return;
        }
        if (animate) {
            ReaderAnim.fadeOut(activityView, ReaderAnim.Duration.SHORT);
        } else {
            activityView.setVisibility(View.GONE);
        }
    }
    private void showActivityContainer(boolean animate) {
        View activityView = getActivityContainer();
        if (activityView == null || activityView.getVisibility() == View.VISIBLE) {
            return;
        }
        if (animate) {
            ReaderAnim.fadeIn(activityView, ReaderAnim.Duration.SHORT);
        } else {
            activityView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        if (mDrawerToggle != null) {
            // Sync the toggle state after onRestoreInstanceState has occurred.
            mDrawerToggle.syncState();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver();

        if (isAnimatingRefreshButton) {
            isAnimatingRefreshButton = false;
        }
        if (mShouldFinish) {
            overridePendingTransition(0, 0);
            finish();
        } else {
            WordPress.shouldRestoreSelectedActivity = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver();
        refreshMenuDrawer();
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    protected boolean isActivityDestroyed() {
        return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed());
    }

    void refreshMenuDrawer() {
        if (mDrawerAdapter == null) return;
        // the current blog may have changed while we were away
        setupCurrentBlog();
        updateMenuDrawer();

        Blog currentBlog = WordPress.getCurrentBlog();

        if (currentBlog != null && mDrawerListView != null && mDrawerListView.getHeaderViewsCount() > 0) {
            for (int i = 0; i < blogIDs.length; i++) {
                if (blogIDs[i] == currentBlog.getLocalTableBlogId()) {
                    if (mBlogSpinner != null) {
                        mBlogSpinner.setSelection(i);
                    }
                }
            }
        }
    }

    /**
     * Create a menu drawer and attach it to the activity.
     *
     * @param contentViewId {@link View} of the main content for the activity.
     */
    protected void createMenuDrawer(int contentViewId) {
        ViewGroup layoutContainer = getActivityContainer();
        layoutContainer.addView(getLayoutInflater().inflate(contentViewId, null));

        initMenuDrawer();
    }

    protected Toolbar getToolbar() {
        if (mToolbar == null) {
            mToolbar = (Toolbar) findViewById(R.id.toolbar);
        }

        return mToolbar;
    }

    public boolean isStaticMenuDrawer() {
        // drawer layout does not exist on landscape tablets
        return (findViewById(R.id.drawer_layout) != null);
    }

    private void initMenuDrawer() {
        initMenuDrawer(-1);
    }

    /**
     * Create menu drawer ListView and listeners
     */
    private void initMenuDrawer(int blogSelection) {
        // locate the drawer layout - note that it will not exist on landscape tablets
        mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        if (mDrawerLayout != null) {
            mDrawerLayout.setDrawerShadow(R.drawable.menu_drawer_shadow, GravityCompat.START);
            mDrawerToggle = new ActionBarDrawerToggle(
                    this, mDrawerLayout, mToolbar, R.string.open_drawer,
                    R.string.close_drawer
            ) {
                public void onDrawerClosed(View view) {
                    invalidateOptionsMenu();
                }
                public void onDrawerOpened(View drawerView) {
                    invalidateOptionsMenu();
                }
            };
            mDrawerToggle.setHomeAsUpIndicator(R.drawable.ic_arrow_back_white_24dp);
        }

        // add listVew header containing spinner if it hasn't already been added
        mDrawerListView = (ListView) findViewById(R.id.drawer_list);
        if (mDrawerListView.getHeaderViewsCount() == 0) {
            View view = getLayoutInflater().inflate(R.layout.menu_drawer_header, mDrawerListView, false);
            mDrawerListView.addHeaderView(view, null, false);
        }

        // blog spinner only appears if there's more than one blog
        mBlogSpinner = (Spinner) findViewById(R.id.blog_spinner);
        View divider = findViewById(R.id.blog_spinner_divider);
        String[] blogNames = getBlogNames();
        if (blogNames.length > 1) {
            mBlogSpinner.setVisibility(View.VISIBLE);
            divider.setVisibility(View.VISIBLE);
            mBlogSpinner.setOnItemSelectedListener(mItemSelectedListener);
            populateBlogSpinner(blogNames);
        } else {
            mBlogSpinner.setVisibility(View.GONE);
            divider.setVisibility(View.GONE);
        }

        View settingsRow = findViewById(R.id.settings_row);
        settingsRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });

        mDrawerAdapter = new MenuDrawerAdapter(this);
        mDrawerListView.setAdapter(mDrawerAdapter);
        mDrawerListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (mDrawerLayout != null) {
                    mDrawerLayout.closeDrawer(GravityCompat.START);
                }

                int menuPosition = position - mDrawerListView.getHeaderViewsCount();
                if (menuPosition < 0 || menuPosition >= mDrawerAdapter.getCount()) {
                    return;
                }
                MenuDrawerItem item = (MenuDrawerItem) mDrawerAdapter.getItem(menuPosition);

                // if the item has an id, remember it for launch
                if (item.hasItemId()) {
                    ActivityId.trackLastActivity(WPDrawerActivity.this, item.getItemId());
                }

                // only perform selection if the item isn't already selected
                if (!item.isSelected()) {
                    item.selectItem();
                }
            }
        });

        if (blogSelection != -1 && mBlogSpinner != null) {
            mBlogSpinner.setSelection(blogSelection);
        }

        updateMenuDrawer();
    }

    protected ActionBarDrawerToggle getDrawerToggle() {
        return mDrawerToggle;
    }

    /*
     * sets the adapter for the blog spinner and populates it with the passed array of blog names
     */
    private void populateBlogSpinner(String[] blogNames) {
        if (mBlogSpinner == null) {
            return;
        }
        mBlogSpinnerInitialized = false;
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            mBlogSpinner.setAdapter(new BlogSpinnerAdapter(actionBar.getThemedContext(), blogNames));
        } else {
            mBlogSpinner.setAdapter(new BlogSpinnerAdapter(this, blogNames));
        }
    }

    /*
     * update the blog names shown by the blog spinner
     */
    void refreshBlogSpinner(String[] blogNames) {
        // spinner will be null if it's not supposed to be shown
        if (mBlogSpinner == null || mBlogSpinner.getAdapter() == null) {
            return;
        }

        ((BlogSpinnerAdapter) mBlogSpinner.getAdapter()).setBlogNames(blogNames);
    }

    /*
     * adapter used by the blog spinner - shows the name of each blog
     */
    private class BlogSpinnerAdapter extends BaseAdapter {
        private String[] mBlogNames;
        private final LayoutInflater mInflater;

        BlogSpinnerAdapter(Context context, String[] blogNames) {
            super();
            mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mBlogNames = blogNames;
        }

        void setBlogNames(String[] blogNames) {
            mBlogNames = blogNames;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return (mBlogNames != null ? mBlogNames.length : 0);
        }

        @Override
        public Object getItem(int position) {
            if (position < 0 || position >= getCount())
                return "";
            return mBlogNames[position];
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View view;
            if (convertView == null) {
                view = mInflater.inflate(R.layout.spinner_menu_dropdown_item, parent, false);
            } else {
                view = convertView;
            }

            final TextView text = (TextView) view.findViewById(R.id.menu_text_dropdown);
            text.setText((String) getItem(position));

            return view;
        }
    }

    /**
     * Update all of the items in the menu drawer based on the current active blog.
     */
    public void updateMenuDrawer() {
        List<MenuDrawerItem> visibleItems = new ArrayList<MenuDrawerItem>();
        for (MenuDrawerItem item : mMenuItems) {
            if (item.isVisible()) {
                visibleItems.add(item);
            }
        }
        mDrawerAdapter.setItems(visibleItems);
    }

    /**
     * Called when the activity has detected the user's press of the back key.
     * If the activity has a menu drawer attached that is opened or in the
     * process of opening, the back button press closes it. Otherwise, the
     * normal back action is taken.
     */
    @Override
    public void onBackPressed() {
        if (mDrawerLayout != null && mDrawerLayout.isDrawerOpen(GravityCompat.START)) {
            mDrawerLayout.closeDrawers();
        } else {
            super.onBackPressed();
        }
    }

    /**
     * Get the names of all the blogs configured within the application. If a
     * blog does not have a specific name, the blog URL is returned.
     *
     * @return array of blog names
     */
    private static String[] getBlogNames() {
        List<Map<String, Object>> accounts = WordPress.wpDB.getVisibleAccounts();

        int blogCount = accounts.size();
        blogIDs = new int[blogCount];
        String[] blogNames = new String[blogCount];

        for (int i = 0; i < blogCount; i++) {
            Map<String, Object> account = accounts.get(i);
            blogNames[i] = BlogUtils.getBlogNameFromAccountMap(account);
            blogIDs[i] = Integer.valueOf(account.get("id").toString());
        }

        return blogNames;
    }

    private boolean askToSignInIfNot() {
        if (!WordPress.isSignedIn(WPDrawerActivity.this)) {
            AppLog.d(T.NUX, "No accounts configured.  Sending user to set up an account");
            mShouldFinish = false;
            Intent intent = new Intent(this, SignInActivity.class);
            intent.putExtra("request", SignInActivity.SIGN_IN_REQUEST);
            startActivityForResult(intent, ADD_ACCOUNT_REQUEST);
            return false;
        }
        return true;
    }

    /**
     * Setup the global state tracking which blog is currently active if the user is signed in.
     */
    public void setupCurrentBlog() {
        if (askToSignInIfNot()) {
            WordPress.getCurrentBlog();
        }
    }

    private void showReader() {
        Intent intent = new Intent(WPDrawerActivity.this, ReaderPostListActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(intent);
    }

    private void showSettings() {
        startActivityForResult(new Intent(this, SettingsActivity.class), SETTINGS_REQUEST);
    }

    /*
     * redirect to the Reader if there aren't any visible blogs
     * returns true if redirected, false otherwise
     */
    protected boolean showReaderIfNoBlog() {
        if (WordPress.wpDB.getNumVisibleAccounts() == 0) {
            showReader();
            return true;
        } else {
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case ADD_ACCOUNT_REQUEST:
                if (resultCode == RESULT_OK) {
                    // new blog has been added, so rebuild cache of blogs and setup current blog
                    getBlogNames();
                    setupCurrentBlog();
                    if (mDrawerListView != null) {
                        initMenuDrawer();
                    }
                    WordPress.registerForCloudMessaging(this);
                    // If logged in without blog, redirect to the Reader view
                    showReaderIfNoBlog();
                } else {
                    finish();
                }
                break;
            case SETTINGS_REQUEST:
                // user returned from settings - skip if user signed out
                if (mDrawerListView != null && resultCode != SettingsActivity.RESULT_SIGNED_OUT) {
                    // If we need to add or remove the blog spinner, init the drawer again
                    initMenuDrawer();

                    String[] blogNames = getBlogNames();
                    if (blogNames.length >= 1) {
                        setupCurrentBlog();
                    }
                    if (data != null && data.getBooleanExtra(SettingsActivity.CURRENT_BLOG_CHANGED, true)) {
                        blogChanged();
                    }
                    WordPress.registerForCloudMessaging(this);
                }

                break;
            case AUTHENTICATE_REQUEST:
                if (resultCode == RESULT_CANCELED) {
                    Intent i = new Intent(this, SignInActivity.class);
                    startActivityForResult(i, ADD_ACCOUNT_REQUEST);
                } else {
                    WordPress.registerForCloudMessaging(this);
                }
                break;
        }
    }

    private final OnItemSelectedListener mItemSelectedListener = new OnItemSelectedListener() {
        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            // http://stackoverflow.com/questions/5624825/spinner-onitemselected-executes-when-it-is-not-suppose-to/5918177#5918177
            if (!mBlogSpinnerInitialized) {
                mBlogSpinnerInitialized = true;
            } else {
                WordPress.setCurrentBlog(blogIDs[position]);
                updateMenuDrawer();
                blogChanged();
            }
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    };

    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home && mDrawerLayout != null) {
            mDrawerLayout.openDrawer(GravityCompat.START);
        }
        return super.onOptionsItemSelected(item);
    }

    private void refreshCurrentBlogContent() {
        if (WordPress.getCurrentBlog() != null) {
            ApiHelper.GenericCallback callback = new ApiHelper.GenericCallback() {
                @Override
                public void onSuccess() {
                    if (isFinishing()) {
                        return;
                    }
                    // refresh spinner in case a blog's name has changed
                    refreshBlogSpinner(getBlogNames());
                    updateMenuDrawer();
                }

                @Override
                public void onFailure(ErrorType errorType, String errorMessage, Throwable throwable) {
                }
            };
            new ApiHelper.RefreshBlogContentTask(WordPress.getCurrentBlog(), callback).executeOnExecutor(
                    AsyncTask.THREAD_POOL_EXECUTOR, false);
        }
    }

    /**
     * This method is called when the user changes the active blog or hides all blogs
     */
    private void blogChanged() {
        WordPress.wpDB.updateLastBlogId(WordPress.getCurrentLocalTableBlogId());
        // the menu may have changed, we need to change the selection if the selected item
        // is not available in the menu anymore
        for (MenuDrawerItem item : mMenuItems) {
            // if the item is selected, but it's no longer visible we need to
            // select the first available item from the adapter
            if (item.isSelected() && !item.isVisible()) {
                // then select the first item and activate it
                if (mDrawerAdapter.getCount() > 0) {
                    ((MenuDrawerItem) mDrawerAdapter.getItem(0)).selectItem();
                }
                // if it has an item id save it to the preferences
                if (item.hasItemId()) {
                    ActivityId.trackLastActivity(WPDrawerActivity.this, item.getItemId());
                }
                break;
            }
        }

        refreshCurrentBlogContent();
        if (shouldUpdateCurrentBlogStatsInBackground()) {
            WordPress.sUpdateCurrentBlogStats.forceRun();
        }

        if (WordPress.getCurrentBlog() != null) {
            onBlogChanged();
        }

    }

    /**
     * This method is called in when the user changes the active blog - descendants should override
     * this to perform activity-specific updates upon blog change
     */
    protected void onBlogChanged() {
    }

    /**
     * this method is called when the user switch blog - descendants should override
     * if want to stop refreshing of Stats when switching blog.
     */
    protected boolean shouldUpdateCurrentBlogStatsInBackground() {
        return true;
    }

    /**
     * this method is called when the user signs out of the app - descendants should override
     * this to perform activity-specific cleanup upon signout
     */
    public void onSignout() {
    }

    /**
     * called when user selects an item from the drawer
     */
    private void startDrawerIntent(final Intent intent) {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(null);
        }
        hideActivityContainer(true);
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                intent.putExtra(OPENED_FROM_DRAWER, true);
                intent.setFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                startActivity(intent);
            }
        }, OPENED_FROM_DRAWER_DELAY);
    }

    private class ReaderMenuItem extends MenuDrawerItem {
        ReaderMenuItem(){
            super(ActivityId.READER, R.string.reader, R.drawable.noticon_reader_alt_black);
        }

        @Override
        public boolean isVisible(){
            return WordPress.hasValidWPComCredentials(WPDrawerActivity.this);
        }

        @Override
        public boolean isSelected(){
            return WPDrawerActivity.this instanceof ReaderPostListActivity;
        }
        @Override
        public void onSelectItem(){
            if (!isSelected())
                mShouldFinish = true;
            startDrawerIntent(new Intent(WPDrawerActivity.this, ReaderPostListActivity.class));
        }
    }

    private class PostsMenuItem extends MenuDrawerItem {
        PostsMenuItem() {
            super(ActivityId.POSTS, R.string.posts, R.drawable.dashicon_admin_post_black);
        }

        @Override
        public boolean isSelected() {
            WPDrawerActivity activity = WPDrawerActivity.this;
            return (activity instanceof PostsActivity) && !(activity instanceof PagesActivity);
        }

        @Override
        public void onSelectItem() {
            if (!(WPDrawerActivity.this instanceof PostsActivity)
                    || (WPDrawerActivity.this instanceof PagesActivity)) {
                mShouldFinish = true;
                AnalyticsTracker.track(AnalyticsTracker.Stat.OPENED_POSTS);
            }
            startDrawerIntent(new Intent(WPDrawerActivity.this, PostsActivity.class));
        }
        @Override
        public boolean isVisible() {
            return WordPress.wpDB.getNumVisibleAccounts() != 0;
        }
    }

    private class MediaMenuItem extends MenuDrawerItem {
        MediaMenuItem(){
            super(ActivityId.MEDIA, R.string.media, R.drawable.dashicon_admin_media_black);
        }
        @Override
        public boolean isSelected(){
            return WPDrawerActivity.this instanceof MediaBrowserActivity;
        }
        @Override
        public void onSelectItem(){
            if (!(WPDrawerActivity.this instanceof MediaBrowserActivity)) {
                mShouldFinish = true;
                AnalyticsTracker.track(AnalyticsTracker.Stat.OPENED_MEDIA_LIBRARY);
            }
            startDrawerIntent(new Intent(WPDrawerActivity.this, MediaBrowserActivity.class));
        }
        @Override
        public boolean isVisible() {
            return WordPress.wpDB.getNumVisibleAccounts() != 0;
        }
    }

    private class PagesMenuItem extends MenuDrawerItem {
        PagesMenuItem(){
            super(ActivityId.PAGES, R.string.pages, R.drawable.dashicon_admin_page_black);
        }
        @Override
        public boolean isSelected(){
            return WPDrawerActivity.this instanceof PagesActivity;
        }
        @Override
        public void onSelectItem(){
            if (WordPress.getCurrentBlog() == null)
                return;
            if (!(WPDrawerActivity.this instanceof PagesActivity)) {
                mShouldFinish = true;
                AnalyticsTracker.track(AnalyticsTracker.Stat.OPENED_PAGES);
            }
            Intent intent = new Intent(WPDrawerActivity.this, PagesActivity.class);
            intent.putExtra("id", WordPress.getCurrentBlog().getLocalTableBlogId());
            intent.putExtra("isNew", true);
            intent.putExtra(PostsActivity.EXTRA_VIEW_PAGES, true);
            startDrawerIntent(intent);
        }
        @Override
        public boolean isVisible() {
            return WordPress.wpDB.getNumVisibleAccounts() != 0;
        }
    }

    private class CommentsMenuItem extends MenuDrawerItem {
        CommentsMenuItem(){
            super(ActivityId.COMMENTS, R.string.tab_comments, R.drawable.dashicon_admin_comments_black);
        }
        @Override
        public boolean isSelected(){
            return WPDrawerActivity.this instanceof CommentsActivity;
        }
        @Override
        public void onSelectItem(){
            if (WordPress.getCurrentBlog() == null)
                return;
            if (!(WPDrawerActivity.this instanceof CommentsActivity)) {
                mShouldFinish = true;
                AnalyticsTracker.track(AnalyticsTracker.Stat.OPENED_COMMENTS);
            }
            Intent intent = new Intent(WPDrawerActivity.this, CommentsActivity.class);
            intent.putExtra("id", WordPress.getCurrentBlog().getLocalTableBlogId());
            intent.putExtra("isNew", true);
            startDrawerIntent(intent);
        }
        @Override
        public int getBadgeCount() {
            // TODO: cache this
            return CommentTable.getUnmoderatedCommentCount(WordPress.getCurrentLocalTableBlogId());
        }
        @Override
        public boolean isVisible() {
            return WordPress.wpDB.getNumVisibleAccounts() != 0;
        }
    }

    private class ThemesMenuItem extends MenuDrawerItem {
        ThemesMenuItem(){
            super(ActivityId.THEMES, R.string.themes, R.drawable.dashboard_icon_themes);
        }
        @Override
        public boolean isSelected(){
            return WPDrawerActivity.this instanceof ThemeBrowserActivity;
        }
        @Override
        public void onSelectItem(){
            if (!(WPDrawerActivity.this instanceof ThemeBrowserActivity))
                mShouldFinish = true;
            startDrawerIntent(new Intent(WPDrawerActivity.this, ThemeBrowserActivity.class));
        }

        @Override
        public boolean isVisible() {
            Blog blog = WordPress.getCurrentBlog();
            return (blog != null && blog.isAdmin() && blog.isDotcomFlag());
        }
    }


    private class StatsMenuItem extends MenuDrawerItem {
        StatsMenuItem(){
            super(ActivityId.STATS, R.string.tab_stats, R.drawable.noticon_milestone_black);
        }
        @Override
        public boolean isSelected(){
            return WPDrawerActivity.this instanceof StatsActivity;
        }
        @Override
        public void onSelectItem(){
            if (WordPress.getCurrentBlog() == null)
                return;
            if (!isSelected())
                mShouldFinish = true;

            Intent intent = new Intent(WPDrawerActivity.this, StatsActivity.class);
            intent.putExtra(StatsActivity.ARG_LOCAL_TABLE_BLOG_ID, WordPress.getCurrentBlog().getLocalTableBlogId());
            startDrawerIntent(intent);
        }
        @Override
        public boolean isVisible() {
            return WordPress.wpDB.getNumVisibleAccounts() != 0;
        }
    }

    private class QuickPhotoMenuItem extends MenuDrawerItem {
        QuickPhotoMenuItem(){
            super(R.string.quick_photo, R.drawable.dashicon_camera_black);
        }
        @Override
        public void onSelectItem(){
            mShouldFinish = false;
            Intent intent = new Intent(WPDrawerActivity.this, EditPostActivity.class);
            intent.putExtra("quick-media", DeviceUtils.getInstance().hasCamera(getApplicationContext())
                    ? Constants.QUICK_POST_PHOTO_CAMERA
                    : Constants.QUICK_POST_PHOTO_LIBRARY);
            intent.putExtra("isNew", true);
            startActivity(intent);
        }
        @Override
        public boolean isVisible() {
            return WordPress.wpDB.getNumVisibleAccounts() != 0;
        }
    }

    private class QuickVideoMenuItem extends MenuDrawerItem {
        QuickVideoMenuItem(){
            super(R.string.quick_video, R.drawable.dashicon_video_alt2_black);
        }
        @Override
        public void onSelectItem(){
            mShouldFinish = false;
            Intent intent = new Intent(WPDrawerActivity.this, EditPostActivity.class);
            intent.putExtra("quick-media", DeviceUtils.getInstance().hasCamera(getApplicationContext())
                    ? Constants.QUICK_POST_VIDEO_CAMERA
                    : Constants.QUICK_POST_VIDEO_LIBRARY);
            intent.putExtra("isNew", true);
            startActivity(intent);
        }
        @Override
        public boolean isVisible() {
            return WordPress.wpDB.getNumVisibleAccounts() != 0;
        }
    }

    private class ViewSiteMenuItem extends MenuDrawerItem {
        ViewSiteMenuItem(){
            super(ActivityId.VIEW_SITE, R.string.view_site, R.drawable.noticon_show_black);
        }
        @Override
        public boolean isSelected(){
            return WPDrawerActivity.this instanceof ViewSiteActivity;
        }
        @Override
        public void onSelectItem(){
            if (!(WPDrawerActivity.this instanceof ViewSiteActivity)) {
                mShouldFinish = true;
                AnalyticsTracker.track(AnalyticsTracker.Stat.OPENED_VIEW_SITE);
            }
            startDrawerIntent(new Intent(WPDrawerActivity.this, ViewSiteActivity.class));
        }
        @Override
        public boolean isVisible() {
            return WordPress.wpDB.getNumVisibleAccounts() != 0;
        }
    }

    private class NotificationsMenuItem extends MenuDrawerItem {
        NotificationsMenuItem(){
            super(ActivityId.NOTIFICATIONS, R.string.notifications, R.drawable.noticon_notification_black);
        }
        @Override
        public boolean isVisible(){
            return WordPress.hasValidWPComCredentials(WPDrawerActivity.this);
        }
        @Override
        public boolean isSelected(){
            return WPDrawerActivity.this instanceof NotificationsActivity;
        }
        @Override
        public void onSelectItem(){
            if (!(WPDrawerActivity.this instanceof NotificationsActivity))
                mShouldFinish = true;
            startDrawerIntent(new Intent(WPDrawerActivity.this, NotificationsActivity.class));
        }
    }

    /**
     * broadcast receiver which detects when user signs out of the app and calls onSignout()
     * so descendants of this activity can do cleanup upon signout
     */
    private void registerReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(WordPress.BROADCAST_ACTION_SIGNOUT);
        filter.addAction(WordPress.BROADCAST_ACTION_XMLRPC_TWO_FA_AUTH);
        filter.addAction(WordPress.BROADCAST_ACTION_XMLRPC_INVALID_CREDENTIALS);
        filter.addAction(WordPress.BROADCAST_ACTION_XMLRPC_INVALID_SSL_CERTIFICATE);
        filter.addAction(WordPress.BROADCAST_ACTION_XMLRPC_LOGIN_LIMIT);
        filter.addAction(WordPress.BROADCAST_ACTION_BLOG_LIST_CHANGED);
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.registerReceiver(mReceiver, filter);
    }

    private void unregisterReceiver() {
        try {
            LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
            lbm.unregisterReceiver(mReceiver);
        } catch (IllegalArgumentException e) {
            // exception occurs if receiver already unregistered (safe to ignore)
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null)
                return;
            if (intent.getAction().equals(WordPress.BROADCAST_ACTION_SIGNOUT)) {
                onSignout();
            }
            if (intent.getAction().equals(WordPress.BROADCAST_ACTION_XMLRPC_INVALID_CREDENTIALS)) {
                AuthenticationDialogUtils.showAuthErrorDialog(WPDrawerActivity.this);
            }
            if (intent.getAction().equals(SimperiumUtils.BROADCAST_ACTION_SIMPERIUM_NOT_AUTHORIZED)
                    && WPDrawerActivity.this instanceof NotificationsActivity) {
                AuthenticationDialogUtils.showAuthErrorDialog(WPDrawerActivity.this, R.string.sign_in_again,
                        R.string.simperium_connection_error);
            }
            if (intent.getAction().equals(WordPress.BROADCAST_ACTION_XMLRPC_TWO_FA_AUTH)) {
                // TODO: add a specific message like "you must use a specific app password"
                AuthenticationDialogUtils.showAuthErrorDialog(WPDrawerActivity.this);
            }
            if (intent.getAction().equals(WordPress.BROADCAST_ACTION_XMLRPC_INVALID_SSL_CERTIFICATE)) {
                SelfSignedSSLCertsManager.askForSslTrust(WPDrawerActivity.this);
            }
            if (intent.getAction().equals(WordPress.BROADCAST_ACTION_XMLRPC_LOGIN_LIMIT)) {
                ToastUtils.showToast(context, R.string.limit_reached, Duration.LONG);
            }
            if (intent.getAction().equals(WordPress.BROADCAST_ACTION_BLOG_LIST_CHANGED)) {
                initMenuDrawer();
            }
        }
    };
}
