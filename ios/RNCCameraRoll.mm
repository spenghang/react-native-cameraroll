/**
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

#import "RNCCameraRoll.h"

#import <CoreLocation/CoreLocation.h>
#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <Photos/Photos.h>
#import <AVFoundation/AVFoundation.h>
#import <CoreMedia/CoreMedia.h>
#import <ImageIO/ImageIO.h>
#import <dlfcn.h>
#import <objc/runtime.h>
#import <MobileCoreServices/UTType.h>

#import <React/RCTBridge.h>
#import <React/RCTConvert.h>
#import <React/RCTLog.h>
#import <React/RCTUtils.h>

#import "RNCAssetsLibraryRequestHandler.h"

#if __has_include(<SDWebImageWebPCoder/SDWebImageWebPCoder.h>)
  #import <SDWebImageWebPCoder/SDWebImageWebPCoder.h>
  #define SD_WEB_IMAGE_WEBP_CODER_AVAILABLE 1
#endif

static NSArray<NSString *> *RCTMediaTypesFromAssetType(id assetType, NSString *defaultMediaType)
{
  NSMutableArray<NSString *> *mediaTypes = [NSMutableArray new];

  if ([assetType isKindOfClass:[NSArray class]]) {
    for (id mediaType in (NSArray *)assetType) {
      NSString *const convertedMediaType = [RCTConvert NSString:mediaType];
      if (convertedMediaType.length > 0) {
        [mediaTypes addObject:convertedMediaType];
      }
    }
  } else {
    NSString *const convertedMediaType = [RCTConvert NSString:assetType];
    if (convertedMediaType.length > 0) {
      [mediaTypes addObject:convertedMediaType];
    }
  }

  if (mediaTypes.count == 0) {
    [mediaTypes addObject:defaultMediaType];
  }

  return mediaTypes;
}

static NSSet<NSString *> *RCTLowercaseMediaTypes(NSArray<NSString *> *mediaTypes)
{
  NSMutableSet<NSString *> *lowercaseMediaTypes = [NSMutableSet new];
  for (NSString *mediaType in mediaTypes) {
    [lowercaseMediaTypes addObject:[mediaType lowercaseString]];
  }

  return lowercaseMediaTypes;
}

@implementation RCTConvert (PHAssetCollectionSubtype)

RCT_ENUM_CONVERTER(PHAssetCollectionSubtype, (@{
   @"album": @(PHAssetCollectionSubtypeAny),
   @"all": @(PHAssetCollectionSubtypeSmartAlbumUserLibrary),
   @"event": @(PHAssetCollectionSubtypeAlbumSyncedEvent),
   @"faces": @(PHAssetCollectionSubtypeAlbumSyncedFaces),
   @"library": @(PHAssetCollectionSubtypeSmartAlbumUserLibrary),
   @"photo-stream": @(PHAssetCollectionSubtypeAlbumMyPhotoStream), // incorrect, but legacy
   @"photostream": @(PHAssetCollectionSubtypeAlbumMyPhotoStream),
   @"saved-photos": @(PHAssetCollectionSubtypeAny), // incorrect, but legacy correspondence in PHAssetCollectionSubtype
   @"savedphotos": @(PHAssetCollectionSubtypeAny), // This was ALAssetsGroupSavedPhotos, seems to have no direct correspondence in PHAssetCollectionSubtype
}), PHAssetCollectionSubtypeAny, integerValue)


@end

@implementation RCTConvert (PHFetchOptions)

+ (PHFetchOptions *)PHFetchOptionsFromMediaTypes:(NSArray<NSString *> *)mediaTypes
                                        fromTime:(NSUInteger)fromTime
                                          toTime:(NSUInteger)toTime
{
  // This is not exhaustive in terms of supported media type predicates; more can be added in the future
  NSSet<NSString *> *const lowercaseMediaTypes = RCTLowercaseMediaTypes(mediaTypes);
  NSMutableArray *format = [NSMutableArray new];
  NSMutableArray *arguments = [NSMutableArray new];

  BOOL const includesAll = [lowercaseMediaTypes containsObject:@"all"];
  BOOL const includesImages = includesAll ||
    [lowercaseMediaTypes containsObject:@"photos"] ||
    [lowercaseMediaTypes containsObject:@"live"];
  BOOL const includesVideos = includesAll ||
    [lowercaseMediaTypes containsObject:@"videos"];

  for (NSString *mediaType in lowercaseMediaTypes) {
    if (![mediaType isEqualToString:@"photos"] &&
        ![mediaType isEqualToString:@"live"] &&
        ![mediaType isEqualToString:@"videos"] &&
        ![mediaType isEqualToString:@"all"]) {
      RCTLogError(@"Invalid filter option: '%@'. Expected one of 'photos',"
                  "'live', 'videos' or 'all'.", mediaType);
    }
  }

  if (includesImages && includesVideos) {
    [format addObject:@"mediaType IN %@"];
    [arguments addObject:@[@(PHAssetMediaTypeImage), @(PHAssetMediaTypeVideo)]];
  } else if (includesImages) {
    [format addObject:@"mediaType = %d"];
    [arguments addObject:@(PHAssetMediaTypeImage)];
  } else if (includesVideos) {
    [format addObject:@"mediaType = %d"];
    [arguments addObject:@(PHAssetMediaTypeVideo)];
  }

  if (fromTime > 0) {
    NSDate* fromDate = [NSDate dateWithTimeIntervalSince1970:fromTime/1000];
    [format addObject:@"creationDate > %@"];
    [arguments addObject:fromDate];
  }
  if (toTime > 0) {
    NSDate* toDate = [NSDate dateWithTimeIntervalSince1970:toTime/1000];
    [format addObject:@"creationDate <= %@"];
    [arguments addObject:toDate];
  }

  // This case includes the "all" mediatype
  PHFetchOptions *const options = [PHFetchOptions new];
  if ([format count] > 0) {
    options.predicate = [NSPredicate predicateWithFormat:[format componentsJoinedByString:@" AND "] argumentArray:arguments];
  }
  return options;
}

+ (PHFetchOptions *)PHFetchOptionsFromMediaType:(NSString *)mediaType
                                       fromTime:(NSUInteger)fromTime
                                         toTime:(NSUInteger)toTime
{
  return [self PHFetchOptionsFromMediaTypes:RCTMediaTypesFromAssetType(mediaType, @"All")
                                   fromTime:fromTime
                                     toTime:toTime];
}

@end

@implementation RNCCameraRoll

RCT_EXPORT_MODULE()

@synthesize bridge = _bridge;

static NSString *const kErrorUnableToSave = @"E_UNABLE_TO_SAVE";
static NSString *const kErrorUnableToLoad = @"E_UNABLE_TO_LOAD";

static NSString *const kErrorAuthRestricted = @"E_PHOTO_LIBRARY_AUTH_RESTRICTED";
static NSString *const kErrorAuthDenied = @"E_PHOTO_LIBRARY_AUTH_DENIED";

typedef void (^PhotosAuthorizedBlock)(bool isLimited);

static void requestPhotoLibraryAccess(RCTPromiseRejectBlock reject, PhotosAuthorizedBlock authorizedBlock, bool requestAddOnly) {
  PHAuthorizationStatus authStatus;
  if (@available(iOS 14, *)) {
      if (requestAddOnly) {
        authStatus = [PHPhotoLibrary authorizationStatusForAccessLevel:PHAccessLevelAddOnly];
      } else {
        authStatus = [PHPhotoLibrary authorizationStatusForAccessLevel:PHAccessLevelReadWrite];
      }
  } else {
    authStatus = [PHPhotoLibrary authorizationStatus];
  }
  if (authStatus == PHAuthorizationStatusRestricted) {
    reject(kErrorAuthRestricted, @"Access to photo library is restricted", nil);
  } else if (authStatus == PHAuthorizationStatusAuthorized) {
    authorizedBlock(false);
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunguarded-availability-new"
  } else if (authStatus == PHAuthorizationStatusLimited) {
#pragma clang diagnostic pop
    authorizedBlock(true);
  } else if (authStatus == PHAuthorizationStatusNotDetermined) {
      if (@available(iOS 14, *)) {
          if (requestAddOnly) {
              [PHPhotoLibrary requestAuthorizationForAccessLevel:PHAccessLevelAddOnly handler:^(PHAuthorizationStatus status) {
                  requestPhotoLibraryAccess(reject, authorizedBlock, requestAddOnly);
              }];
          } else {
              [PHPhotoLibrary requestAuthorizationForAccessLevel:PHAccessLevelReadWrite handler:^(PHAuthorizationStatus status) {
                  requestPhotoLibraryAccess(reject, authorizedBlock, requestAddOnly);
              }];
          }
      } else {
          [PHPhotoLibrary requestAuthorization:^(PHAuthorizationStatus status) {
              requestPhotoLibraryAccess(reject, authorizedBlock, requestAddOnly);
          }];
      }
  } else {
    reject(kErrorAuthDenied, @"Access to photo library was denied", nil);
  }
}

RCT_EXPORT_METHOD(saveToCameraRoll:(NSURLRequest *)request
                  options:(NSDictionary *)options
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)
{
  // We load images and videos differently.
  // Images have many custom loaders which can load images from ALAssetsLibrary URLs, PHPhotoLibrary
  // URLs, `data:` URIs, etc. Video URLs are passed directly through for now; it may be nice to support
  // more ways of loading videos in the future.
  __block NSURL *inputURI = nil;
  __block PHFetchResult *photosAsset;
  __block PHAssetCollection *collection;
  __block PHObjectPlaceholder *placeholder;

  void (^saveBlock)(void) = ^void() {
    // performChanges and the completionHandler are called on
    // arbitrary threads, not the main thread - this is safe
    // for now since all JS is queued and executed on a single thread.
    // We should reevaluate this if that assumption changes.

    [[PHPhotoLibrary sharedPhotoLibrary] performChanges:^{
      PHAssetChangeRequest *assetRequest ;
      if ([options[@"type"] isEqualToString:@"video"]) {
        assetRequest = [PHAssetChangeRequest creationRequestForAssetFromVideoAtFileURL:inputURI];
        assetRequest.creationDate = [NSDate date];
      } else if ([[inputURI.pathExtension lowercaseString] isEqualToString:@"gif"]) {
        NSData *data = [NSData dataWithContentsOfURL:inputURI];
        PHAssetCreationRequest *request = [PHAssetCreationRequest creationRequestForAsset];
        [request addResourceWithType:PHAssetResourceTypePhoto data:data options:NULL];
        assetRequest = request;
      } else {
        NSData *data = [NSData dataWithContentsOfURL:inputURI];
        if ([[inputURI.pathExtension lowercaseString] isEqualToString:@"webp"]) {
          UIImage *webpImage;

          #ifdef SD_WEB_IMAGE_WEBP_CODER_AVAILABLE
            webpImage = [[SDImageWebPCoder sharedCoder] decodedImageWithData:data options:nil];
          #else
            if (@available(iOS 14, *)) {
              webpImage = [UIImage imageWithData:data];
            }
          #endif

          if (webpImage) {
            data = UIImageJPEGRepresentation(webpImage, 1.0);
          }
        }

        // 设置标题
        PHAssetCreationRequest *creationRequest = [PHAssetCreationRequest creationRequestForAsset];
        PHAssetResourceCreationOptions *createOptions = [[PHAssetResourceCreationOptions alloc] init];
        if(![options[@"title"] isEqualToString:@""]){
            createOptions.originalFilename = options[@"title"];
        }
        [creationRequest addResourceWithType:PHAssetResourceTypePhoto data:data options:createOptions];
        assetRequest = creationRequest;

//         UIImage *image = [UIImage imageWithData:data];
//         assetRequest = [PHAssetChangeRequest creationRequestForAssetFromImage:image];
      }
      placeholder = [assetRequest placeholderForCreatedAsset];
      if (![options[@"album"] isEqualToString:@""]) {
        photosAsset = [PHAsset fetchAssetsInAssetCollection:collection options:nil];
        PHAssetCollectionChangeRequest *albumChangeRequest = [PHAssetCollectionChangeRequest changeRequestForAssetCollection:collection assets:photosAsset];
        [albumChangeRequest addAssets:@[placeholder]];
      }
    } completionHandler:^(BOOL success, NSError *error) {
      if (success) {
        NSString *uri = [NSString stringWithFormat:@"ph://%@", [placeholder localIdentifier]];
        resolve(uri);
      } else {
        reject(kErrorUnableToSave, nil, error);
      }
    }];
  };
  void (^saveWithOptions)(void) = ^void() {
    if (![options[@"album"] isEqualToString:@""]) {

      PHFetchOptions *fetchOptions = [[PHFetchOptions alloc] init];
      fetchOptions.predicate = [NSPredicate predicateWithFormat:@"title = %@", options[@"album"] ];
      collection = [PHAssetCollection fetchAssetCollectionsWithType:PHAssetCollectionTypeAlbum
                                                            subtype:PHAssetCollectionSubtypeAny
                                                            options:fetchOptions].firstObject;
      // Create the album
      if (!collection) {
        [[PHPhotoLibrary sharedPhotoLibrary] performChanges:^{
          PHAssetCollectionChangeRequest *createAlbum = [PHAssetCollectionChangeRequest creationRequestForAssetCollectionWithTitle:options[@"album"]];
          placeholder = [createAlbum placeholderForCreatedAssetCollection];
        } completionHandler:^(BOOL success, NSError *error) {
          if (success) {
            PHFetchResult *collectionFetchResult = [PHAssetCollection fetchAssetCollectionsWithLocalIdentifiers:@[placeholder.localIdentifier]
                                                                                                        options:nil];
            collection = collectionFetchResult.firstObject;
            saveBlock();
          } else {
            reject(kErrorUnableToSave, nil, error);
          }
        }];
      } else {
        saveBlock();
      }
    } else {
      saveBlock();
    }
  };

  void (^loadBlock)(bool isLimited) = ^void(bool isLimited) {
    inputURI = request.URL;
    saveWithOptions();
  };

  requestPhotoLibraryAccess(reject, loadBlock, true);
}

#pragma mark - Live Photo

static NSString *const kLivePhotoContentIdentifierKey = @"com.apple.quicktime.content.identifier";
static NSString *const kLivePhotoStillImageTimeKey = @"com.apple.quicktime.still-image-time";

+ (NSURL *)rnc_resolveLocalFileURL:(NSString *)input
{
  if (input.length == 0) {
    return nil;
  }
  NSURL *url = [NSURL URLWithString:input];
  if (url == nil || url.scheme == nil) {
    url = [NSURL fileURLWithPath:input];
  } else if ([url.scheme.lowercaseString isEqualToString:@"file"]) {
    // already a file URL
  } else {
    return nil;
  }
  if (![[NSFileManager defaultManager] fileExistsAtPath:url.path]) {
    return nil;
  }
  return url;
}

+ (BOOL)rnc_writeImage:(NSURL *)sourceURL
    assetIdentifier:(NSString *)assetIdentifier
          outputURL:(NSURL *)outputURL
              error:(NSError **)error
{
  NSData *imageData = [NSData dataWithContentsOfURL:sourceURL];
  if (imageData == nil) {
    if (error) {
      *error = [NSError errorWithDomain:@"RNCCameraRoll" code:-10 userInfo:@{NSLocalizedDescriptionKey: @"Unable to read cover image"}];
    }
    return NO;
  }

  CGImageSourceRef source = CGImageSourceCreateWithData((__bridge CFDataRef)imageData, NULL);
  if (source == NULL) {
    if (error) {
      *error = [NSError errorWithDomain:@"RNCCameraRoll" code:-11 userInfo:@{NSLocalizedDescriptionKey: @"Cover image is not decodable"}];
    }
    return NO;
  }

  CFStringRef sourceType = CGImageSourceGetType(source);
  if (sourceType == NULL) {
    sourceType = (__bridge CFStringRef)@"public.jpeg";
  }

  NSDictionary *originalMetadata = (__bridge_transfer NSDictionary *)CGImageSourceCopyPropertiesAtIndex(source, 0, NULL);
  NSMutableDictionary *mutableMetadata = originalMetadata ? [originalMetadata mutableCopy] : [NSMutableDictionary dictionary];
  NSMutableDictionary *makerApple = [mutableMetadata[(NSString *)kCGImagePropertyMakerAppleDictionary] mutableCopy];
  if (makerApple == nil) {
    makerApple = [NSMutableDictionary dictionary];
  }
  // Apple uses the integer key "17" for the Live Photo asset identifier.
  makerApple[@"17"] = assetIdentifier;
  mutableMetadata[(NSString *)kCGImagePropertyMakerAppleDictionary] = makerApple;

  [[NSFileManager defaultManager] removeItemAtURL:outputURL error:nil];
  CGImageDestinationRef destination = CGImageDestinationCreateWithURL((__bridge CFURLRef)outputURL, sourceType, 1, NULL);
  if (destination == NULL) {
    CFRelease(source);
    if (error) {
      *error = [NSError errorWithDomain:@"RNCCameraRoll" code:-12 userInfo:@{NSLocalizedDescriptionKey: @"Unable to create image destination"}];
    }
    return NO;
  }

  CGImageDestinationAddImageFromSource(destination, source, 0, (__bridge CFDictionaryRef)mutableMetadata);
  BOOL finalized = CGImageDestinationFinalize(destination);
  CFRelease(destination);
  CFRelease(source);

  if (!finalized && error) {
    *error = [NSError errorWithDomain:@"RNCCameraRoll" code:-13 userInfo:@{NSLocalizedDescriptionKey: @"Unable to finalize image destination"}];
  }
  return finalized;
}

+ (AVMutableMetadataItem *)rnc_contentIdentifierMetadataItem:(NSString *)assetIdentifier
{
  AVMutableMetadataItem *item = [AVMutableMetadataItem metadataItem];
  item.keySpace = AVMetadataKeySpaceQuickTimeMetadata;
  item.key = kLivePhotoContentIdentifierKey;
  item.value = assetIdentifier;
  item.dataType = (__bridge NSString *)kCMMetadataBaseDataType_UTF8;
  return item;
}

+ (AVMutableMetadataItem *)rnc_stillImageTimeMetadataItem
{
  AVMutableMetadataItem *item = [AVMutableMetadataItem metadataItem];
  item.keySpace = AVMetadataKeySpaceQuickTimeMetadata;
  item.key = kLivePhotoStillImageTimeKey;
  // Apple uses a signed 8-bit value of 0xFF (-1) here; the timestamp is carried
  // by the timed metadata group's time range.
  item.value = @(-1);
  item.dataType = (__bridge NSString *)kCMMetadataBaseDataType_SInt8;
  return item;
}

+ (BOOL)rnc_writeLivePhotoVideo:(NSURL *)sourceURL
                assetIdentifier:(NSString *)assetIdentifier
                stillImageTimeMs:(NSNumber *)stillImageTimeMs
                      outputURL:(NSURL *)outputURL
                          error:(NSError **)error
{
  [[NSFileManager defaultManager] removeItemAtURL:outputURL error:nil];

  AVURLAsset *asset = [AVURLAsset URLAssetWithURL:sourceURL options:nil];
  AVAssetTrack *videoTrack = [[asset tracksWithMediaType:AVMediaTypeVideo] firstObject];
  if (videoTrack == nil) {
    if (error) {
      *error = [NSError errorWithDomain:@"RNCCameraRoll" code:-20 userInfo:@{NSLocalizedDescriptionKey: @"Video file has no video track"}];
    }
    return NO;
  }

  NSError *readerError = nil;
  AVAssetReader *reader = [AVAssetReader assetReaderWithAsset:asset error:&readerError];
  if (reader == nil) {
    if (error) *error = readerError;
    return NO;
  }

  NSError *writerError = nil;
  AVAssetWriter *writer = [AVAssetWriter assetWriterWithURL:outputURL
                                                  fileType:AVFileTypeQuickTimeMovie
                                                     error:&writerError];
  if (writer == nil) {
    if (error) *error = writerError;
    return NO;
  }
  writer.metadata = @[[self rnc_contentIdentifierMetadataItem:assetIdentifier]];

  // Video passthrough.
  AVAssetReaderTrackOutput *videoOutput = [AVAssetReaderTrackOutput
                                           assetReaderTrackOutputWithTrack:videoTrack
                                           outputSettings:nil];
  if (![reader canAddOutput:videoOutput]) {
    if (error) {
      *error = [NSError errorWithDomain:@"RNCCameraRoll" code:-21 userInfo:@{NSLocalizedDescriptionKey: @"Cannot add video output"}];
    }
    return NO;
  }
  [reader addOutput:videoOutput];

  CMFormatDescriptionRef videoFormat = (__bridge CMFormatDescriptionRef)videoTrack.formatDescriptions.firstObject;
  AVAssetWriterInput *videoInput = [AVAssetWriterInput
                                    assetWriterInputWithMediaType:AVMediaTypeVideo
                                    outputSettings:nil
                                    sourceFormatHint:videoFormat];
  videoInput.expectsMediaDataInRealTime = NO;
  videoInput.transform = videoTrack.preferredTransform;
  if (![writer canAddInput:videoInput]) {
    if (error) {
      *error = [NSError errorWithDomain:@"RNCCameraRoll" code:-22 userInfo:@{NSLocalizedDescriptionKey: @"Cannot add video input"}];
    }
    return NO;
  }
  [writer addInput:videoInput];

  // Audio passthrough (optional).
  AVAssetTrack *audioTrack = [[asset tracksWithMediaType:AVMediaTypeAudio] firstObject];
  AVAssetReaderTrackOutput *audioOutput = nil;
  AVAssetWriterInput *audioInput = nil;
  if (audioTrack) {
    audioOutput = [AVAssetReaderTrackOutput assetReaderTrackOutputWithTrack:audioTrack outputSettings:nil];
    if ([reader canAddOutput:audioOutput]) {
      [reader addOutput:audioOutput];
      CMFormatDescriptionRef audioFormat = (__bridge CMFormatDescriptionRef)audioTrack.formatDescriptions.firstObject;
      audioInput = [AVAssetWriterInput assetWriterInputWithMediaType:AVMediaTypeAudio
                                                       outputSettings:nil
                                                     sourceFormatHint:audioFormat];
      audioInput.expectsMediaDataInRealTime = NO;
      if ([writer canAddInput:audioInput]) {
        [writer addInput:audioInput];
      } else {
        audioInput = nil;
        audioOutput = nil;
      }
    } else {
      audioOutput = nil;
    }
  }

  // Timed metadata track (still-image-time).
  CMFormatDescriptionRef metadataFormatDesc = NULL;
  NSArray *specs = @[@{
    (__bridge NSString *)kCMMetadataFormatDescriptionMetadataSpecificationKey_Identifier: [NSString stringWithFormat:@"mdta/%@", kLivePhotoStillImageTimeKey],
    (__bridge NSString *)kCMMetadataFormatDescriptionMetadataSpecificationKey_DataType: (__bridge NSString *)kCMMetadataBaseDataType_SInt8
  }];
  OSStatus status = CMMetadataFormatDescriptionCreateWithMetadataSpecifications(
      kCFAllocatorDefault,
      kCMMetadataFormatType_Boxed,
      (__bridge CFArrayRef)specs,
      &metadataFormatDesc);
  AVAssetWriterInput *metadataInput = nil;
  AVAssetWriterInputMetadataAdaptor *metadataAdaptor = nil;
  if (status == noErr && metadataFormatDesc != NULL) {
    metadataInput = [AVAssetWriterInput assetWriterInputWithMediaType:AVMediaTypeMetadata
                                                        outputSettings:nil
                                                      sourceFormatHint:metadataFormatDesc];
    metadataInput.expectsMediaDataInRealTime = NO;
    metadataAdaptor = [AVAssetWriterInputMetadataAdaptor assetWriterInputMetadataAdaptorWithAssetWriterInput:metadataInput];
    if ([writer canAddInput:metadataInput]) {
      [writer addInput:metadataInput];
    } else {
      metadataInput = nil;
      metadataAdaptor = nil;
    }
    CFRelease(metadataFormatDesc);
  }

  if (![writer startWriting]) {
    if (error) *error = writer.error;
    return NO;
  }
  if (![reader startReading]) {
    if (error) *error = reader.error;
    return NO;
  }
  [writer startSessionAtSourceTime:kCMTimeZero];

  // Append the still-image-time metadata at the requested still frame timestamp.
  if (metadataAdaptor) {
    CMTime startTime = CMTimeMake([stillImageTimeMs longLongValue], 1000);
    CMTimeRange range = CMTimeRangeMake(startTime, CMTimeMake(1, 100));
    AVMutableMetadataItem *stillImageTimeItem = [self rnc_stillImageTimeMetadataItem];
    AVTimedMetadataGroup *group = [[AVTimedMetadataGroup alloc] initWithItems:@[stillImageTimeItem]
                                                                    timeRange:range];
    [metadataAdaptor appendTimedMetadataGroup:group];
    [metadataInput markAsFinished];
  }

  dispatch_group_t group = dispatch_group_create();
  dispatch_queue_t videoQueue = dispatch_queue_create("RNCLivePhoto.video", DISPATCH_QUEUE_SERIAL);
  dispatch_queue_t audioQueue = dispatch_queue_create("RNCLivePhoto.audio", DISPATCH_QUEUE_SERIAL);

  dispatch_group_enter(group);
  [videoInput requestMediaDataWhenReadyOnQueue:videoQueue usingBlock:^{
    while (videoInput.isReadyForMoreMediaData) {
      if (reader.status != AVAssetReaderStatusReading) {
        [videoInput markAsFinished];
        dispatch_group_leave(group);
        return;
      }
      CMSampleBufferRef sampleBuffer = [videoOutput copyNextSampleBuffer];
      if (sampleBuffer) {
        BOOL appended = [videoInput appendSampleBuffer:sampleBuffer];
        CFRelease(sampleBuffer);
        if (!appended) {
          [videoInput markAsFinished];
          dispatch_group_leave(group);
          return;
        }
      } else {
        [videoInput markAsFinished];
        dispatch_group_leave(group);
        return;
      }
    }
  }];

  if (audioInput && audioOutput) {
    dispatch_group_enter(group);
    [audioInput requestMediaDataWhenReadyOnQueue:audioQueue usingBlock:^{
      while (audioInput.isReadyForMoreMediaData) {
        if (reader.status != AVAssetReaderStatusReading) {
          [audioInput markAsFinished];
          dispatch_group_leave(group);
          return;
        }
        CMSampleBufferRef sampleBuffer = [audioOutput copyNextSampleBuffer];
        if (sampleBuffer) {
          BOOL appended = [audioInput appendSampleBuffer:sampleBuffer];
          CFRelease(sampleBuffer);
          if (!appended) {
            [audioInput markAsFinished];
            dispatch_group_leave(group);
            return;
          }
        } else {
          [audioInput markAsFinished];
          dispatch_group_leave(group);
          return;
        }
      }
    }];
  }

  dispatch_group_wait(group, DISPATCH_TIME_FOREVER);

  if (reader.status == AVAssetReaderStatusFailed) {
    if (error) *error = reader.error;
    [writer cancelWriting];
    return NO;
  }

  __block BOOL finishSuccess = NO;
  dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);
  [writer finishWritingWithCompletionHandler:^{
    finishSuccess = (writer.status == AVAssetWriterStatusCompleted);
    dispatch_semaphore_signal(semaphore);
  }];
  dispatch_semaphore_wait(semaphore, DISPATCH_TIME_FOREVER);

  if (!finishSuccess) {
    if (error) *error = writer.error;
    return NO;
  }
  return YES;
}

RCT_EXPORT_METHOD(saveLivePhoto:(NSDictionary *)options
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)
{
  NSString *imageUriStr = [RCTConvert NSString:options[@"imageUri"]];
  NSString *videoUriStr = [RCTConvert NSString:options[@"videoUri"]];
  NSString *album = [RCTConvert NSString:options[@"album"]];
  NSNumber *stillImageTimeMs = @(0);
  id stillImageTimeOption = options[@"stillImageTime"];
  if (stillImageTimeOption != nil && stillImageTimeOption != [NSNull null]) {
    stillImageTimeMs = [RCTConvert NSNumber:stillImageTimeOption];
    long long stillImageTimeValue = [stillImageTimeMs longLongValue];
    if (stillImageTimeMs == nil
        || [stillImageTimeMs doubleValue] != (double)stillImageTimeValue
        || stillImageTimeValue < 0) {
      reject(kErrorUnableToSave, @"stillImageTime must be a non-negative integer in milliseconds", nil);
      return;
    }
  }

  if (imageUriStr.length == 0 || videoUriStr.length == 0) {
    reject(kErrorUnableToSave, @"saveLivePhoto requires both imageUri and videoUri", nil);
    return;
  }

  NSURL *imageURL = [RNCCameraRoll rnc_resolveLocalFileURL:imageUriStr];
  NSURL *videoURL = [RNCCameraRoll rnc_resolveLocalFileURL:videoUriStr];
  if (imageURL == nil) {
    reject(kErrorUnableToLoad, [NSString stringWithFormat:@"Could not read cover image at %@", imageUriStr], nil);
    return;
  }
  if (videoURL == nil) {
    reject(kErrorUnableToLoad, [NSString stringWithFormat:@"Could not read video at %@", videoUriStr], nil);
    return;
  }

  requestPhotoLibraryAccess(reject, ^(bool isLimited) {
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
      NSString *assetIdentifier = [[NSUUID UUID] UUIDString];

      NSString *imageExt = [imageURL.pathExtension lowercaseString] ?: @"jpg";
      NSString *tempImagePath = [NSTemporaryDirectory() stringByAppendingPathComponent:
                                  [NSString stringWithFormat:@"live-%@.%@", assetIdentifier, imageExt]];
      NSURL *tempImageURL = [NSURL fileURLWithPath:tempImagePath];

      NSString *tempVideoPath = [NSTemporaryDirectory() stringByAppendingPathComponent:
                                  [NSString stringWithFormat:@"live-%@.mov", assetIdentifier]];
      NSURL *tempVideoURL = [NSURL fileURLWithPath:tempVideoPath];

      NSError *imageError = nil;
      if (![RNCCameraRoll rnc_writeImage:imageURL
                         assetIdentifier:assetIdentifier
                               outputURL:tempImageURL
                                   error:&imageError]) {
        reject(kErrorUnableToSave, @"Failed to rewrite cover image", imageError);
        return;
      }

      NSError *videoError = nil;
      if (![RNCCameraRoll rnc_writeLivePhotoVideo:videoURL
                                  assetIdentifier:assetIdentifier
                                 stillImageTimeMs:stillImageTimeMs
                                        outputURL:tempVideoURL
                                            error:&videoError]) {
        [[NSFileManager defaultManager] removeItemAtURL:tempImageURL error:nil];
        reject(kErrorUnableToSave, @"Failed to rewrap paired video", videoError);
        return;
      }

      void (^cleanup)(void) = ^{
        [[NSFileManager defaultManager] removeItemAtURL:tempImageURL error:nil];
        [[NSFileManager defaultManager] removeItemAtURL:tempVideoURL error:nil];
      };

      __block PHObjectPlaceholder *placeholder = nil;

      void (^performSave)(PHAssetCollection *collection) = ^(PHAssetCollection *collection) {
        [[PHPhotoLibrary sharedPhotoLibrary] performChanges:^{
          PHAssetCreationRequest *request = [PHAssetCreationRequest creationRequestForAsset];
          request.creationDate = [NSDate date];
          PHAssetResourceCreationOptions *imageOptions = [[PHAssetResourceCreationOptions alloc] init];
          imageOptions.shouldMoveFile = YES;
          [request addResourceWithType:PHAssetResourceTypePhoto
                               fileURL:tempImageURL
                               options:imageOptions];

          PHAssetResourceCreationOptions *videoOptions = [[PHAssetResourceCreationOptions alloc] init];
          videoOptions.shouldMoveFile = YES;
          [request addResourceWithType:PHAssetResourceTypePairedVideo
                               fileURL:tempVideoURL
                               options:videoOptions];
          placeholder = [request placeholderForCreatedAsset];

          if (collection) {
            PHFetchResult *assetsInCollection = [PHAsset fetchAssetsInAssetCollection:collection options:nil];
            PHAssetCollectionChangeRequest *albumChange = [PHAssetCollectionChangeRequest
                                                           changeRequestForAssetCollection:collection
                                                                                    assets:assetsInCollection];
            [albumChange addAssets:@[placeholder]];
          }
        } completionHandler:^(BOOL success, NSError * _Nullable error) {
          cleanup();
          if (success && placeholder) {
            NSString *uri = [NSString stringWithFormat:@"ph://%@", placeholder.localIdentifier];
            resolve(uri);
          } else {
            NSString *msg = error
              ? [NSString stringWithFormat:@"Failed to save live photo to the photo library: %@", error.localizedDescription]
              : @"Failed to save live photo to the photo library";
            reject(kErrorUnableToSave, msg, error);
          }
        }];
      };

      if (album.length > 0) {
        PHFetchOptions *fetchOptions = [[PHFetchOptions alloc] init];
        fetchOptions.predicate = [NSPredicate predicateWithFormat:@"title = %@", album];
        PHAssetCollection *existing = [PHAssetCollection fetchAssetCollectionsWithType:PHAssetCollectionTypeAlbum
                                                                                subtype:PHAssetCollectionSubtypeAny
                                                                                options:fetchOptions].firstObject;
        if (existing) {
          performSave(existing);
        } else {
          __block NSString *placeholderLocalId = nil;
          [[PHPhotoLibrary sharedPhotoLibrary] performChanges:^{
            PHAssetCollectionChangeRequest *create = [PHAssetCollectionChangeRequest
                                                      creationRequestForAssetCollectionWithTitle:album];
            placeholderLocalId = create.placeholderForCreatedAssetCollection.localIdentifier;
          } completionHandler:^(BOOL success, NSError * _Nullable error) {
            if (!success || placeholderLocalId == nil) {
              cleanup();
              reject(kErrorUnableToSave, @"Failed to create target album", error);
              return;
            }
            PHAssetCollection *created = [PHAssetCollection fetchAssetCollectionsWithLocalIdentifiers:@[placeholderLocalId]
                                                                                              options:nil].firstObject;
            performSave(created);
          }];
        }
      } else {
        performSave(nil);
      }
    });
  }, true);
}

#pragma mark -

RCT_EXPORT_METHOD(getAlbums:(NSDictionary *)params
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)
{
  NSString *const mediaType = [params objectForKey:@"assetType"] ? [RCTConvert NSString:params[@"assetType"]] : @"All";
  NSString *const albumType = [params objectForKey:@"albumType"] ? [RCTConvert NSString:params[@"albumType"]] : @"Album";

  NSMutableArray * result = [NSMutableArray new];
  NSString *__block fetchedAlbumType = nil;
  void (^convertAsset)(PHAssetCollection * _Nonnull obj, NSUInteger idx, BOOL * _Nonnull stop) =
    ^(PHAssetCollection * _Nonnull obj, NSUInteger idx, BOOL * _Nonnull stop) {

      BOOL isShared = (obj.assetCollectionSubtype == PHAssetCollectionSubtypeAlbumCloudShared);
      PHFetchOptions *const assetFetchOptions = [RCTConvert PHFetchOptionsFromMediaType:mediaType fromTime:0 toTime:0];
      PHFetchResult<PHAsset *> *const assetsFetchResult = [PHAsset fetchAssetsInAssetCollection:obj options:assetFetchOptions];
      if (assetsFetchResult.count > 0) {
        [result addObject:@{
          @"title": [obj localizedTitle],
          @"count": @(assetsFetchResult.count),
          @"type": fetchedAlbumType,
          @"isShared": @(isShared)
        }];
      }
    };

  PHFetchOptions* options = [[PHFetchOptions alloc] init];
  if ([albumType isEqualToString:@"SmartAlbum"] || [albumType isEqualToString:@"All"]) {
    fetchedAlbumType = @"SmartAlbum";
    PHFetchResult<PHAssetCollection *> *const assets = [PHAssetCollection fetchAssetCollectionsWithType:PHAssetCollectionTypeSmartAlbum subtype:PHAssetCollectionSubtypeAny options:options];
    [assets enumerateObjectsUsingBlock:convertAsset];
  }
  if ([albumType isEqualToString:@"Album"] || [albumType isEqualToString:@"All"]) {
    fetchedAlbumType = @"Album";
    PHFetchResult<PHAssetCollection *> *const assets = [PHAssetCollection fetchAssetCollectionsWithType:PHAssetCollectionTypeAlbum subtype:PHAssetCollectionSubtypeAny options:options];
    [assets enumerateObjectsUsingBlock:convertAsset];
  }

  resolve(result);
}

static void RCTResolvePromise(RCTPromiseResolveBlock resolve,
                              NSArray<NSDictionary<NSString *, id> *> *assets,
                              BOOL hasNextPage,
                              bool isLimited)
{
  if (!assets.count) {
    resolve(@{
      @"edges": assets,
      @"page_info": @{
        @"has_next_page": @NO,
      },
      @"limited": @(isLimited)
    });
    return;
  }
  resolve(@{
    @"edges": assets,
    @"page_info": @{
      @"start_cursor": assets[0][@"node"][@"image"][@"uri"],
      @"end_cursor": assets[assets.count - 1][@"node"][@"image"][@"uri"],
      @"has_next_page": @(hasNextPage),
    },
    @"limited": @(isLimited)
  });
}

RCT_EXPORT_METHOD(getPhotos:(NSDictionary *)params
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)
{
  checkPhotoLibraryConfig();

  NSUInteger const first = [RCTConvert NSInteger:params[@"first"]];
  NSString *const afterCursor = [RCTConvert NSString:params[@"after"]];
  NSString *const groupName = [RCTConvert NSString:params[@"groupName"]];
  NSString *const groupTypes = [[RCTConvert NSString:params[@"groupTypes"]] lowercaseString];
  NSArray<NSString *> *const mediaTypes = RCTMediaTypesFromAssetType(params[@"assetType"], @"All");
  NSSet<NSString *> *const lowercaseMediaTypes = RCTLowercaseMediaTypes(mediaTypes);
  NSUInteger const fromTime = [RCTConvert NSInteger:params[@"fromTime"]];
  NSUInteger const toTime = [RCTConvert NSInteger:params[@"toTime"]];
  NSArray<NSString *> *const mimeTypes = [RCTConvert NSStringArray:params[@"mimeTypes"]];
  NSArray<NSString *> *const include = [RCTConvert NSStringArray:params[@"include"]];
  BOOL const filtersLivePhotos = [lowercaseMediaTypes containsObject:@"live"] &&
    ![lowercaseMediaTypes containsObject:@"photos"] &&
    ![lowercaseMediaTypes containsObject:@"all"];

  BOOL __block includeSharedAlbums = [params[@"includeSharedAlbums"] boolValue];

  BOOL __block includeFilename = [include indexOfObject:@"filename"] != NSNotFound;
  BOOL __block includeFileSize = [include indexOfObject:@"fileSize"] != NSNotFound;
  BOOL __block includeFileExtension = [include indexOfObject:@"fileExtension"] != NSNotFound;
  BOOL __block includeLocation = [include indexOfObject:@"location"] != NSNotFound;
  BOOL __block includeImageSize = [include indexOfObject:@"imageSize"] != NSNotFound;
  BOOL __block includePlayableDuration = [include indexOfObject:@"playableDuration"] != NSNotFound;
  BOOL __block includeAlbums = [include indexOfObject:@"albums"] != NSNotFound;

  // Predicate for fetching assets within a collection
  PHFetchOptions *const assetFetchOptions = [RCTConvert PHFetchOptionsFromMediaTypes:mediaTypes fromTime:fromTime toTime:toTime];
  // We can directly set the limit if we guarantee every image fetched will be
  // added to the output array within the `collectAsset` block
  BOOL collectAssetMayOmitAsset = !!afterCursor || [mimeTypes count] > 0 || filtersLivePhotos;
  if (!collectAssetMayOmitAsset) {
    // We set the fetchLimit to first + 1 so that `hasNextPage` will be set
    // correctly:
    // - If the user set `first: 10` and there are 11 photos, `hasNextPage`
    //   will be set to true below inside of `collectAsset`
    // - If the user set `first: 10` and there are 10 photos, `hasNextPage`
    //   will not be set, as expected
//     assetFetchOptions.fetchLimit = first + 1;
  }
//   assetFetchOptions.sortDescriptors = @[[NSSortDescriptor sortDescriptorWithKey:@"creationDate" ascending:NO]];

  if (includeSharedAlbums) {
    assetFetchOptions.includeAssetSourceTypes = PHAssetSourceTypeUserLibrary | PHAssetSourceTypeCloudShared;
  }

  BOOL __block foundAfter = NO;
  BOOL __block hasNextPage = NO;
  BOOL __block resolvedPromise = NO;
  NSMutableArray<NSDictionary<NSString *, id> *> *assets = [NSMutableArray new];

  BOOL __block stopCollections_;

  requestPhotoLibraryAccess(reject, ^(bool isLimited){
      void (^collectAsset)(PHAsset*, NSUInteger, BOOL*) = ^(PHAsset * _Nonnull asset, NSUInteger assetIdx, BOOL * _Nonnull stopAssets) {
          NSString *const uri = [NSString stringWithFormat:@"ph://%@", [asset localIdentifier]];

          if (afterCursor && !foundAfter) {
              if ([afterCursor isEqualToString:uri]) {
                  foundAfter = YES;
              }
              return;
          }

          if (filtersLivePhotos && asset.mediaType == PHAssetMediaTypeImage && ![self isLivePhotoAsset:asset]) {
              return;
          }

          NSString *_Nullable originalFilename = NULL;
          NSString *_Nullable fileExtension = NULL;
          PHAssetResource *_Nullable resource = NULL;
          NSNumber* fileSize = [NSNumber numberWithInt:0];

          if (includeFilename || includeFileSize || [mimeTypes count] > 0) {
              // Get underlying resources of an asset - this includes files as well as details about edited PHAssets
              // This is required for the filename and mimeType filtering
              NSArray<PHAssetResource *> *const assetResources = [PHAssetResource assetResourcesForAsset:asset];
              resource = [assetResources firstObject];
              originalFilename = resource.originalFilename;
              fileSize = [resource valueForKey:@"fileSize"];
          }

          // WARNING: If you add any code to `collectAsset` that may skip adding an
          // asset to the `assets` output array, you should do it inside this
          // block and ensure the logic for `collectAssetMayOmitAsset` above is
          // updated
          if (collectAssetMayOmitAsset) {
              if ([mimeTypes count] > 0 && resource) {
                  CFStringRef const uti = (__bridge CFStringRef _Nonnull)(resource.uniformTypeIdentifier);
                  NSString *const mimeType = (NSString *)CFBridgingRelease(UTTypeCopyPreferredTagWithClass(uti, kUTTagClassMIMEType));

                  BOOL __block mimeTypeFound = NO;
                  [mimeTypes enumerateObjectsUsingBlock:^(NSString * _Nonnull mimeTypeFilter, NSUInteger idx, BOOL * _Nonnull stop) {
                      if ([mimeType isEqualToString:mimeTypeFilter]) {
                          mimeTypeFound = YES;
                          *stop = YES;
                      }
                  }];

                  if (!mimeTypeFound) {
                      return;
                  }
              }
          }

          // If we've accumulated enough results to resolve a single promise
          if (first == assets.count) {
              *stopAssets = YES;
              stopCollections_ = YES;
              hasNextPage = YES;
              RCTAssert(resolvedPromise == NO, @"Resolved the promise before we finished processing the results.");
              RCTResolvePromise(resolve, assets, hasNextPage, isLimited);
              resolvedPromise = YES;
              return;
          }

          NSString *const assetMediaTypeLabel = (asset.mediaType == PHAssetMediaTypeVideo
                                                 ? @"video"
                                                 : (asset.mediaType == PHAssetMediaTypeImage
                                                    ? @"image"
                                                    : (asset.mediaType == PHAssetMediaTypeAudio
                                                       ? @"audio"
                                                       : @"unknown")));

          NSArray<NSString*> *const assetMediaSubtypesLabel = [self mediaSubTypeLabelsForAsset:asset];

          NSArray<NSString*> *albums = @[];

          if (includeAlbums) {
              albums = [self getAlbumsForAsset:asset];
          }

          if (includeFileExtension) {
              NSString *name = [asset valueForKey:@"filename"];
              NSString *extension = [name pathExtension];
              fileExtension = [extension lowercaseString];
          }

          CLLocation *const loc = asset.location;
          NSString *localIdentifier = asset.localIdentifier;

          [assets addObject:@{
            @"node": @{
                @"id": localIdentifier,
                @"type": assetMediaTypeLabel, // TODO: switch to mimeType?
                @"subTypes": assetMediaSubtypesLabel,
                @"group_name": albums,
                @"image": @{
                    @"uri": uri,
                    @"extension": (includeFileExtension ? fileExtension : [NSNull null]),
                    @"filename": (includeFilename && originalFilename ? originalFilename : [NSNull null]),
                    @"height": (includeImageSize ? @([asset pixelHeight]) : [NSNull null]),
                    @"width": (includeImageSize ? @([asset pixelWidth]) : [NSNull null]),
                    @"fileSize": (includeFileSize && fileSize ? fileSize : [NSNull null]),
                    @"playableDuration": (includePlayableDuration && asset.mediaType != PHAssetMediaTypeImage
                        ? @([asset duration]) // fractional seconds
                        : [NSNull null])
                },
                @"timestamp": @(asset.creationDate.timeIntervalSince1970),
                @"modificationTimestamp": @(asset.modificationDate.timeIntervalSince1970),
                @"location": (includeLocation && loc ? @{
                    @"latitude": @(loc.coordinate.latitude),
                    @"longitude": @(loc.coordinate.longitude),
                    @"altitude": @(loc.altitude),
                    @"heading": @(loc.course),
                    @"speed": @(loc.speed), // speed in m/s
                } : [NSNull null])
            }
          }];
      };

    if ([groupTypes isEqualToString:@"all"]) {
      PHFetchResult <PHAsset *> *const assetFetchResult = [PHAsset fetchAssetsWithOptions: assetFetchOptions];
      [assetFetchResult enumerateObjectsWithOptions:NSEnumerationReverse usingBlock:collectAsset];
    } else {
      PHFetchResult<PHAssetCollection *> * assetCollectionFetchResult;
      if ([groupTypes isEqualToString:@"smartalbum"]) {
        assetCollectionFetchResult = [PHAssetCollection fetchAssetCollectionsWithType:PHAssetCollectionTypeSmartAlbum subtype:PHAssetCollectionSubtypeAny options:nil];
        [assetCollectionFetchResult enumerateObjectsUsingBlock:^(PHAssetCollection * _Nonnull assetCollection, NSUInteger collectionIdx, BOOL * _Nonnull stopCollections) {
          if ([assetCollection.localizedTitle isEqualToString:groupName]) {
            PHFetchResult<PHAsset *> *const assetsFetchResult = [PHAsset fetchAssetsInAssetCollection:assetCollection options:assetFetchOptions];
            [assetsFetchResult enumerateObjectsWithOptions:NSEnumerationReverse usingBlock:collectAsset];
            *stopCollections = stopCollections_;
          }
        }];
      } else {
        PHAssetCollectionSubtype const collectionSubtype = [RCTConvert PHAssetCollectionSubtype:groupTypes];

        // Filter collection name ("group")
        PHFetchOptions *const collectionFetchOptions = [PHFetchOptions new];
        collectionFetchOptions.sortDescriptors = @[[NSSortDescriptor sortDescriptorWithKey:@"endDate" ascending:NO]];
        if (groupName != nil) {
          collectionFetchOptions.predicate = [NSPredicate predicateWithFormat:@"localizedTitle = %@", groupName];
        }
        assetCollectionFetchResult = [PHAssetCollection fetchAssetCollectionsWithType:PHAssetCollectionTypeAlbum subtype:collectionSubtype options:collectionFetchOptions];
        [assetCollectionFetchResult enumerateObjectsUsingBlock:^(PHAssetCollection * _Nonnull assetCollection, NSUInteger collectionIdx, BOOL * _Nonnull stopCollections) {
            // Enumerate assets within the collection
          PHFetchResult<PHAsset *> *const assetsFetchResult = [PHAsset fetchAssetsInAssetCollection:assetCollection options:assetFetchOptions];
          [assetsFetchResult enumerateObjectsWithOptions:NSEnumerationReverse usingBlock:collectAsset];
          *stopCollections = stopCollections_;
        }];
      }
    }

    // If we get this far and haven't resolved the promise yet, we reached the end of the list of photos
    if (!resolvedPromise) {
      hasNextPage = NO;
      RCTResolvePromise(resolve, assets, hasNextPage, isLimited);
      resolvedPromise = YES;
    }
  }, false);
}

RCT_EXPORT_METHOD(deletePhotos:(NSArray<NSString *>*)assets
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)
{
  NSMutableArray *convertedAssets = [NSMutableArray array];

  for (NSString *asset in assets) {
    [convertedAssets addObject: [asset stringByReplacingOccurrencesOfString:@"ph://" withString:@""]];
  }

  [[PHPhotoLibrary sharedPhotoLibrary] performChanges:^{
      PHFetchResult<PHAsset *> *fetched =
        [PHAsset fetchAssetsWithLocalIdentifiers:convertedAssets options:nil];
      [PHAssetChangeRequest deleteAssets:fetched];
    }
  completionHandler:^(BOOL success, NSError *error) {
    if (success == YES) {
      resolve(@(success));
    }
    else {
      reject(@"Couldn't delete", @"Couldn't delete assets", error);
    }
  }
  ];
}

RCT_EXPORT_METHOD(getPhotoByInternalID:(NSString *)internalId
                  options:(NSDictionary *)options
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)
{
  checkPhotoLibraryConfig();

  BOOL const convertHeic = [RCTConvert BOOL:options[@"convertHeicImages"]];
  CGFloat quality = options[@"quality"] == nil ? 1.0 : [RCTConvert CGFloat:options[@"quality"]];

  requestPhotoLibraryAccess(reject, ^(bool isLimited){

    PHFetchResult<PHAsset *> *fetchResult;
    PHAsset *asset;

    NSString *mediaIdentifier = internalId;

    if ([internalId rangeOfString:@"ph://"].location != NSNotFound) {
      mediaIdentifier = [internalId stringByReplacingOccurrencesOfString:@"ph://"
                                                                   withString:@""];
    }

    fetchResult = [PHAsset fetchAssetsWithLocalIdentifiers:@[mediaIdentifier] options:nil];
    if(fetchResult){
      asset = fetchResult.firstObject;//only object in the array.
    }

    if(asset){
      __block NSURL *imageURL = [[NSURL alloc]initWithString:@""];

      NSString *const assetMediaTypeLabel = (asset.mediaType == PHAssetMediaTypeVideo
                                             ? @"video"
                                             : (asset.mediaType == PHAssetMediaTypeImage
                                                ? @"image"
                                                : (asset.mediaType == PHAssetMediaTypeAudio
                                                   ? @"audio"
                                                   : @"unknown")));


      CLLocation *const loc = asset.location;

      NSArray<PHAssetResource *> *const assetResources = [PHAssetResource assetResourcesForAsset:asset];
      if (![assetResources firstObject]) {
        return;
      }
      PHAssetResource *const _Nonnull resource = [assetResources firstObject];

      __block NSString *originalFilename = resource.originalFilename;
      NSString *const uniformMimeType = resource.uniformTypeIdentifier;

      __block NSString *filePath = @"";

      NSArray<NSString*> *const assetMediaSubtypesLabel = [self mediaSubTypeLabelsForAsset:asset];

      // check if HEIC extension asset
      if (convertHeic && asset.mediaType == PHAssetMediaTypeImage && [uniformMimeType  isEqual: @"public.heic"]) {
        // convert to JPEG
        PHImageRequestOptions *const requestOptions = [PHImageRequestOptions new];
        requestOptions.networkAccessAllowed = YES;
        requestOptions.version = PHImageRequestOptionsVersionCurrent;
        requestOptions.deliveryMode = PHImageRequestOptionsDeliveryModeHighQualityFormat;

        CGSize const targetSize = CGSizeMake((CGFloat)asset.pixelWidth, (CGFloat)asset.pixelHeight);
        [[PHImageManager defaultManager] requestImageForAsset:asset
                                                     targetSize:targetSize
                                                    contentMode:PHImageContentModeDefault
                                                        options:requestOptions
                                                  resultHandler:^(UIImage * _Nullable image,
                                                                  NSDictionary * _Nullable info) {
          NSError *const error = [info objectForKey:PHImageErrorKey];
          if (error) {
            reject(@"Error while converting to JPEG image",@"Error while converting",error);
          }

          originalFilename = [originalFilename stringByReplacingOccurrencesOfString:@"HEIC" withString:@"JPEG" options:NSCaseInsensitiveSearch range:NSMakeRange(0, [originalFilename length])];
          NSData *const imageData = UIImageJPEGRepresentation(image, quality);
          NSFileManager *fileManager = [NSFileManager defaultManager];
          NSString *fullPath = [NSTemporaryDirectory() stringByAppendingPathComponent:originalFilename];
          if ([fileManager createFileAtPath:fullPath contents:imageData attributes:nil]) {
            unsigned long long fileSize = [[fileManager attributesOfItemAtPath:fullPath error:nil] fileSize];

            resolve(@{
                      @"node": @{
                          @"type": assetMediaTypeLabel,
                          @"subTypes":assetMediaSubtypesLabel,
                          @"image": @{
                              @"filepath": fullPath,
                              @"filename": originalFilename,
                              @"height": @([asset pixelHeight]),
                              @"width": @([asset pixelWidth]),
                              @"isStored": @YES,
                              @"playableDuration": @([asset duration]), // fractional seconds
                              @"fileSize": @(fileSize)
                              },
                          @"timestamp": @(asset.creationDate.timeIntervalSince1970),
                          @"modificationTimestamp": @(asset.modificationDate.timeIntervalSince1970),
                          @"location": (loc ? @{
                                                @"latitude": @(loc.coordinate.latitude),
                                                @"longitude": @(loc.coordinate.longitude),
                                                @"altitude": @(loc.altitude),
                                                @"heading": @(loc.course),
                                                @"speed": @(loc.speed), // speed in m/s
                                                } : @{})
                          }
                      });
          } else {
            NSString *errorMessage = [NSString stringWithFormat:@"Failed to create tmp file for asset %@.", originalFilename];
            NSError *error = RCTErrorWithMessage(errorMessage);
            reject(@"Error while creating image tmp file",@"Error creating tmp file",error);
          }

        }];
      } else {
        NSNumber* fileSize = [resource valueForKey:@"fileSize"];
        PHContentEditingInputRequestOptions *const editOptions = [PHContentEditingInputRequestOptions new];
        // Download asset if on icloud.
        editOptions.networkAccessAllowed = YES;

        [asset requestContentEditingInputWithOptions:editOptions completionHandler:^(PHContentEditingInput *contentEditingInput, NSDictionary *info) {
          if (contentEditingInput.mediaType == PHAssetMediaTypeImage) {
              imageURL = contentEditingInput.fullSizeImageURL;
          } else {
              AVURLAsset *avURLAsset = (AVURLAsset*)contentEditingInput.audiovisualAsset;
              imageURL = [avURLAsset URL];
          }

          if (imageURL.absoluteString.length != 0) {

            filePath = [imageURL.absoluteString stringByReplacingOccurrencesOfString:@"pathfile:" withString:@"file:"];

            resolve(@{
                      @"node": @{
                          @"type": assetMediaTypeLabel,
                          @"subTypes":assetMediaSubtypesLabel,
                          @"image": @{
                              @"filepath": filePath,
                              @"filename": originalFilename,
                              @"height": @([asset pixelHeight]),
                              @"width": @([asset pixelWidth]),
                              @"isStored": @YES,
                              @"playableDuration": @([asset duration]), // fractional seconds
                              @"fileSize": fileSize
                              },
                          @"timestamp": @(asset.creationDate.timeIntervalSince1970),
                          @"modificationTimestamp": @(asset.modificationDate.timeIntervalSince1970),
                          @"location": (loc ? @{
                                                @"latitude": @(loc.coordinate.latitude),
                                                @"longitude": @(loc.coordinate.longitude),
                                                @"altitude": @(loc.altitude),
                                                @"heading": @(loc.course),
                                                @"speed": @(loc.speed), // speed in m/s
                                                } : @{})
                          }
                      });
          } else {
            NSString *errorMessage = [NSString stringWithFormat:@"Failed to load asset"
                                      " with localIdentifier %@ with no error message.", internalId];
            NSError *error = RCTErrorWithMessage(errorMessage);
            reject(@"Error while getting file path",@"Error while getting file path",error);
          }
        }];
      }

    } else {
      NSString *errorMessage = [NSString stringWithFormat:@"Failed to load asset"
                                " with localIdentifier %@ with no error message.", internalId];
      NSError *error = RCTErrorWithMessage(errorMessage);
      reject(@"No asset found",@"No asset found",error);
    }

  }, false);
}

RCT_EXPORT_METHOD(getPhotoVideoURI:(NSString *)internalId
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)
{
  checkPhotoLibraryConfig();

  requestPhotoLibraryAccess(reject, ^(bool isLimited){
    PHAsset *asset = [self assetForInternalID:internalId];

    if (asset) {
      NSString *liveVideoURI = [self isLivePhotoAsset:asset] ? [self liveVideoURIForAsset:asset] : nil;
      resolve(@{
        @"liveVideoUri": (liveVideoURI ? liveVideoURI : [NSNull null])
      });
    } else {
      NSString *errorMessage = [NSString stringWithFormat:@"Failed to load asset"
                                " with localIdentifier %@ with no error message.", internalId];
      NSError *error = RCTErrorWithMessage(errorMessage);
      reject(@"No asset found",@"No asset found",error);
    }
  }, false);
}

RCT_EXPORT_METHOD(getPhotoThumbnail:(NSString *)internalId
                  options:(NSDictionary *)options
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)
{
    checkPhotoLibraryConfig();

    BOOL const allowNetworkAccess = options[@"allowNetworkAccess"] == nil ? NO : [RCTConvert BOOL:options[@"allowNetworkAccess"]];

    NSDictionary *const targetSize = [RCTConvert NSDictionary:options[@"targetSize"]];
    CGFloat const targetHeight = targetSize[@"height"] == nil ? 400 : [RCTConvert CGFloat:targetSize[@"height"]];
    CGFloat const targetWidth = targetSize[@"width"] == nil ? 400 : [RCTConvert CGFloat:targetSize[@"width"]];

    CGFloat quality = options[@"quality"] == nil ? 1.0 : [RCTConvert CGFloat:options[@"quality"]];
    NSString *cacheDirectory = options[@"cacheDirectory"] == nil ? nil : [RCTConvert NSString:options[@"cacheDirectory"]];
    NSString *cacheKey = options[@"cacheKey"] == nil ? nil : [RCTConvert NSString:options[@"cacheKey"]];

    requestPhotoLibraryAccess(reject, ^(bool isLimited){

        PHFetchResult<PHAsset *> *fetchResult;
        PHAsset *asset;
        NSString *mediaIdentifier = internalId;

        if ([internalId rangeOfString:@"ph://"].location != NSNotFound) {
          mediaIdentifier = [internalId stringByReplacingOccurrencesOfString:@"ph://"
                                                                       withString:@""];
        }

        fetchResult = [PHAsset fetchAssetsWithLocalIdentifiers:@[mediaIdentifier] options:nil];
        if(fetchResult){
          asset = fetchResult.firstObject;//only object in the array.
        }

        if(asset){
            NSString *thumbnailCacheDirectory = [self thumbnailCacheDirectory:cacheDirectory];
            NSError *directoryError = nil;
            BOOL didCreateDirectory = [[NSFileManager defaultManager] createDirectoryAtPath:thumbnailCacheDirectory
                                                                withIntermediateDirectories:YES
                                                                                 attributes:nil
                                                                                      error:&directoryError];
            if (!didCreateDirectory) {
                if (directoryError == nil) {
                    directoryError = RCTErrorWithMessage(@"Failed to create thumbnail cache directory.");
                }
                reject(@"Error while creating thumbnail cache directory",
                       @"Error while creating thumbnail cache directory",
                       directoryError);
                return;
            }

            NSString *thumbnailFilename = [NSString stringWithFormat:@"RNCCameraRoll-thumbnail-%@.jpg",
                                           [self safeThumbnailCacheKey:cacheKey ?: [self thumbnailCacheKeyForAsset:asset]]];
            NSString *thumbnailPath = [thumbnailCacheDirectory stringByAppendingPathComponent:thumbnailFilename];
            if ([[NSFileManager defaultManager] fileExistsAtPath:thumbnailPath]) {
                resolve(@{
                    @"thumbnailUri": [[NSURL fileURLWithPath:thumbnailPath] absoluteString]
                });
                return;
            }

            PHImageRequestOptions *const requestOptions = [PHImageRequestOptions new];
            requestOptions.networkAccessAllowed = allowNetworkAccess;
            requestOptions.version = PHImageRequestOptionsVersionCurrent;
            requestOptions.deliveryMode = PHImageRequestOptionsDeliveryModeHighQualityFormat;

            CGSize const thumbnailSize = CGSizeMake(targetWidth, targetHeight);
            [[PHImageManager defaultManager] requestImageForAsset:asset
                                                       targetSize:thumbnailSize
                                                      contentMode:PHImageContentModeAspectFill
                                                          options:requestOptions
                                                    resultHandler:^(UIImage * _Nullable image,
                                                                    NSDictionary * _Nullable info) {
                NSError *const error = [info objectForKey:PHImageErrorKey];
                if (error) {
                    reject(@"Error while getting thumbnail image",@"Error while getting thumbnail image",error);
                    return;
                }

                if (image == nil) {
                    NSError *imageError = RCTErrorWithMessage(@"Failed to load thumbnail image.");
                    reject(@"No thumbnail image",@"No thumbnail image",imageError);
                    return;
                }

                NSData *thumbnailData = UIImageJPEGRepresentation(image, quality);
                if (thumbnailData == nil) {
                    NSError *encodingError = RCTErrorWithMessage(@"Failed to encode thumbnail image.");
                    reject(@"Error while encoding thumbnail image",@"Error while encoding thumbnail image",encodingError);
                    return;
                }

                NSError *writeError = nil;
                BOOL didWrite = [thumbnailData writeToFile:thumbnailPath options:NSDataWritingAtomic error:&writeError];
                if (!didWrite) {
                    if (writeError == nil) {
                        writeError = RCTErrorWithMessage(@"Failed to save thumbnail image.");
                    }
                    reject(@"Error while saving thumbnail image",@"Error while saving thumbnail image",writeError);
                    return;
                }

                resolve(@{
                    @"thumbnailUri": [[NSURL fileURLWithPath:thumbnailPath] absoluteString]
                });
            }];
        } else {
            NSString *errorMessage = [NSString stringWithFormat:@"Failed to load asset"
                                      " with localIdentifier %@ with no error message.", internalId];
            NSError *error = RCTErrorWithMessage(errorMessage);
            reject(@"No asset found",@"No asset found",error);
        }
    }, false);
}

NSString *subTypeLabelForCollection(PHAssetCollection *assetCollection) {
    PHAssetCollectionSubtype subtype = assetCollection.assetCollectionSubtype;

    switch (subtype) {
        case PHAssetCollectionSubtypeAlbumRegular:
            return @"AlbumRegular";
        case PHAssetCollectionSubtypeAlbumSyncedEvent:
            return @"AlbumSyncedEvent";
        case PHAssetCollectionSubtypeAlbumSyncedFaces:
          return @"AlbumSyncedFaces";
      case PHAssetCollectionSubtypeAlbumSyncedAlbum:
          return @"AlbumSyncedAlbum";
      case PHAssetCollectionSubtypeAlbumImported:
          return @"AlbumImported";
      case PHAssetCollectionSubtypeAlbumMyPhotoStream:
          return @"AlbumMyPhotoStream";
      case PHAssetCollectionSubtypeAlbumCloudShared:
          return @"AlbumCloudShared";
      default:
          return @"Unknown";
  }
}

- (PHAsset *)assetForInternalID:(NSString *)internalId {
    NSString *mediaIdentifier = internalId;

    if ([internalId rangeOfString:@"ph://"].location != NSNotFound) {
        mediaIdentifier = [internalId stringByReplacingOccurrencesOfString:@"ph://"
                                                                withString:@""];
    }

    PHFetchResult<PHAsset *> *fetchResult =
      [PHAsset fetchAssetsWithLocalIdentifiers:@[mediaIdentifier] options:nil];
    return fetchResult.firstObject;
}

- (BOOL)isLivePhotoAsset:(PHAsset *)asset {
    return asset.mediaType == PHAssetMediaTypeImage &&
      (asset.mediaSubtypes & PHAssetMediaSubtypePhotoLive) == PHAssetMediaSubtypePhotoLive;
}

// 获取相册缩略图缓存目录，优先使用业务传入的缓存目录，设置页清除缓存时会释放。
- (NSString *)thumbnailCacheDirectory:(NSString *)cacheDirectory {
    if (cacheDirectory.length > 0) {
        return cacheDirectory;
    }

    NSArray<NSURL *> *cacheURLs = [[NSFileManager defaultManager] URLsForDirectory:NSCachesDirectory
                                                                         inDomains:NSUserDomainMask];
    NSString *cachePath = cacheURLs.firstObject.path ?: NSTemporaryDirectory();
    return [cachePath stringByAppendingPathComponent:@"RNCCameraRollThumbnails"];
}

// 生成相册缩略图缓存 key，修改时间变化时视为新版本。
- (NSString *)thumbnailCacheKeyForAsset:(PHAsset *)asset {
    return [NSString stringWithFormat:@"%@_%.0f",
            asset.localIdentifier,
            asset.modificationDate.timeIntervalSince1970];
}

// 转换文件名中的特殊字符，避免 asset identifier 中的分隔符影响路径。
- (NSString *)safeThumbnailCacheKey:(NSString *)cacheKey {
    NSCharacterSet *allowedCharacterSet = [NSCharacterSet characterSetWithCharactersInString:@"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_."];
    NSMutableString *safeKey = [NSMutableString string];
    for (NSUInteger index = 0; index < cacheKey.length; index++) {
        unichar character = [cacheKey characterAtIndex:index];
        if ([allowedCharacterSet characterIsMember:character]) {
            [safeKey appendFormat:@"%C", character];
        } else {
            [safeKey appendString:@"_"];
        }
    }
    return safeKey.length > 0 ? safeKey : [NSUUID UUID].UUIDString;
}

- (NSString *)temporaryPathForAssetIdentifier:(NSString *)identifier
                             originalFilename:(NSString *)originalFilename {
    NSString *safeIdentifier = [[identifier stringByReplacingOccurrencesOfString:@"/" withString:@"_"]
                                stringByReplacingOccurrencesOfString:@":" withString:@"_"];
    NSString *filename = originalFilename.length > 0
      ? [NSString stringWithFormat:@"%@-%@", safeIdentifier, originalFilename]
      : [NSString stringWithFormat:@"%@.mov", safeIdentifier];
    return [NSTemporaryDirectory() stringByAppendingPathComponent:filename];
}

- (NSString *)liveVideoURIForAsset:(PHAsset *)asset {
    if (![self isLivePhotoAsset:asset]) {
        return nil;
    }

    PHAssetResource *pairedVideoResource = nil;
    for (PHAssetResource *resource in [PHAssetResource assetResourcesForAsset:asset]) {
        if (resource.type == PHAssetResourceTypePairedVideo) {
            pairedVideoResource = resource;
            break;
        }
    }

    if (pairedVideoResource == nil) {
        return nil;
    }

    NSString *outputPath = [self temporaryPathForAssetIdentifier:asset.localIdentifier
                                                originalFilename:pairedVideoResource.originalFilename];
    NSURL *outputURL = [NSURL fileURLWithPath:outputPath];
    [[NSFileManager defaultManager] removeItemAtURL:outputURL error:nil];

    PHAssetResourceRequestOptions *requestOptions = [PHAssetResourceRequestOptions new];
    requestOptions.networkAccessAllowed = YES;

    dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);
    __block NSError *writeError = nil;
    [[PHAssetResourceManager defaultManager] writeDataForAssetResource:pairedVideoResource
                                                                toFile:outputURL
                                                               options:requestOptions
                                                     completionHandler:^(NSError * _Nullable error) {
        writeError = error;
        dispatch_semaphore_signal(semaphore);
    }];
    dispatch_semaphore_wait(semaphore, DISPATCH_TIME_FOREVER);

    if (writeError) {
        RCTLogWarn(@"Failed to export Live Photo paired video for asset %@: %@",
                   asset.localIdentifier,
                   writeError.localizedDescription);
        return nil;
    }

    return outputURL.absoluteString;
}

- (NSArray<NSString *> *) mediaSubTypeLabelsForAsset:(PHAsset *)asset {
    PHAssetMediaSubtype subtype = asset.mediaSubtypes;
    NSMutableArray<NSString*> *mediaSubTypeLabels = [NSMutableArray array];

    if (subtype & PHAssetMediaSubtypePhotoPanorama) {
        [mediaSubTypeLabels addObject:@"PhotoPanorama"];
    }
    if (subtype & PHAssetMediaSubtypePhotoHDR) {
        [mediaSubTypeLabels addObject:@"PhotoHDR"];
    }
    if (subtype & PHAssetMediaSubtypePhotoScreenshot) {
        [mediaSubTypeLabels addObject:@"PhotoScreenshot"];
    }
    if (subtype & PHAssetMediaSubtypePhotoLive) {
        [mediaSubTypeLabels addObject:@"PhotoLive"];
    }
    if (subtype & PHAssetMediaSubtypePhotoDepthEffect) {
        [mediaSubTypeLabels addObject:@"PhotoDepthEffect"];
    }
    if (subtype & PHAssetMediaSubtypeVideoStreamed) {
        [mediaSubTypeLabels addObject:@"VideoStreamed"];
    }
    if (subtype & PHAssetMediaSubtypeVideoHighFrameRate) {
        [mediaSubTypeLabels addObject:@"VideoHighFrameRate"];
    }
    if (subtype & PHAssetMediaSubtypeVideoTimelapse) {
        [mediaSubTypeLabels addObject:@"VideoTimelapse"];
    }

    return mediaSubTypeLabels;
}

- (NSArray<NSString *> *) getAlbumsForAsset:(PHAsset *)asset {
    NSMutableArray<NSString *> *albumTitles = [NSMutableArray array];

    PHFetchResult<PHAssetCollection *> *collections = [PHAssetCollection fetchAssetCollectionsContainingAsset:asset withType:PHAssetCollectionTypeAlbum options:nil];

    for (PHAssetCollection *collection in collections) {
        [albumTitles addObject:collection.localizedTitle];
    }

    return [albumTitles copy];
}

static void checkPhotoLibraryConfig()
{
#if RCT_DEV
  if (![[NSBundle mainBundle] objectForInfoDictionaryKey:@"NSPhotoLibraryUsageDescription"]) {
    RCTLogError(@"NSPhotoLibraryUsageDescription key must be present in Info.plist to use camera roll.");
  }
#endif
}

#if RCT_NEW_ARCH_ENABLED
- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
  return std::make_shared<facebook::react::NativeCameraRollModuleSpecJSI>(params);
}
#endif

@end
