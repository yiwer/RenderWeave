import { Check, Circle } from 'lucide-react';

const steps = [
  { title: '准备输入', description: '选择模式、文件与执行边界' },
  { title: '受控识别', description: '归一化、推断与确定性校验' },
  { title: '逐项校对', description: '处理证据、类型、约束与引用' },
  { title: '原子创建', description: '全部门通过后创建 Draft' },
] as const;

export function InferenceFlowSteps({ current }: { current: 1 | 2 | 3 | 4 }) {
  return (
    <nav className="inference-flow-steps" aria-label="数据结构识别进度">
      <ol>
        {steps.map((step, index) => {
          const number = index + 1;
          const completed = number < current;
          const active = number === current;
          return (
            <li key={step.title} className={`${completed ? 'completed' : ''} ${active ? 'active' : ''}`} aria-current={active ? 'step' : undefined}>
              <span className="inference-step-number">{completed ? <Check aria-hidden="true" size={15} /> : active ? <Circle aria-hidden="true" size={12} fill="currentColor" /> : number}</span>
              <span><strong>{step.title}</strong><small>{step.description}</small></span>
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
