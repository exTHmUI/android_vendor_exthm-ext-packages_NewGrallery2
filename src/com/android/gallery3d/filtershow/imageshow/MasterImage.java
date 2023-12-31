/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.gallery3d.filtershow.imageshow;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;

import com.android.gallery3d.exif.ExifTag;
import com.android.gallery3d.filtershow.FilterShowActivity;
import com.android.gallery3d.filtershow.cache.BitmapCache;
import com.android.gallery3d.filtershow.cache.ImageLoader;
import com.android.gallery3d.filtershow.editors.Editor;
import com.android.gallery3d.filtershow.filters.FilterCropRepresentation;
import com.android.gallery3d.filtershow.filters.FilterDrawRepresentation;
import com.android.gallery3d.filtershow.filters.FilterImageBorderRepresentation;
import com.android.gallery3d.filtershow.filters.FilterMirrorRepresentation;
import com.android.gallery3d.filtershow.filters.FilterRepresentation;
import com.android.gallery3d.filtershow.filters.FilterRotateRepresentation;
import com.android.gallery3d.filtershow.filters.FilterStraightenRepresentation;
import com.android.gallery3d.filtershow.filters.FilterUserPresetRepresentation;
import com.android.gallery3d.filtershow.filters.ImageFilter;
import com.android.gallery3d.filtershow.history.HistoryItem;
import com.android.gallery3d.filtershow.history.HistoryManager;
import com.android.gallery3d.filtershow.pipeline.Buffer;
import com.android.gallery3d.filtershow.pipeline.ImagePreset;
import com.android.gallery3d.filtershow.pipeline.RenderingRequest;
import com.android.gallery3d.filtershow.pipeline.RenderingRequestCaller;
import com.android.gallery3d.filtershow.pipeline.SharedBuffer;
import com.android.gallery3d.filtershow.pipeline.SharedPreset;
import com.android.gallery3d.filtershow.state.StateAdapter;

import java.util.List;
import java.util.Vector;

public class MasterImage implements RenderingRequestCaller {

    private static final String LOGTAG = "MasterImage";
    private boolean DEBUG = false;
    private static final boolean DISABLEZOOM = false;
    public static final int SMALL_BITMAP_DIM = 160;
    public static final int MAX_BITMAP_DIM = 1000;
    private static MasterImage sMasterImage = null;

    private boolean mSupportsHighRes = false;

    private ImageFilter mCurrentFilter = null;
    private ImagePreset mPreset = null;
    private ImagePreset mLoadedPreset = null;
    private ImagePreset mGeometryOnlyPreset = null;
    private ImagePreset mFiltersOnlyPreset = null;

    private SharedBuffer mPreviewBuffer = new SharedBuffer();
    private SharedPreset mPreviewPreset = new SharedPreset();

    private Bitmap mOriginalBitmapSmall = null;
    private Bitmap mOriginalBitmapLarge = null;
    private Bitmap mOriginalBitmapHighres = null;
    private Bitmap mTemporaryThumbnail = null;
    private int mOrientation;
    private Rect mOriginalBounds;
    private final Vector<ImageShow> mLoadListeners = new Vector<ImageShow>();
    private Uri mUri = null;
    private int mZoomOrientation = ImageLoader.ORI_NORMAL;

    private Bitmap mGeometryOnlyBitmap = null;
    private Bitmap mFiltersOnlyBitmap = null;
    private Bitmap mPartialBitmap = null;
    private Bitmap mHighresBitmap = null;
    private Bitmap mPreviousImage = null;
    private int mShadowMargin = 15; // not scaled, fixed in the asset
    private Rect mPartialBounds = new Rect();

    private ValueAnimator mAnimator = null;
    private float mMaskScale = 1;
    private boolean mOnGoingNewLookAnimation = false;
    private float mAnimRotationValue = 0;
    private float mCurrentAnimRotationStartValue = 0;
    private float mAnimFraction = 0;
    private int mCurrentLookAnimation = 0;
    public static final int CIRCLE_ANIMATION = 1;
    public static final int ROTATE_ANIMATION = 2;
    public static final int MIRROR_ANIMATION = 3;

