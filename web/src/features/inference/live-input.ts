export type LiveFileKind = 'IMAGE' | 'JSON';

export interface LiveFileIssue {
  code: 'COUNT_EXCEEDED' | 'FILE_TOO_LARGE' | 'TOTAL_TOO_LARGE' | 'TYPE_UNSUPPORTED';
  fileIndex: number | null;
  message: string;
}

const MIB = 1024 * 1024;

const policies = {
  IMAGE: { maximumCount: 10, maximumFileBytes: 10 * MIB, maximumTotalBytes: 30 * MIB },
  JSON: { maximumCount: 20, maximumFileBytes: 256 * 1024, maximumTotalBytes: 2 * MIB },
} as const;

export function validateLiveFiles(kind: LiveFileKind, files: File[]): LiveFileIssue[] {
  const policy = policies[kind];
  const issues: LiveFileIssue[] = [];
  if (files.length > policy.maximumCount) {
    issues.push({
      code: 'COUNT_EXCEEDED', fileIndex: null,
      message: `${kind === 'IMAGE' ? '设计图' : 'JSON 样本'}最多 ${policy.maximumCount} 份，当前 ${files.length} 份。`,
    });
  }
  files.forEach((file, fileIndex) => {
    if (!supported(kind, file)) {
      issues.push({
        code: 'TYPE_UNSUPPORTED', fileIndex,
        message: kind === 'IMAGE' ? '仅支持 PNG 或 JPEG。' : '仅支持 .json 文件。',
      });
    }
    if (file.size > policy.maximumFileBytes) {
      issues.push({
        code: 'FILE_TOO_LARGE', fileIndex,
        message: kind === 'IMAGE' ? '单张图片不能超过 10 MiB。' : '单份 JSON 不能超过 256 KiB。',
      });
    }
  });
  const totalBytes = files.reduce((total, file) => total + file.size, 0);
  if (totalBytes > policy.maximumTotalBytes) {
    issues.push({
      code: 'TOTAL_TOO_LARGE', fileIndex: null,
      message: kind === 'IMAGE' ? '图片总大小不能超过 30 MiB。' : 'JSON 总大小不能超过 2 MiB。',
    });
  }
  return issues;
}

export function mergeLiveFiles(existing: File[], incoming: File[]): File[] {
  const result = [...existing];
  const identities = new Set(existing.map(fileIdentity));
  incoming.forEach((file) => {
    const identity = fileIdentity(file);
    if (identities.has(identity)) return;
    identities.add(identity);
    result.push(file);
  });
  return result;
}

export function formatFileSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < MIB) return `${(bytes / 1024).toFixed(bytes < 10 * 1024 ? 1 : 0)} KiB`;
  return `${(bytes / MIB).toFixed(bytes < 10 * MIB ? 1 : 0)} MiB`;
}

export function filesForLiveMode<T>(mode: 'IMAGE_ONLY' | 'JSON_ONLY' | 'COMBINED', images: T[], jsonSamples: T[]) {
  return {
    images: mode === 'JSON_ONLY' ? [] : images,
    jsonSamples: mode === 'IMAGE_ONLY' ? [] : jsonSamples,
  };
}

function supported(kind: LiveFileKind, file: File) {
  const name = file.name.toLocaleLowerCase('en-US');
  const mime = file.type.toLocaleLowerCase('en-US');
  if (kind === 'IMAGE') {
    return (name.endsWith('.png') || name.endsWith('.jpg') || name.endsWith('.jpeg'))
      && (mime === 'image/png' || mime === 'image/jpeg');
  }
  return name.endsWith('.json') && (!mime || mime === 'application/json');
}

function fileIdentity(file: File) {
  return `${file.name}\u0000${file.size}\u0000${file.lastModified}\u0000${file.type}`;
}
