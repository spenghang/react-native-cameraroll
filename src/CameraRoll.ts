/**
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import {Platform} from 'react-native';
import RNCCameraRoll from './NativeCameraRollModule';

const GROUP_TYPES_OPTIONS = {
  Album: 'Album',
  All: 'All', // default
  Event: 'Event',
  Faces: 'Faces',
  Library: 'Library',
  SmartAlbum: 'SmartAlbum',
  PhotoStream: 'PhotoStream',
  SavedPhotos: 'SavedPhotos',
};

const ASSET_TYPE_OPTIONS = {
  All: 'All',
  Videos: 'Videos',
  Photos: 'Photos',
};

const ALBUM_TYPE_OPTIONS = {
  All: 'All',
  Album: 'Album',
  SmartAlbum: 'SmartAlbum',
}

export type GroupTypes =
  | 'Album'
  | 'All'
  | 'Event'
  | 'Faces'
  | 'Library'
  | 'SmartAlbum'
  | 'PhotoStream'
  | 'SavedPhotos';

export type SubTypes =
  | 'PhotoPanorama'
  | 'PhotoHDR'
  | 'PhotoScreenshot'
  | 'PhotoLive'
  | 'PhotoDepthEffect'
  | 'VideoStreamed'
  | 'VideoHighFrameRate'
  | 'VideoTimelapse';

export type Include =
  | 'filename'
  | 'fileSize'
  | 'fileExtension'
  | 'location'
  | 'imageSize'
  | 'playableDuration'
  | 'orientation'
  | 'albums';

export type AssetType = 'All' | 'Videos' | 'Photos';
export type GetPhotosAssetType = AssetType | 'Live';

export type AlbumType = 'All' | 'Album' | 'SmartAlbum';

/**
 * Shape of the param arg for the `getPhotos` function.
 */
export type GetPhotosParams = {
  /**
   * The number of photos wanted in reverse order of the photo application
   * (i.e. most recent first).
   */
  first: number;

  /**
   * A cursor that matches `page_info { end_cursor }` returned from a previous
   * call to `getPhotos`
   */
  after?: string;

  /**
   * Specifies which group types to filter the results to.
   */
  groupTypes?: GroupTypes;

  /**
   * Specifies filter on group names, like 'Recent Photos' or custom album
   * titles.
   */
  groupName?: string;

  /**
   * Include assets originating from an iCloud Shared Album. iOS only.
   */
  includeSharedAlbums?: boolean;

  /**
   * Specifies filter on asset type. `Live` returns only Live Photos / Motion Photos.
   */
  assetType?: GetPhotosAssetType;

  /**
   * Detect Live Photos / Motion Photos in regular results. Android only.
   * Defaults to false.
   */
  detectLivePhoto?: boolean;

  /**
   * Earliest time to get photos from. A timestamp in milliseconds. Exclusive.
   */
  fromTime?: number;

  /**
   * Latest time to get photos from. A timestamp in milliseconds. Inclusive.
   */
  toTime?: number;

  /**
   * Filter by mimetype (e.g. image/jpeg).
   */
  mimeTypes?: Array<string>;

  /**
   * Specific fields in the output that we want to include, even though they
   * might have some performance impact.
   */
  include?: Include[];
};

export type PhotoIdentifier = {
  node: {
    id: string;
    type: string;
    subTypes: SubTypes[];
    group_name: string[];
    image: {
      filename: string | null;
      filepath: string | null;
      extension: string | null;
      uri: string;
      height: number;
      width: number;
      fileSize: number | null;
      playableDuration: number;
      orientation: number | null;
    };
    timestamp: number;
    modificationTimestamp: number;
    location: {
      latitude?: number;
      longitude?: number;
      altitude?: number;
      heading?: number;
      speed?: number;
    } | null;
  };
};

export type PhotoConvertionOptions = {
  convertHeicImages?: boolean;
  quality?: number
};

export type PhotoIdentifiersPage = {
  edges: Array<PhotoIdentifier>;
  page_info: {
    has_next_page: boolean;
    start_cursor?: string;
    end_cursor?: string;
  };
  limited?: boolean;
};

export type SaveToCameraRollOptions = {
  type?: 'photo' | 'video' | 'auto';
  album?: string;
  title?: string;
};

/**
 * Options for `CameraRoll.saveLivePhoto`.
 *
 * On iOS this produces a real Live Photo (image + paired video) by injecting
 * a shared content identifier into the image maker note and the video
 * QuickTime metadata.
 *
 * On Android this produces a Google/Samsung compatible Motion Photo
 * (JPEG + appended MP4 with XMP markers).
 */
export type SaveLivePhotoOptions = {
  /** Local URI of the cover image (file:// or absolute path). */
  imageUri: string;
  /** Local URI of the paired video (file:// or absolute path). */
  videoUri: string;
  /** Optional album name to save the Live Photo into. */
  album?: string;
  /** Optional output filename (without extension). Android only. */
  title?: string;
};

export type GetAlbumsParams = {
  assetType?: AssetType;
  albumType?: AlbumType;
};

export type AlbumSubType =
  | 'AlbumRegular'
  | 'AlbumSyncedEvent'
  | 'AlbumSyncedFaces'
  | 'AlbumSyncedAlbum'
  | 'AlbumImported'
  | 'AlbumMyPhotoStream'
  | 'AlbumCloudShared'
  | 'Unknown';

export type Album = {
  title: string;
  count: number;
  type: AlbumType;
  subtype?: AlbumSubType;
  isShared?: boolean;
};