    private HistoryManager mHistory = null;
    private StateAdapter mState = null;

    private FilterShowActivity mActivity = null;

    private Vector<ImageShow> mObservers = new Vector<ImageShow>();
    private FilterRepresentation mCurrentFilterRepresentation;

    private float mScaleFactor = 1.0f;
    private float mMaxScaleFactor = 3.0f; // TODO: base this on the current view / image
    private Point mTranslation = new Point();
    private Point mOriginalTranslation = new Point();

    private Point mImageShowSize = new Point();

    private boolean mShowsOriginal;
    private List<ExifTag> mEXIF;
    private BitmapCache mBitmapCache = new BitmapCache();
    private boolean mQuitGeometry = false;

    // SPRD: Modify 20151231 for bug519394, TransactionTooLargeException thrown if size of parcel too large
    // especially do much change when do ImageDraw. So consider to save ImagePreset instead of transfer it
    // by intent.
    private ImagePreset mSavedPreset = null;

    // SPRD：Add for bug#616421, Do not respond click events if the category view is not available
    public boolean mIsAvailable = false;

    private Editor mEditor;

    private MasterImage() {
    }

    // TODO: remove singleton
    public static void setMaster(MasterImage master) {
        sMasterImage = master;
    }

    public static MasterImage getImage() {
        if (sMasterImage == null) {
            sMasterImage = new MasterImage();
        }
        return sMasterImage;
    }

    public Bitmap getOriginalBitmapSmall() {
        return mOriginalBitmapSmall;
    }

    public Bitmap getOriginalBitmapLarge() {
        return mOriginalBitmapLarge;
    }

    public Bitmap getOriginalBitmapHighres() {
        if (mCurrentFilterRepresentation != null) {
            String serializationName = mCurrentFilterRepresentation.getSerializationName();
            if (serializationName != null && serializationName.equals("LUT3D_DEHAZE")) {
                return mOriginalBitmapLarge;
            }
        }
        if (mOriginalBitmapHighres == null) {
            return mOriginalBitmapLarge;
        }
        return mOriginalBitmapHighres;
    }

    public void setOriginalBitmapHighres(Bitmap mOriginalBitmapHighres) {
        this.mOriginalBitmapHighres = mOriginalBitmapHighres;
    }

    public int getOrientation() {
        return mOrientation;
    }

    public Rect getOriginalBounds() {
        return mOriginalBounds;
    }

    public void setOriginalBounds(Rect r) {
        mOriginalBounds = r;
    }

    public Uri getUri() {
        return mUri;
    }

    public void setUri(Uri uri) {
        mUri = uri;
    }

    public int getZoomOrientation() {
        return mZoomOrientation;
    }

    public void addListener(ImageShow imageShow) {
        if (!mLoadListeners.contains(imageShow)) {
            mLoadListeners.add(imageShow);
        }
    }

    public void warnListeners() {
        mActivity.runOnUiThread(mWarnListenersRunnable);
    }

    private Runnable mWarnListenersRunnable = new Runnable() {
        @Override
        public void run() {
            for (int i = 0; i < mLoadListeners.size(); i++) {
                ImageShow imageShow = mLoadListeners.elementAt(i);
                imageShow.imageLoaded();
            }
            invalidatePreview();
        }
    };

