export const IMAGE_UPLOAD_LIMIT_BYTES = 10 * 1024 * 1024;
export const VIDEO_UPLOAD_LIMIT_BYTES = 100 * 1024 * 1024;
export const IMAGE_UPLOAD_LIMIT_LABEL = '10 MB';
export const VIDEO_UPLOAD_LIMIT_LABEL = '100 MB';

const IMAGE_TARGET_BYTES = 8 * 1024 * 1024;
const MAX_SOURCE_IMAGE_BYTES = 50 * 1024 * 1024;
const MAX_OUTPUT_PIXELS = 12_000_000;
const MAX_OUTPUT_EDGE = 3_840;
const OUTPUT_QUALITIES = [0.84, 0.76, 0.68, 0.6];
const preparedFiles = new WeakSet<File>();

export class MediaValidationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'MediaValidationError';
  }
}

const formatMegabytes = (bytes: number): string => `${(bytes / 1024 / 1024).toFixed(1)} MB`;

const loadImage = (file: File): Promise<HTMLImageElement> => new Promise((resolve, reject) => {
  const objectUrl = URL.createObjectURL(file);
  const image = new Image();
  image.onload = () => {
    URL.revokeObjectURL(objectUrl);
    resolve(image);
  };
  image.onerror = () => {
    URL.revokeObjectURL(objectUrl);
    reject(new MediaValidationError(
      `${file.name} could not be prepared. Choose a JPG, PNG, WebP, or another image format supported by this browser.`
    ));
  };
  image.src = objectUrl;
});

const canvasToWebP = (canvas: HTMLCanvasElement, quality: number): Promise<Blob> =>
  new Promise((resolve, reject) => {
    canvas.toBlob((blob) => {
      if (!blob) {
        reject(new MediaValidationError('This image could not be compressed. Choose another image and try again.'));
        return;
      }
      resolve(blob);
    }, 'image/webp', quality);
  });

const getOutputDimensions = (width: number, height: number): { width: number; height: number } => {
  const edgeScale = Math.min(1, MAX_OUTPUT_EDGE / Math.max(width, height));
  const pixelScale = Math.min(1, Math.sqrt(MAX_OUTPUT_PIXELS / (width * height)));
  const scale = Math.min(edgeScale, pixelScale);
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale))
  };
};

/** Compresses every selected image to a bounded WebP before upload. */
export async function compressImageToWebP(file: File): Promise<File> {
  if (!file.type.startsWith('image/')) {
    throw new MediaValidationError(`${file.name} is not a supported image.`);
  }
  if (file.size > MAX_SOURCE_IMAGE_BYTES) {
    throw new MediaValidationError(
      `${file.name} is ${formatMegabytes(file.size)}. Choose an image below 50 MB so it can be compressed safely.`
    );
  }

  const image = await loadImage(file);
  const dimensions = getOutputDimensions(image.naturalWidth, image.naturalHeight);
  const canvas = document.createElement('canvas');
  canvas.width = dimensions.width;
  canvas.height = dimensions.height;

  const context = canvas.getContext('2d');
  if (!context) {
    throw new MediaValidationError('Image compression is unavailable in this browser. Try an updated browser.');
  }

  context.imageSmoothingEnabled = true;
  context.imageSmoothingQuality = 'high';
  context.drawImage(image, 0, 0, dimensions.width, dimensions.height);

  let compressedBlob: Blob | null = null;
  for (const quality of OUTPUT_QUALITIES) {
    compressedBlob = await canvasToWebP(canvas, quality);
    if (compressedBlob.size <= IMAGE_TARGET_BYTES) break;
  }

  if (!compressedBlob || compressedBlob.size > IMAGE_UPLOAD_LIMIT_BYTES) {
    throw new MediaValidationError(
      `${file.name} could not be reduced below ${IMAGE_UPLOAD_LIMIT_LABEL}. Choose a smaller image.`
    );
  }

  const fileNameWithoutExtension = file.name.includes('.')
    ? file.name.slice(0, file.name.lastIndexOf('.'))
    : file.name;
  const compressedFile = new File([compressedBlob], `${fileNameWithoutExtension}.webp`, {
    type: 'image/webp',
    lastModified: file.lastModified
  });
  if ((file as any).draftMediaId) {
    (compressedFile as any).draftMediaId = (file as any).draftMediaId;
  }
  preparedFiles.add(compressedFile);
  return compressedFile;
}

export async function prepareMediaForUpload(file: File): Promise<File> {
  if (preparedFiles.has(file)) return file;

  if (file.type.startsWith('image/')) {
    return compressImageToWebP(file);
  }
  if (file.type.startsWith('video/')) {
    if (file.size > VIDEO_UPLOAD_LIMIT_BYTES) {
      throw new MediaValidationError(
        `${file.name} is ${formatMegabytes(file.size)}. Walkthrough videos must be ${VIDEO_UPLOAD_LIMIT_LABEL} or smaller.`
      );
    }
    preparedFiles.add(file);
    return file;
  }

  throw new MediaValidationError(`${file.name} is not a supported image or video.`);
}

export const describeMediaLimits = (): string =>
  `Images are compressed automatically and must be ${IMAGE_UPLOAD_LIMIT_LABEL} or smaller after compression. Videos must be ${VIDEO_UPLOAD_LIMIT_LABEL} or smaller.`;
