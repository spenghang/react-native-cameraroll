/**
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.reactnativecommunity.cameraroll;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.provider.MediaStore;
import android.provider.MediaStore.Images;
import android.text.TextUtils;
import android.media.ExifInterface;

import com.facebook.common.logging.FLog;
import com.facebook.react.bridge.GuardedAsyncTask;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.module.annotations.ReactModule;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * {@link NativeModule} that allows JS to interact with the photos and videos on the device (i.e.
 * {@link MediaStore.Images}).
 */
@ReactModule(name = CameraRollModule.NAME)
public class CameraRollModule extends NativeCameraRollModuleSpec {

  public static final String NAME = "RNCCameraRoll";

  private static final String ERROR_UNABLE_TO_LOAD = "E_UNABLE_TO_LOAD";
  private static final String ERROR_UNABLE_TO_LOAD_PERMISSION = "E_UNABLE_TO_LOAD_PERMISSION";
  private static final String ERROR_UNABLE_TO_SAVE = "E_UNABLE_TO_SAVE";
  private static final String ERROR_UNABLE_TO_DELETE = "E_UNABLE_TO_DELETE";
  private static final String ERROR_UNABLE_TO_FILTER = "E_UNABLE_TO_FILTER";

  private static final String ASSET_TYPE_PHOTOS = "Photos";
  private static final String ASSET_TYPE_VIDEOS = "Videos";
  private static final String ASSET_TYPE_ALL = "All";
  private static final String ASSET_TYPE_LIVE = "Live";

  private static final String INCLUDE_FILENAME = "filename";
  private static final String INCLUDE_FILE_SIZE = "fileSize";
  private static final String INCLUDE_FILE_EXTENSION = "fileExtension";
  private static final String INCLUDE_LOCATION = "location";
  private static final String INCLUDE_IMAGE_SIZE = "imageSize";
  private static final String INCLUDE_PLAYABLE_DURATION = "playableDuration";
  private static final String INCLUDE_ORIENTATION = "orientation";
  private static final String INCLUDE_ALBUMS = "albums";
  private static final String MOTION_PHOTO_CACHE_DIR = "rn-cameraroll-motion-photo";
  private static final int MOTION_PHOTO_XMP_SCAN_BYTES = 512 * 1024;
  private static final int MOTION_PHOTO_VIDEO_SCAN_BYTES = 16 * 1024 * 1024;
  private static final int HUAWEI_LIVE_PHOTO_TRAILER_SIZE = 60;
  private static final int VIVO_LIVE_PHOTO_TAIL_SCAN_BYTES = 2048;
  private static final Pattern MICRO_VIDEO_OFFSET_PATTERN =
          Pattern.compile("(?:Camera|GCamera):MicroVideoOffset[^0-9]*(\\d+)");
  private static final Pattern MOTION_PHOTO_LENGTH_PATTERN =
          Pattern.compile("Item:Semantic=\"MotionPhoto\"[^>]*Item:Length=\"(\\d+)\"");
  private static final Pattern MOTION_PHOTO_LENGTH_PATTERN_REVERSED =
          Pattern.compile("Item:Length=\"(\\d+)\"[^>]*Item:Semantic=\"MotionPhoto\"");

  private static final String[] PROJECTION = {
          Images.Media._ID,
          Images.Media.MIME_TYPE,
          Images.Media.BUCKET_DISPLAY_NAME,
          Images.Media.DATE_TAKEN,
          MediaStore.MediaColumns.DATE_ADDED,
          MediaStore.MediaColumns.DATE_MODIFIED,
          MediaStore.MediaColumns.WIDTH,
          MediaStore.MediaColumns.HEIGHT,
          MediaStore.MediaColumns.SIZE,
          MediaStore.MediaColumns.DATA,
          MediaStore.MediaColumns.ORIENTATION,
  };

  private static final String SELECTION_BUCKET = Images.Media.BUCKET_DISPLAY_NAME + " = ?";

  public CameraRollModule(ReactApplicationContext reactContext) {
    super(reactContext);
  }

  private static boolean isHuaweiDevice() {
    String manufacturer = Build.MANUFACTURER;
    return manufacturer != null
            && (manufacturer.equalsIgnoreCase("HUAWEI") || manufacturer.equalsIgnoreCase("HONOR"));
  }

  private static boolean isVivoDevice() {
    String manufacturer = Build.MANUFACTURER;
    return manufacturer != null && manufacturer.equalsIgnoreCase("vivo");
  }

  @Override
  public String getName() {
    return NAME;
  }

  /**
   * Save an image to the gallery (i.e. {@link MediaStore.Images}). This copies the original file
   * from wherever it may be to the external storage pictures directory, so that it can be scanned
   * by the MediaScanner.
   *
   * @param uri     the file:// URI of the image to save
   * @param promise to be resolved or rejected
   */
  @ReactMethod
  public void saveToCameraRoll(String uri, ReadableMap options, Promise promise) {
    new SaveToCameraRoll(getReactApplicationContext(), Uri.parse(uri), options, promise)
            .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }

  /**
   * Build a Motion Photo (JPEG + appended MP4 with Google/Samsung-compatible
   * XMP markers) from a cover image and a paired video, then save it into
   * the camera roll. The resulting file is recognised as a Live / Motion
   * Photo by Google Photos, Samsung Gallery and this library's own
   * {@code getPhotos({ assetType: 'Live' })} query.
   */
  @ReactMethod
  public void saveLivePhoto(ReadableMap options, Promise promise) {
    if (options == null
            || !options.hasKey("imageUri") || options.isNull("imageUri")
            || !options.hasKey("videoUri") || options.isNull("videoUri")) {
      promise.reject(ERROR_UNABLE_TO_SAVE, "saveLivePhoto requires both imageUri and videoUri");
      return;
    }
    new SaveLivePhotoTask(getReactApplicationContext(), options, promise)
            .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }

  private static class SaveLivePhotoTask extends GuardedAsyncTask<Void, Void> {

    private final Context mContext;
    private final ReadableMap mOptions;
    private final Promise mPromise;

    SaveLivePhotoTask(ReactContext context, ReadableMap options, Promise promise) {
      super(context);
      mContext = context;
      mOptions = options;
      mPromise = promise;
    }