    public boolean loadBitmap(Uri uri, int size) {
        setUri(uri);
        mEXIF = ImageLoader.getExif(getActivity(), uri);
        mOrientation = ImageLoader.getMetadataOrientation(mActivity, uri);
        Rect originalBounds = new Rect();
        mOriginalBitmapLarge = ImageLoader.loadOrientedConstrainedBitmap(uri, mActivity,
                Math.min(MAX_BITMAP_DIM, size),
                mOrientation, originalBounds);
        setOriginalBounds(originalBounds);
        if (mOriginalBitmapLarge == null) {
            return false;
        }
        /* SPRD: fix bug 384670,load special picture may cause OutOfMemoryError @{ */
        // int sw = SMALL_BITMAP_DIM;
        //  int sh = (int) (sw * (float) mOriginalBitmapLarge.getHeight() / mOriginalBitmapLarge
        //        .getWidth());
        // mOriginalBitmapSmall = Bitmap.createScaledBitmap(mOriginalBitmapLarge, sw, sh, true);
        int tempWidth = mOriginalBitmapLarge.getWidth();
        int tempHeight = mOriginalBitmapLarge.getHeight();
        int sw = 0;
        int sh = 0;
        if (tempWidth >= tempHeight) {
            sw = SMALL_BITMAP_DIM;
            sh = (int) (sw * (float) tempHeight / tempWidth);
            mOriginalBitmapSmall = Bitmap.createScaledBitmap(mOriginalBitmapLarge, sw, sh == 0 ? 1
                    : sh, true);
        } else {
            sh = SMALL_BITMAP_DIM;
            sw = (int) (sh * (float) tempWidth / tempHeight);
            mOriginalBitmapSmall = Bitmap.createScaledBitmap(mOriginalBitmapLarge,
                    sw == 0 ? 1 : sw, sh, true);
        }
        /* @} */
        mZoomOrientation = mOrientation;
        warnListeners();
        return true;
    }

    public void setSupportsHighRes(boolean value) {
        mSupportsHighRes = value;
    }

    public void addObserver(ImageShow observer) {
        if (mObservers.contains(observer)) {
            return;
        }
        mObservers.add(observer);
    }

    public void removeObserver(ImageShow observer) {
        mObservers.remove(observer);
    }

    public void setActivity(FilterShowActivity activity) {
        mActivity = activity;
    }

    public FilterShowActivity getActivity() {
        return mActivity;
    }

    public synchronized ImagePreset getPreset() {
        return mPreset;
    }

    public synchronized ImagePreset getGeometryPreset() {
        return mGeometryOnlyPreset;
    }

    public synchronized ImagePreset getFiltersOnlyPreset() {
        return mFiltersOnlyPreset;
    }

    public synchronized void setPreset(ImagePreset preset,
                                       FilterRepresentation change,
                                       boolean addToHistory) {
        if (DEBUG) {
            preset.showFilters();
        }
        mPreset = preset;
        mPreset.fillImageStateAdapter(mState);
        if (addToHistory) {
            HistoryItem historyItem = new HistoryItem(mPreset, change);
            mHistory.addHistoryItem(historyItem);
        }
        updatePresets(true);
        resetGeometryImages(false);
        mActivity.updateCategories();
    }

    public void onHistoryItemClick(int position) {
        HistoryItem historyItem = mHistory.getItem(position);
        // SPRD: for bug500874, the historyItem maybe null;
        if (historyItem == null) {
            return;
        }
        // We need a copy from the history
        ImagePreset newPreset = new ImagePreset(historyItem.getImagePreset());
        /* SPRD: bug 594896,FilterDrawRepresentation need save */
        if (historyItem.getFilterRepresentation() != null
                && historyItem.getFilterRepresentation() instanceof FilterDrawRepresentation) {
            FilterRepresentation filter = historyItem.getFilterRepresentation().copy();
            filter.setIgnoreStatus(true);
            newPreset.addFilter(filter);
            MasterImage.getImage().commitDrawFilter(newPreset);
        } else {
            // don't need to add it to the history
            setPreset(newPreset, historyItem.getFilterRepresentation(), false);
        }
        /* @} */
        mHistory.setCurrentPreset(position);
    }