export type ThumbnailSize = {
  height: number,
  width: number
};

export type PhotoThumbnailOptions = {
  allowNetworkAccess: boolean,  //iOS only
  targetSize: ThumbnailSize,
  quality: number
};

export type PhotoThumbnail = {
  thumbnailUri: string,
};

export type PhotoVideoURI = {
  liveVideoUri: string | null,
};

/**
 * `CameraRoll` provides access to the local camera roll or photo library.
 *
 * See https://facebook.github.io/react-native/docs/cameraroll.html
 */
export class CameraRoll {
  static GroupTypesOptions = GROUP_TYPES_OPTIONS;
  static AssetTypeOptions = ASSET_TYPE_OPTIONS;
  static AlbumTypeOptions = ALBUM_TYPE_OPTIONS;

  /**
   * On iOS: requests deletion of a set of photos from the camera roll.
   * On Android: Deletes a set of photos from the camera roll.
   *
   */
  static deletePhotos(photoUris: Array<string>): Promise<void> {
    return RNCCameraRoll.deletePhotos(photoUris);
  }

  /**
   * Saves the photo or video to the camera roll or photo library.
   *
   */
  static save(
    tag: string,
    options: SaveToCameraRollOptions = {},
  ): Promise<string> {
    let {type = 'auto'} = options;
    const {album = '',title = ''} = options;
    if (tag === '') throw new Error('tag must be a valid string');

    if (type === 'auto') {
      const fileExtension = tag.split('.').slice(-1)[0] ?? '';
      if (['mov', 'mp4'].indexOf(fileExtension.toLowerCase()) >= 0)
        type = 'video';
      else type = 'photo';
    }
    return RNCCameraRoll.saveToCameraRoll(tag, {type, album, title});
  }

  static saveToCameraRoll(
    tag: string,
    type?: 'photo' | 'video' | 'auto',
  ): Promise<string> {
    console.warn(
      'CameraRoll.saveToCameraRoll(tag, type) is deprecated.  Use the save function instead',
    );
    return CameraRoll.save(tag, {type});
  }

  /**
   * Save a Live Photo (iOS) / Motion Photo (Android) built from a cover image
   * and a paired video.
   *
   * - On iOS the image and video are rewritten with a shared
   *   `assetIdentifier` so the Photos app recognises them as a native Live
   *   Photo and adds them as a single asset.
   * - On Android a Motion Photo file is produced (JPEG + appended MP4 with
   *   XMP markers) and inserted into MediaStore so the system gallery and
   *   this library's `getPhotos({ assetType: 'Live' })` treat it as a Live
   *   asset.
   *
   * @returns A Promise resolving to the saved asset URI (iOS: `ph://...`,
   * Android: content / file URI).
   */
  static saveLivePhoto(options: SaveLivePhotoOptions): Promise<string> {
    if (!options || !options.imageUri || !options.videoUri) {
      throw new Error('saveLivePhoto requires both imageUri and videoUri');
    }
    const {imageUri, videoUri, album = '', title = ''} = options;
    return RNCCameraRoll.saveLivePhoto({imageUri, videoUri, album, title});
  }

  static getAlbums(
    params: GetAlbumsParams = {assetType: 'All', albumType: 'Album'},
  ): Promise<Album[]> {
    return RNCCameraRoll.getAlbums(params);
  }

  static getParamsWithDefaults(params: GetPhotosParams): GetPhotosParams {
    const newParams = {...params};
    if (newParams.assetType === undefined) newParams.assetType = 'All';

    if (newParams.groupTypes === undefined && Platform.OS !== 'android')
      newParams.groupTypes = 'All';

    return newParams;
  }

  /**
   * Returns a Promise with photo identifier objects from the local camera
   * roll of the device matching shape defined by `getPhotosReturnChecker`.
   *
   * See https://facebook.github.io/react-native/docs/cameraroll.html#getphotos
   */
  static getPhotos(params: GetPhotosParams): Promise<PhotoIdentifiersPage> {
    params = CameraRoll.getParamsWithDefaults(params);
    return RNCCameraRoll.getPhotos(params);
  }

  /**
   * Returns a Promise with photo internal path.
   * if conversion is requested from HEIC then temporary file is created.
   *
   * @param internalID - PH photo internal ID.
   * @param options - photo conversion options.
   * @returns Promise<PhotoIdentifier>
   */
  static iosGetImageDataById(
    internalID: string,
    options: PhotoConvertionOptions = {},
  ): Promise<PhotoIdentifier> {
    const conversionOptions = {
      convertHeicImages: false,
      ...options
    }
    return RNCCameraRoll.getPhotoByInternalID(internalID, conversionOptions);
  }

  /**
   * Returns a Promise with the paired video URI for a Live Photo / Motion Photo asset.
   *
   * @param internalID - PH photo internal ID.
   * @returns Promise<PhotoVideoURI>
   */
  static getPhotoVideoURI(internalID: string): Promise<PhotoVideoURI> {
    return RNCCameraRoll.getPhotoVideoURI(internalID);
  }

    /**
   * Returns a Promise with thumbnail photo.
   *
   * @param internalID - PH photo internal ID.
   * @param options - thumbnail photo options.
   * @returns Promise<PhotoThumbnail>
   */
    static getPhotoThumbnail(internalID: string, options: PhotoThumbnailOptions): Promise<PhotoThumbnail> {
      return RNCCameraRoll.getPhotoThumbnail(internalID, options);
    }
}
