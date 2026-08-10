import { Activity, FileCheck2, History, Plus } from 'lucide-react';
import { NavLink } from 'react-router-dom';

type ModuleItem = {
  label: string;
  description: string;
  icon: typeof History;
  to?: string;
  end?: boolean;
};

export function InferenceModuleNav({
  runId,
  resultAvailable = false,
}: {
  runId?: string;
  resultAvailable?: boolean;
}) {
  const items: ModuleItem[] = [
    { label: '历史任务', description: '查找与恢复识别记录', icon: History, to: '/inference', end: true },
    { label: '新增识别', description: '准备输入与执行边界', icon: Plus, to: '/inference/new', end: true },
    {
      label: '识别监控',
      description: runId ? '查看阶段、调用与费用' : '选择任务后查看',
      icon: Activity,
      to: runId ? `/inference-runs/${runId}/monitor` : undefined,
      end: true,
    },
    {
      label: '识别结果',
      description: resultAvailable ? '查看并校对 Candidate' : 'Candidate 生成后开放',
      icon: FileCheck2,
      to: runId && resultAvailable ? `/inference-runs/${runId}/review` : undefined,
      end: true,
    },
  ];

  return (
    <nav className="inference-module-nav" aria-label="智能识别版面">
      {items.map((item) => {
        const Icon = item.icon;
        const content = (
          <>
            <span className="inference-module-icon"><Icon aria-hidden="true" size={17} /></span>
            <span><strong>{item.label}</strong><small>{item.description}</small></span>
          </>
        );
        return item.to
          ? <NavLink key={item.label} to={item.to} end={item.end} className={({ isActive }) => isActive ? 'active' : ''}>{content}</NavLink>
          : <span key={item.label} className="disabled" aria-disabled="true">{content}</span>;
      })}
    </nav>
  );
}