    public boolean resetGeometryFilter(int position) {
        HistoryItem historyItem = mHistory.getItem(position);
        // SPRD: for bug500874, the historyItem maybe null;
        if (historyItem == null) {
            return true;
        }
        // We need a copy from the history
        if (historyItem.getFilterRepresentation() != null && (historyItem
                .getFilterRepresentation() instanceof FilterStraightenRepresentation
                || historyItem.getFilterRepresentation() instanceof FilterRotateRepresentation
                || historyItem.getFilterRepresentation() instanceof FilterMirrorRepresentation
                || historyItem.getFilterRepresentation() instanceof FilterCropRepresentation)) {
            mQuitGeometry = true;
            return mQuitGeometry;
        }
        if (mQuitGeometry) {
            ImagePreset newPreset = new ImagePreset(historyItem.getImagePreset());
            /* SPRD: bug 594896,FilterDrawRepresentation need save */
            if (historyItem.getFilterRepresentation() != null
                    && historyItem.getFilterRepresentation() instanceof FilterDrawRepresentation) {
                FilterRepresentation filter = historyItem.getFilterRepresentation().copy();
                filter.setIgnoreStatus(true);
                newPreset.addFilter(filter);
                MasterImage.getImage().commitDrawFilter(newPreset);
            } else {
                // don't need to add it to the history
                setPreset(newPreset, historyItem.getFilterRepresentation(), false);
            }
            /* @} */
            mHistory.setCurrentPreset(position);
            mQuitGeometry = false;
        }
        return mQuitGeometry;
    }

    public HistoryManager getHistory() {
        return mHistory;
    }

    public StateAdapter getState() {
        return mState;
    }

    public void setHistoryManager(HistoryManager adapter) {
        mHistory = adapter;
    }

    public void setStateAdapter(StateAdapter adapter) {
        mState = adapter;
    }

    public void setCurrentFilter(ImageFilter filter) {
        mCurrentFilter = filter;
    }

    public ImageFilter getCurrentFilter() {
        return mCurrentFilter;
    }

    public synchronized boolean hasModifications() {
        // TODO: We need to have a better same effects check to see if two
        // presets are functionally the same. Right now, we are relying on a
        // stricter check as equals().
        ImagePreset loadedPreset = getLoadedPreset();
        if (mPreset == null) {
            if (loadedPreset == null) {
                return false;
            } else {
                return loadedPreset.hasModifications();
            }
        } else {
            if (loadedPreset == null) {
                return mPreset.hasModifications();
            } else {
                return !mPreset.equals(loadedPreset);
            }
        }
    }

    public SharedBuffer getPreviewBuffer() {
        return mPreviewBuffer;
    }

    public SharedPreset getPreviewPreset() {
        return mPreviewPreset;
    }

    public Bitmap getFilteredImage() {
        mPreviewBuffer.swapConsumerIfNeeded(); // get latest bitmap
        Buffer consumer = mPreviewBuffer.getConsumer();
        if (consumer != null) {
            return consumer.getBitmap();
        }
        return null;
    }

    public Bitmap getFiltersOnlyImage() {
        return mFiltersOnlyBitmap;
    }

    public Bitmap getGeometryOnlyImage() {
        return mGeometryOnlyBitmap;
    }

    public Bitmap getPartialImage() {
        return mPartialBitmap;
    }

    public Rect getPartialBounds() {
        return mPartialBounds;
    }

    public Bitmap getHighresImage() {
        if (mHighresBitmap == null) {
            return getFilteredImage();
        }
        return mHighresBitmap;
    }

    public Bitmap getPreviousImage() {
        return mPreviousImage;
    }

    public ImagePreset getCurrentPreset() {
        return getPreviewBuffer().getConsumer().getPreset();
    }

    public float getMaskScale() {
        return mMaskScale;
    }

    public void setMaskScale(float scale) {
        mMaskScale = scale;
        notifyObservers();
    }

    public float getAnimRotationValue() {
        return mAnimRotationValue;
    }

    public void setAnimRotation(float rotation) {
        mAnimRotationValue = mCurrentAnimRotationStartValue + rotation;
        notifyObservers();
    }

    public void setAnimFraction(float fraction) {
        mAnimFraction = fraction;
    }

    public float getAnimFraction() {
        return mAnimFraction;
    }

    public boolean onGoingNewLookAnimation() {
        return mOnGoingNewLookAnimation;
    }

    public int getCurrentLookAnimation() {
        return mCurrentLookAnimation;
    }

    public void resetAnimBitmap() {
        mBitmapCache.cache(mPreviousImage);
        mPreviousImage = null;
    }

