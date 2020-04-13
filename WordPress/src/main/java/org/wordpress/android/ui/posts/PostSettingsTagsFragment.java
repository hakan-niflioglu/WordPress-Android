package org.wordpress.android.ui.posts;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.apache.commons.text.StringEscapeUtils;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.fluxc.Dispatcher;
import org.wordpress.android.fluxc.model.SiteModel;
import org.wordpress.android.fluxc.store.TaxonomyStore;
import org.wordpress.android.fluxc.store.TaxonomyStore.OnTaxonomyChanged;
import org.wordpress.android.util.ActivityUtils;
import org.wordpress.android.util.ToastUtils;
import org.wordpress.android.util.ToastUtils.Duration;

import javax.inject.Inject;

import static org.wordpress.android.ui.posts.PostSettingsTagsActivity.KEY_TAGS;

/**
 * A simple {@link Fragment} subclass.
 */
public class PostSettingsTagsFragment extends Fragment implements TextWatcher, View.OnKeyListener, TagSelectedListener {
    public static final String TAG = "post_settings_tags_fragment_tag";

    private SiteModel mSite;

    private EditText mTagsEditText;
    private TagsRecyclerViewAdapter mAdapter;

    @Inject Dispatcher mDispatcher;
    @Inject TaxonomyStore mTaxonomyStore;

    private String mTags = null;

    private TagsSelectedListener mTagsSelectedListener = null;

    public PostSettingsTagsFragment() {
        // Required empty public constructor
    }

    public static PostSettingsTagsFragment newInstance(SiteModel site, String tags) {
        Bundle args = new Bundle();
        args.putSerializable(WordPress.SITE, site);
        args.putString(KEY_TAGS, tags);

        PostSettingsTagsFragment fragment = new PostSettingsTagsFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Override public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ((WordPress) requireActivity().getApplication()).component().inject(this);

        if (getArguments() != null) {
            mSite = (SiteModel) getArguments().getSerializable(WordPress.SITE);
            mTags = requireActivity().getIntent().getStringExtra(KEY_TAGS);
        }
    }

    @Override public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        if (context instanceof PostSettingsTagsActivity) {
            mTagsSelectedListener = (TagsSelectedListener) context;
        } else {
            mTagsSelectedListener = (TagsSelectedListener) getParentFragment();
        }
    }


    @Override public void onDetach() {
        super.onDetach();
        mTagsSelectedListener = null;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_post_settings_tags, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        RecyclerView recyclerView = (RecyclerView) view.findViewById(R.id.tags_suggestion_list);
        recyclerView.setHasFixedSize(true);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireActivity()));

        mAdapter = new TagsRecyclerViewAdapter(requireActivity(), this);
        mAdapter.setAllTags(mTaxonomyStore.getTagsForSite(mSite));
        recyclerView.setAdapter(mAdapter);

        mTagsEditText = (EditText) view.findViewById(R.id.tags_edit_text);
        mTagsEditText.setOnKeyListener(this);
        mTagsEditText.addTextChangedListener(this);
        if (!TextUtils.isEmpty(mTags)) {
            // add a , at the end so the user can start typing a new tag
            mTags += ",";
            mTags = StringEscapeUtils.unescapeHtml4(mTags);
            mTagsEditText.setText(mTags);
            mTagsEditText.setSelection(mTagsEditText.length());
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        mDispatcher.register(this);
    }

    @Override
    public void onStop() {
        mDispatcher.unregister(this);
        super.onStop();
    }

    @Override
    public boolean onKey(View view, int keyCode, KeyEvent keyEvent) {
        if ((keyEvent.getAction() == KeyEvent.ACTION_DOWN)
            && (keyCode == KeyEvent.KEYCODE_ENTER)) {
            // Since we don't allow new lines, we should add comma on "enter" to separate the tags
            String currentText = mTagsEditText.getText().toString();
            if (!currentText.isEmpty() && !currentText.endsWith(",")) {
                mTagsEditText.setText(currentText.concat(","));
                mTagsEditText.setSelection(mTagsEditText.length());
            }
            return true;
        }
        return false;
    }

    @Override
    public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
        // No-op
    }

    @Override
    public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
        filterListForCurrentText();
        mTagsSelectedListener.onTagsSelected(charSequence.toString());
    }

    @Override
    public void afterTextChanged(Editable editable) {
        // No-op
    }

    // Find the text after the last occurrence of "," and filter with it
    private void filterListForCurrentText() {
        String text = mTagsEditText.getText().toString();
        int endIndex = text.lastIndexOf(",");
        if (endIndex == -1) {
            mAdapter.filter(text);
        } else {
            String textToFilter = text.substring(endIndex + 1).trim();
            mAdapter.filter(textToFilter);
        }
    }

    public void onTagSelected(@NonNull String selectedTag) {
        String text = mTagsEditText.getText().toString();
        String updatedText;
        int endIndex = text.lastIndexOf(",");
        if (endIndex == -1) {
            // no "," found, replace the current text with the selectedTag
            updatedText = selectedTag;
        } else {
            // there are multiple tags already, only update the text after the last ","
            updatedText = text.substring(0, endIndex + 1) + selectedTag;
        }
        updatedText += ",";
        updatedText = StringEscapeUtils.unescapeHtml4(updatedText);
        mTagsEditText.setText(updatedText);
        mTagsEditText.setSelection(mTagsEditText.length());
    }

    @SuppressWarnings("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onTaxonomyChanged(OnTaxonomyChanged event) {
        switch (event.causeOfChange) {
            case FETCH_TAGS:
                mAdapter.setAllTags(mTaxonomyStore.getTagsForSite(mSite));
                filterListForCurrentText();
                break;
        }
    }

    public void closeKeyboard() {
        ActivityUtils.hideKeyboardForced(mTagsEditText);
    }
}

