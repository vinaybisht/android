/*
 * Copyright (C) 2019 Veli Tasalı
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.genonbeta.TrebleShot.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.collection.ArrayMap;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.genonbeta.TrebleShot.R;
import com.genonbeta.TrebleShot.dialog.SelectionEditorDialog;
import com.genonbeta.TrebleShot.exception.NotReadyException;
import com.genonbeta.TrebleShot.object.Editable;
import com.genonbeta.TrebleShot.object.Shareable;
import com.genonbeta.TrebleShot.util.AppUtils;
import com.genonbeta.TrebleShot.util.FileUtils;
import com.genonbeta.TrebleShot.util.SelectionUtils;
import com.genonbeta.TrebleShot.view.LongTextBubbleFastScrollViewProvider;
import com.genonbeta.TrebleShot.widget.EditableListAdapter;
import com.genonbeta.TrebleShot.widget.EditableListAdapterImpl;
import com.genonbeta.TrebleShot.widget.recyclerview.PaddingItemDecoration;
import com.genonbeta.TrebleShot.widget.recyclerview.SwipeSelectionListener;
import com.genonbeta.android.framework.app.DynamicRecyclerViewFragment;
import com.genonbeta.android.framework.object.Selectable;
import com.genonbeta.android.framework.ui.PerformerMenu;
import com.genonbeta.android.framework.util.actionperformer.*;
import com.genonbeta.android.framework.widget.RecyclerViewAdapter;
import com.genonbeta.android.framework.widget.recyclerview.FastScroller;
import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * created by: Veli
 * date: 21.11.2017 10:12
 */