    public void onNewLook(FilterRepresentation newRepresentation) {
        if (getFilteredImage() == null) {
            return;
        }
        if (mAnimator != null) {
            mAnimator.cancel();
            if (mCurrentLookAnimation == ROTATE_ANIMATION) {
                /* SPRD:Modify 20151026 of bug489398, some error happen when rotate and then mirror image @{
                 * mCurrentAnimRotationStartValue += 90;*/
                // if newRepresentation not of rotating purpose, do not add curRotVal
                // otherwise, e.x. scaling factor for mirror option would be around 90
                if (newRepresentation instanceof FilterRotateRepresentation) {
                    mCurrentAnimRotationStartValue += 90;
                }
                /* @} */
            }
        } else {
            resetAnimBitmap();
            mPreviousImage = mBitmapCache.getBitmapCopy(getFilteredImage(), BitmapCache.NEW_LOOK);
        }
        if (newRepresentation instanceof FilterUserPresetRepresentation) {
            mCurrentLookAnimation = CIRCLE_ANIMATION;
            mAnimator = ValueAnimator.ofFloat(0, 1);
            mAnimator.setDuration(650);
        }
        if (newRepresentation instanceof FilterRotateRepresentation) {
            mCurrentLookAnimation = ROTATE_ANIMATION;
            // mAnimator = ValueAnimator.ofFloat(0, 90); // Animator Clockwise
            mAnimator = ValueAnimator.ofFloat(0, -90); // Animator Anti-clockwise
            mAnimator.setDuration(500);
        }
        if (newRepresentation instanceof FilterMirrorRepresentation) {
            mCurrentLookAnimation = MIRROR_ANIMATION;
            mAnimator = ValueAnimator.ofFloat(1, 0, -1);
            mAnimator.setDuration(500);
        }
        mAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                if (mCurrentLookAnimation == CIRCLE_ANIMATION) {
                    setMaskScale((Float) animation.getAnimatedValue());
                } else if (mCurrentLookAnimation == ROTATE_ANIMATION
                        || mCurrentLookAnimation == MIRROR_ANIMATION) {
                    setAnimRotation((Float) animation.getAnimatedValue());
                    setAnimFraction(animation.getAnimatedFraction());
                }
            }
        });
        mAnimator.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {
                mOnGoingNewLookAnimation = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mOnGoingNewLookAnimation = false;
                mCurrentAnimRotationStartValue = 0;
                mAnimator = null;
                notifyObservers();
            }

            @Override
            public void onAnimationCancel(Animator animation) {

            }

            @Override
            public void onAnimationRepeat(Animator animation) {

            }
        });
        mAnimator.start();
        notifyObservers();
    }

    public void notifyObservers() {
        for (ImageShow observer : mObservers) {
            observer.invalidate();

        }
        if (mEditor != null) {
            mEditor.detach();
            mEditor = null;
        }
    }

    public void setEditorPanel(Editor editor) {
        mEditor = editor;
    }

    public void resetGeometryImages(boolean force) {
        if (mPreset == null) {
            return;
        }
        ImagePreset newPresetGeometryOnly = new ImagePreset(mPreset);
        newPresetGeometryOnly.setDoApplyFilters(false);
        newPresetGeometryOnly.setDoApplyGeometry(true);
        if (force || mGeometryOnlyPreset == null
                || !newPresetGeometryOnly.equals(mGeometryOnlyPreset)) {
            mGeometryOnlyPreset = newPresetGeometryOnly;
            RenderingRequest.post(mActivity, null,
                    mGeometryOnlyPreset, RenderingRequest.GEOMETRY_RENDERING, this);
        }
        ImagePreset newPresetFiltersOnly = new ImagePreset(mPreset);
        newPresetFiltersOnly.setDoApplyFilters(true);
        newPresetFiltersOnly.setDoApplyGeometry(false);
        if (force || mFiltersOnlyPreset == null
                || !newPresetFiltersOnly.same(mFiltersOnlyPreset)) {
            mFiltersOnlyPreset = newPresetFiltersOnly;
            RenderingRequest.post(mActivity, null,
                    mFiltersOnlyPreset, RenderingRequest.FILTERS_RENDERING, this);
        }
    }

    public void updatePresets(boolean force) {
        invalidatePreview();
    }

    public FilterRepresentation getCurrentFilterRepresentation() {
        return mCurrentFilterRepresentation;
    }

    public void setCurrentFilterRepresentation(FilterRepresentation currentFilterRepresentation) {
        mCurrentFilterRepresentation = currentFilterRepresentation;
    }

    public void invalidateFiltersOnly() {
        mFiltersOnlyPreset = null;
        invalidatePreview();
    }

    public void invalidatePartialPreview() {
        if (mPartialBitmap != null) {
            mBitmapCache.cache(mPartialBitmap);
            mPartialBitmap = null;
            notifyObservers();
        }
    }

    public void invalidateHighresPreview() {
        if (mHighresBitmap != null) {
            mBitmapCache.cache(mHighresBitmap);
            mHighresBitmap = null;
            notifyObservers();
        }
    }

    public void invalidatePreview() {
        if (mPreset == null) {
            return;
        }

        mPreviewPreset.enqueuePreset(mPreset);
        mPreviewBuffer.invalidate();
        invalidatePartialPreview();
        invalidateHighresPreview();
        needsUpdatePartialPreview();
        needsUpdateHighResPreview();
        mActivity.getProcessingService().updatePreviewBuffer();
    }

    public void setImageShowSize(int w, int h) {
        if (mImageShowSize.x != w || mImageShowSize.y != h) {
            mImageShowSize.set(w, h);
            // SPRD: fix bug 375291,NullPointerException in monkey test
            if (mOriginalBounds == null) {
                return;
            }
            float maxWidth = mOriginalBounds.width() / (float) w;
            float maxHeight = mOriginalBounds.height() / (float) h;
            mMaxScaleFactor = Math.max(3.f, Math.max(maxWidth, maxHeight));
            needsUpdatePartialPreview();
            needsUpdateHighResPreview();
        }
    }

    public Matrix originalImageToScreen() {
        return computeImageToScreen(null, 0, true);
    }

    public Matrix computeImageToScreen(Bitmap bitmapToDraw,
                                       float rotate,
                                       boolean applyGeometry) {
        if (getOriginalBounds() == null
                || mImageShowSize.x == 0
                || mImageShowSize.y == 0) {
            return null;
        }

        Matrix m = null;
        float scale = 1f;
        float translateX = 0;
        float translateY = 0;

        // SPRD: fix bug 502315 NullPointerException
        if (applyGeometry && mPreset != null) {
            GeometryMathUtils.GeometryHolder holder = GeometryMathUtils.unpackGeometry(
                    mPreset.getGeometryFilters());
            m = GeometryMathUtils.getCropSelectionToScreenMatrix(null, holder,
                    getOriginalBounds().width(), getOriginalBounds().height(),
                    mImageShowSize.x, mImageShowSize.y);
        } else if (bitmapToDraw != null) {
            m = new Matrix();
            RectF size = new RectF(0, 0,
                    bitmapToDraw.getWidth(),
                    bitmapToDraw.getHeight());
            /* SPRD: bug 492442, when edit image,some image will happen Overlapping phenomenon @} */
            //            scale = mImageShowSize.x / size.width();
            //            if (size.width() < size.height()) {
            //                scale = mImageShowSize.y / size.height();
            //            }
            float scaleWidth = mImageShowSize.x / size.width();
            float scaleHeight = mImageShowSize.y / size.height();
            scale = Math.min(scaleWidth, scaleHeight);
            /* @{ */

            translateX = (mImageShowSize.x - (size.width() * scale)) / 2.0f;
            translateY = (mImageShowSize.y - (size.height() * scale)) / 2.0f;
        } else {
            return null;
        }

        Point translation = getTranslation();
        m.postScale(scale, scale);
        m.postRotate(rotate, mImageShowSize.x / 2.0f, mImageShowSize.y / 2.0f);
        m.postTranslate(translateX, translateY);
        m.postTranslate(mShadowMargin, mShadowMargin);
        m.postScale(getScaleFactor(), getScaleFactor(),
                mImageShowSize.x / 2.0f,
                mImageShowSize.y / 2.0f);
        m.postTranslate(translation.x * getScaleFactor(),
                translation.y * getScaleFactor());
        return m;
    }

    private Matrix getImageToScreenMatrix(boolean reflectRotation) {
        if (getOriginalBounds() == null || mImageShowSize.x == 0 || mImageShowSize.y == 0) {
            return new Matrix();
        }
        Matrix m = GeometryMathUtils.getImageToScreenMatrix(mPreset.getGeometryFilters(),
                reflectRotation, getOriginalBounds(), mImageShowSize.x, mImageShowSize.y);
        if (m == null) {
            m = new Matrix();
            m.reset();
            return m;
        }
        Point translate = getTranslation();
        float scaleFactor = getScaleFactor();
        m.postTranslate(translate.x, translate.y);
        m.postScale(scaleFactor, scaleFactor, mImageShowSize.x / 2.0f, mImageShowSize.y / 2.0f);
        return m;
    }

    private Matrix getScreenToImageMatrix(boolean reflectRotation) {
        Matrix m = getImageToScreenMatrix(reflectRotation);
        Matrix invert = new Matrix();
        m.invert(invert);
        return invert;
    }

    public void needsUpdateHighResPreview() {
        if (!mSupportsHighRes) {
            return;
        }
        if (mActivity.getProcessingService() == null) {
            return;
        }
        if (mPreset == null) {
            return;
        }
        mActivity.getProcessingService().postHighresRenderingRequest(mPreset,
                getScaleFactor(), this);
        invalidateHighresPreview();
    }

    public void needsUpdatePartialPreview() {
        if (mPreset == null) {
            return;
        }
        // UNISOC added for bug 1433782, solve the problem of border disappear.
        HistoryItem historyItem = mHistory.getCurrent();
        if (historyItem == null) {
            return;
        }
        FilterRepresentation currentFilterRepresentation = historyItem.getFilterRepresentation();
        boolean isFilterImageBorderRepresentation = false;
        if (currentFilterRepresentation != null
                && currentFilterRepresentation instanceof FilterImageBorderRepresentation) {
            isFilterImageBorderRepresentation = true;
        }
        if (!isFilterImageBorderRepresentation && !mPreset.canDoPartialRendering()) {
            invalidatePartialPreview();
            return;
        }
        Matrix originalToScreen = MasterImage.getImage().originalImageToScreen();
        if (originalToScreen == null) {
            return;
        }
        Matrix screenToOriginal = new Matrix();
        originalToScreen.invert(screenToOriginal);
        RectF bounds = new RectF(0, 0,
                mImageShowSize.x + 2 * mShadowMargin,
                mImageShowSize.y + 2 * mShadowMargin);
        screenToOriginal.mapRect(bounds);
        Rect rBounds = new Rect();
        bounds.roundOut(rBounds);

        mActivity.getProcessingService().postFullresRenderingRequest(mPreset,
                getScaleFactor(), rBounds,
                new Rect(0, 0, mImageShowSize.x, mImageShowSize.y), this);
        invalidatePartialPreview();
    }

    @Override
    public void available(RenderingRequest request) {
        if (request.getBitmap() == null) {
            return;
        }

        boolean needsCheckModification = false;
        if (request.getType() == RenderingRequest.GEOMETRY_RENDERING) {
            mBitmapCache.cache(mGeometryOnlyBitmap);
            mGeometryOnlyBitmap = request.getBitmap();
            needsCheckModification = true;
        }
        if (request.getType() == RenderingRequest.FILTERS_RENDERING) {
            mBitmapCache.cache(mFiltersOnlyBitmap);
            mFiltersOnlyBitmap = request.getBitmap();
            notifyObservers();
            needsCheckModification = true;
        }
        if (request.getType() == RenderingRequest.PARTIAL_RENDERING
                && request.getScaleFactor() == getScaleFactor()) {
            mBitmapCache.cache(mPartialBitmap);
            mPartialBitmap = request.getBitmap();
            mPartialBounds.set(request.getBounds());
            notifyObservers();
            needsCheckModification = true;
        }
        if (request.getType() == RenderingRequest.HIGHRES_RENDERING) {
            mBitmapCache.cache(mHighresBitmap);

            mHighresBitmap = request.getBitmap();
            notifyObservers();
            needsCheckModification = true;
        }
        if (needsCheckModification) {
            mActivity.enableSave(hasModifications());
        }
        // SPRD：Add for bug#616421, Do not respond click events if the category view is not available
        mIsAvailable = true;
    }

    public static void reset() {
        sMasterImage = null;
    }

    public float getScaleFactor() {
        return mScaleFactor;
    }

    public void setScaleFactor(float scaleFactor) {
        if (DISABLEZOOM) {
            return;
        }
        if (scaleFactor == mScaleFactor) {
            return;
        }
        mScaleFactor = scaleFactor;
        invalidatePartialPreview();
    }

    public Point getTranslation() {
        return mTranslation;
    }

    public void setTranslation(Point translation) {
        if (DISABLEZOOM) {
            mTranslation.x = 0;
            mTranslation.y = 0;
            return;
        }
        mTranslation.x = translation.x;
        mTranslation.y = translation.y;
        needsUpdatePartialPreview();
    }

    public Point getOriginalTranslation() {
        return mOriginalTranslation;
    }

    public void setOriginalTranslation(Point originalTranslation) {
        if (DISABLEZOOM) {
            return;
        }
        mOriginalTranslation.x = originalTranslation.x;
        mOriginalTranslation.y = originalTranslation.y;
    }

    public void resetTranslation() {
        mTranslation.x = 0;
        mTranslation.y = 0;
        needsUpdatePartialPreview();
    }

    public Bitmap getTemporaryThumbnailBitmap() {
        if (mTemporaryThumbnail == null
                && getOriginalBitmapSmall() != null) {
            mTemporaryThumbnail = getOriginalBitmapSmall().copy(Bitmap.Config.ARGB_8888, true);
            Canvas canvas = new Canvas(mTemporaryThumbnail);
            canvas.drawARGB(200, 80, 80, 80);
        }
        return mTemporaryThumbnail;
    }

    public Bitmap getThumbnailBitmap() {
        return getOriginalBitmapSmall();
    }

    public Bitmap getLargeThumbnailBitmap() {
        return getOriginalBitmapLarge();
    }

    public float getMaxScaleFactor() {
        if (DISABLEZOOM) {
            return 1;
        }
        return mMaxScaleFactor;
    }

    public void setMaxScaleFactor(float maxScaleFactor) {
        mMaxScaleFactor = maxScaleFactor;
    }

    public boolean supportsHighRes() {
        return mSupportsHighRes;
    }

    public void setShowsOriginal(boolean value) {
        mShowsOriginal = value;
        notifyObservers();
    }

    public boolean showsOriginal() {
        return mShowsOriginal;
    }

    public void setLoadedPreset(ImagePreset preset) {
        mLoadedPreset = preset;
    }

    public ImagePreset getLoadedPreset() {
        return mLoadedPreset;
    }

    public List<ExifTag> getEXIF() {
        return mEXIF;
    }

    public BitmapCache getBitmapCache() {
        return mBitmapCache;
    }

    public boolean hasTinyPlanet() {
        // SPRD: fix bug 494383,NullPointerException in monkeytest
        if (mPreset == null) {
            return false;
        }
        return mPreset.contains(FilterRepresentation.TYPE_TINYPLANET);
    }

    // SPRD: Modify 20151231 for bug519394, TransactionTooLargeException thrown if size of parcel too large
    // especially do much change when do ImageDraw. So consider to save ImagePreset instead of transfer it
    // by intent. @{
    public synchronized void setSavedPreset(ImagePreset preset) {
        mSavedPreset = preset;
    }

    public synchronized ImagePreset getSavedPreset() {
        return mSavedPreset;
    }
    // @}

    /* SPRD:bug 590378, after draw image,apply mirrorFilter error @{ */
    public synchronized void commitDrawFilter(ImagePreset preset) {
        mPreset = preset;
        mPreset.fillImageStateAdapter(mState);
        updatePresets(true);
        resetGeometryImages(false);
        mActivity.updateCategories();
    }
    /* @} */
}
