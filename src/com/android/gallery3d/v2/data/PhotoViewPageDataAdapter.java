package com.android.gallery3d.v2.data;

import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapRegionDecoder;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Log;

import com.android.gallery3d.app.GalleryApp;
import com.android.gallery3d.app.GalleryAppImpl;
import com.android.gallery3d.app.LoadingListener;
import com.android.gallery3d.common.BitmapUtils;
import com.android.gallery3d.common.Utils;
import com.android.gallery3d.data.ContentListener;
import com.android.gallery3d.data.DataManager;
import com.android.gallery3d.data.DecodeUtils;
import com.android.gallery3d.data.ImageCacheService;
import com.android.gallery3d.data.LocalImage;
import com.android.gallery3d.data.LocalMediaItem;
import com.android.gallery3d.data.MediaItem;
import com.android.gallery3d.data.MediaObject;
import com.android.gallery3d.data.MediaSet;
import com.android.gallery3d.data.Path;
import com.android.gallery3d.glrenderer.TiledTexture;
import com.android.gallery3d.ui.GLRoot;
import com.android.gallery3d.ui.PhotoView;
import com.android.gallery3d.ui.ScreenNail;
import com.android.gallery3d.ui.SprdTiledScreenNail;
import com.android.gallery3d.ui.SynchronizedHandler;
import com.android.gallery3d.ui.TileImageViewAdapter;
import com.android.gallery3d.ui.TiledScreenNail;
import com.android.gallery3d.util.Future;
import com.android.gallery3d.util.FutureListener;
import com.android.gallery3d.util.GalleryUtils;
import com.android.gallery3d.util.ImageCache;
import com.android.gallery3d.util.MediaSetUtils;
import com.android.gallery3d.util.ThreadPool;
import com.android.gallery3d.v2.app.GalleryActivity2;
import com.android.gallery3d.v2.page.PhotoViewPageFragment;
import com.sprd.frameworks.StandardFrameworks;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public class PhotoViewPageDataAdapter implements PhotoViewPageFragment.Model {
    private static final String TAG = PhotoViewPageDataAdapter.class.getSimpleName();

    private static final int ITEM_LARGE_COUNT = 1500;

    private static final int MSG_LOAD_START = 1;
    private static final int MSG_LOAD_FINISH = 2;
    private static final int MSG_RUN_OBJECT = 3;
    private static final int MSG_UPDATE_IMAGE_REQUESTS = 4;

    private static final int MIN_LOAD_COUNT = 16;
    private static boolean isLowRam = StandardFrameworks.getInstances().isLowRam();
    private static final int DATA_CACHE_SIZE = isLowRam ? 128 : 256;
    private static final int SCREEN_NAIL_MAX = PhotoView.SCREEN_NAIL_MAX;
    private static final int IMAGE_CACHE_SIZE = 2 * SCREEN_NAIL_MAX + 1;

    private static final int BIT_SCREEN_NAIL = 1;
    private static final int BIT_FULL_IMAGE = 2;

    // sImageFetchSeq is the fetching sequence for images.
    // We want to fetch the current screennail first (offset = 0), the next
    // screennail (offset = +1), then the previous screennail (offset = -1) etc.
    // After all the screennail are fetched, we fetch the full images (only some
    // of them because of we don't want to use too much memory).
    private static ImageFetch[] sImageFetchSeq;

    private static class ImageFetch {
        int indexOffset;
        int imageBit;

        public ImageFetch(int offset, int bit) {
            indexOffset = offset;
            imageBit = bit;
        }
    }

    static {
        int k = 0;
        sImageFetchSeq = new ImageFetch[1 + (IMAGE_CACHE_SIZE - 1) * 2 + 3];
        sImageFetchSeq[k++] = new ImageFetch(0, BIT_SCREEN_NAIL);

        for (int i = 1; i < IMAGE_CACHE_SIZE; ++i) {
            sImageFetchSeq[k++] = new ImageFetch(i, BIT_SCREEN_NAIL);
            sImageFetchSeq[k++] = new ImageFetch(-i, BIT_SCREEN_NAIL);
        }

        sImageFetchSeq[k++] = new ImageFetch(0, BIT_FULL_IMAGE);
        sImageFetchSeq[k++] = new ImageFetch(1, BIT_FULL_IMAGE);
        sImageFetchSeq[k++] = new ImageFetch(-1, BIT_FULL_IMAGE);
    }

    private final TileImageViewAdapter mTileProvider = new TileImageViewAdapter();

    // PhotoDataAdapter caches MediaItems (data) and ImageEntries (image).
    //
    // The MediaItems are stored in the mData array, which has DATA_CACHE_SIZE
    // entries. The valid index range are [mContentStart, mContentEnd). We keep
    // mContentEnd - mContentStart <= DATA_CACHE_SIZE, so we can use
    // (i % DATA_CACHE_SIZE) as index to the array.
    //
    // The valid MediaItem window size (mContentEnd - mContentStart) may be
    // smaller than DATA_CACHE_SIZE because we only update the window and reload
    // the MediaItems when there are significant changes to the window position
    // (>= MIN_LOAD_COUNT).
    private final MediaItem mData[] = new MediaItem[DATA_CACHE_SIZE];
    private int mContentStart = 0;
    private int mContentEnd = 0;

    // The ImageCache is a Path-to-ImageEntry map. It only holds the
    // ImageEntries in the range of [mActiveStart, mActiveEnd).  We also keep
    // mActiveEnd - mActiveStart <= IMAGE_CACHE_SIZE.  Besides, the
    // [mActiveStart, mActiveEnd) range must be contained within
    // the [mContentStart, mContentEnd) range.
    private HashMap<Path, ImageEntry> mImageCache =
            new HashMap<Path, ImageEntry>();
    private HashMap<Path, Future<Bitmap>> mImageBokehCache =
            new HashMap<Path, Future<Bitmap>>();
    private int mActiveStart = 0;
    private int mActiveEnd = 0;

    // mCurrentIndex is the "center" image the user is viewing. The change of
    // mCurrentIndex triggers the data loading and image loading.
    private int mCurrentIndex;

    // mChanges keeps the version number (of MediaItem) about the images. If any
    // of the version number changes, we notify the view. This is used after a
    // database reload or mCurrentIndex changes.
    private final long mChanges[] = new long[IMAGE_CACHE_SIZE];
    // mPaths keeps the corresponding Path (of MediaItem) for the images. This
    // is used to determine the item movement.
    private final Path mPaths[] = new Path[IMAGE_CACHE_SIZE];

    private final Handler mMainHandler;
    private final ThreadPool mThreadPool;

    private final PhotoView mPhotoView;
    private final MediaSet mSource;
    private ReloadTask mReloadTask;
    private GalleryApp mApplication;

    private long mSourceVersion = MediaObject.INVALID_DATA_VERSION;
    private int mSize = 0;
    private Path mItemPath;
    private int mCameraIndex;
    private boolean mIsPanorama;
    private boolean mIsStaticCamera;
    private boolean mIsActive;
    private boolean mNeedFullImage;
    private int mFocusHintDirection = FOCUS_HINT_NEXT;
    private Path mFocusHintPath = null;

    private long mSecureCameraEnterTime = -1L;

    public interface DataListener extends LoadingListener {
        void onPhotoChanged(int index, Path item);
    }

    private DataListener mDataListener;

    private final SourceListener mSourceListener = new SourceListener();
    private final TiledTexture.Uploader mUploader;

    private int mIndexHint = -1;

    // The path of the current viewing item will be stored in mItemPath.
    // If mItemPath is not null, mCurrentIndex is only a hint for where we
    // can find the item. If mItemPath is null, then we use the mCurrentIndex to
    // find the image being viewed. cameraIndex is the index of the camera
    // preview. If cameraIndex < 0, there is no camera preview.
    public PhotoViewPageDataAdapter(GalleryActivity2 activity, PhotoView view, GLRoot glRoot,
                                    MediaSet mediaSet, Path itemPath, int indexHint, int cameraIndex,
                                    boolean isPanorama, boolean isStaticCamera, long secureCameraEnterTime,
                                    int itemCount) {
        mApplication = (GalleryApp) (activity).getApplication();
        mSecureCameraEnterTime = secureCameraEnterTime;
        mSource = Utils.checkNotNull(mediaSet);
        mPhotoView = Utils.checkNotNull(view);
        mItemPath = Utils.checkNotNull(itemPath);
        mCurrentIndex = indexHint;
        //优化: 图片特别多时(> ITEM_LARGE_COUNT), 快速的浏览点击的图片
        if (itemCount > ITEM_LARGE_COUNT) {
            MediaObject item = mApplication.getDataManager().getMediaObject(mItemPath);
            if (item != null) {
                mData[0] = (MediaItem) item;
                mCurrentIndex = 0;
                mIndexHint = indexHint;
                mSize = 1;
                Log.d(TAG, "indexHint = " + indexHint + ", mData[0] = " + item);
            }
        }
        mCameraIndex = cameraIndex;
        mIsPanorama = isPanorama;
        mIsStaticCamera = isStaticCamera;
        mThreadPool = activity.getThreadPool();
        mNeedFullImage = true;
        Arrays.fill(mChanges, MediaObject.INVALID_DATA_VERSION);
        mUploader = new TiledTexture.Uploader(glRoot);
        mMainHandler = new MySynchronizedHandler(glRoot, this);
        updateSlidingWindow(mCurrentIndex);
    }

    private void handleMySynchronizedHandlerMsg(Message message) {
        switch (message.what) {
            case MSG_RUN_OBJECT:
                ((Runnable) message.obj).run();
                return;
            case MSG_LOAD_START: {
                if (mDataListener != null) {
                    mDataListener.onLoadingStarted();
                }
                return;
            }
            case MSG_LOAD_FINISH: {
                if (mDataListener != null) {
                    mDataListener.onLoadingFinished(false);
                }
                return;
            }
            case MSG_UPDATE_IMAGE_REQUESTS: {
                updateImageRequests();
                return;
            }
            default:
                throw new AssertionError();
        }
    }

    private static class MySynchronizedHandler extends SynchronizedHandler {
        private final WeakReference<PhotoViewPageDataAdapter> mPhotoDataAdapter;

        public MySynchronizedHandler(GLRoot root, PhotoViewPageDataAdapter photoDataAdapter) {
            super(root);
            mPhotoDataAdapter = new WeakReference<>(photoDataAdapter);
        }

        @Override
        public void handleMessage(Message message) {
            PhotoViewPageDataAdapter photoDataAdapter = mPhotoDataAdapter.get();
            if (photoDataAdapter != null) {
                photoDataAdapter.handleMySynchronizedHandlerMsg(message);
            }
        }
    }


    private MediaItem getItemInternal(int index) {
        if (index < 0 || index >= mSize) {
            return null;
        }
        if (index >= mContentStart && index < mContentEnd) {
            return mData[index % DATA_CACHE_SIZE];
        }
        return null;
    }

    private long getVersion(int index) {
        MediaItem item = getItemInternal(index);
        if (item == null) {
            return MediaObject.INVALID_DATA_VERSION;
        }
        return item.getDataVersion();
    }

    private Path getPath(int index) {
        MediaItem item = getItemInternal(index);
        if (item == null) {
            return null;
        }
        return item.getPath();
    }

    private void fireDataChange() {
        // First check if data actually changed.
        boolean changed = false;
        for (int i = -SCREEN_NAIL_MAX; i <= SCREEN_NAIL_MAX; ++i) {
            long newVersion = getVersion(mCurrentIndex + i);
            if (mChanges[i + SCREEN_NAIL_MAX] != newVersion) {
                mChanges[i + SCREEN_NAIL_MAX] = newVersion;
                changed = true;
            }
        }

        if (!changed) {
            return;
        }

        // Now calculate the fromIndex array. fromIndex represents the item
        // movement. It records the index where the picture come from. The
        // special value Integer.MAX_VALUE means it's a new picture.
        final int N = IMAGE_CACHE_SIZE;
        int fromIndex[] = new int[N];

        // Remember the old path array.
        Path oldPaths[] = new Path[N];
        System.arraycopy(mPaths, 0, oldPaths, 0, N);

        // Update the mPaths array.
        for (int i = 0; i < N; ++i) {
            mPaths[i] = getPath(mCurrentIndex + i - SCREEN_NAIL_MAX);
        }

        // Calculate the fromIndex array.
        for (int i = 0; i < N; i++) {
            Path p = mPaths[i];
            if (p == null) {
                fromIndex[i] = Integer.MAX_VALUE;
                continue;
            }

            // Try to find the same path in the old array
            int j;
            for (j = 0; j < N; j++) {
                if (oldPaths[j] == p) {
                    break;
                }
            }
            fromIndex[i] = (j < N) ? j - SCREEN_NAIL_MAX : Integer.MAX_VALUE;
        }

        mPhotoView.notifyDataChange(fromIndex, -mCurrentIndex,
                mSize - 1 - mCurrentIndex);
    }

    public void setDataListener(DataListener listener) {
        mDataListener = listener;
    }

    private void updateScreenNail(Path path, Future<ScreenNail> future) {
        ImageEntry entry = mImageCache.get(path);
        ScreenNail screenNail = future.get();
        /*bug 536012,if image decoder fail,need show "no thumbnail" @{*/
        //whether load has failed,should be decided by the screennail in decode result
        boolean loadFailed = (screenNail == null);
        /* @} */

        if (entry == null || entry.screenNailTask != future) {
            if (screenNail != null) {
                screenNail.recycle();
            }
            return;
        }

        entry.screenNailTask = null;

        // Combine the ScreenNails if we already have a BitmapScreenNail
        if (entry.screenNail instanceof TiledScreenNail) {
            TiledScreenNail original = (TiledScreenNail) entry.screenNail;
            screenNail = original.combine(screenNail);
        }

        if (screenNail == null) {
            /* bug 536012,if image decoder fail,need show "no thumbnail" @{*/
            // entry.failToLoad = true;
            entry.failToLoad = loadFailed;
            /* @} */
        } else {
            /* bug 536012,if image decoder fail,need show "no thumbnail" @{*/
            // entry.failToLoad = false;
            entry.failToLoad = loadFailed;
            /* @} */
            entry.screenNail = screenNail;
            if (entry.screenNail != null && screenNail != null) {
                GalleryUtils.logs(this.getClass(), "screenNail code = " + screenNail.hashCode());
                GalleryUtils.logs(this.getClass(), "entry.screenNail code = " + entry.screenNail.hashCode());
            }
        }

        for (int i = -SCREEN_NAIL_MAX; i <= SCREEN_NAIL_MAX; ++i) {
            if (path == getPath(mCurrentIndex + i)) {
                if (i == 0) {
                    updateTileProvider(entry);
                }
                mPhotoView.notifyImageChange(i);
                break;
            }
        }
        updateImageRequests();
        updateScreenNailUploadQueue();
    }

    private void updateBokehScreenNail(Path path, ScreenNail screenNail) {
        ImageEntry entry = mImageCache.get(path);
        if (entry == null) {
            if (screenNail != null) {
                screenNail.recycle();
            }
            return;
        }
        Log.d(TAG, "do updateBokehScreenNail.");
        // Combine the ScreenNails if we already have a BitmapScreenNail
        if (entry.screenNail instanceof TiledScreenNail) {
            TiledScreenNail original = (TiledScreenNail) entry.screenNail;
            screenNail = original.combine(screenNail);
        }
        if (screenNail == null) {
            return;
        }
        entry.screenNail = screenNail;
        entry.fullImage = null;
        if (path == getPath(mCurrentIndex)) {
            Log.d(TAG, "PhotoView Bokeh update");
            updateTileProvider(entry);
            mPhotoView.invalidate();
        }
    }

    private void updateFullImage(Path path, Future<BitmapRegionDecoder> future) {
        ImageEntry entry = mImageCache.get(path);
        if (entry == null || entry.fullImageTask != future) {
            BitmapRegionDecoder fullImage = future.get();
            if (fullImage != null) {
                fullImage.recycle();
            }
            return;
        }

        entry.fullImageTask = null;
        entry.fullImage = future.get();
        if (entry.fullImage != null) {
            if (path == getPath(mCurrentIndex)) {
                updateTileProvider(entry);
                mPhotoView.notifyImageChange(0);
            }
        }
        updateImageRequests();
    }

    @Override
    public void resume() {
        if (mReloadTask != null) {
            return;
        }
        mIsActive = true;
        TiledTexture.prepareResources();

        mSource.addContentListener(mSourceListener);
        updateImageCache();
        updateImageRequests();

        if (mIndexHint >= 0 && mDataListener != null) {
            mDataListener.onPhotoChanged(mCurrentIndex, mItemPath);
        }

        fireDataChange();

        mReloadTask = new ReloadTask();
        mReloadTask.start();
    }

    @Override
    public void pause() {
        if (mReloadTask == null) {
            return;
        }
        mIsActive = false;

        mReloadTask.terminate();
        mReloadTask = null;

        mSource.removeContentListener(mSourceListener);

        for (ImageEntry entry : mImageCache.values()) {
            if (entry.fullImageTask != null) {
                entry.fullImageTask.cancel();
            }
            if (entry.screenNailTask != null) {
                entry.screenNailTask.cancel();
            }
            if (entry.screenNail != null) {
                entry.screenNail.recycle();
            }
        }
        mImageCache.clear();
        mTileProvider.clear();

        mUploader.clear();
        TiledTexture.freeResources();
        mMainHandler.removeCallbacksAndMessages(null);
    }

    private MediaItem getItem(int index) {
        if (index < 0 || index >= mSize || !mIsActive) {
            return null;
        }
        if (mActiveEnd == 0) {
            Log.i(TAG, "getItem index = " + index + " mSize = " + mSize + "ActiveStart-End = " + mActiveStart + " - " + mActiveEnd);
            return null;
        }
        boolean condition = index >= mActiveStart && index < mActiveEnd;
        if (!condition) {
            return null;
        }
        if (index >= mContentStart && index < mContentEnd) {
            return mData[index % DATA_CACHE_SIZE];
        }
        return null;
    }

    private void updateCurrentIndex(int index) {
        if (mCurrentIndex == index) {
            return;
        }
        checkNeedCancelBokeh();
        mCurrentIndex = index;
        updateSlidingWindow(mCurrentIndex);

        MediaItem item = mData[index % DATA_CACHE_SIZE];
        mItemPath = item == null ? null : item.getPath();

        updateImageCache();
        updateImageRequests();
        updateTileProvider();

        if (mDataListener != null) {
            mDataListener.onPhotoChanged(index, mItemPath);
        }

        fireDataChange();
    }

    private void uploadScreenNail(int offset) {
        int index = mCurrentIndex + offset;
        if (index < mActiveStart || index >= mActiveEnd) {
            return;
        }

        MediaItem item = getItem(index);
        if (item == null) {
            return;
        }

        ImageEntry e = mImageCache.get(item.getPath());
        if (e == null) {
            return;
        }

        ScreenNail s = e.screenNail;
        if (s instanceof TiledScreenNail) {
            TiledTexture t = ((TiledScreenNail) s).getTexture();
            if (t != null && !t.isReady()) {
                mUploader.addTexture(t);
            }
        }
    }

    private void updateScreenNailUploadQueue() {
        mUploader.clear();
        uploadScreenNail(0);
        for (int i = 1; i < IMAGE_CACHE_SIZE; ++i) {
            uploadScreenNail(i);
            uploadScreenNail(-i);
        }
    }

    @Override
    public void moveTo(int index) {
        // SPRD: Modify 20151212 for bug516191, ArrayIndexOutOfBoundsException will be thrown
        // if index is less than 0.
        if (index < 0) {
            return;
        }
        mPhotoView.setScaleState(false);
        updateCurrentIndex(index);
    }

    @Override
    public ScreenNail getScreenNail(int offset) {
        // GalleryUtils.start(this.getClass(), "getScreenNail " + mCurrentIndex + "---" + offset + " ");
        int index = mCurrentIndex + offset;
        if (index < 0 || index >= mSize || !mIsActive) {
            return null;
        }
        boolean condition = index >= mActiveStart && index < mActiveEnd;
        if (!condition) {
            return null;
        }

        MediaItem item = getItem(index);
        if (item == null) {
            return null;
        }
        if (offset == 0) {
            GalleryUtils.logs(this.getClass(), "getScreenNail " + item.getPath() + "  " + item.getFilePath());
        }
        ImageEntry entry = mImageCache.get(item.getPath());
        if (entry == null) {
            return null;
        }

        // Create a default ScreenNail if the real one is not available yet,
        // except for camera that a black screen is better than a gray tile.
        if (entry.screenNail == null && !isCamera(offset)) {
            if (offset == 0) {
                entry.screenNail = bitmapScreenNail(item);
            } else {
                entry.screenNail = newPlaceholderScreenNail(item);
            }
            if (offset == 0) {
                updateTileProvider(entry);
            }
        }
        if (offset == 0) {
            GalleryUtils.end(this.getClass(), "getScreenNail " + mCurrentIndex + "---" + offset + " " + entry.screenNail.hashCode() + "---");
        }
        return entry.screenNail;
    }

    @Override
    public void getImageSize(int offset, PhotoView.Size size) {
        MediaItem item = getItem(mCurrentIndex + offset);
        if (item == null) {
            size.width = 0;
            size.height = 0;
        } else {
            size.width = item.getWidth();
            size.height = item.getHeight();
        }
    }

    @Override
    public int getImageRotation(int offset) {
        MediaItem item = getItem(mCurrentIndex + offset);
        return (item == null) ? 0 : item.getFullImageRotation();
    }

    @Override
    public void setNeedFullImage(boolean enabled) {
        mNeedFullImage = enabled;
        mMainHandler.sendEmptyMessage(MSG_UPDATE_IMAGE_REQUESTS);
    }

    @Override
    public boolean isCamera(int offset) {
        return mCurrentIndex + offset == mCameraIndex;
    }

    @Override
    public boolean isPanorama(int offset) {
        return isCamera(offset) && mIsPanorama;
    }

    @Override
    public boolean isStaticCamera(int offset) {
        return isCamera(offset) && mIsStaticCamera;
    }

    @Override
    public boolean isVideo(int offset) {
        MediaItem item = getItem(mCurrentIndex + offset);
        return (item != null) && item.getMediaType() == MediaItem.MEDIA_TYPE_VIDEO;
    }

    @Override
    public boolean isRefocusNoBokeh(int offset) {
        MediaItem item = getItem(mCurrentIndex + offset);
        return (item != null) && ((item.getMediaType() == MediaItem.MEDIA_TYPE_IMAGE_BOKEH_GALLERY)
                || (item.getMediaType() == MediaItem.MEDIA_TYPE_IMAGE_BOKEH_HDR_GALLERY)
                || (item.getMediaType() == MediaItem.MEDIA_TYPE_IMAGE_BOKEH_FDR_GALLERY));
    }

    @Override
    public boolean isDeletable(int offset) {
        MediaItem item = getItem(mCurrentIndex + offset);
        return (item != null) && (item.getSupportedOperations() & MediaItem.SUPPORT_DELETE) != 0;
    }

    @Override
    public int getLoadingState(int offset) {
        ImageEntry entry = mImageCache.get(getPath(mCurrentIndex + offset));
        if (entry == null) {
            return LOADING_INIT;
        }
        if (entry.failToLoad) {
            return LOADING_FAIL;
        }
        if (entry.screenNail != null) {
            return LOADING_COMPLETE;
        }
        return LOADING_INIT;
    }

    @Override
    public ScreenNail getScreenNail() {
        return getScreenNail(0);
    }

    @Override
    public int getImageHeight() {
        return mTileProvider.getImageHeight();
    }

    @Override
    public int getImageWidth() {
        return mTileProvider.getImageWidth();
    }

    @Override
    public int getLevelCount() {
        return mTileProvider.getLevelCount();
    }

    @Override
    public Bitmap getTile(int level, int x, int y, int tileSize) {
        return mTileProvider.getTile(level, x, y, tileSize);
    }

    @Override
    public boolean isEmpty() {
        return mSize == 0;
    }

    @Override
    public int getCurrentIndex() {
        return mCurrentIndex;
    }

    @Override
    public MediaItem getMediaItem(int offset) {
        int index = mCurrentIndex + offset;
        if (index >= mContentStart && index < mContentEnd) {
            return mData[index % DATA_CACHE_SIZE];
        }
        return null;
    }

    @Override
    public void setCurrentPhoto(Path path, int indexHint) {
        if (mItemPath == path) {
            return;
        }
        mItemPath = path;
        mCurrentIndex = indexHint;
        updateSlidingWindow(mCurrentIndex);
        updateImageCache();
        fireDataChange();

        // We need to reload content if the path doesn't match.
        MediaItem item = getMediaItem(0);
        if (item != null && item.getPath() != path) {
            if (mReloadTask != null) {
                mReloadTask.notifyDirty();
            }
        }
    }

    @Override
    public void setFocusHintDirection(int direction) {
        mFocusHintDirection = direction;
    }

    @Override
    public void setFocusHintPath(Path path) {
        mFocusHintPath = path;
    }

    @Override
    public void needReDecode(MediaItem item) {
        Log.d(TAG, "Bokeh Picture needReDecode .");
        Path path = item.getPath();
        if (item instanceof LocalImage) {
            LocalImage localImage = (LocalImage) item;
            long modifiedInSec = localImage.getModifiedInSec();
            ImageCacheService cacheService = mApplication.getImageCacheService();
            cacheService.clearImageData(path, modifiedInSec, MediaItem.TYPE_THUMBNAIL);
            cacheService.clearImageData(path, modifiedInSec, MediaItem.TYPE_MICROTHUMBNAIL);
        }
        mTileProvider.clear();
        ImageEntry entry = mImageCache.get(path);
        if (entry != null) {
            // set INVALID_DATA_VERSION, will re decode
            entry.requestedScreenNail = MediaObject.INVALID_DATA_VERSION;
            entry.requestedFullImage = MediaObject.INVALID_DATA_VERSION;
        }
    }

    @Override
    public void updateBokehPicture(MediaItem item, byte[] bokehPicture) {
        if (item == null) {
            return;
        }
        Log.d(TAG, " updateBokehPicture");
        Future<Bitmap> bokehTask = mThreadPool.submit(
                new BokehImageJob(bokehPicture),
                new BokehImageListener(item));
        mImageBokehCache.put(item.getPath(), bokehTask);
    }

    public void checkNeedCancelBokeh() {
        MediaItem mediaItem = getMediaItem(0);
        if (mediaItem == null) {
            return;
        }
        Path path = mediaItem.getPath();
        Future<Bitmap> bokehFuture = mImageBokehCache.get(path);
        if (bokehFuture == null) {
            return;
        }
        mImageBokehCache.remove(path);
        if (!bokehFuture.isCancelled()) {
            bokehFuture.cancel();
            Log.d(TAG, "bokehTask Cancel ! path = " + path);
        }
    }

    private void updateTileProvider() {
        ImageEntry entry = mImageCache.get(getPath(mCurrentIndex));
        if (entry == null) { // in loading
            mTileProvider.clear();
        } else {
            updateTileProvider(entry);
        }
    }

    private void updateTileProvider(ImageEntry entry) {
        ScreenNail screenNail = entry.screenNail;
        BitmapRegionDecoder fullImage = entry.fullImage;
        if (screenNail != null) {
            if (fullImage != null) {
                mTileProvider.setScreenNail(screenNail,
                        fullImage.getWidth(), fullImage.getHeight());
                mTileProvider.setRegionDecoder(fullImage);
            } else {
                int width = screenNail.getWidth();
                int height = screenNail.getHeight();
                mTileProvider.setScreenNail(screenNail, width, height);
            }
        } else {
            mTileProvider.clear();
        }
    }

    private void updateSlidingWindow(int index) {
        // 1. Update the image window
        int start = Utils.clamp(index - IMAGE_CACHE_SIZE / 2,
                0, Math.max(0, mSize - IMAGE_CACHE_SIZE));
        int end = Math.min(mSize, start + IMAGE_CACHE_SIZE);

        if (mActiveStart == start && mActiveEnd == end) {
            return;
        }

        mActiveStart = start;
        mActiveEnd = end;

        // 2. Update the data window
        start = Utils.clamp(index - DATA_CACHE_SIZE / 2,
                0, Math.max(0, mSize - DATA_CACHE_SIZE));
        end = Math.min(mSize, start + DATA_CACHE_SIZE);
        if (mContentStart > mActiveStart || mContentEnd < mActiveEnd
                || Math.abs(start - mContentStart) > MIN_LOAD_COUNT) {
            for (int i = mContentStart; i < mContentEnd; ++i) {
                if (i < start || i >= end) {
                    mData[i % DATA_CACHE_SIZE] = null;
                }
            }
            mContentStart = start;
            mContentEnd = end;
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mReloadTask != null) {
                        GalleryUtils.logs(this.getClass(), "updateSlidingWindow ReloadTask notifyDirty!");
                        mReloadTask.notifyDirty();
                    }
                }
            });
        }
    }

    private void updateImageRequests() {
        if (!mIsActive) {
            return;
        }

        int currentIndex = mCurrentIndex;
        MediaItem item = mData[currentIndex % DATA_CACHE_SIZE];
        if (item == null || item.getPath() != mItemPath) {
            // current item mismatch - don't request image
            return;
        }
        GalleryUtils.logs(this.getClass(), "updateImageRequests: mCurrentIndex:" + mCurrentIndex);
        // 1. Find the most wanted request and start it (if not already started).
        Future<?> task = null;
        for (int i = 0; i < sImageFetchSeq.length; i++) {
            int offset = sImageFetchSeq[i].indexOffset;
            int bit = sImageFetchSeq[i].imageBit;
            if (bit == BIT_FULL_IMAGE && !mNeedFullImage) {
                continue;
            }
            task = startTaskIfNeeded(currentIndex + offset, bit);
            if (task != null) {
                break;
            }
        }

        // 2. Cancel everything else.
        for (ImageEntry entry : mImageCache.values()) {
            if (entry.screenNailTask != null && entry.screenNailTask != task) {
                entry.screenNailTask.cancel();
                entry.screenNailTask = null;
                entry.requestedScreenNail = MediaObject.INVALID_DATA_VERSION;
            }
            if (entry.fullImageTask != null && entry.fullImageTask != task) {
                entry.fullImageTask.cancel();
                entry.fullImageTask = null;
                entry.requestedFullImage = MediaObject.INVALID_DATA_VERSION;
            }
        }
    }

    private class ScreenNailJob implements ThreadPool.Job<ScreenNail> {
        private MediaItem mItem;

        public ScreenNailJob(MediaItem item) {
            mItem = item;
        }

        @Override
        public ScreenNail run(ThreadPool.JobContext jc) {
            // We try to get a ScreenNail first, if it fails, we fallback to get
            // a Bitmap and then wrap it in a BitmapScreenNail instead.
            ScreenNail s = mItem.getScreenNail();
            if (s != null) {
                return s;
            }

            // If this is a temporary item, don't try to get its bitmap because
            // it won't be available. We will get its bitmap after a data reload.
            if (isTemporaryItem(mItem)) {
                return newPlaceholderScreenNail(mItem);
            }
            GalleryUtils.start(this.getClass(), "ScreenNailJob run: requestImage ");
            Bitmap bitmap = mItem.requestImage(MediaItem.TYPE_THUMBNAIL).run(jc);
            GalleryUtils.end(this.getClass(), "ScreenNailJob run: requestImage ");
            if (jc.isCancelled()) {
                return null;
            }
            if (bitmap != null) {
                bitmap = BitmapUtils.rotateBitmap(bitmap,
                        mItem.getRotation() - mItem.getFullImageRotation(), true);
            } else {
                // SPRD: Decode thumbnail failed, no need request large image
                mItem.setDecodeThumbnailSuccess(false);
            }
            if (bitmap == null) {
                return null;
            } else {
                TiledScreenNail tiledScreenNail = new TiledScreenNail(bitmap);
                GalleryUtils.logs(this.getClass(), "create TiledScreenNail " + tiledScreenNail.hashCode());
                return tiledScreenNail;
            }
        }
    }

    private class FullImageJob implements ThreadPool.Job<BitmapRegionDecoder> {
        private MediaItem mItem;

        public FullImageJob(MediaItem item) {
            mItem = item;
        }

        @Override
        public BitmapRegionDecoder run(ThreadPool.JobContext jc) {
            if (isTemporaryItem(mItem)) {
                return null;
            }
            // SPRD: Decode thumbnail failed, no need request large image
            if (!mItem.getDecodeThumbnailSuccess()) {
                return null;
            }
            return mItem.requestLargeImage().run(jc);
        }
    }

    // Returns true if we think this is a temporary item created by Camera. A
    // temporary item is an image or a video whose data is still being
    // processed, but an incomplete entry is created first in MediaProvider, so
    // we can display them (in grey tile) even if they are not saved to disk
    // yet. When the image or video data is actually saved, we will get
    // notification from MediaProvider, reload data, and show the actual image
    // or video data.
    private boolean isTemporaryItem(MediaItem mediaItem) {
        // Must have camera to create a temporary item.
        if (mCameraIndex < 0) {
            return false;
        }
        // Must be an item in camera roll.
        if (!(mediaItem instanceof LocalMediaItem)) {
            return false;
        }
        LocalMediaItem item = (LocalMediaItem) mediaItem;
        if (item.getBucketId() != MediaSetUtils.CAMERA_BUCKET_ID) {
            return false;
        }
        // Must have no size, but must have width and height information
        if (item.getSize() != 0) {
            return false;
        }
        if (item.getWidth() == 0) {
            return false;
        }
        if (item.getHeight() == 0) {
            return false;
        }
        // Must be created in the last 10 seconds.
        return item.getDateInMs() - System.currentTimeMillis() <= 10000;
    }

    // Create a default ScreenNail when a ScreenNail is needed, but we don't yet
    // have one available (because the image data is still being saved, or the
    // Bitmap is still being loaded.
    private ScreenNail newPlaceholderScreenNail(MediaItem item) {
        int width = item.getWidth();
        int height = item.getHeight();
        return new TiledScreenNail(width, height);
    }

    private ScreenNail bitmapScreenNail(MediaItem item) {
        Bitmap bitmap = null;
        if (ImageCache.getImageCache() != null) {
            bitmap = ImageCache.getImageCache().getGlideBitmap(item.getFilePath());
        }
        Log.d(TAG, "bitmapScreenNail path :" + item.getFilePath() + " with " + bitmap);
        if (bitmap == null || bitmap.isRecycled()) {
            return newPlaceholderScreenNail(item);
        }
        bitmap = BitmapUtils.rotateBitmap(bitmap, 360 - item.getRotation(), false);
        return new SprdTiledScreenNail(bitmap);
    }

    // Returns the task if we started the task or the task is already started.
    private Future<?> startTaskIfNeeded(int index, int which) {
        if (index < mActiveStart || index >= mActiveEnd) {
            return null;
        }

        ImageEntry entry = mImageCache.get(getPath(index));
        if (entry == null) {
            return null;
        }

        MediaItem item = mData[index % DATA_CACHE_SIZE];
        Utils.assertTrue(item != null);
        long version = item.getDataVersion();

        if (which == BIT_SCREEN_NAIL && entry.screenNailTask != null
                && entry.requestedScreenNail == version) {
            //GalleryUtils.start(this.getClass(), "startTaskIfNeeded: end1");
            return entry.screenNailTask;
        } else if (which == BIT_FULL_IMAGE && entry.fullImageTask != null
                && entry.requestedFullImage == version) {
            //GalleryUtils.start(this.getClass(), "startTaskIfNeeded: end2");
            return entry.fullImageTask;
        }

        if (which == BIT_SCREEN_NAIL && entry.requestedScreenNail != version) {
            entry.requestedScreenNail = version;
            entry.screenNailTask = mThreadPool.submit(
                    new ScreenNailJob(item),
                    new ScreenNailListener(item));
            // request screen nail
            return entry.screenNailTask;
        }
        if (which == BIT_FULL_IMAGE && entry.requestedFullImage != version
                && (item.getSupportedOperations()
                & MediaItem.SUPPORT_FULL_IMAGE) != 0) {
            entry.requestedFullImage = version;
            entry.fullImageTask = mThreadPool.submit(
                    new FullImageJob(item),
                    new FullImageListener(item));
            // request full image
            return entry.fullImageTask;
        }
        return null;
    }

    private void updateImageCache() {
        HashSet<Path> toBeRemoved = new HashSet<Path>(mImageCache.keySet());
        for (int i = mActiveStart; i < mActiveEnd; ++i) {
            MediaItem item = mData[i % DATA_CACHE_SIZE];
            if (item == null) {
                continue;
            }
            Path path = item.getPath();
            ImageEntry entry = mImageCache.get(path);
            toBeRemoved.remove(path);
            if (entry != null) {
                if (Math.abs(i - mCurrentIndex) > 1) {
                    if (entry.fullImageTask != null) {
                        entry.fullImageTask.cancel();
                        entry.fullImageTask = null;
                    }
                    entry.fullImage = null;
                    entry.requestedFullImage = MediaObject.INVALID_DATA_VERSION;
                }
                if (entry.requestedScreenNail != item.getDataVersion()) {
                    // This ScreenNail is outdated, we want to update it if it's
                    // still a placeholder.
                    if (entry.screenNail instanceof TiledScreenNail) {
                        TiledScreenNail s = (TiledScreenNail) entry.screenNail;
                        s.updatePlaceholderSize(
                                item.getWidth(), item.getHeight());
                    }
                }
            } else {
                entry = new ImageEntry();
                mImageCache.put(path, entry);
            }
        }

        // Clear the data and requests for ImageEntries outside the new window.
        for (Path path : toBeRemoved) {
            ImageEntry entry = mImageCache.remove(path);
            if (entry.fullImageTask != null) {
                entry.fullImageTask.cancel();
            }
            if (entry.screenNailTask != null) {
                entry.screenNailTask.cancel();
            }
            if (entry.screenNail != null) {
                entry.screenNail.recycle();
            }
        }

        updateScreenNailUploadQueue();
    }

    private class FullImageListener
            implements Runnable, FutureListener<BitmapRegionDecoder> {
        private final Path mPath;
        private Future<BitmapRegionDecoder> mFuture;

        public FullImageListener(MediaItem item) {
            mPath = item.getPath();
        }

        @Override
        public void onFutureDone(Future<BitmapRegionDecoder> future) {
            mFuture = future;
            mMainHandler.sendMessage(
                    mMainHandler.obtainMessage(MSG_RUN_OBJECT, this));
        }

        @Override
        public void run() {
            updateFullImage(mPath, mFuture);
        }
    }

    private class ScreenNailListener
            implements Runnable, FutureListener<ScreenNail> {
        private final Path mPath;
        private Future<ScreenNail> mFuture;

        public ScreenNailListener(MediaItem item) {
            mPath = item.getPath();
        }

        @Override
        public void onFutureDone(Future<ScreenNail> future) {
            mFuture = future;
            mMainHandler.sendMessage(
                    mMainHandler.obtainMessage(MSG_RUN_OBJECT, this));
        }

        @Override
        public void run() {
            updateScreenNail(mPath, mFuture);
        }
    }

    private static class ImageEntry {
        public BitmapRegionDecoder fullImage;
        public ScreenNail screenNail;
        public Future<ScreenNail> screenNailTask;
        public Future<BitmapRegionDecoder> fullImageTask;
        public long requestedScreenNail = MediaObject.INVALID_DATA_VERSION;
        public long requestedFullImage = MediaObject.INVALID_DATA_VERSION;
        public boolean failToLoad = false;
    }

    private class SourceListener implements ContentListener {
        @Override
        public void onContentDirty(Uri uri) {
            if (mReloadTask != null) {
                mReloadTask.notifyDirty();
            }
        }
    }

    private <T> T executeAndWait(Callable<T> callable) {
        FutureTask<T> task = new FutureTask<T>(callable);
        mMainHandler.sendMessage(
                mMainHandler.obtainMessage(MSG_RUN_OBJECT, task));
        try {
            return task.get();
        } catch (InterruptedException e) {
            return null;
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private static class UpdateInfo {
        public long version;
        public boolean reloadContent;
        public Path target;
        public int indexHint;
        public int contentStart;
        public int contentEnd;

        public int size;
        public ArrayList<MediaItem> items;
    }

    private class GetUpdateInfo implements Callable<UpdateInfo> {

        private boolean needContentReload() {
            for (int i = mContentStart, n = mContentEnd; i < n; ++i) {
                if (mData[i % DATA_CACHE_SIZE] == null) {
                    return true;
                }
            }
            MediaItem current = mData[mCurrentIndex % DATA_CACHE_SIZE];
            return current == null || current.getPath() != mItemPath;
        }

        @Override
        public UpdateInfo call() {
            // TODO: Try to load some data in first update
            UpdateInfo info = new UpdateInfo();
            info.version = mSourceVersion;
            info.reloadContent = needContentReload();
            info.target = mItemPath;
            info.indexHint = mCurrentIndex;
            info.contentStart = mContentStart;
            info.contentEnd = mContentEnd;
            info.size = mSize;
            return info;
        }
    }

    private class UpdateContent implements Callable<Void> {
        UpdateInfo mUpdateInfo;

        public UpdateContent(UpdateInfo updateInfo) {
            mUpdateInfo = updateInfo;
        }

        @Override
        public Void call() {
            GalleryUtils.start(this.getClass(), "UpdateContent call");
            UpdateInfo info = mUpdateInfo;
            mSourceVersion = info.version;

            if (info.size != mSize) {
                mSize = info.size;
                if (mContentEnd > mSize) {
                    mContentEnd = mSize;
                }
                if (mActiveEnd > mSize) {
                    mActiveEnd = mSize;
                }
            }

            /* SPRD: fix bug 520139,indexHint needs to be limited to [0, mSize) @{ */
            if (mSize > 0 && info.indexHint >= mSize) {
                info.indexHint = mSize - 1;
            }
            /* @} */
            mCurrentIndex = info.indexHint;
            updateSlidingWindow(mCurrentIndex);

            if (info.items != null) {
                int start = Math.max(info.contentStart, mContentStart);
                int end = Math.min(info.contentStart + info.items.size(), mContentEnd);
                int dataIndex = start % DATA_CACHE_SIZE;
                for (int i = start; i < end; ++i) {
                    mData[dataIndex] = info.items.get(i - info.contentStart);
                    if (++dataIndex == DATA_CACHE_SIZE) {
                        dataIndex = 0;
                    }
                }
            }

            // update mItemPath
            MediaItem current = mData[mCurrentIndex % DATA_CACHE_SIZE];
            mItemPath = current == null ? null : current.getPath();

            updateImageCache();
            updateTileProvider();
            updateImageRequests();

            if (mDataListener != null) {
                mDataListener.onPhotoChanged(mCurrentIndex, mItemPath);
            }

            fireDataChange();
            GalleryUtils.end(this.getClass(), "UpdateContent call");
            return null;
        }
    }

    private class ReloadTask extends Thread {
        private volatile boolean mActive = true;
        private volatile boolean mDirty = true;

        private boolean mIsLoading = false;

        private void updateLoading(boolean loading) {
            if (mIsLoading == loading) {
                return;
            }
            mIsLoading = loading;
            mMainHandler.sendEmptyMessage(loading ? MSG_LOAD_START : MSG_LOAD_FINISH);
        }

        @Override
        public void run() {
            while (mActive) {
                synchronized (this) {
                    if (!mDirty && mActive) {
                        updateLoading(false);
                        Utils.waitWithoutInterrupt(this);
                        continue;
                    }
                }
                mDirty = false;
                mItemPath = findBurstConverIfBurstPhoto(mItemPath);
                GalleryUtils.logs(this.getClass(), "ReloadTask GetUpdateInfo");
                UpdateInfo info = executeAndWait(new GetUpdateInfo());
                if (info == null) {
                    continue;
                }
                updateLoading(true);
                int[] contentSize = null;
                long version = mSource.reload();
                if (info.version != version) {
                    info.reloadContent = true;
                    info.size = mSource.getMediaItemCount();
                    if (mSecureCameraEnterTime > 0) {
                        ArrayList<MediaItem> items = mSource.getMediaItem(0, info.size);
                        if (items != null) {
                            int count = 0;
                            for (MediaItem item : items) {
                                if (item.getDateInMs() >= mSecureCameraEnterTime) {
                                    count++;
                                }
                            }
                            info.size = count;
                        }
                    }
                    GalleryUtils.logs(this.getClass(), "info.size = " + info.size + ", mSize =  " + mSize);
                    if (info.size != 0 && mSize != info.size) {
                        mSize = info.size;
                        if (mIndexHint >= 0) {
                            contentSize = getContentSize(mIndexHint);
                            info.contentStart = contentSize[0];
                            info.contentEnd = contentSize[1];
                            mIndexHint = -1;
                        } else {
                            updateSlidingWindow(mCurrentIndex);
                            info.contentStart = mContentStart;
                            info.contentEnd = mContentEnd;
                        }
                        Log.d(TAG, "ReloadTask info size-start-end = " + info.size + "-" + info.contentStart + "-" + info.contentEnd);
                    }
                }
                if (!info.reloadContent) {
                    continue;
                }
                Log.d(TAG, "ReloadTask getMediaItem(" + info.contentStart
                        + ", " + info.contentEnd + "), mSize = " + mSize + ", B.");
                info.items = mSource.getMediaItem(
                        info.contentStart, info.contentEnd);
                Log.d(TAG, "ReloadTask getMediaItem(" + info.contentStart
                        + ", " + info.contentEnd + "), E.");
                int index = MediaSet.INDEX_NOT_FOUND;

                info.indexHint = mCurrentIndex;
                info.target = mItemPath;

                // First try to focus on the given hint path if there is one.
                if (mFocusHintPath != null) {
                    index = findIndexOfPathInCache(info, mFocusHintPath);
                    mFocusHintPath = null;
                }

                // Otherwise try to see if the currently focused item can be found.
                if (index == MediaSet.INDEX_NOT_FOUND) {
                    MediaItem item = findCurrentMediaItem(info);
                    if (item != null && item.getPath() == info.target) {
                        index = info.indexHint;
                    } else {
                        index = findIndexOfTarget(info);
                    }
                }

                // The image has been deleted. Focus on the next image (keep
                // mCurrentIndex unchanged) or the previous image (decrease
                // mCurrentIndex by 1). In page mode we want to see the next
                // image, so we focus on the next one. In film mode we want the
                // later images to shift left to fill the empty space, so we
                // focus on the previous image (so it will not move). In any
                // case the index needs to be limited to [0, mSize).
                if (index == MediaSet.INDEX_NOT_FOUND) {
                    index = info.indexHint;
                    int focusHintDirection = mFocusHintDirection;
                    if (index == (mCameraIndex + 1)) {
                        focusHintDirection = FOCUS_HINT_NEXT;
                    }
                    if (focusHintDirection == FOCUS_HINT_PREVIOUS
                            && index > 0) {
                        index--;
                    }
                }

                info.indexHint = index;

                if (contentSize != null) {
                    updateContentSize(info.indexHint);
                }

                GalleryUtils.logs(this.getClass(), "ReloadTask UpdateContent");
                executeAndWait(new UpdateContent(info));
            }
        }

        public synchronized void notifyDirty() {
            mDirty = true;
            notifyAll();
        }

        public synchronized void terminate() {
            mActive = false;
            notifyAll();
        }

        private MediaItem findCurrentMediaItem(UpdateInfo info) {
            ArrayList<MediaItem> items = info.items;

            int index = info.indexHint - info.contentStart;
            return index < 0 || index >= items.size() ? null : items.get(index);
        }

        private int findIndexOfTarget(UpdateInfo info) {
            if (info.target == null) {
                return info.indexHint;
            }
            ArrayList<MediaItem> items = info.items;

            // First, try to find the item in the data just loaded
            if (items != null) {
                int i = findIndexOfPathInCache(info, info.target);
                if (i != MediaSet.INDEX_NOT_FOUND) {
                    return i;
                }
            }

            // Not found, find it in mSource.
            return mSource.getIndexOfItem(info.target, info.indexHint);
        }

        private int findIndexOfPathInCache(UpdateInfo info, Path path) {
            ArrayList<MediaItem> items = info.items;
            for (int i = 0, n = items.size(); i < n; ++i) {
                MediaItem item = items.get(i);
                if (item != null && item.getPath() == path) {
                    return i + info.contentStart;
                }
            }
            return MediaSet.INDEX_NOT_FOUND;
        }

        private int[] getContentSize(int index) {
            int start = Utils.clamp(index - DATA_CACHE_SIZE / 2,
                    0, Math.max(0, mSize - DATA_CACHE_SIZE));
            int end = Math.min(mSize, start + DATA_CACHE_SIZE);
            return new int[]{
                    start, end
            };
        }

        private void updateContentSize(int index) {
            // 1. Update the image window
            int start = Utils.clamp(index - IMAGE_CACHE_SIZE / 2,
                    0, Math.max(0, mSize - IMAGE_CACHE_SIZE));
            int end = Math.min(mSize, start + IMAGE_CACHE_SIZE);

            if (mActiveStart == start && mActiveEnd == end) {
                return;
            }

            mActiveStart = start;
            mActiveEnd = end;

            // 2. Update the data window
            start = Utils.clamp(index - DATA_CACHE_SIZE / 2,
                    +0, Math.max(0, mSize - DATA_CACHE_SIZE));
            end = Math.min(mSize, start + DATA_CACHE_SIZE);
            if (mContentStart > mActiveStart || mContentEnd < mActiveEnd
                    || Math.abs(start - mContentStart) > MIN_LOAD_COUNT) {
                for (int i = mContentStart; i < mContentEnd; ++i) {
                    if (i < start || i >= end) {
                        mData[i % DATA_CACHE_SIZE] = null;
                    }
                }
                mContentStart = start;
                mContentEnd = end;
            }
        }
    }

    // SPRD: bug473914 add to support play gif
    @Override
    public boolean isGif(int offset) {
        MediaItem item = getItem(mCurrentIndex + offset);
        return (item != null) && item.getMediaType() == MediaItem.MEDIA_TYPE_GIF;
    }

    // SPRD: bug473914 add to support play gif
    @Override
    public Uri getItemUri(int offset) {
        MediaItem item = getItem(mCurrentIndex + offset);
        if (item != null) {
            return item.getContentUri();
        }
        return null;
    }

    private Path findBurstConverIfBurstPhoto(Path currentPath) {
        Path path = currentPath;
        DataManager dataManager = GalleryAppImpl.getApplication().getDataManager();
        if (currentPath != null && dataManager.getMediaObject(currentPath) != null && dataManager.getMediaType
                (currentPath) == MediaObject.MEDIA_TYPE_IMAGE_BURST) {
            LocalImage burstPhoto = (LocalImage) dataManager.getMediaObject(currentPath);
            long dataTaken = burstPhoto.getDateInMs();
            long id = -1;
            Cursor cursor = null;
            try {
                cursor = GalleryAppImpl.getApplication().getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        new String[]{
                                MediaStore.Images.ImageColumns._ID,
                        }, " datetaken = ? AND file_flag = ? ", new String[]{
                                String.valueOf(dataTaken),
                                String.valueOf(LocalImage.IMG_TYPE_MODE_BURST_COVER)
                        }, null);

                if (cursor != null && cursor.moveToFirst()) {
                    id = cursor.getLong(0);
                }
            } catch (Exception e) {
                Log.e(TAG, "findBurstConverIfBurstPhoto occur exception " + e.toString());
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            if (id != -1) {
                Uri newUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon().appendPath(String.valueOf(id))
                        .build();
                path = dataManager.findPathByUri(newUri, null);
            }
        }
        Log.d(TAG, "findBurstConverIfBurstPhoto found path=" + path);
        return path;
    }

    private class BokehImageListener
            implements Runnable, FutureListener<Bitmap> {
        private final Path mPath;
        private Future<Bitmap> mFuture;

        public BokehImageListener(MediaItem item) {
            mPath = item.getPath();
        }

        @Override
        public void onFutureDone(Future<Bitmap> future) {
            mFuture = future;
            mImageBokehCache.remove(mPath);
            mMainHandler.sendMessage(
                    mMainHandler.obtainMessage(MSG_RUN_OBJECT, this));
        }

        @Override
        public void run() {
            Bitmap bitmap = mFuture.get();
            if (bitmap == null) {
                return;
            }
            if (mPath != getPath(mCurrentIndex)) {
                Log.d(TAG, "updateBokehScreenNail, current item change");
                return;
            }
            Log.d(TAG, "BokehImageListener  onFutureDone, to updateBokehScreenNail");
            updateBokehScreenNail(mPath, new TiledScreenNail(bitmap));
            mFuture = null;
        }
    }


    private class BokehImageJob implements ThreadPool.Job<Bitmap> {
        private byte[] mBokehImage;

        public BokehImageJob(byte[] bokehImage) {
            mBokehImage = bokehImage;
        }

        @Override
        public Bitmap run(ThreadPool.JobContext jc) {
            int type = MediaItem.TYPE_THUMBNAIL;
            int targetSize = MediaItem.getTargetSize(type);
            return DecodeUtils.decodeThumbnail(jc, mBokehImage, null, targetSize, type);
        }
    }
}
