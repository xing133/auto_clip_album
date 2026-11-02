# Offline segmentation models

Bundled official Google models; switching is local, with no image upload or runtime download.
Documentation: https://ai.google.dev/edge/mediapipe/solutions/vision/image_segmenter

| Model | Asset | Official model version | SHA-256 |
| --- | --- | --- | --- |
| DeepLab | deeplab_v3.tflite | image_segmenter/deeplab_v3/float32/1 | ff36e24d40547fe9e645e2f4e8745d1876d6e38b332d39a82f0bf0f5d1d561b3 |
| Selfie | selfie_segmenter.tflite | image_segmenter/selfie_segmenter/float16/1 | 191ac9529ae506ee0beefa6b2c945a172dab9d07d1e802a290a4e4038226658b |
| Multiclass | selfie_multiclass_256x256.tflite | image_segmenter/selfie_multiclass_256x256/float32/1 | c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0 |

Download URLs are https://storage.googleapis.com/mediapipe-models/{version}/{asset}.

- DeepLab preserves its semantic categories (background plus 20 VOC object classes).
- Selfie thresholds the single person-confidence channel at 0.5. It does not use argmax on that single channel.
- Multiclass merges hair, skin, face, clothing and accessories (classes 1-5) into a person foreground before connected-region extraction. Default for a new setting.
- Disconnected foreground regions remain separately selectable. Touching people may merge. Tiny mask speckles are filtered.
- The arrow menu remembers the model selection. Switching while masks are displayed reruns inference on the current viewport and clears the previous masks.
- All use MediaPipe Tasks Vision 0.10.9 CPU. Each task owns a copy of the bitmap because closing an MPImage recycles its wrapped bitmap in that SDK version.
- Mask bounds are mapped back to preview coordinates; the save path converts them to original image coordinates.

Actual same-image Android comparison artifacts, if available locally: build/reports/segmentation-comparison/android/.

## Point-selected mosaic

The separate bystander icon uses `magic_touch.tflite` (6,227,884 bytes), official
`interactive_segmenter/magic_touch/float32/1`, SHA-256
`e24338a717c1b7ad8d159666677ef400babb7f33b8ad60c4d96db4ecf694cd25`.
Source: https://storage.googleapis.com/mediapipe-models/interactive_segmenter/magic_touch/float32/1/magic_touch.tflite

- Tap the visible part of a person; subsequent taps add to the selection. Add/erase brushes and undo allow manual refinement.
- A compact icon toolbar replaces normal editor actions. Tool choices are in a popup; brush size and mosaic strength are in a dialog. Two fingers keep zoom/pan available.
- Masks are stored in full preview-image coordinates and use the same viewport transform as the photo. Saving maps pixel centers to the full-resolution, EXIF-oriented image.
- MediaPipe Tasks Vision 0.10.9's interactive Java converter omits `Point.normalized=true`: the keypoint must contain **input-image pixel coordinates**, despite its `NormalizedKeypoint` type. Recheck this when upgrading MediaPipe. The Android result has one foreground confidence mask; threshold at 0.5.
- The compact toolbar has separate direct actions for overwrite (fifth icon) and save copy (sixth icon), with cancel seventh. Its width adapts to the screen. Overwrite keeps the same URI/name/resolution and refreshes the editor; Android write permission is requested when necessary. Denial retains the mask and discards the pending temporary file.
- Mosaic block size is saved immediately to app settings and restored when the editor opens, including after process restart.
- JPEG/PNG/WebP overwrite uses the actual source encoding (JPEG quality 100; lossless PNG/WebP where supported), rather than writing PNG data under another extension. Other formats offer the PNG copy path. JPEG recompression can affect pixels outside the mask.
- Save copy creates a new full-resolution PNG in `Pictures/AutoCropAlbum`; unselected decoded pixels stay unchanged and the source is unchanged. A selected foreground is not guaranteed to correspond to exactly one person, so inspect the green mask and mosaic preview before saving.
