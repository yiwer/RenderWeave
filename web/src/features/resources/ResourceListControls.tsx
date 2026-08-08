import {
  ChevronLeft,
  ChevronRight,
  ChevronsLeft,
  ChevronsRight,
  Search,
  X,
} from 'lucide-react';

export interface ResourceSortOption<T extends string> {
  value: T;
  label: string;
}

export function ResourceSearchInput({
  id,
  value,
  label,
  placeholder,
  onChange,
}: {
  id: string;
  value: string;
  label: string;
  placeholder: string;
  onChange: (value: string) => void;
}) {
  return (
    <div className="resource-search">
      <Search aria-hidden="true" size={16} />
      <label className="sr-only" htmlFor={id}>{label}</label>
      <input
        id={id}
        value={value}
        type="search"
        maxLength={128}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
      />
      {value && (
        <button type="button" className="resource-search-clear" aria-label="清除搜索" onClick={() => onChange('')}>
          <X aria-hidden="true" size={14} />
        </button>
      )}
    </div>
  );
}

export function ResourceSortSelect<T extends string>({
  value,
  options,
  onChange,
}: {
  value: T;
  options: Array<ResourceSortOption<T>>;
  onChange: (value: T) => void;
}) {
  return (
    <label className="resource-sort-control">
      <span>排序</span>
      <select value={value} onChange={(event) => onChange(event.target.value as T)}>
        {options.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
      </select>
    </label>
  );
}

export function ResourceOriginSwitch({
  systemOnly,
  onChange,
}: {
  systemOnly: boolean;
  onChange: (systemOnly: boolean) => void;
}) {
  return (
    <button
      type="button"
      className="resource-origin-switch"
      role="switch"
      aria-checked={systemOnly}
      aria-label="只显示系统预设"
      onClick={() => onChange(!systemOnly)}
    >
      <span className="resource-switch-track" aria-hidden="true"><i /></span>
      <span>{systemOnly ? '仅系统预设' : '用户发布资产'}</span>
    </button>
  );
}

export function ResourcePagination({
  label,
  page,
  size,
  total,
  onPageChange,
  onSizeChange,
}: {
  label: string;
  page: number;
  size: number;
  total: number;
  onPageChange: (page: number) => void;
  onSizeChange: (size: number) => void;
}) {
  if (total === 0) return null;
  const totalPages = Math.max(1, Math.ceil(total / size));
  return (
    <nav className="resource-pagination" aria-label={`${label}分页`}>
      <div className="resource-page-size">
        <span>每页</span>
        <select aria-label="每页数量" value={size} onChange={(event) => onSizeChange(Number(event.target.value))}>
          <option value={9}>9</option>
          <option value={18}>18</option>
          <option value={36}>36</option>
        </select>
        <span>项</span>
      </div>
      <span className="resource-page-status">共 {total} 项 · 第 {page} / {totalPages} 页</span>
      <div className="resource-page-actions">
        <button type="button" aria-label="第一页" disabled={page === 1} onClick={() => onPageChange(1)}><ChevronsLeft aria-hidden="true" size={15} /></button>
        <button type="button" aria-label="上一页" disabled={page === 1} onClick={() => onPageChange(page - 1)}><ChevronLeft aria-hidden="true" size={15} /></button>
        <button type="button" aria-label="下一页" disabled={page === totalPages} onClick={() => onPageChange(page + 1)}><ChevronRight aria-hidden="true" size={15} /></button>
        <button type="button" aria-label="最后一页" disabled={page === totalPages} onClick={() => onPageChange(totalPages)}><ChevronsRight aria-hidden="true" size={15} /></button>
      </div>
    </nav>
  );
}