abstract public class EditableListFragment<T extends Editable, V extends RecyclerViewAdapter.ViewHolder,
        E extends EditableListAdapter<T, V>> extends DynamicRecyclerViewFragment<T, V, E>
        implements EditableListFragmentImpl<T>, EditableListFragmentModelImpl<V>, SelectableHost<T>
{
    public final static String
            ARG_SELECT_BY_CLICK = "argSelectByClick",
            ARG_HAS_BOTTOM_SPACE = "argSelectByClick",
            ARG_SELECTION_CLASSES = "argSelectionClasses",
            ARG_SELECTION_OBJECTS = "argSelectionObjects";

    private IEngineConnection<T> mEngineConnection = new EngineConnection<>(this, this);
    private IPerformerEngine mPerformerEngine = new PerformerEngine();
    private PerformerMenu mPerformerMenu = null;
    private FilteringDelegate<T> mFilteringDelegate;
    private Snackbar mRefreshDelayedSnackbar;
    private boolean mRefreshRequested = false;
    private boolean mSortingSupported = true;
    private boolean mFilteringSupported = false;
    private boolean mUseDefaultPaddingDecoration = false;
    private boolean mUseDefaultPaddingDecorationSpaceForEdges = true;
    private boolean mTwoRowLayoutState = false;
    private boolean mSelectByClick = false;
    private boolean mHasBottomSpace = false;
    private boolean mLocalSelectionActivated = false;
    private float mDefaultPaddingDecorationSize = -1;
    private int mDefaultOrderingCriteria = EditableListAdapter.MODE_SORT_ORDER_ASCENDING;
    private int mDefaultSortingCriteria = EditableListAdapter.MODE_SORT_BY_NAME;
    private int mDefaultViewingGridSize = 1;
    private int mDefaultViewingGridSizeLandscape = 1;
    private int mDividerResId = R.id.abstract_layout_fast_scroll_recyclerview_bottom_divider;
    private FastScroller mFastScroller;
    private Map<String, Integer> mSortingOptions = new ArrayMap<>();
    private Map<String, Integer> mOrderingOptions = new ArrayMap<>();
    private List<T> mSelectableList = new ArrayList<>();
    private ContentObserver mObserver;
    private LayoutClickListener<V> mLayoutClickListener;
    private String mSearchText;
    private FilteringDelegate<T> mDefaultFilteringDelegate = new FilteringDelegate<T>()
    {
        @Override
        public boolean changeFilteringKeyword(@Nullable String keyword)
        {
            mSearchText = keyword;
            return true;
        }

        @Nullable
        @Override
        public String[] getFilteringKeyword(EditableListFragmentImpl<T> listFragment)
        {
            if (mSearchText != null && mSearchText.length() > 0)
                return mSearchText.split(" ");

            return null;
        }
    };

    abstract public boolean onDefaultClickAction(V holder);

    public boolean onDefaultLongClickAction(V holder)
    {
        return false;
    }

    @Nullable
    public PerformerMenu onCreatePerformerMenu(Context context)
    {
        return null;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        getAdapter().setFragment(this);
        Bundle arguments = getArguments();
        mTwoRowLayoutState = isTwoRowLayout();
        mEngineConnection.setSelectableProvider(getAdapterImpl());

        if (arguments != null) {
            mSelectByClick = arguments.getBoolean(ARG_SELECT_BY_CLICK, mSelectByClick);
            mHasBottomSpace = arguments.getBoolean(ARG_HAS_BOTTOM_SPACE, mHasBottomSpace);
        }

        if (savedInstanceState != null && savedInstanceState.containsKey(ARG_SELECTION_CLASSES)
                && savedInstanceState.containsKey(ARG_SELECTION_OBJECTS)) {
            Kryo kryo = new Kryo();
            String[] classes = savedInstanceState.getStringArray(ARG_SELECTION_CLASSES);
            byte[] objects = savedInstanceState.getByteArray(ARG_SELECTION_OBJECTS);

            if (classes != null && objects != null) {
                ByteArrayInputStream inputStream = new ByteArrayInputStream(objects);
                Input input = new Input(inputStream);
                List<T> restoredList = new ArrayList<>();

                for (String className : classes) {
                    try {
                        Class<T> clazz = (Class<T>) Class.forName(className);
                        T object = kryo.readObject(input, clazz);

                        restoredList.add(object);
                    } catch (ClassCastException e) {
                        e.printStackTrace();
                    } catch (ClassNotFoundException e) {
                        Log.e(TAG, "The class " + className + " not found and therefore cannot be loaded back" +
                                "to restore previous state.");
                    }
                }
            }
        }

        if (mPerformerMenu != null)
            mPerformerMenu.setUp(mPerformerEngine);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState)
    {
        super.onActivityCreated(savedInstanceState);

        setHasOptionsMenu(true);

        if (mUseDefaultPaddingDecoration) {
            float padding = mDefaultPaddingDecorationSize > -1 ? mDefaultPaddingDecorationSize
                    : getResources().getDimension(R.dimen.padding_list_content_parent_layout);

            getListView().addItemDecoration(new PaddingItemDecoration((int) padding,
                    mUseDefaultPaddingDecorationSpaceForEdges, isHorizontalOrientation()));
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState)
    {
        super.onViewCreated(view, savedInstanceState);

        getAdapter().notifyGridSizeUpdate(getViewingGridSize(), isScreenLarge());
        getAdapter().setSortingCriteria(getSortingCriteria(), getOrderingCriteria());

        // We have to recreate the provider class because old one doesn't work when
        // same instance is used.
        getFastScroller().setViewProvider(new LongTextBubbleFastScrollViewProvider());
        setDividerVisible(true);
        getListView().addOnItemTouchListener(new SwipeSelectionListener<>(this));

        if (mHasBottomSpace) {
            int bottomSpace = (int) (getResources().getDimension(R.dimen.fab_margin) * 4);
            getListView().setClipToPadding(false);
            getListView().setPadding(0, 0, 0, bottomSpace);
        }
    }


    @Override
    protected RecyclerView onListView(View mainContainer, ViewGroup listViewContainer)
    {
        super.onListView(mainContainer, listViewContainer);
        View view = getLayoutInflater().inflate(R.layout.abstract_layout_fast_scroll_recyclerview_container, null,
                false);

        ViewGroup recyclerViewContainer = view.findViewById(R.id.abstract_layout_fast_scroll_recyclerview_container);
        RecyclerView recyclerView = onListView(recyclerViewContainer);
        mFastScroller = view.findViewById(R.id.abstract_layout_fast_scroll_recyclerview_fastscroll_view);

        // TODO: 1/18/19 Something like onSetListView method would be more safe to set the layout manager etc.
        recyclerView.setLayoutManager(onLayoutManager());
        listViewContainer.addView(view);

        return recyclerView;
    }

    protected RecyclerView onListView(ViewGroup container)
    {
        RecyclerView view = (RecyclerView) getLayoutInflater().inflate(R.layout.abstract_recyclerview, null,
                false);

        container.addView(view);

        return view;
    }

    @Override
    public boolean onSetListAdapter(E adapter)
    {
        if (super.onSetListAdapter(adapter)) {
            mFastScroller.setRecyclerView(getListView());
            return true;
        }

        return false;
    }

    @Override
    public void onResume()
    {
        super.onResume();
        refreshList();

        if (mTwoRowLayoutState != isTwoRowLayout())
            toggleTwoRowLayout();
    }

    @Override
    public void onAttach(@NonNull Context context)
    {
        super.onAttach(context);
        mPerformerMenu = onCreatePerformerMenu(context);
        mEngineConnection.setDefinitiveTitle(getDistinctiveTitle(context));
        mEngineConnection.addSelectionListener(this);

        if (getPerformerEngine() != null)
            getPerformerEngine().ensureSlot(this, getEngineConnection());
    }

    @Override
    public void onDetach()
    {
        super.onDetach();
        if (getPerformerEngine() != null)
            getPerformerEngine().removeSlot(getEngineConnection());
    }

    @Override
    public void onSelected(IPerformerEngine engine, IEngineConnection<T> owner, T selectable, boolean isSelected,
                           int position)
    {
        if (position >= 0)
            getAdapter().syncAndNotify(position);
        else
            getAdapter().syncAllAndNotify();

        ensureLocalSelection();
    }

    @Override
    public void onSelected(IPerformerEngine engine, IEngineConnection<T> owner, List<T> selectableList,
                           boolean isSelected, int[] positions)
    {
        getAdapter().syncAllAndNotify();
        ensureLocalSelection();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState)
    {
        super.onSaveInstanceState(outState);

        {
            SelectableHost<T> host = getEngineConnection().getSelectableHost();
            List<T> list = new ArrayList<>(host.getSelectableList());

            if (host == this && list.size() > 0) {
                Kryo kryo = new Kryo();
                String[] classes = new String[list.size()];

                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                Output output = new Output(outputStream);

                for (int i = 0; i < list.size(); i++) {
                    T obj = list.get(i);
                    Class<? extends Editable> clazz = obj.getClass();
                    classes[i] = clazz.getName();

                    kryo.register(clazz);
                    kryo.writeObject(output, obj);
                    output.flush();
                }

                outState.putStringArray(ARG_SELECTION_CLASSES, classes);
                outState.putByteArray(ARG_SELECTION_OBJECTS, outputStream.toByteArray());
            }
        }
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater)
    {
        super.onCreateOptionsMenu(menu, inflater);

        if (isUsingLocalSelection() && isLocalSelectionActivated())
            mPerformerMenu.populateMenu(menu);
        else {
            inflater.inflate(R.menu.actions_abs_editable_list, menu);

            MenuItem filterItem = menu.findItem(R.id.actions_abs_editable_filter);

            if (filterItem != null) {
                filterItem.setVisible(mFilteringSupported);

                if (mFilteringSupported) {
                    View view = filterItem.getActionView();

                    if (view instanceof SearchView) {
                        ((SearchView) view).setOnQueryTextListener(new SearchView.OnQueryTextListener()
                        {
                            @Override
                            public boolean onQueryTextSubmit(String query)
                            {
                                refreshList();
                                return true;
                            }

                            @Override
                            public boolean onQueryTextChange(String newText)
                            {
                                mSearchText = newText;
                                refreshList();
                                return true;
                            }
                        });
                    }
                }
            }

            MenuItem gridSizeItem = menu.findItem(R.id.actions_abs_editable_grid_size);

            if (gridSizeItem != null) {
                Menu gridSizeMenu = gridSizeItem.getSubMenu();

                for (int i = 1; i < (isScreenLandscape() ? 7 : 5); i++)
                    gridSizeMenu.add(R.id.actions_abs_editable_group_grid_size, 0, i,
                            getContext().getResources().getQuantityString(R.plurals.text_gridRow, i, i));

                gridSizeMenu.setGroupCheckable(R.id.actions_abs_editable_group_grid_size, true, true);
            }

            Map<String, Integer> sortingOptions = new ArrayMap<>();
            onSortingOptions(sortingOptions);

            if (sortingOptions.size() > 0) {
                mSortingOptions.clear();
                mSortingOptions.putAll(sortingOptions);

                applyDynamicMenuItems(menu.findItem(R.id.actions_abs_editable_sort_by),
                        R.id.actions_abs_editable_group_sorting, mSortingOptions);

                Map<String, Integer> orderingOptions = new ArrayMap<>();
                onOrderingOptions(orderingOptions);

                if (orderingOptions.size() > 0) {
                    mOrderingOptions.clear();
                    mOrderingOptions.putAll(orderingOptions);

                    applyDynamicMenuItems(menu.findItem(R.id.actions_abs_editable_order_by),
                            R.id.actions_abs_editable_group_sort_order, mOrderingOptions);
                }
            }
        }
    }

    @Override
    public void onPrepareOptionsMenu(@NonNull Menu menu)
    {
        super.onPrepareOptionsMenu(menu);

        if (!isLocalSelectionActivated()) {
            menu.findItem(R.id.actions_abs_editable_sort_by).setEnabled(isSortingSupported());
            menu.findItem(R.id.actions_abs_editable_multi_select).setVisible(mPerformerMenu != null);

            if (!getAdapter().isGridSupported())
                menu.findItem(R.id.actions_abs_editable_grid_size).setVisible(false);

            MenuItem sortingItem = menu.findItem(R.id.actions_abs_editable_sort_by);

            if (sortingItem != null) {
                sortingItem.setVisible(mSortingSupported);

                if (sortingItem.isVisible()) {
                    checkPreferredDynamicItem(sortingItem, getSortingCriteria(), mSortingOptions);

                    MenuItem orderingItem = menu.findItem(R.id.actions_abs_editable_order_by);

                    if (orderingItem != null)
                        checkPreferredDynamicItem(orderingItem, getOrderingCriteria(), mOrderingOptions);
                }
            }

            MenuItem gridSizeItem = menu.findItem(R.id.actions_abs_editable_grid_size);

            if (gridSizeItem != null) {
                Menu gridRowMenu = gridSizeItem.getSubMenu();
                int currentRow = getViewingGridSize() - 1;

                if (currentRow < gridRowMenu.size())
                    gridRowMenu.getItem(currentRow).setChecked(true);
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        int id = item.getItemId();
        int groupId = item.getGroupId();

        if (id == R.id.actions_abs_editable_multi_select && mPerformerMenu != null && getActivity() != null) {
            setLocalSelectionActivated(!mLocalSelectionActivated);
        } else if (mPerformerMenu != null) {
            if (mPerformerMenu.onMenuItemClick(item))
                setLocalSelectionActivated(false);

            return true;
        } else if (groupId == R.id.actions_abs_editable_group_sorting)
            changeSortingCriteria(item.getOrder());
        else if (groupId == R.id.actions_abs_editable_group_sort_order)
            changeOrderingCriteria(item.getOrder());
        else if (groupId == R.id.actions_abs_editable_group_grid_size)
            changeGridViewSize(item.getOrder());

        return super.onOptionsItemSelected(item);
    }

    public void onSortingOptions(Map<String, Integer> options)
    {
        options.put(getString(R.string.text_sortByName), EditableListAdapter.MODE_SORT_BY_NAME);
        options.put(getString(R.string.text_sortByDate), EditableListAdapter.MODE_SORT_BY_DATE);
        options.put(getString(R.string.text_sortBySize), EditableListAdapter.MODE_SORT_BY_SIZE);
    }

    public void onOrderingOptions(Map<String, Integer> options)
    {
        options.put(getString(R.string.text_sortOrderAscending), EditableListAdapter.MODE_SORT_ORDER_ASCENDING);
        options.put(getString(R.string.text_sortOrderDescending), EditableListAdapter.MODE_SORT_ORDER_DESCENDING);
    }

    public int onGridSpanSize(int viewType, int currentSpanSize)
    {
        return 1;
    }

    @Override
    public RecyclerView.LayoutManager onLayoutManager()
    {
        final RecyclerView.LayoutManager defaultLayoutManager = super.onLayoutManager();
        final int optimumGridSize = getOptimumGridSize();

        final GridLayoutManager layoutManager;

        if (defaultLayoutManager instanceof GridLayoutManager) {
            layoutManager = (GridLayoutManager) defaultLayoutManager;
            layoutManager.setSpanCount(optimumGridSize);
        } else
            layoutManager = new GridLayoutManager(getContext(), optimumGridSize);

        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup()
        {
            @Override
            public int getSpanSize(int position)
            {
                // should be reserved so it can occupy all the available space of a row.
                int viewType = getAdapter().getItemViewType(position);

                return viewType == EditableListAdapter.VIEW_TYPE_DEFAULT ? 1
                        : onGridSpanSize(viewType, optimumGridSize);
            }
        });

        return layoutManager;
    }

    protected void applyDynamicMenuItems(MenuItem mainItem, int groupId, Map<String, Integer> options)
    {
        if (mainItem != null) {
            mainItem.setVisible(true);

            Menu dynamicMenu = mainItem.getSubMenu();

            for (String currentKey : options.keySet()) {
                int modeId = options.get(currentKey);
                dynamicMenu.add(groupId, 0, modeId, currentKey);
            }

            dynamicMenu.setGroupCheckable(groupId, true, true);
        }
    }

    public void applyViewingChanges(int gridSize)
    {
        applyViewingChanges(gridSize, false);
    }

    public void applyViewingChanges(int gridSize, boolean override)
    {
        if (!getAdapter().isGridSupported() && !override)
            return;

        getAdapter().notifyGridSizeUpdate(gridSize, isScreenLarge());

        getListView().setLayoutManager(onLayoutManager());
        getListView().setAdapter(getAdapter());

        refreshList();
    }

    public boolean canShowWideView()
    {
        return !getAdapter().isGridSupported() && isScreenLarge() && !isHorizontalOrientation();
    }

    public void changeGridViewSize(int gridSize)
    {
        getViewPreferences().edit()
                .putInt(getUniqueSettingKey("GridSize" + (isScreenLandscape() ? "Landscape" : "")), gridSize)
                .apply();

        applyViewingChanges(gridSize);
    }

    public void changeOrderingCriteria(int id)
    {
        getViewPreferences().edit()
                .putInt(getUniqueSettingKey("SortOrder"), id)
                .apply();

        getAdapter().setSortingCriteria(getSortingCriteria(), id);

        refreshList();
    }

    public void changeSortingCriteria(int id)
    {
        getViewPreferences().edit()
                .putInt(getUniqueSettingKey("SortBy"), id)
                .apply();

        getAdapter().setSortingCriteria(id, getOrderingCriteria());

        refreshList();
    }

    public void checkPreferredDynamicItem(MenuItem dynamicItem, int preferredItemId, Map<String, Integer> options)
    {
        if (dynamicItem != null) {
            Menu gridSizeMenu = dynamicItem.getSubMenu();

            for (String title : options.keySet()) {
                if (options.get(title) == preferredItemId) {
                    MenuItem menuItem;
                    int iterator = 0;

                    while ((menuItem = gridSizeMenu.getItem(iterator)) != null) {
                        if (title.equals(String.valueOf(menuItem.getTitle()))) {
                            menuItem.setChecked(true);
                            return;
                        }

                        iterator++;
                    }

                    // normally we should not be here
                    return;
                }
            }
        }
    }

    protected void ensureLocalSelection()
    {
        boolean shouldBeEnabled = mEngineConnection.getSelectedItemList().size() > 0;
        if (mLocalSelectionActivated != shouldBeEnabled) {
            Log.d(TAG, "ensureLocalSelection: Altering local selection state to '" + shouldBeEnabled + "'");
            setLocalSelectionActivated(shouldBeEnabled);
        }
    }

    public EditableListAdapterImpl<T> getAdapterImpl()
    {
        return getAdapter();
    }

    public ContentObserver getDefaultContentObserver()
    {
        if (mObserver == null)
            mObserver = new ContentObserver(new Handler(Looper.getMainLooper()))
            {
                @Override
                public boolean deliverSelfNotifications()
                {
                    return true;
                }

                @Override
                public void onChange(boolean selfChange)
                {
                    refreshList();
                }
            };

        return mObserver;
    }

    @Override
    public FilteringDelegate<T> getFilteringDelegate()
    {
        return mFilteringDelegate == null ? mDefaultFilteringDelegate : mFilteringDelegate;
    }

    @Override
    public void setFilteringDelegate(FilteringDelegate<T> delegate)
    {
        mFilteringDelegate = delegate;
    }

    public FastScroller getFastScroller()
    {
        return mFastScroller;
    }

    public int getOrderingCriteria()
    {
        return getViewPreferences().getInt(getUniqueSettingKey("SortOrder"), mDefaultOrderingCriteria);
    }

    private int getOptimumGridSize()
    {
        final int preferredGridSize = getViewingGridSize();
        return preferredGridSize > 1 ? preferredGridSize : canShowWideView() && isTwoRowLayout() ? 2 : 1;
    }

    public String getUniqueSettingKey(String setting)
    {
        return getClass().getSimpleName() + "_" + setting;
    }

    public IEngineConnection<T> getEngineConnection()
    {
        return mEngineConnection;
    }

    @Override
    public List<T> getSelectableList()
    {
        return mSelectableList;
    }

    public int getSortingCriteria()
    {
        return getViewPreferences().getInt(getUniqueSettingKey("SortBy"), mDefaultSortingCriteria);
    }

    public IPerformerEngine getPerformerEngine()
    {
        if (getContext() != null && getActivity() instanceof PerformerEngineProvider)
            return ((PerformerEngineProvider) getActivity()).getPerformerEngine();

        return mPerformerMenu != null ? mPerformerEngine : null;
    }

    public SharedPreferences getViewPreferences()
    {
        return AppUtils.getViewingPreferences(getContext());
    }

    public int getViewingGridSize()
    {
        if (getViewPreferences() == null)
            return 1;

        return isScreenLandscape() ? getViewPreferences().getInt(getUniqueSettingKey("GridSizeLandscape"),
                mDefaultViewingGridSizeLandscape) : getViewPreferences().getInt(getUniqueSettingKey("GridSize"),
                mDefaultViewingGridSize);
    }

    public int getActiveViewingGridSize()
    {
        return getListView().getLayoutManager() instanceof GridLayoutManager
                ? ((GridLayoutManager) getListView().getLayoutManager()).getSpanCount() : 1;
    }

    public boolean invokeClickListener(V holder, boolean longClick)
    {
        return mLayoutClickListener != null && mLayoutClickListener.onLayoutClick(this, holder, longClick);
    }

    @Override
    public boolean isLocalSelectionActivated()
    {
        return mLocalSelectionActivated;
    }

    public boolean isRefreshLocked()
    {
        return false;
    }

    public boolean isRefreshRequested()
    {
        return mRefreshRequested;
    }

    public void setRefreshRequested(boolean requested)
    {
        mRefreshRequested = requested;
    }

    public boolean isSelectByClick()
    {
        return mSelectByClick || mLocalSelectionActivated;
    }

    public boolean isSortingSupported()
    {
        return mSortingSupported;
    }

    public boolean isTwoRowLayout()
    {
        return AppUtils.getDefaultPreferences(getContext()).getBoolean("two_row_layout", true);
    }

    @Override
    public boolean isUsingLocalSelection()
    {
        return !(getActivity() instanceof PerformerEngineProvider) && mPerformerMenu != null;
    }

    public void setSortingSupported(boolean sortingSupported)
    {
        mSortingSupported = sortingSupported;
    }

    public boolean loadIfRequested()
    {
        boolean refreshed = isRefreshRequested();

        setRefreshRequested(false);

        if (refreshed)
            refreshList();

        return refreshed;
    }

    public boolean openUri(Uri uri)
    {
        return FileUtils.openUri(getContext(), uri);
    }

    public boolean performLayoutClick(V holder)
    {
        return setItemSelected(holder) || invokeClickListener(holder, false) || onDefaultClickAction(holder);
    }

    public boolean performLayoutLongClick(V holder)
    {
        return invokeClickListener(holder, true) || onDefaultLongClickAction(holder)
                || setItemSelected(holder, true);
    }

    public boolean performLayoutClickOpen(V holder)
    {
        try {
            T object = getAdapter().getItem(holder);

            if (object instanceof Shareable)
                return openUri(((Shareable) object).uri);
        } catch (NotReadyException e) {
            e.printStackTrace();
        }

        return false;
    }

    public void registerLayoutViewClicks(final V holder)
    {
        holder.itemView.setOnClickListener(v -> performLayoutClick(holder));
        holder.itemView.setOnLongClickListener(v -> performLayoutLongClick(holder));
    }

    @Override
    public void refreshList()
    {
        if (isRefreshLocked()) {
            setRefreshRequested(true);

            if (mRefreshDelayedSnackbar == null) {
                mRefreshDelayedSnackbar = createSnackbar(R.string.mesg_listRefreshSnoozed);
                mRefreshDelayedSnackbar.setDuration(BaseTransientBottomBar.LENGTH_LONG);
            }

            mRefreshDelayedSnackbar.show();
        } else {
            super.refreshList();

            if (mRefreshDelayedSnackbar != null) {
                mRefreshDelayedSnackbar.dismiss();
                mRefreshDelayedSnackbar = null;
            }
        }
    }

    public void setDefaultPaddingDecorationSize(float defaultPadding)
    {
        mDefaultPaddingDecorationSize = defaultPadding;
    }

    public void setDefaultOrderingCriteria(int criteria)
    {
        mDefaultOrderingCriteria = criteria;
    }

    public void setDefaultSortingCriteria(int criteria)
    {
        mDefaultSortingCriteria = criteria;
    }

    public void setDefaultViewingGridSize(int gridSize, int gridSizeLandscape)
    {
        mDefaultViewingGridSize = gridSize;
        mDefaultViewingGridSizeLandscape = gridSizeLandscape;
    }

    public void setDividerVisible(boolean visible)
    {
        if (getView() != null) {
            View divider = getView().findViewById(mDividerResId);
            divider.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    public void setDividerView(int resId)
    {
        mDividerResId = resId;
    }

    public boolean setItemSelected(V holder)
    {
        return setItemSelected(holder, isSelectByClick());
    }

    public boolean setItemSelected(V holder, boolean force)
    {
        if (getEngineConnection().getSelectedItemList().size() <= 0 && !force)
            return false;

        try {
            getEngineConnection().setSelected(holder);
            //holder.setSelected();
            return true;
        } catch (SelectableNotFoundException e) {
            e.printStackTrace();
        } catch (CouldNotAlterException e) {
            e.printStackTrace();
        }

        return false;
    }

    @Override
    public void setLayoutClickListener(LayoutClickListener<V> clickListener)
    {
        mLayoutClickListener = clickListener;
    }

    protected void setLocalSelectionActivated(boolean activate)
    {
        mLocalSelectionActivated = activate;
        if (!mLocalSelectionActivated) {
            List<T> selectedItems = new ArrayList<>(mEngineConnection.getSelectedItemList());
            if (selectedItems.size() > 0)
                mEngineConnection.setSelected(selectedItems, new int[selectedItems.size()], false);
        }

        if (getActivity() != null)
            getActivity().invalidateOptionsMenu();
    }

    public void setFilteringSupported(boolean supported)
    {
        mFilteringSupported = supported;
    }

    public void setHasBottomSpace(boolean has)
    {
        mHasBottomSpace = has;
    }

    public void setUseDefaultPaddingDecoration(boolean use)
    {
        mUseDefaultPaddingDecoration = use;
    }

    public void setUseDefaultPaddingDecorationSpaceForEdges(boolean use)
    {
        mUseDefaultPaddingDecorationSpaceForEdges = use;
    }

    public void toggleTwoRowLayout()
    {
        mTwoRowLayoutState = isTwoRowLayout();
        applyViewingChanges(getOptimumGridSize(), true);
    }

    public interface LayoutClickListener<V extends RecyclerViewAdapter.ViewHolder>
    {
        boolean onLayoutClick(EditableListFragmentImpl<?> listFragment, V holder, boolean longClick);
    }

    public interface FilteringDelegate<T extends Editable>
    {
        boolean changeFilteringKeyword(@Nullable String keyword);

        @Nullable
        String[] getFilteringKeyword(EditableListFragmentImpl<T> listFragment);
    }

    public static class SelectionCallback implements PerformerMenu.Callback, PerformerEngineProvider
    {
        private android.app.Activity mActivity;
        private PerformerEngineProvider mProvider;
        private MenuItem mPreviewSelections;
        private IEngineConnection<?> mForegroundConnection = null;
        private boolean mCancellable = true;

        public SelectionCallback(android.app.Activity activity, PerformerEngineProvider provider)
        {
            mActivity = activity;
            mProvider = provider;
        }

        @Override
        public boolean onPerformerMenuList(PerformerMenu performerMenu, MenuInflater inflater, Menu targetMenu)
        {
            inflater.inflate(R.menu.action_mode_abs_editable, targetMenu);

            if (!mCancellable)
                targetMenu.findItem(R.id.action_mode_abs_editable_cancel_selection).setVisible(false);

            mPreviewSelections = targetMenu.findItem(R.id.action_mode_abs_editable_preview_selections);

            if (getPerformerEngine() != null)
                updateTitle(SelectionUtils.getTotalSize(getPerformerEngine()));
            return true;
        }

        @Override
        public boolean onPerformerMenuSelected(PerformerMenu performerMenu, MenuItem item)
        {
            int id = item.getItemId();

            if (id == R.id.action_mode_abs_editable_cancel_selection) {
                setSelectionState(false, false);
            } else if (id == R.id.action_mode_abs_editable_select_all) {
                setSelectionState(true, true);
            } else if (id == R.id.action_mode_abs_editable_select_none) {
                setSelectionState(false, true);
            } else if (id == R.id.action_mode_abs_editable_preview_selections)
                new SelectionEditorDialog(mActivity, mProvider).show();

            // Returning false means that the target item did not execute anything other than small changes.
            // It can be said that descendants can interfere with this by making changes and returning true;
            return false;
        }

        @Override
        public boolean onPerformerMenuItemSelection(PerformerMenu performerMenu, IPerformerEngine engine,
                                                    IBaseEngineConnection owner, Selectable selectable,
                                                    boolean isSelected, int position)
        {
            return true;
        }

        @Override
        public boolean onPerformerMenuItemSelection(PerformerMenu performerMenu, IPerformerEngine engine,
                                                    IBaseEngineConnection owner,
                                                    List<? extends Selectable> selectableList, boolean isSelected,
                                                    int[] positions)
        {
            return true;
        }

        @Override
        public void onPerformerMenuItemSelected(PerformerMenu performerMenu, IPerformerEngine engine,
                                                IBaseEngineConnection owner, Selectable selectable, boolean isSelected,
                                                int position)
        {
            updateTitle(SelectionUtils.getTotalSize(engine));
        }

        @Override
        public void onPerformerMenuItemSelected(PerformerMenu performerMenu, IPerformerEngine engine,
                                                IBaseEngineConnection owner, List<? extends Selectable> selectableList,
                                                boolean isSelected, int[] positions)
        {
            updateTitle(SelectionUtils.getTotalSize(engine));
        }

        public Activity getActivity()
        {
            return mActivity;
        }

        public void setSelectionState(boolean newState, boolean tryForeground)
        {
            IPerformerEngine engine = mProvider.getPerformerEngine();
            if (mForegroundConnection != null && tryForeground)
                setSelectionState(mForegroundConnection, newState);
            else if (engine != null) {
                for (IBaseEngineConnection baseEngineConnection : engine.getConnectionList())
                    if (baseEngineConnection instanceof IEngineConnection<?>)
                        setSelectionState((IEngineConnection<? extends Selectable>) baseEngineConnection, newState);
            }
        }

        private <T extends Selectable> void setSelectionState(IEngineConnection<T> connection, boolean newState)
        {
            List<T> availableList = connection.getAvailableList();
            if (availableList.size() > 0) {
                int[] positions = new int[availableList.size()];
                for (int i = 0; i < positions.length; i++)
                    positions[i] = i;
                connection.setSelected(availableList, positions, newState);
            }
        }

        public void setCancellable(boolean cancellable)
        {
            mCancellable = cancellable;
        }

        @Nullable
        @Override
        public IPerformerEngine getPerformerEngine()
        {
            return mProvider.getPerformerEngine();
        }

        private void updateTitle(int totalSelections)
        {
            // For local selections, the menu may be invalidated or may be just created meaning the menu item may not be
            // available yet.
            if (mPreviewSelections != null) {
                mPreviewSelections.setTitle(String.valueOf(totalSelections));
                mPreviewSelections.setEnabled(totalSelections > 0);
            }
        }

        /**
         * If you want to only use a single connection with {@link #setSelectionState} calls, you should provide the foreground
         * connection that should be used.
         *
         * @param connection to be used with foreground operations like select all or none
         */
        public void setForegroundConnection(IEngineConnection<?> connection)
        {
            mForegroundConnection = connection;
        }
    }
}