    @Override
    protected void doInBackgroundGuarded(Void... params) {
      File tempFile = null;
      File tempVivoMp4 = null;
      try {
        String imageUriStr = mOptions.getString("imageUri");
        String videoUriStr = mOptions.getString("videoUri");
        String album = mOptions.hasKey("album") && !mOptions.isNull("album")
                ? mOptions.getString("album") : "";
        String title = mOptions.hasKey("title") && !mOptions.isNull("title")
                ? mOptions.getString("title") : "";

        File imageFile = resolveLocalFile(imageUriStr);
        File videoFile = resolveLocalFile(videoUriStr);
        if (imageFile == null || !imageFile.isFile()) {
          mPromise.reject(ERROR_UNABLE_TO_LOAD, "Could not read cover image at " + imageUriStr);
          return;
        }
        if (videoFile == null || !videoFile.isFile()) {
          mPromise.reject(ERROR_UNABLE_TO_LOAD, "Could not read video at " + videoUriStr);
          return;
        }

        File cacheDir = new File(mContext.getCacheDir(), MOTION_PHOTO_CACHE_DIR);
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
          mPromise.reject(ERROR_UNABLE_TO_SAVE, "Could not create motion photo cache dir");
          return;
        }

        String baseName = TextUtils.isEmpty(title)
                ? "LIVE_" + System.currentTimeMillis()
                : title;
        tempFile = new File(cacheDir, baseName + ".jpg");
        if (tempFile.exists() && !tempFile.delete()) {
          mPromise.reject(ERROR_UNABLE_TO_SAVE, "Could not overwrite temp motion photo file");
          return;
        }

        if (isVivoDevice()) {
          String livePhotoId = String.format("%012x%08x00000000",
                  System.currentTimeMillis(),
                  (int) (Math.random() * 0xFFFFFFFF));
          if (!buildVivoLivePhotoJpeg(imageFile, tempFile, livePhotoId)) {
            mPromise.reject(ERROR_UNABLE_TO_SAVE, "Failed to build vivo live photo jpeg");
            return;
          }
          tempVivoMp4 = new File(cacheDir, baseName + ".mp4");
          if (!buildVivoLivePhotoMp4(videoFile, tempVivoMp4, livePhotoId)) {
            mPromise.reject(ERROR_UNABLE_TO_SAVE, "Failed to build vivo live photo mp4");
            return;
          }
          String savedUri = insertVivoLivePhotoIntoMediaStore(
                  mContext, tempFile, tempVivoMp4, album, baseName);
          if (savedUri == null) {
            mPromise.reject(ERROR_UNABLE_TO_SAVE, "Could not insert vivo live photo into MediaStore");
            return;
          }
          mPromise.resolve(savedUri);
          return;
        }

        long videoLength = videoFile.length();
        boolean success;
        if (isHuaweiDevice()) {
          int[] videoMeta = extractVideoMetadata(videoFile);
          success = buildHuaweiMotionPhotoFile(imageFile, videoFile, tempFile,
                  videoLength, videoMeta[0], videoMeta[1]);
        } else {
          success = buildStandardMotionPhotoFile(imageFile, videoFile, tempFile, videoLength);
        }
        if (!success) {
          mPromise.reject(ERROR_UNABLE_TO_SAVE, "Failed to build motion photo payload");
          return;
        }

        String savedUri = insertMotionPhotoIntoMediaStore(mContext, tempFile, album, baseName);
        if (savedUri == null) {
          mPromise.reject(ERROR_UNABLE_TO_SAVE, "Could not insert motion photo into MediaStore");
          return;
        }

        mPromise.resolve(savedUri);
      } catch (Exception e) {
        mPromise.reject(ERROR_UNABLE_TO_SAVE, "Failed to save live photo: " + e.getMessage(), e);
      } finally {
        if (tempFile != null && tempFile.exists()) {
          //noinspection ResultOfMethodCallIgnored
          tempFile.delete();
        }
        if (tempVivoMp4 != null && tempVivoMp4.exists()) {
          //noinspection ResultOfMethodCallIgnored
          tempVivoMp4.delete();
        }
      }
    }
  }

  @Nullable
  private static File resolveLocalFile(@Nullable String uriStr) {
    if (TextUtils.isEmpty(uriStr)) {
      return null;
    }
    try {
      Uri uri = Uri.parse(uriStr);
      String scheme = uri.getScheme();
      if (scheme == null || "file".equalsIgnoreCase(scheme)) {
        String path = uri.getPath();
        if (path != null) {
          File f = new File(path);
          if (f.exists()) return f;
        }
      }
      File direct = new File(uriStr);
      if (direct.exists()) return direct;
    } catch (Exception ignored) {
    }
    return null;
  }

  /**
   * Standard Motion Photo: inject Google/Samsung-compatible XMP into the
   * JPEG header, then append the raw MP4. Works on Xiaomi, Google, Samsung.
   */
  private static boolean buildStandardMotionPhotoFile(
          File imageFile,
          File videoFile,
          File outputFile,
          long videoLength) {
    byte[] xmpSegment = buildMotionPhotoXmpAppSegment(videoLength);
    if (xmpSegment == null) {
      return false;
    }

    byte[] buffer = new byte[32 * 1024];
    try (FileInputStream imageIn = new FileInputStream(imageFile);
         FileOutputStream out = new FileOutputStream(outputFile)) {

      byte[] soi = new byte[2];
      int read = imageIn.read(soi);
      if (read != 2 || (soi[0] & 0xFF) != 0xFF || (soi[1] & 0xFF) != 0xD8) {
        FLog.w(ReactConstants.TAG, "Cover image is not a JPEG (missing SOI marker)");
        return false;
      }
      out.write(soi);
      out.write(xmpSegment);

      int n;
      while ((n = imageIn.read(buffer)) != -1) {
        out.write(buffer, 0, n);
      }

      try (FileInputStream videoIn = new FileInputStream(videoFile)) {
        while ((n = videoIn.read(buffer)) != -1) {
          out.write(buffer, 0, n);
        }
      }
      out.flush();
      return true;
    } catch (IOException e) {
      FLog.w(ReactConstants.TAG, "Could not build standard motion photo file", e);
      return false;
    }
  }

  /**
   * Huawei/Honor Motion Photo: copy JPEG and MP4 as-is, then append a
   * 60-byte trailer that their Gallery recognises.
   */
  private static boolean buildHuaweiMotionPhotoFile(
          File imageFile,
          File videoFile,
          File outputFile,
          long videoLength,
          int frameCount,
          int durationMs) {
    long mdatOffsetInVideo = findMdatOffset(videoFile);
    long liveValue = videoLength - mdatOffsetInVideo + HUAWEI_LIVE_PHOTO_TRAILER_SIZE;

    byte[] buffer = new byte[32 * 1024];
    try (FileInputStream imageIn = new FileInputStream(imageFile);
         FileOutputStream out = new FileOutputStream(outputFile)) {

      int n;
      while ((n = imageIn.read(buffer)) != -1) {
        out.write(buffer, 0, n);
      }

      try (FileInputStream videoIn = new FileInputStream(videoFile)) {
        while ((n = videoIn.read(buffer)) != -1) {
          out.write(buffer, 0, n);
        }
      }

      out.write(buildHuaweiLivePhotoTrailer(liveValue, frameCount, durationMs));
      out.flush();
      return true;
    } catch (IOException e) {
      FLog.w(ReactConstants.TAG, "Could not build Huawei motion photo file", e);
      return false;
    }
  }

  /**
   * Find the byte offset of the mdat box within an MP4 file.
   * Returns 0 if mdat is not found (falls back to entire video).
   */
  private static long findMdatOffset(File videoFile) {
    try (RandomAccessFile raf = new RandomAccessFile(videoFile, "r")) {
      long fileLen = raf.length();
      long pos = 0;
      while (pos + 8 <= fileLen) {
        raf.seek(pos);
        long boxSize = raf.readInt() & 0xFFFFFFFFL;
        byte[] type = new byte[4];
        raf.readFully(type);
        if (boxSize == 1 && pos + 16 <= fileLen) {
          boxSize = raf.readLong();
        }
        if (boxSize == 0) boxSize = fileLen - pos;
        if (boxSize < 8) break;
        if (type[0] == 'm' && type[1] == 'd' && type[2] == 'a' && type[3] == 't') {
          return pos;
        }
        pos += boxSize;
      }
    } catch (IOException e) {
      FLog.w(ReactConstants.TAG, "Could not find mdat in video file", e);
    }
    return 0;
  }

  /**
   * Extract frame count and duration (ms) from a video file.
   * Returns {@code int[]{frameCount, durationMs}}.
   */
  private static int[] extractVideoMetadata(File videoFile) {
    int frameCount = 0;
    int durationMs = 0;
    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
    try {
      retriever.setDataSource(videoFile.getAbsolutePath());
      String dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
      if (dur != null) {
        durationMs = Integer.parseInt(dur);
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        String fc = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
        if (fc != null) {
          frameCount = Integer.parseInt(fc);
        }
      }
    } catch (Exception e) {
      FLog.w(ReactConstants.TAG, "Could not extract video metadata for live photo trailer", e);
    } finally {
      try { retriever.release(); } catch (Exception ignored) { }
    }
    return new int[]{frameCount, durationMs};
  }

  /**
   * Build the 60-byte trailer that Huawei/Honor Gallery uses to identify
   * a live (motion) photo. The format is three left-justified,
   * space-padded fields of 20 characters each:
   * <pre>
   *   v2_f{frameCount}      {pts}:{duration}      LIVE_{videoSize}
   * </pre>
   */
  private static byte[] buildHuaweiLivePhotoTrailer(
          long videoDataSize,
          int frameCount,
          int durationMs) {
    String f1 = padRight("v2_f" + frameCount, 20);
    String f2 = padRight("0:" + durationMs, 20);
    String f3 = padRight("LIVE_" + videoDataSize, 20);
    return (f1 + f2 + f3).getBytes(StandardCharsets.US_ASCII);
  }

  private static String padRight(String s, int width) {
    if (s.length() >= width) return s.substring(0, width);
    StringBuilder sb = new StringBuilder(width);
    sb.append(s);
    while (sb.length() < width) sb.append(' ');
    return sb.toString();
  }

  /**
   * Build the JPEG part of a vivo live photo: copy the original JPEG,
   * append the streaminfo section and vivo JSON trailer so the vivo
   * Gallery recognises the JPEG + companion-MP4 pair.
   *
   * On-disk layout after the JPEG FFD9 end marker:
   * <pre>
   *   [streaminfo entries]      – binary video-stream descriptors
   *   streamcount\x00{count}    – stream count marker
   *   vivo{...JSON...}          – UTF-8 JSON with camera / livephoto metadata
   *   [4-byte BE jsonBodyLen]   – length of JSON body "{...}"
   *   cameralbum!               – ASCII marker
   *   \x00\x00\x00/{id}        – companion-video identifier path
   *   \xFF\xFF\xFF\xFF          – end marker
   *   [gradient LUT]            – 11-byte ramp (optional display hint)
   * </pre>
   */
  private static boolean buildVivoLivePhotoJpeg(
          File imageFile, File outputFile, String livePhotoId) {
    byte[] buffer = new byte[32 * 1024];
    try (FileInputStream imageIn = new FileInputStream(imageFile);
         FileOutputStream out = new FileOutputStream(outputFile)) {
      int n;
      while ((n = imageIn.read(buffer)) != -1) {
        out.write(buffer, 0, n);
      }
      out.write(buildVivoStreamInfoSection());
      out.write(buildVivoLivePhotoTrailer(livePhotoId));
      out.flush();
      return true;
    } catch (IOException e) {
      FLog.w(ReactConstants.TAG, "Could not build vivo live photo jpeg", e);
      return false;
    }
  }

  /**
   * Build the MP4 part of a vivo live photo: copy the original MP4 and
   * append a {@code vivoMediaExtInfo} UUID box containing the same
   * vivo JSON trailer used in the JPEG.  The vivo Gallery requires
   * this box in both files to recognise the pair.
   */
  private static boolean buildVivoLivePhotoMp4(
          File videoFile, File outputFile, String livePhotoId) {
    byte[] buffer = new byte[32 * 1024];
    try (FileInputStream videoIn = new FileInputStream(videoFile);
         FileOutputStream out = new FileOutputStream(outputFile)) {
      int n;
      while ((n = videoIn.read(buffer)) != -1) {
        out.write(buffer, 0, n);
      }
      byte[] trailer = buildVivoLivePhotoTrailer(livePhotoId);
      byte[] uuidName = "vivoMediaExtInfo".getBytes(StandardCharsets.US_ASCII);
      int boxSize = 4 + 4 + uuidName.length + trailer.length; // size + 'uuid' + name + payload
      out.write(new byte[] {
              (byte) ((boxSize >> 24) & 0xFF),
              (byte) ((boxSize >> 16) & 0xFF),
              (byte) ((boxSize >> 8) & 0xFF),
              (byte) (boxSize & 0xFF),
              'u', 'u', 'i', 'd'
      });
      out.write(uuidName);
      out.write(trailer);
      out.flush();
      return true;
    } catch (IOException e) {
      FLog.w(ReactConstants.TAG, "Could not build vivo live photo mp4", e);
      return false;
    }
  }

  /**
   * Minimal streaminfo / streamcount section placed between the JPEG
   * FFD9 end marker and the {@code vivo{}} JSON.  Values are based on
   * captures from a vivo X100; the Gallery appears to require the
   * section to be present but tolerates generic stream descriptors.
   */
  private static byte[] buildVivoStreamInfoSection() {
    return new byte[] {
      // 6 × streaminfo entries (19 bytes each)
      0x73,0x74,0x72,0x65,0x61,0x6d,0x69,0x6e,0x66,0x6f, 0x00, 0x02,0x00,0x6c,0x0a,0x00,0x00,0x00,0x04,
      0x73,0x74,0x72,0x65,0x61,0x6d,0x69,0x6e,0x66,0x6f, 0x00, 0x02,0x00,0x01,0x01,0x00,0x00,(byte)0xcd,0x58,
      0x73,0x74,0x72,0x65,0x61,0x6d,0x69,0x6e,0x66,0x6f, 0x00, 0x02,0x00,0x04,0x02,0x00,0x00,0x78,0x6d,
      0x73,0x74,0x72,0x65,0x61,0x6d,0x69,0x6e,0x66,0x6f, 0x00, 0x02,0x00,0x05,0x02,0x00,0x00,0x4e,0x38,
      0x73,0x74,0x72,0x65,0x61,0x6d,0x69,0x6e,0x66,0x6f, 0x00, 0x02,0x00,0x65,0x0a,0x00,0x00,0x00,0x2e,
      0x73,0x74,0x72,0x65,0x61,0x6d,0x69,0x6e,0x66,0x6f, 0x00, 0x03,0x00,0x01,0x01,0x00,0x0c,0x1d,(byte)0x80,
      // streamcount marker + count=6
      0x73,0x74,0x72,0x65,0x61,0x6d,0x63,0x6f,0x75,0x6e,0x74, 0x00, 0x06,
    };
  }

  private static byte[] buildVivoLivePhotoTrailer(String livePhotoId) {
    String model = Build.MODEL != null ? Build.MODEL : "vivo";
    String jsonBody = "{"
            + "\"com.android.camera.joint.fullview.orientation\":0,"
            + "\"com.android.camera.hdr\":-1,"
            + "\"com.android.camera.fisheye\":-1,"
            + "\"com.android.camera.takenmodel\":\"" + model + "\","
            + "\"com.android.camera.camerafacing\":\"0\","
            + "\"com.android.camera.document\":-1,"
            + "\"com.android.camera.joint.conshoot\":0,"
            + "\"com.android.camera.joint.motioncapture\":0,"
            + "\"com.android.camera.moduleid\":\"photo\","
            + "\"com.android.camera.livephoto\":\"" + livePhotoId + "\","
            + "\"version\":2014,"
            + "\"com.android.camera.joint.fullview\":false"
            + "}";

    byte[] vivoJson = ("vivo" + jsonBody).getBytes(StandardCharsets.UTF_8);
    int jsonBodyLen = jsonBody.getBytes(StandardCharsets.UTF_8).length;
    byte[] cameralbum = "cameralbum!".getBytes(StandardCharsets.US_ASCII);
    byte[] idPath = ("/" + livePhotoId).getBytes(StandardCharsets.US_ASCII);

    // Trailing: \xFF\xFF\xFF\xFF + 11-byte gradient ramp
    byte[] trailing = new byte[] {
      (byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,
      0x1b,0x2a,0x39,0x48,0x57,0x66,0x75,(byte)0x84,(byte)0x93,(byte)0xa2,(byte)0xb3
    };

    int totalLen = vivoJson.length + 4 + cameralbum.length + 3 + idPath.length + trailing.length;
    byte[] result = new byte[totalLen];
    int pos = 0;

    System.arraycopy(vivoJson, 0, result, pos, vivoJson.length);
    pos += vivoJson.length;

    result[pos++] = (byte) ((jsonBodyLen >> 24) & 0xFF);
    result[pos++] = (byte) ((jsonBodyLen >> 16) & 0xFF);
    result[pos++] = (byte) ((jsonBodyLen >> 8) & 0xFF);
    result[pos++] = (byte) (jsonBodyLen & 0xFF);

    System.arraycopy(cameralbum, 0, result, pos, cameralbum.length);
    pos += cameralbum.length;

    result[pos++] = 0x00;
    result[pos++] = 0x00;
    result[pos++] = 0x00;

    System.arraycopy(idPath, 0, result, pos, idPath.length);
    pos += idPath.length;

    System.arraycopy(trailing, 0, result, pos, trailing.length);

    return result;
  }

  private static boolean copyFileSimple(File src, File dest) {
    byte[] buffer = new byte[32 * 1024];
    try (FileInputStream in = new FileInputStream(src);
         FileOutputStream out = new FileOutputStream(dest)) {
      int n;
      while ((n = in.read(buffer)) != -1) {
        out.write(buffer, 0, n);
      }
      out.flush();
      return true;
    } catch (IOException e) {
      FLog.w(ReactConstants.TAG, "Could not copy file " + src + " to " + dest, e);
      return false;
    }
  }

  @Nullable
  private static byte[] buildMotionPhotoXmpAppSegment(long videoLength) {
    String xmp =
            "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
            + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"RNCameraRoll\">"
            + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
            + "<rdf:Description rdf:about=\"\""
            + " xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\""
            + " xmlns:Container=\"http://ns.google.com/photos/1.0/container/\""
            + " xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\""
            + " GCamera:MotionPhoto=\"1\""
            + " GCamera:MotionPhotoVersion=\"1\""
            + " GCamera:MotionPhotoPresentationTimestampUs=\"0\""
            + " GCamera:MicroVideo=\"1\""
            + " GCamera:MicroVideoVersion=\"1\""
            + " GCamera:MicroVideoOffset=\"" + videoLength + "\""
            + " GCamera:MicroVideoPresentationTimestampUs=\"0\">"
            + "<Container:Directory>"
            + "<rdf:Seq>"
            + "<rdf:li rdf:parseType=\"Resource\">"
            + "<Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\" Item:Length=\"0\"/>"
            + "</rdf:li>"
            + "<rdf:li rdf:parseType=\"Resource\">"
            + "<Container:Item Item:Mime=\"video/mp4\" Item:Semantic=\"MotionPhoto\" Item:Length=\""
            + videoLength + "\"/>"
            + "</rdf:li>"
            + "</rdf:Seq>"
            + "</Container:Directory>"
            + "</rdf:Description>"
            + "</rdf:RDF>"
            + "</x:xmpmeta>"
            + "<?xpacket end=\"w\"?>";

    byte[] xmpBytes = xmp.getBytes(StandardCharsets.UTF_8);
    byte[] header = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.US_ASCII);

    int payloadLength = header.length + xmpBytes.length;
    int segmentLength = 2 + payloadLength; // length field itself + payload
    if (segmentLength > 0xFFFF) {
      FLog.w(ReactConstants.TAG, "Motion photo XMP payload too large: " + segmentLength);
      return null;
    }

    byte[] segment = new byte[2 + segmentLength];
    segment[0] = (byte) 0xFF;
    segment[1] = (byte) 0xE1; // APP1
    segment[2] = (byte) ((segmentLength >> 8) & 0xFF);
    segment[3] = (byte) (segmentLength & 0xFF);
    System.arraycopy(header, 0, segment, 4, header.length);
    System.arraycopy(xmpBytes, 0, segment, 4 + header.length, xmpBytes.length);
    return segment;
  }

  @Nullable
  private static String insertMotionPhotoIntoMediaStore(
          Context context,
          File sourceFile,
          String album,
          String displayBaseName) {
    ContentResolver resolver = context.getContentResolver();
    boolean hasAlbum = !TextUtils.isEmpty(album);
    String displayName = displayBaseName + ".jpg";

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ContentValues values = new ContentValues();
      values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
      values.put(Images.Media.DISPLAY_NAME, displayName);
      if (hasAlbum) {
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DCIM + File.separator + album);
      } else if (isHuaweiDevice()) {
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DCIM + File.separator + "Camera");
      } else {
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM);
      }
      values.put(Images.Media.IS_PENDING, 1);

      Uri target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
      if (target == null) return null;

      try (FileInputStream in = new FileInputStream(sourceFile);
           OutputStream out = resolver.openOutputStream(target)) {
        if (out == null) return null;
        FileUtils.copy(in, out);
      } catch (IOException e) {
        FLog.w(ReactConstants.TAG, "Could not write motion photo to MediaStore", e);
        resolver.delete(target, null, null);
        return null;
      }

      ContentValues update = new ContentValues();
      update.put(Images.Media.IS_PENDING, 0);
      resolver.update(target, update, null, null);
      return target.toString();
    } else {
      File exportDir;
      if (hasAlbum) {
        exportDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), album);
      } else if (isHuaweiDevice()) {
        exportDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM), "Camera");
      } else {
        exportDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
      }
      if (!exportDir.exists() && !exportDir.mkdirs()) {
        return null;
      }
      File dest = new File(exportDir, displayName);
      int n = 0;
      while (dest.exists()) {
        dest = new File(exportDir, displayBaseName + "_" + (n++) + ".jpg");
      }
      try (FileInputStream in = new FileInputStream(sourceFile);
           FileOutputStream out = new FileOutputStream(dest)) {
        byte[] buffer = new byte[32 * 1024];
        int r;
        while ((r = in.read(buffer)) != -1) {
          out.write(buffer, 0, r);
        }
        out.flush();
      } catch (IOException e) {
        FLog.w(ReactConstants.TAG, "Could not write motion photo to external storage", e);
        return null;
      }

      final String[] savedUri = new String[1];
      final Object lock = new Object();
      MediaScannerConnection.scanFile(
              context,
              new String[]{dest.getAbsolutePath()},
              new String[]{"image/jpeg"},
              (path, uri) -> {
                synchronized (lock) {
                  savedUri[0] = uri != null ? uri.toString() : Uri.fromFile(new File(path)).toString();
                  lock.notifyAll();
                }
              });
      synchronized (lock) {
        if (savedUri[0] == null) {
          try {
            lock.wait(5000);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          }
        }
      }
      return savedUri[0] != null ? savedUri[0] : Uri.fromFile(dest).toString();
    }
  }

  /**
   * Insert a vivo live photo pair (JPEG + companion MP4) into MediaStore.
   * Both files are placed in the same directory with matching base names
   * so the vivo Gallery can associate them.
   */
  @Nullable
  private static String insertVivoLivePhotoIntoMediaStore(
          Context context,
          File jpegFile,
          File mp4File,
          String album,
          String displayBaseName) {
    ContentResolver resolver = context.getContentResolver();
    boolean hasAlbum = !TextUtils.isEmpty(album);
    String relativePath = hasAlbum
            ? Environment.DIRECTORY_DCIM + File.separator + album
            : Environment.DIRECTORY_DCIM + File.separator + "Camera";

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      // --- Insert JPEG ---
      ContentValues jpegValues = new ContentValues();
      jpegValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
      jpegValues.put(Images.Media.DISPLAY_NAME, displayBaseName + ".jpg");
      jpegValues.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
      jpegValues.put(Images.Media.IS_PENDING, 1);

      Uri jpegUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, jpegValues);
      if (jpegUri == null) return null;

      try (FileInputStream in = new FileInputStream(jpegFile);
           OutputStream out = resolver.openOutputStream(jpegUri)) {
        if (out == null) {
          resolver.delete(jpegUri, null, null);
          return null;
        }
        FileUtils.copy(in, out);
      } catch (IOException e) {
        FLog.w(ReactConstants.TAG, "Could not write vivo live photo jpeg to MediaStore", e);
        resolver.delete(jpegUri, null, null);
        return null;
      }

      ContentValues jpegDone = new ContentValues();
      jpegDone.put(Images.Media.IS_PENDING, 0);
      resolver.update(jpegUri, jpegDone, null, null);

      // --- Insert companion MP4 ---
      ContentValues mp4Values = new ContentValues();
      mp4Values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
      mp4Values.put(MediaStore.Video.Media.DISPLAY_NAME, displayBaseName + ".mp4");
      mp4Values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
      mp4Values.put(MediaStore.Video.Media.IS_PENDING, 1);

      Uri mp4Uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mp4Values);
      if (mp4Uri != null) {
        try (FileInputStream in = new FileInputStream(mp4File);
             OutputStream out = resolver.openOutputStream(mp4Uri)) {
          if (out != null) {
            FileUtils.copy(in, out);
            ContentValues mp4Done = new ContentValues();
            mp4Done.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(mp4Uri, mp4Done, null, null);
          } else {
            resolver.delete(mp4Uri, null, null);
          }
        } catch (IOException e) {
          FLog.w(ReactConstants.TAG, "Could not write vivo companion mp4 to MediaStore", e);
          resolver.delete(mp4Uri, null, null);
        }
      }

      return jpegUri.toString();
    } else {
      // Pre-Q: write files directly to external storage
      File exportDir = new File(
              Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
              hasAlbum ? album : "Camera");
      if (!exportDir.exists() && !exportDir.mkdirs()) return null;

      String actualBase = displayBaseName;
      File destJpeg = new File(exportDir, actualBase + ".jpg");
      File destMp4 = new File(exportDir, actualBase + ".mp4");
      int n = 0;
      while (destJpeg.exists() || destMp4.exists()) {
        actualBase = displayBaseName + "_" + (n++);
        destJpeg = new File(exportDir, actualBase + ".jpg");
        destMp4 = new File(exportDir, actualBase + ".mp4");
      }

      if (!copyFileSimple(jpegFile, destJpeg)) return null;
      copyFileSimple(mp4File, destMp4);

      final String[] savedUri = new String[1];
      final Object lock = new Object();
      MediaScannerConnection.scanFile(
              context,
              new String[]{destJpeg.getAbsolutePath(), destMp4.getAbsolutePath()},
              new String[]{"image/jpeg", "video/mp4"},
              (path, uri) -> {
                if (path.endsWith(".jpg") || path.endsWith(".JPG")) {
                  synchronized (lock) {
                    savedUri[0] = uri != null ? uri.toString()
                            : Uri.fromFile(new File(path)).toString();
                    lock.notifyAll();
                  }
                }
              });
      synchronized (lock) {
        if (savedUri[0] == null) {
          try {
            lock.wait(5000);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          }
        }
      }
      return savedUri[0] != null ? savedUri[0] : Uri.fromFile(destJpeg).toString();
    }
  }

  private static class SaveToCameraRoll extends GuardedAsyncTask<Void, Void> {

    private final Context mContext;
    private final Uri mUri;
    private final Promise mPromise;
    private final ReadableMap mOptions;

    public SaveToCameraRoll(ReactContext context, Uri uri, ReadableMap options, Promise promise) {
      super(context);
      mContext = context;
      mUri = uri;
      mPromise = promise;
      mOptions = options;
    }

    @Override
    protected void doInBackgroundGuarded(Void... params) {
      File source = new File(mUri.getPath());
      FileInputStream input = null;
      OutputStream output = null;

      String mimeType = Utils.getMimeType(mUri.toString());
      Boolean isVideo = mimeType != null && mimeType.contains("video");

      try {
        String album = mOptions.getString("album");
        boolean isAlbumPresent = !TextUtils.isEmpty(album);

        // Android Q and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          ContentValues mediaDetails = new ContentValues();
          if (isAlbumPresent) {
            String relativePath = Environment.DIRECTORY_DCIM + File.separator + album;
            mediaDetails.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
          }
          mediaDetails.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
          mediaDetails.put(Images.Media.DISPLAY_NAME, source.getName());
          mediaDetails.put(Images.Media.IS_PENDING, 1);
          ContentResolver resolver = mContext.getContentResolver();
          Uri mediaContentUri = isVideo
                  ? resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaDetails)
                  : resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaDetails);
          if (mediaContentUri == null) {
            mPromise.reject(ERROR_UNABLE_TO_LOAD, "ContentResolver#insert() returns null, insert failed");
          }
          output = resolver.openOutputStream(mediaContentUri);
          input = new FileInputStream(source);
          FileUtils.copy(input, output);
          mediaDetails.clear();
          mediaDetails.put(Images.Media.IS_PENDING, 0);
          resolver.update(mediaContentUri, mediaDetails, null, null);
          mPromise.resolve(mediaContentUri.toString());
        } else {
          final File environment;
          // Media is not saved into an album when using Environment.DIRECTORY_DCIM.
          if (isAlbumPresent) {
            if ("video".equals(mOptions.getString("type"))) {
              environment = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
            } else {
              environment = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            }
          } else {
            environment = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
          }
          File exportDir;
          if (isAlbumPresent) {
            exportDir = new File(environment, album);
            if (!exportDir.exists() && !exportDir.mkdirs()) {
              mPromise.reject(ERROR_UNABLE_TO_LOAD, "Album Directory not created. Did you request WRITE_EXTERNAL_STORAGE?");
              return;
            }
          } else {
            exportDir = environment;
          }

          if (!exportDir.isDirectory()) {
            mPromise.reject(ERROR_UNABLE_TO_LOAD, "External media storage directory not available");
            return;
          }

          File dest = new File(exportDir, source.getName());
          int n = 0;
          String fullSourceName = source.getName();
          String sourceName, sourceExt;
          if (fullSourceName.indexOf('.') >= 0) {
            sourceName = fullSourceName.substring(0, fullSourceName.lastIndexOf('.'));
            sourceExt = fullSourceName.substring(fullSourceName.lastIndexOf('.'));
          } else {
            sourceName = fullSourceName;
            sourceExt = "";
          }
          while (!dest.createNewFile()) {
            dest = new File(exportDir, sourceName + "_" + (n++) + sourceExt);
          }
          input = new FileInputStream(source);
          output = new FileOutputStream(dest);
          ((FileOutputStream) output).getChannel()
                  .transferFrom(input.getChannel(), 0, input.getChannel().size());
          input.close();
          output.close();

          MediaScannerConnection.scanFile(
                  mContext,
                  new String[]{dest.getAbsolutePath()},
                  null,
                  (path, uri) -> {
                    if (uri != null) {
                      mPromise.resolve(uri.toString());
                    } else {
                      mPromise.reject(ERROR_UNABLE_TO_SAVE, "Could not add image to gallery");
                    }
                  });
        }
      } catch (IOException e) {
        mPromise.reject(e);
      } finally {
        if (input != null) {
          try {
            input.close();
          } catch (IOException e) {
            FLog.e(ReactConstants.TAG, "Could not close input channel", e);
          }
        }
        if (output != null) {
          try {
            output.close();
          } catch (IOException e) {
            FLog.e(ReactConstants.TAG, "Could not close output channel", e);
          }
        }
      }
    }
  }

  /**
   * Get photos from {@link MediaStore.Images}, most recent first.
   *
   * @param params  a map containing the following keys:
   *                <ul>
   *                  <li>first (mandatory): a number representing the number of photos to fetch</li>
   *                  <li>
   *                    after (optional): a cursor that matches page_info[end_cursor] returned by a
   *                    previous call to {@link #getPhotos}
   *                  </li>
   *                  <li>groupName (optional): an album name</li>
   *                  <li>
   *                    mimeType (optional): restrict returned images to a specific mimetype (e.g.
   *                    image/jpeg)
   *                  </li>
   *                  <li>
   *                    assetType (optional): chooses between photos, videos, or live photos from
   *                    the camera roll. Valid values are "Photos", "Videos", or "Live".
   *                    Defaults to photos.
   *                  </li>
   *                </ul>
   * @param promise the Promise to be resolved when the photos are loaded; for a format of the
   *                parameters passed to this callback, see {@code getPhotosReturnChecker} in CameraRoll.js
   */
  @ReactMethod
  public void getPhotos(final ReadableMap params, final Promise promise) {
    int first = params.getInt("first");
    String after = params.hasKey("after") ? params.getString("after") : null;
    String groupName = params.hasKey("groupName") ? params.getString("groupName") : null;
    String assetType = params.hasKey("assetType") ? params.getString("assetType") : ASSET_TYPE_PHOTOS;
    long fromTime = params.hasKey("fromTime") ? (long) params.getDouble("fromTime") : 0;
    long toTime = params.hasKey("toTime") ? (long) params.getDouble("toTime") : 0;
    ReadableArray mimeTypes = params.hasKey("mimeTypes")
            ? params.getArray("mimeTypes")
            : null;
    ReadableArray include = params.hasKey("include") ? params.getArray("include") : null;

    new GetMediaTask(
            getReactApplicationContext(),
            first,
            after,
            groupName,
            mimeTypes,
            assetType,
            fromTime,
            toTime,
            include,
            promise)
            .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }

  private static class GetMediaTask extends GuardedAsyncTask<Void, Void> {
    private final Context mContext;
    private final int mFirst;
    private final @Nullable
    String mAfter;
    private final @Nullable
    String mGroupName;
    private final @Nullable
    ReadableArray mMimeTypes;
    private final Promise mPromise;
    private final String mAssetType;
    private final long mFromTime;
    private final long mToTime;
    private final Set<String> mInclude;

    private GetMediaTask(
            ReactContext context,
            int first,
            @Nullable String after,
            @Nullable String groupName,
            @Nullable ReadableArray mimeTypes,
            String assetType,
            long fromTime,
            long toTime,
            @Nullable ReadableArray include,
            Promise promise) {
      super(context);
      mContext = context;
      mFirst = first;
      mAfter = after;
      mGroupName = groupName;
      mMimeTypes = mimeTypes;
      mPromise = promise;
      mAssetType = assetType;
      mFromTime = fromTime;
      mToTime = toTime;
      mInclude = createSetFromIncludeArray(include);
    }

    private static Set<String> createSetFromIncludeArray(@Nullable ReadableArray includeArray) {
      Set<String> includeSet = new HashSet<>();

      if (includeArray == null) {
        return includeSet;
      }

      for (int i = 0; i < includeArray.size(); i++) {
        @Nullable String includeItem = includeArray.getString(i);
        if (includeItem != null) {
          includeSet.add(includeItem);
        }
      }

      return includeSet;
    }

    @Override
    protected void doInBackgroundGuarded(Void... params) {
      StringBuilder selection = new StringBuilder("1");
      List<String> selectionArgs = new ArrayList<>();
      boolean isLivePhotosOnly = mAssetType.equals(ASSET_TYPE_LIVE);
      if (!TextUtils.isEmpty(mGroupName)) {
        selection.append(" AND " + SELECTION_BUCKET);
        selectionArgs.add(mGroupName);
      }

      if (mAssetType.equals(ASSET_TYPE_PHOTOS) || isLivePhotosOnly) {
        selection.append(" AND " + MediaStore.Files.FileColumns.MEDIA_TYPE + " = "
                + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE);
      } else if (mAssetType.equals(ASSET_TYPE_VIDEOS)) {
        selection.append(" AND " + MediaStore.Files.FileColumns.MEDIA_TYPE + " = "
                + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO);
      } else if (mAssetType.equals(ASSET_TYPE_ALL)) {
        selection.append(" AND " + MediaStore.Files.FileColumns.MEDIA_TYPE + " IN ("
                + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO + ","
                + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE + ")");
      } else {
        mPromise.reject(
                ERROR_UNABLE_TO_FILTER,
                "Invalid filter option: '" + mAssetType + "'. Expected one of '"
                        + ASSET_TYPE_PHOTOS + "', '" + ASSET_TYPE_VIDEOS + "', '"
                        + ASSET_TYPE_LIVE + "' or '" + ASSET_TYPE_ALL + "'."
        );
        return;
      }


      if (mMimeTypes != null && mMimeTypes.size() > 0) {
        selection.append(" AND " + Images.Media.MIME_TYPE + " IN (");
        for (int i = 0; i < mMimeTypes.size(); i++) {
          selection.append("?,");
          selectionArgs.add(mMimeTypes.getString(i));
        }
        selection.replace(selection.length() - 1, selection.length(), ")");
      }

      if (mFromTime > 0) {
        long addedDate = mFromTime / 1000;
        selection.append(" AND (" + Images.Media.DATE_TAKEN + " > ? OR ( " + Images.Media.DATE_TAKEN
                + " IS NULL AND " + Images.Media.DATE_ADDED + "> ? ))");
        selectionArgs.add(mFromTime + "");
        selectionArgs.add(addedDate + "");
      }
      if (mToTime > 0) {
        long addedDate = mToTime / 1000;
        selection.append(" AND (" + Images.Media.DATE_TAKEN + " <= ? OR ( " + Images.Media.DATE_TAKEN
                + " IS NULL AND " + Images.Media.DATE_ADDED + " <= ? ))");
        selectionArgs.add(mToTime + "");
        selectionArgs.add(addedDate + "");
      }

      WritableMap response = new WritableNativeMap();
      ContentResolver resolver = mContext.getContentResolver();

      try {
        Cursor media;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          Bundle bundle = new Bundle();
          bundle.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection.toString());
          bundle.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                  selectionArgs.toArray(new String[selectionArgs.size()]));
          bundle.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, Images.Media.DATE_ADDED + " DESC, " + Images.Media.DATE_MODIFIED + " DESC");
          if (!isLivePhotosOnly) {
            bundle.putInt(ContentResolver.QUERY_ARG_LIMIT, mFirst + 1);
          }
          if (!isLivePhotosOnly && !TextUtils.isEmpty(mAfter)) {
            bundle.putInt(ContentResolver.QUERY_ARG_OFFSET, Integer.parseInt(mAfter));
          }
          media = resolver.query(
                  MediaStore.Files.getContentUri("external"),
                  PROJECTION,
                  bundle,
                  null);
        } else {
          // set LIMIT to first + 1 so that we know how to populate page_info
          Uri mediaStoreUri = MediaStore.Files.getContentUri("external");
          if (!isLivePhotosOnly) {
            String limit = "limit=" + (mFirst + 1);
            if (!TextUtils.isEmpty(mAfter)) {
              limit = "limit=" + mAfter + "," + (mFirst + 1);
            }
            mediaStoreUri = mediaStoreUri.buildUpon().encodedQuery(limit).build();
          }
          media = resolver.query(
                  mediaStoreUri,
                  PROJECTION,
                  selection.toString(),
                  selectionArgs.toArray(new String[selectionArgs.size()]),
                  Images.Media.DATE_ADDED + " DESC, " + Images.Media.DATE_MODIFIED + " DESC");
        }

        if (media == null) {
          mPromise.reject(ERROR_UNABLE_TO_LOAD, "Could not get media");
        } else {
          try {
            if (isLivePhotosOnly) {
              int offset = !TextUtils.isEmpty(mAfter) ? Integer.parseInt(mAfter) : 0;
              putLivePhotoEdges(resolver, media, response, mFirst, mInclude, offset);
            } else {
              putEdges(resolver, media, response, mFirst, mInclude);
              putPageInfo(media, response, mFirst, !TextUtils.isEmpty(mAfter) ? Integer.parseInt(mAfter) : 0);
            }
          } finally {
            media.close();
            mPromise.resolve(response);
          }
        }
      } catch (SecurityException e) {
        mPromise.reject(
                ERROR_UNABLE_TO_LOAD_PERMISSION,
                "Could not get media: need READ_EXTERNAL_STORAGE permission",
                e);
      }
    }
  }

  @ReactMethod
  public void getAlbums(final ReadableMap params, final Promise promise) {
    String assetType = params.hasKey("assetType") ? params.getString("assetType") : ASSET_TYPE_ALL;
    StringBuilder selection = new StringBuilder("1");
    List<String> selectionArgs = new ArrayList<>();
    if (assetType.equals(ASSET_TYPE_PHOTOS)) {
      selection.append(" AND " + MediaStore.Files.FileColumns.MEDIA_TYPE + " = "
              + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE);
    } else if (assetType.equals(ASSET_TYPE_VIDEOS)) {
      selection.append(" AND " + MediaStore.Files.FileColumns.MEDIA_TYPE + " = "
              + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO);
    } else if (assetType.equals(ASSET_TYPE_ALL)) {
      selection.append(" AND " + MediaStore.Files.FileColumns.MEDIA_TYPE + " IN ("
              + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO + ","
              + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE + ")");
    } else {
      promise.reject(
              ERROR_UNABLE_TO_FILTER,
              "Invalid filter option: '" + assetType + "'. Expected one of '"
                      + ASSET_TYPE_PHOTOS + "', '" + ASSET_TYPE_VIDEOS + "' or '" + ASSET_TYPE_ALL + "'."
      );
      return;
    }

    final String[] projection = {MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME};

    try {
      Cursor media = getReactApplicationContext().getContentResolver().query(
              MediaStore.Files.getContentUri("external"),
              projection,
              selection.toString(),
              selectionArgs.toArray(new String[selectionArgs.size()]),
              null);
      if (media == null) {
        promise.reject(ERROR_UNABLE_TO_LOAD, "Could not get media");
      } else {
        WritableArray response = new WritableNativeArray();
        try {
          if (media.moveToFirst()) {
            Map<String, Integer> albums = new HashMap<>();
            do {
              int column = media.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME);
              if (column < 0) {
                throw new IndexOutOfBoundsException();
              }
              String albumName = media.getString(column);
              if (albumName != null) {
                Integer albumCount = albums.get(albumName);
                if (albumCount == null) {
                  albums.put(albumName, 1);
                } else {
                  albums.put(albumName, albumCount + 1);
                }
              }
            } while (media.moveToNext());

            for (Map.Entry<String, Integer> albumEntry : albums.entrySet()) {
              WritableMap album = new WritableNativeMap();
              album.putString("title", albumEntry.getKey());
              album.putInt("count", albumEntry.getValue());
              response.pushMap(album);
            }
          }
        } finally {
          media.close();
          promise.resolve(response);
        }
      }
    } catch (Exception e) {
      promise.reject(ERROR_UNABLE_TO_LOAD, "Could not get media", e);
    }
  }

  private static void putPageInfo(Cursor media, WritableMap response, int limit, int offset) {
    putPageInfo(
            response,
            limit < media.getCount(),
            limit < media.getCount() ? Integer.toString(offset + limit) : null
    );
  }

  private static void putPageInfo(
          WritableMap response,
          boolean hasNextPage,
          @Nullable String endCursor) {
    WritableMap pageInfo = new WritableNativeMap();
    pageInfo.putBoolean("has_next_page", hasNextPage);
    if (hasNextPage && endCursor != null) {
      pageInfo.putString("end_cursor", endCursor);
    }
    response.putMap("page_info", pageInfo);
  }

  private static void putEdges(
          ContentResolver resolver,
          Cursor media,
          WritableMap response,
          int limit,
          Set<String> include) {
    WritableArray edges = new WritableNativeArray();
    media.moveToFirst();
    int idIndex = media.getColumnIndex(Images.Media._ID);
    int mimeTypeIndex = media.getColumnIndex(Images.Media.MIME_TYPE);
    int groupNameIndex = media.getColumnIndex(Images.Media.BUCKET_DISPLAY_NAME);
    int dateTakenIndex = media.getColumnIndex(Images.Media.DATE_TAKEN);
    int dateAddedIndex = media.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED);
    int dateModifiedIndex = media.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
    int widthIndex = media.getColumnIndex(MediaStore.MediaColumns.WIDTH);
    int heightIndex = media.getColumnIndex(MediaStore.MediaColumns.HEIGHT);
    int sizeIndex = media.getColumnIndex(MediaStore.MediaColumns.SIZE);
    int dataIndex = media.getColumnIndex(MediaStore.MediaColumns.DATA);
    int orientationIndex = media.getColumnIndex(MediaStore.MediaColumns.ORIENTATION);

    boolean includeLocation = include.contains(INCLUDE_LOCATION);
    boolean includeFilename = include.contains(INCLUDE_FILENAME);
    boolean includeFileSize = include.contains(INCLUDE_FILE_SIZE);
    boolean includeFileExtension = include.contains(INCLUDE_FILE_EXTENSION);
    boolean includeImageSize = include.contains(INCLUDE_IMAGE_SIZE);
    boolean includePlayableDuration = include.contains(INCLUDE_PLAYABLE_DURATION);
    boolean includeOrientation = include.contains(INCLUDE_ORIENTATION);
    boolean includeAlbums = include.contains(INCLUDE_ALBUMS);

    for (int i = 0; i < limit && !media.isAfterLast(); i++) {
      String currentPath = media.getString(dataIndex);
      String currentMime = media.getString(mimeTypeIndex);

      if (isVivoCompanionVideo(currentPath, currentMime)) {
        i--;
        media.moveToNext();
        continue;
      }

      WritableMap edge = new WritableNativeMap();
      WritableMap node = new WritableNativeMap();
      boolean imageInfoSuccess =
              putImageInfo(resolver, media, node, widthIndex, heightIndex, sizeIndex, dataIndex, orientationIndex,
                      mimeTypeIndex, includeFilename, includeFileSize, includeFileExtension, includeImageSize,
                      includePlayableDuration, includeOrientation);
      if (imageInfoSuccess) {
        boolean isLivePhoto = isMotionPhotoAsset(currentPath, currentMime);
        putBasicNodeInfo(media, node, idIndex, mimeTypeIndex, groupNameIndex, dateTakenIndex, dateAddedIndex, dateModifiedIndex, includeAlbums, isLivePhoto);
        putLocationInfo(media, node, dataIndex, includeLocation, mimeTypeIndex, resolver);

        edge.putMap("node", node);
        edges.pushMap(edge);
      } else {
        i--;
      }
      media.moveToNext();
    }
    response.putArray("edges", edges);
  }

  private static void putLivePhotoEdges(
          ContentResolver resolver,
          Cursor media,
          WritableMap response,
          int limit,
          Set<String> include,
          int offset) {
    WritableArray edges = new WritableNativeArray();
    int idIndex = media.getColumnIndex(Images.Media._ID);
    int mimeTypeIndex = media.getColumnIndex(Images.Media.MIME_TYPE);
    int groupNameIndex = media.getColumnIndex(Images.Media.BUCKET_DISPLAY_NAME);
    int dateTakenIndex = media.getColumnIndex(Images.Media.DATE_TAKEN);
    int dateAddedIndex = media.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED);
    int dateModifiedIndex = media.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
    int widthIndex = media.getColumnIndex(MediaStore.MediaColumns.WIDTH);
    int heightIndex = media.getColumnIndex(MediaStore.MediaColumns.HEIGHT);
    int sizeIndex = media.getColumnIndex(MediaStore.MediaColumns.SIZE);
    int dataIndex = media.getColumnIndex(MediaStore.MediaColumns.DATA);
    int orientationIndex = media.getColumnIndex(MediaStore.MediaColumns.ORIENTATION);

    if (!media.moveToPosition(offset)) {
      response.putArray("edges", edges);
      putPageInfo(response, false, null);
      return;
    }

    boolean hasNextPage = false;
    @Nullable String endCursor = null;
    int collected = 0;

    do {
      if (isMotionPhotoAsset(media.getString(dataIndex), media.getString(mimeTypeIndex))) {
        if (collected == limit) {
          hasNextPage = true;
          endCursor = Integer.toString(media.getPosition());
          break;
        }

        WritableMap edge = new WritableNativeMap();
        WritableMap node = new WritableNativeMap();
        boolean imageInfoSuccess =
                putImageInfo(resolver, media, node, widthIndex, heightIndex, sizeIndex, dataIndex, orientationIndex,
                        mimeTypeIndex, include.contains(INCLUDE_FILENAME), include.contains(INCLUDE_FILE_SIZE),
                        include.contains(INCLUDE_FILE_EXTENSION), include.contains(INCLUDE_IMAGE_SIZE),
                        include.contains(INCLUDE_PLAYABLE_DURATION), include.contains(INCLUDE_ORIENTATION));
        if (imageInfoSuccess) {
          putBasicNodeInfo(media, node, idIndex, mimeTypeIndex, groupNameIndex, dateTakenIndex, dateAddedIndex,
                  dateModifiedIndex, include.contains(INCLUDE_ALBUMS), true);
          putLocationInfo(media, node, dataIndex, include.contains(INCLUDE_LOCATION), mimeTypeIndex, resolver);

          edge.putMap("node", node);
          edges.pushMap(edge);
          collected++;
        }
      }
    } while (media.moveToNext());

    response.putArray("edges", edges);
    putPageInfo(response, hasNextPage, endCursor);
  }

  private static void putBasicNodeInfo(
          Cursor media,
          WritableMap node,
          int idIndex,
          int mimeTypeIndex,
          int groupNameIndex,
          int dateTakenIndex,
          int dateAddedIndex,
          int dateModifiedIndex,
          boolean includeAlbums,
          boolean isLivePhoto) {
    node.putString("id", Long.toString(media.getLong(idIndex)));
    node.putString("type", media.getString(mimeTypeIndex));
    WritableArray subTypes = Arguments.createArray();
    if (isLivePhoto) {
      // Align with iOS: Motion Photos are surfaced as "PhotoLive" subtype so JS
      // consumers can detect live/motion assets uniformly across platforms.
      subTypes.pushString("PhotoLive");
    }
    node.putArray("subTypes", subTypes);
    WritableArray group_name = Arguments.createArray();
    if (includeAlbums) {
      group_name.pushString(media.getString(groupNameIndex));
    }
    node.putArray("group_name", group_name);
    long dateTaken = media.getLong(dateTakenIndex);
    if (dateTaken == 0L) {
      //date added is in seconds, date taken in milliseconds, thus the multiplication
      dateTaken = media.getLong(dateAddedIndex) * 1000;
    }
    node.putDouble("timestamp", dateTaken / 1000d);
    node.putDouble("modificationTimestamp", media.getLong(dateModifiedIndex));
  }

  /**
   * @return Whether we successfully fetched all the information about the image that we were asked
   * to include
   */
  private static boolean putImageInfo(
          ContentResolver resolver,
          Cursor media,
          WritableMap node,
          int widthIndex,
          int heightIndex,
          int sizeIndex,
          int dataIndex,
          int orientationIndex,
          int mimeTypeIndex,
          boolean includeFilename,
          boolean includeFileSize,
          boolean includeFileExtension,
          boolean includeImageSize,
          boolean includePlayableDuration,
          boolean includeOrientation) {
    WritableMap image = new WritableNativeMap();
    String filePath = media.getString(dataIndex);
    Uri photoUri = Uri.parse("file://" + filePath);
    image.putString("uri", photoUri.toString());
    String mimeType = media.getString(mimeTypeIndex);

    boolean isVideo = mimeType != null && mimeType.startsWith("video");
    boolean putImageSizeSuccess = putImageSize(resolver, media, image, widthIndex, heightIndex, orientationIndex,
            photoUri, isVideo, includeImageSize);
    boolean putPlayableDurationSuccess = putPlayableDuration(resolver, image, photoUri, isVideo,
            includePlayableDuration);

    if (includeFilename) {
      File file = new File(media.getString(dataIndex));
      String strFileName = file.getName();
      image.putString("filename", strFileName);
    } else {
      image.putNull("filename");
    }

    if (includeFileSize) {
      image.putDouble("fileSize", media.getLong(sizeIndex));
    } else {
      image.putNull("fileSize");
    }

    if (includeFileExtension) {
      image.putString("extension", Utils.getExtension(mimeType));
    } else {
      image.putNull("extension");
    }

    if (includeOrientation) {
      if(media.isNull(orientationIndex)) {
        image.putInt("orientation", media.getInt(orientationIndex));
      } else {
        image.putInt("orientation", 0);
      }
    } else {
      image.putNull("orientation");
    }

    node.putMap("image", image);
    return putImageSizeSuccess && putPlayableDurationSuccess;
  }

  @ReactMethod
  public void getPhotoVideoURI(String internalID, Promise promise) {
    String filePath = resolveMediaPathFromInternalID(getReactApplicationContext(), internalID);
    if (filePath == null) {
      promise.reject(ERROR_UNABLE_TO_LOAD, "Could not find media for identifier: " + internalID);
      return;
    }

    String mimeType = Utils.getMimeType(filePath);
    @Nullable String liveVideoUri = extractMotionPhotoVideoUri(
            getReactApplicationContext(),
            filePath,
            mimeType);

    WritableMap response = new WritableNativeMap();
    if (liveVideoUri != null) {
      response.putString("liveVideoUri", liveVideoUri);
    } else {
      response.putNull("liveVideoUri");
    }
    promise.resolve(response);
  }

  @Nullable
  private static String resolveMediaPathFromInternalID(Context context, String internalID) {
    if (TextUtils.isEmpty(internalID)) {
      return null;
    }

    if (internalID.startsWith("file://")) {
      return Uri.parse(internalID).getPath();
    }

    File directFile = new File(internalID);
    if (directFile.exists()) {
      return directFile.getAbsolutePath();
    }

    ContentResolver resolver = context.getContentResolver();
    Cursor cursor = null;
    try {
      cursor = resolver.query(
              MediaStore.Files.getContentUri("external"),
              new String[]{MediaStore.MediaColumns.DATA},
              MediaStore.Files.FileColumns._ID + " = ?",
              new String[]{internalID},
              null);
      if (cursor != null && cursor.moveToFirst()) {
        int dataIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
        if (dataIndex >= 0) {
          return cursor.getString(dataIndex);
        }
      }
    } catch (SecurityException e) {
      FLog.w(ReactConstants.TAG, "Could not resolve media path for identifier " + internalID, e);
    } finally {
      if (cursor != null) {
        cursor.close();
      }
    }

    return null;
  }

  @Nullable
  private static String extractMotionPhotoVideoUri(
          Context context,
          String filePath,
          @Nullable String mimeType) {
    if (mimeType == null || !mimeType.startsWith("image")) {
      return null;
    }

    File sourceFile = new File(filePath);
    if (!sourceFile.exists() || !sourceFile.isFile()) {
      return null;
    }

    if (hasVivoLivePhotoTrailer(sourceFile)) {
      File vivoMp4 = findVivoCompanionVideo(sourceFile);
      if (vivoMp4 != null) {
        return Uri.fromFile(vivoMp4).toString();
      }
    }

    boolean hasHuaweiTrailer = hasHuaweiLivePhotoTrailer(sourceFile);

    String metadata = readMotionPhotoMetadata(sourceFile);
    if (!hasHuaweiTrailer && !containsMotionPhotoMarker(metadata)) {
      return null;
    }

    File cacheDir = new File(context.getCacheDir(), MOTION_PHOTO_CACHE_DIR);
    if (!cacheDir.exists() && !cacheDir.mkdirs()) {
      return null;
    }
    File outputFile = buildMotionPhotoCacheFile(cacheDir, sourceFile);

    if (hasHuaweiTrailer) {
      return extractHuaweiMotionPhotoVideo(sourceFile, outputFile);
    } else {
      return extractStandardMotionPhotoVideo(sourceFile, metadata, outputFile);
    }
  }

  /** Standard path: XMP-based offset, read to file end. */
  @Nullable
  private static String extractStandardMotionPhotoVideo(
          File sourceFile, String metadata, File outputFile) {
    long videoStartOffset = resolveMotionPhotoVideoStartOffset(sourceFile, metadata);
    if (videoStartOffset <= 0 || videoStartOffset >= sourceFile.length()) {
      return null;
    }
    long expectedLength = sourceFile.length() - videoStartOffset;
    if (outputFile.exists() && outputFile.length() == expectedLength) {
      return Uri.fromFile(outputFile).toString();
    }
    if (!writeMotionPhotoVideoFile(sourceFile, outputFile, videoStartOffset)) {
      return null;
    }
    return Uri.fromFile(outputFile).toString();
  }

  /** Huawei path: trailer-based offset, exclude 60-byte trailer. */
  @Nullable
  private static String extractHuaweiMotionPhotoVideo(
          File sourceFile, File outputFile) {
    long liveValue = parseHuaweiTrailerLiveValue(sourceFile);
    long videoEndOffset = sourceFile.length() - HUAWEI_LIVE_PHOTO_TRAILER_SIZE;
    long videoStartOffset;
    if (liveValue > 0) {
      long mdatApprox = sourceFile.length() - liveValue;
      videoStartOffset = findFtypNear(sourceFile, mdatApprox);
    } else {
      videoStartOffset = findEmbeddedMp4StartOffset(sourceFile);
    }
    if (videoStartOffset <= 0 || videoStartOffset >= sourceFile.length()) {
      return null;
    }
    long expectedLength = videoEndOffset - videoStartOffset;
    if (outputFile.exists() && outputFile.length() == expectedLength) {
      return Uri.fromFile(outputFile).toString();
    }
    if (!writeHuaweiMotionPhotoVideoFile(sourceFile, outputFile, videoStartOffset, videoEndOffset)) {
      return null;
    }
    return Uri.fromFile(outputFile).toString();
  }

  private static boolean isMotionPhotoAsset(
          @Nullable String filePath,
          @Nullable String mimeType) {
    if (mimeType == null || !mimeType.startsWith("image") || TextUtils.isEmpty(filePath)) {
      return false;
    }

    File sourceFile = new File(filePath);
    if (!sourceFile.exists() || !sourceFile.isFile()) {
      return false;
    }

    if (hasHuaweiLivePhotoTrailer(sourceFile)) {
      return true;
    }

    if (hasVivoLivePhotoTrailer(sourceFile) && findVivoCompanionVideo(sourceFile) != null) {
      return true;
    }

    String metadata = readMotionPhotoMetadata(sourceFile);
    if (!containsMotionPhotoMarker(metadata)) {
      return false;
    }

    long videoStartOffset = resolveMotionPhotoVideoStartOffset(sourceFile, metadata);
    return videoStartOffset > 0 && videoStartOffset < sourceFile.length();
  }

  private static boolean containsMotionPhotoMarker(String metadata) {
    return metadata.contains("MotionPhoto")
            || metadata.contains("MicroVideo")
            || metadata.contains("MotionPhoto_Data");
  }

  /**
   * Parse the LIVE_ value from a Huawei trailer, or return -1.
   * The value represents the distance from mdat start to file end.
   */
  private static long parseHuaweiTrailerLiveValue(File file) {
    if (file.length() < HUAWEI_LIVE_PHOTO_TRAILER_SIZE) return -1;
    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
      raf.seek(file.length() - HUAWEI_LIVE_PHOTO_TRAILER_SIZE);
      byte[] tail = new byte[HUAWEI_LIVE_PHOTO_TRAILER_SIZE];
      raf.readFully(tail);
      String trailer = new String(tail, StandardCharsets.US_ASCII);
      int idx = trailer.indexOf("LIVE_");
      if (idx < 0) return -1;
      String numStr = trailer.substring(idx + 5).trim();
      return Long.parseLong(numStr);
    } catch (Exception e) {
      return -1;
    }
  }

  /**
   * Scan for a JPEG ftyp box in the region before {@code mdatApprox}.
   * Searches up to 4KB before the mdat position to find ftyp+free/etc.
   */
  private static long findFtypNear(File file, long mdatApprox) {
    long searchStart = Math.max(0, mdatApprox - 4096);
    int searchLen = (int) (mdatApprox - searchStart + 8);
    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
      raf.seek(searchStart);
      byte[] buf = new byte[searchLen];
      int read = raf.read(buf);
      if (read <= 8) return -1;
      for (int i = 0; i < read - 7; i++) {
        if (buf[i] == 'f' && buf[i+1] == 't' && buf[i+2] == 'y' && buf[i+3] == 'p'
                && isLikelyMp4Brand(buf, i + 4)) {
          return searchStart + i - 4;
        }
      }
    } catch (IOException e) {
      FLog.w(ReactConstants.TAG, "Could not scan for ftyp near mdat", e);
    }
    return -1;
  }

  /**
   * Check whether the file ends with a Huawei/Honor 60-byte live photo
   * trailer. Known versions: {@code v2_f...} and {@code v3_f...}.
   */
  private static boolean hasHuaweiLivePhotoTrailer(File file) {
    if (file.length() < HUAWEI_LIVE_PHOTO_TRAILER_SIZE) {
      return false;
    }
    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
      raf.seek(file.length() - HUAWEI_LIVE_PHOTO_TRAILER_SIZE);
      byte[] tail = new byte[HUAWEI_LIVE_PHOTO_TRAILER_SIZE];
      raf.readFully(tail);
      String trailer = new String(tail, StandardCharsets.US_ASCII);
      return trailer.contains("LIVE_")
              && trailer.length() >= 4
              && trailer.charAt(0) == 'v'
              && Character.isDigit(trailer.charAt(1))
              && trailer.charAt(2) == '_'
              && trailer.charAt(3) == 'f';
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Check whether a JPEG has a vivo live-photo trailer at the end.
   * The trailer starts with {@code "vivo{"} and contains a JSON object
   * with a non-empty {@code "com.android.camera.livephoto"} field.
   */
  private static boolean hasVivoLivePhotoTrailer(File file) {
    long fileLen = file.length();
    if (fileLen < 32) return false;
    int scanLen = (int) Math.min(fileLen, VIVO_LIVE_PHOTO_TAIL_SCAN_BYTES);
    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
      raf.seek(fileLen - scanLen);
      byte[] tail = new byte[scanLen];
      int read = raf.read(tail);
      if (read <= 0) return false;
      String tailStr = new String(tail, 0, read, StandardCharsets.UTF_8);
      int vivoIdx = tailStr.indexOf("vivo{");
      if (vivoIdx < 0) return false;
      int jsonStart = vivoIdx + 4;
      int jsonEnd = tailStr.indexOf('}', jsonStart);
      if (jsonEnd < 0) return false;
      String json = tailStr.substring(jsonStart, jsonEnd + 1);
      return json.contains("\"com.android.camera.livephoto\"")
              && !json.contains("\"com.android.camera.livephoto\":\"\"");
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Locate the companion MP4 video for a vivo live-photo JPEG.
   * vivo stores the video as a separate file with the same base name.
   */
  @Nullable
  private static File findVivoCompanionVideo(File jpgFile) {
    String name = jpgFile.getName();
    int dot = name.lastIndexOf('.');
    if (dot <= 0) return null;
    String baseName = name.substring(0, dot);
    File mp4 = new File(jpgFile.getParent(), baseName + ".mp4");
    if (mp4.exists() && mp4.isFile()) return mp4;
    File mp4Upper = new File(jpgFile.getParent(), baseName + ".MP4");
    if (mp4Upper.exists() && mp4Upper.isFile()) return mp4Upper;
    return null;
  }

  /**
   * Return {@code true} if this video file is a vivo companion MP4 that
   * should be hidden from results (the live photo JPEG is the primary asset).
   */
  private static boolean isVivoCompanionVideo(
          @Nullable String filePath, @Nullable String mimeType) {
    if (mimeType == null || !mimeType.startsWith("video") || TextUtils.isEmpty(filePath)) {
      return false;
    }
    File videoFile = new File(filePath);
    if (!videoFile.exists()) return false;
    String name = videoFile.getName();
    int dot = name.lastIndexOf('.');
    if (dot <= 0) return false;
    String baseName = name.substring(0, dot);
    File parent = videoFile.getParentFile();
    if (parent == null) return false;
    File jpgFile = new File(parent, baseName + ".jpg");
    if (!jpgFile.exists()) {
      jpgFile = new File(parent, baseName + ".JPG");
    }
    if (!jpgFile.exists()) return false;
    return hasVivoLivePhotoTrailer(jpgFile);
  }

  private static String readMotionPhotoMetadata(File sourceFile) {
    int bytesToRead = (int) Math.min(sourceFile.length(), MOTION_PHOTO_XMP_SCAN_BYTES);
    if (bytesToRead <= 0) {
      return "";
    }

    byte[] buffer = new byte[bytesToRead];
    try (FileInputStream inputStream = new FileInputStream(sourceFile)) {
      int bytesRead = inputStream.read(buffer);
      if (bytesRead <= 0) {
        return "";
      }
      return new String(buffer, 0, bytesRead, StandardCharsets.ISO_8859_1);
    } catch (IOException e) {
      FLog.w(ReactConstants.TAG, "Could not read Motion Photo metadata for " + sourceFile, e);
      return "";
    }
  }

  private static long resolveMotionPhotoVideoStartOffset(File sourceFile, String metadata) {
    long fileLength = sourceFile.length();

    long microVideoOffset = parseLongAttribute(metadata, MICRO_VIDEO_OFFSET_PATTERN);
    if (microVideoOffset > 0 && microVideoOffset < fileLength) {
      return fileLength - microVideoOffset;
    }

    long motionPhotoLength = parseLongAttribute(metadata, MOTION_PHOTO_LENGTH_PATTERN);
    if (motionPhotoLength <= 0) {
      motionPhotoLength = parseLongAttribute(metadata, MOTION_PHOTO_LENGTH_PATTERN_REVERSED);
    }
    if (motionPhotoLength > 0 && motionPhotoLength < fileLength) {
      return fileLength - motionPhotoLength;
    }

    return findEmbeddedMp4StartOffset(sourceFile);
  }

  private static long parseLongAttribute(String value, Pattern pattern) {
    Matcher matcher = pattern.matcher(value);
    if (!matcher.find()) {
      return -1;
    }

    try {
      return Long.parseLong(matcher.group(1));
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static long findEmbeddedMp4StartOffset(File sourceFile) {
    long fileLength = sourceFile.length();
    long bytesToScan = Math.min(fileLength, MOTION_PHOTO_VIDEO_SCAN_BYTES);
    if (bytesToScan <= 8) {
      return -1;
    }

    byte[] buffer = new byte[(int) bytesToScan];
    long scanStart = fileLength - bytesToScan;

    try (RandomAccessFile randomAccessFile = new RandomAccessFile(sourceFile, "r")) {
      randomAccessFile.seek(scanStart);
      randomAccessFile.readFully(buffer);
    } catch (IOException e) {
      FLog.w(ReactConstants.TAG, "Could not scan Motion Photo video payload for " + sourceFile, e);
      return -1;
    }

    for (int i = buffer.length - 8; i >= 4; i--) {
      if (buffer[i] == 'f'
              && buffer[i + 1] == 't'
              && buffer[i + 2] == 'y'
              && buffer[i + 3] == 'p'
              && isLikelyMp4Brand(buffer, i + 4)) {
        return scanStart + i - 4L;
      }
    }

    return -1;
  }

  private static boolean isLikelyMp4Brand(byte[] buffer, int brandOffset) {
    if (brandOffset + 4 > buffer.length) {
      return false;
    }

    String brand = new String(buffer, brandOffset, 4, StandardCharsets.US_ASCII);
    return "mp4 ".equals(brand)
            || "mp41".equals(brand)
            || "mp42".equals(brand)
            || "isom".equals(brand)
            || "iso6".equals(brand)
            || "M4V ".equals(brand);
  }

  private static File buildMotionPhotoCacheFile(File cacheDir, File sourceFile) {
    String fileName = sourceFile.getName();
    int extensionIndex = fileName.lastIndexOf('.');
    String baseName = extensionIndex >= 0 ? fileName.substring(0, extensionIndex) : fileName;
    String safeBaseName = baseName.replaceAll("[^a-zA-Z0-9._-]", "_");
    String cacheName = safeBaseName
            + "-"
            + sourceFile.length()
            + "-"
            + sourceFile.lastModified()
            + ".mp4";
    return new File(cacheDir, cacheName);
  }

  /** Standard extraction: read from startOffset to end of file. */
  private static boolean writeMotionPhotoVideoFile(
          File sourceFile, File outputFile, long startOffset) {
    File tempFile = new File(outputFile.getAbsolutePath() + ".tmp");
    if (tempFile.exists() && !tempFile.delete()) {
      return false;
    }

    byte[] buffer = new byte[16 * 1024];
    try (RandomAccessFile inputFile = new RandomAccessFile(sourceFile, "r");
         FileOutputStream outputStream = new FileOutputStream(tempFile)) {
      inputFile.seek(startOffset);

      int bytesRead;
      while ((bytesRead = inputFile.read(buffer)) != -1) {
        outputStream.write(buffer, 0, bytesRead);
      }
      outputStream.flush();
    } catch (IOException e) {
      FLog.w(ReactConstants.TAG, "Could not export Motion Photo video for " + sourceFile, e);
      tempFile.delete();
      return false;
    }

    if (outputFile.exists() && !outputFile.delete()) {
      tempFile.delete();
      return false;
    }

    if (!tempFile.renameTo(outputFile)) {
      tempFile.delete();
      return false;
    }

    return true;
  }

  /** Huawei extraction: read from startOffset to endOffset (excluding trailer). */
  private static boolean writeHuaweiMotionPhotoVideoFile(
          File sourceFile, File outputFile, long startOffset, long endOffset) {
    File tempFile = new File(outputFile.getAbsolutePath() + ".tmp");
    if (tempFile.exists() && !tempFile.delete()) {
      return false;
    }

    byte[] buffer = new byte[16 * 1024];
    long remaining = endOffset - startOffset;
    try (RandomAccessFile inputFile = new RandomAccessFile(sourceFile, "r");
         FileOutputStream outputStream = new FileOutputStream(tempFile)) {
      inputFile.seek(startOffset);

      int bytesRead;
      while (remaining > 0 && (bytesRead = inputFile.read(buffer, 0,
              (int) Math.min(buffer.length, remaining))) != -1) {
        outputStream.write(buffer, 0, bytesRead);
        remaining -= bytesRead;
      }
      outputStream.flush();
    } catch (IOException e) {
      FLog.w(ReactConstants.TAG, "Could not export Huawei Motion Photo video for " + sourceFile, e);
      tempFile.delete();
      return false;
    }

    if (outputFile.exists() && !outputFile.delete()) {
      tempFile.delete();
      return false;
    }

    if (!tempFile.renameTo(outputFile)) {
      tempFile.delete();
      return false;
    }

    return true;
  }

  /**
   * @return Whether we succeeded in fetching and putting the playableDuration
   */
  private static boolean putPlayableDuration(
          ContentResolver resolver,
          WritableMap image,
          Uri photoUri,
          boolean isVideo,
          boolean includePlayableDuration) {
    image.putNull("playableDuration");

    if (!includePlayableDuration || !isVideo) {
      return true;
    }

    boolean success = true;
    @Nullable Integer playableDuration = null;
    @Nullable AssetFileDescriptor photoDescriptor = null;
    try {
      photoDescriptor = resolver.openAssetFileDescriptor(photoUri, "r");
    } catch (FileNotFoundException e) {
      success = false;
      FLog.e(ReactConstants.TAG, "Could not open asset file " + photoUri.toString(), e);
    }

    if (photoDescriptor != null) {
      MediaMetadataRetriever retriever = new MediaMetadataRetriever();
      try {
        retriever.setDataSource(photoDescriptor.getFileDescriptor());
      } catch (RuntimeException e) {
        // Do nothing. We can't handle this, and this is usually a system problem
      }
      try {
        int timeInMillisecond = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        playableDuration = timeInMillisecond / 1000;
      } catch (NumberFormatException e) {
        success = false;
        FLog.e(
                ReactConstants.TAG,
                "Number format exception occurred while trying to fetch video metadata for "
                        + photoUri.toString(),
                e);
      }
      try {
        retriever.release();
      } catch (Exception e) { // Use general Exception here, see: https://developer.android.com/reference/android/media/MediaMetadataRetriever#release()
        // Do nothing. We can't handle this, and this is usually a system problem
      }
    }

    if (photoDescriptor != null) {
      try {
        photoDescriptor.close();
      } catch (IOException e) {
        // Do nothing. We can't handle this, and this is usually a system problem
      }
    }

    if (playableDuration != null) {
      image.putInt("playableDuration", playableDuration);
    }

    return success;
  }

  private static boolean putImageSize(
          ContentResolver resolver,
          Cursor media,
          WritableMap image,
          int widthIndex,
          int heightIndex,
          int orientationIndex,
          Uri photoUri,
          boolean isVideo,
          boolean includeImageSize) {
    image.putNull("width");
    image.putNull("height");

    if (!includeImageSize) {
      return true;
    }

    boolean success = true;

    int width = media.getInt(widthIndex);
    int height = media.getInt(heightIndex);

    /* If the columns don't contain the size information, read the media file */
    if (width <= 0 || height <= 0) {
      @Nullable AssetFileDescriptor mediaDescriptor = null;
      try {
        mediaDescriptor = resolver.openAssetFileDescriptor(photoUri, "r");
      } catch (FileNotFoundException e) {
        success = false;
        FLog.e(ReactConstants.TAG, "Could not open asset file " + photoUri.toString(), e);
      }
      if (mediaDescriptor != null) {
        if (isVideo) {
          MediaMetadataRetriever retriever = new MediaMetadataRetriever();
          try {
            retriever.setDataSource(mediaDescriptor.getFileDescriptor());
          } catch (RuntimeException e) {
            // Do nothing. We can't handle this, and this is usually a system problem
          }
          try {
            width = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            height = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
          } catch (NumberFormatException e) {
            success = false;
            FLog.e(
                    ReactConstants.TAG,
                    "Number format exception occurred while trying to fetch video metadata for "
                            + photoUri.toString(),
                    e);
          }
          try {
            retriever.release();
          } catch (Exception e) { // Use general Exception here, see: https://developer.android.com/reference/android/media/MediaMetadataRetriever#release()
            // Do nothing. We can't handle this, and this is usually a system problem
          }
        } else {
          BitmapFactory.Options options = new BitmapFactory.Options();
          // Set inJustDecodeBounds to true so we don't actually load the Bitmap, but only get its
          // dimensions instead.
          options.inJustDecodeBounds = true;
          BitmapFactory.decodeFileDescriptor(mediaDescriptor.getFileDescriptor(), null, options);
          width = options.outWidth;
          height = options.outHeight;
        }

        try {
          mediaDescriptor.close();
        } catch (IOException e) {
          FLog.e(
                  ReactConstants.TAG,
                  "Can't close media descriptor "
                          + photoUri.toString(),
                  e);
        }
      }

    }

    if(!media.isNull(orientationIndex)) {
      int orientation = media.getInt(orientationIndex);
      if (orientation >= 0 && orientation % 180 != 0) {
        int temp = width;
        width = height;
        height = temp;
      }
    }

    image.putInt("width", width);
    image.putInt("height", height);
    return success;
  }

  private static void putLocationInfo(
          Cursor media,
          WritableMap node,
          int dataIndex,
          boolean includeLocation,
          int mimeTypeIndex,
          ContentResolver resolver) {
    node.putNull("location");

    if (!includeLocation) {
      return;
    }

    try {
      String mimeType = media.getString(mimeTypeIndex);
      boolean isVideo = mimeType != null && mimeType.startsWith("video");
      if(isVideo){
        Uri photoUri = Uri.parse("file://" + media.getString(dataIndex));
        @Nullable AssetFileDescriptor photoDescriptor = null;
        try {
          photoDescriptor = resolver.openAssetFileDescriptor(photoUri, "r");
        } catch (FileNotFoundException e) {
          FLog.e(ReactConstants.TAG, "Could not open asset file " + photoUri.toString(), e);
        }

        if (photoDescriptor != null) {
          MediaMetadataRetriever retriever = new MediaMetadataRetriever();
          try {
            retriever.setDataSource(photoDescriptor.getFileDescriptor());
          } catch (RuntimeException e) {
            // Do nothing. We can't handle this, and this is usually a system problem
          }
          try {
            String videoGeoTag = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
            if (videoGeoTag!=null){
              String filtered = videoGeoTag.replaceAll("/","");
              WritableMap location = new WritableNativeMap();
              location.putDouble("latitude", Double.parseDouble(filtered.split("[+]|[-]")[1]));
              location.putDouble("longitude", Double.parseDouble(filtered.split("[+]|[-]")[2]));
              node.putMap("location", location);
            }
          } catch (NumberFormatException e) {
            FLog.e(ReactConstants.TAG,"Number format exception occurred while trying to fetch video metadata for "+ photoUri.toString(),e);
          }
          try {
            retriever.release();
          } catch (Exception e) { // Use general Exception here, see: https://developer.android.com/reference/android/media/MediaMetadataRetriever#release()
            // Do nothing. We can't handle this, and this is usually a system problem
          }
        }
        if (photoDescriptor != null) {
          try {
            photoDescriptor.close();
          } catch (IOException e) {
            // Do nothing. We can't handle this, and this is usually a system problem
          }
        }
      }
      else{
        // location details are no longer indexed for privacy reasons using string Media.LATITUDE, Media.LONGITUDE
        // we manually obtain location metadata using ExifInterface#getLatLong(float[]).
        // ExifInterface is added in API level 5
        final ExifInterface exif = new ExifInterface(media.getString(dataIndex));
        float[] imageCoordinates = new float[2];
        boolean hasCoordinates = exif.getLatLong(imageCoordinates);
        if (hasCoordinates) {
          double longitude = imageCoordinates[1];
          double latitude = imageCoordinates[0];
          WritableMap location = new WritableNativeMap();
          location.putDouble("longitude", longitude);
          location.putDouble("latitude", latitude);
          node.putMap("location", location);
        }
      }
    } catch (IOException e) {
      FLog.e(ReactConstants.TAG, "Could not read the metadata", e);
    }
  }

  /**
   * Delete a set of images.
   *
   * @param uris    array of file:// URIs of the images to delete
   * @param promise to be resolved
   */
  @ReactMethod
  public void deletePhotos(ReadableArray uris, Promise promise) {
    if (uris.size() == 0) {
      promise.reject(ERROR_UNABLE_TO_DELETE, "Need at least one URI to delete");
    } else {
      new DeletePhotos(getReactApplicationContext(), uris, promise)
              .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
  }

  @ReactMethod
  public void getPhotoByInternalID(String internalID, ReadableMap options, Promise promise) {
    promise.reject("CameraRoll:getPhotoByInternalID", "getPhotoByInternalID is not supported on Android");
  }

  private static class DeletePhotos extends GuardedAsyncTask<Void, Void> {

    private final Context mContext;
    private final ReadableArray mUris;
    private final Promise mPromise;

    public DeletePhotos(ReactContext context, ReadableArray uris, Promise promise) {
      super(context);
      mContext = context;
      mUris = uris;
      mPromise = promise;
    }

    @Override
    protected void doInBackgroundGuarded(Void... params) {
      ContentResolver resolver = mContext.getContentResolver();

      // Set up the projection (we only need the ID)
      String[] projection = {MediaStore.Images.Media._ID};

      // Match on the file path
      String innerWhere = "?";
      for (int i = 1; i < mUris.size(); i++) {
        innerWhere += ", ?";
      }

      String selection = MediaStore.Images.Media.DATA + " IN (" + innerWhere + ")";
      // Query for the ID of the media matching the file path
      Uri queryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

      String[] selectionArgs = new String[mUris.size()];
      for (int i = 0; i < mUris.size(); i++) {
        Uri uri = Uri.parse(mUris.getString(i));
        selectionArgs[i] = uri.getPath();
      }

      Cursor cursor = resolver.query(queryUri, projection, selection, selectionArgs, null);
      int deletedCount = 0;

      while (cursor.moveToNext()) {
        long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID));
        Uri deleteUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);

        if (resolver.delete(deleteUri, null, null) == 1) {
          deletedCount++;
        }
      }

      cursor.close();

      if (deletedCount == mUris.size()) {
        mPromise.resolve(true);
      } else {
        mPromise.reject(ERROR_UNABLE_TO_DELETE,
                "Could not delete all media, only deleted " + deletedCount + " photos.");
      }
    }
  }

  @ReactMethod
  public void getPhotoThumbnail(String internalID, ReadableMap options, Promise promise) {
    promise.reject("CameraRoll:getPhotoThumbnail", "getPhotoThumbnail is not supported on Android");
  }
}
