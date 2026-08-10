import * as Dialog from '@radix-ui/react-dialog';
import { XCircle } from 'lucide-react';

export function RunCancelButton({ pending, onCancel }: { pending: boolean; onCancel: () => void }) {
  return (
    <Dialog.Root>
      <Dialog.Trigger asChild>
        <button type="button" className="button ghost-button inference-cancel-trigger" disabled={pending}><XCircle aria-hidden="true" size={15} />{pending ? '正在取消…' : '取消任务'}</button>
      </Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="dialog-overlay" />
        <Dialog.Content className="dialog-content inference-cancel-dialog" aria-describedby="inference-cancel-description">
          <Dialog.Title>取消这次识别任务？</Dialog.Title>
          <Dialog.Description id="inference-cancel-description">已发生的模型费用仍会计入预算；任务取消后不能继续，只能显式创建一个可审计的新重试任务。</Dialog.Description>
          <div className="dialog-actions">
            <Dialog.Close asChild><button type="button" className="button ghost-button" disabled={pending}>继续当前任务</button></Dialog.Close>
            <Dialog.Close asChild><button type="button" className="button danger-button" disabled={pending} onClick={onCancel}>确认取消</button></Dialog.Close>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
