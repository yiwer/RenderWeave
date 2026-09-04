import { Suspense } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';

import { lazyRoute } from './lazy-route';

const SchemaStudioPage = lazyRoute(async () => ({
  default: (await import('../features/schema-studio/SchemaStudioPage')).SchemaStudioPage,
}));
const SchemaStudioPrototype = lazyRoute(async () => ({
  default: (await import('../prototype/schema-studio/SchemaStudioPrototype')).SchemaStudioPrototype,
}));
const DraftListPage = lazyRoute(async () => ({
  default: (await import('../features/resources/DraftListPage')).DraftListPage,
}));
const StaticSchemaListPage = lazyRoute(async () => ({
  default: (await import('../features/resources/StaticSchemaPages')).StaticSchemaListPage,
}));
const StaticSchemaDetailPage = lazyRoute(async () => ({
  default: (await import('../features/resources/StaticSchemaPages')).StaticSchemaDetailPage,
}));
const RootDocumentValidatorPage = lazyRoute(async () => ({
  default: (await import('../features/resources/RootDocumentValidatorPage')).RootDocumentValidatorPage,
}));
const InferenceStartPage = lazyRoute(async () => ({
  default: (await import('../features/inference/InferenceStartPage')).InferenceStartPage,
}));
const InferenceHistoryPage = lazyRoute(async () => ({
  default: (await import('../features/inference/InferenceHistoryPage')).InferenceHistoryPage,
}));
const InferenceReplayPage = lazyRoute(async () => ({
  default: (await import('../features/inference/InferenceReplayPage')).InferenceReplayPage,
}));
const InferenceMonitorPage = lazyRoute(async () => ({
  default: (await import('../features/inference/InferenceMonitorPage')).InferenceMonitorPage,
}));
const CandidateReviewPage = lazyRoute(async () => ({
  default: (await import('../features/inference/CandidateReviewPage')).CandidateReviewPage,
}));
const TemplateListPage = lazyRoute(async () => ({
  default: (await import('../features/templates/TemplateProductPages')).TemplateListPage,
}));
const TemplateCreatePage = lazyRoute(async () => ({
  default: (await import('../features/templates/TemplateProductPages')).TemplateCreatePage,
}));
const TemplateEditorPage = lazyRoute(async () => ({
  default: (await import('../features/templates/TemplateProductPages')).TemplateEditorPage,
}));

export function App() {
  return (
    <Suspense fallback={<div className="route-loading" role="status">正在打开工作台…</div>}>
      <Routes>
        <Route path="/schemas" element={<DraftListPage />} />
        <Route path="/schemas/new" element={<SchemaStudioPage />} />
        <Route path="/schemas/:schemaKey" element={<SchemaStudioPage />} />
        <Route path="/static-schemas" element={<StaticSchemaListPage />} />
        <Route path="/static-schemas/:schemaKey/:versionTag" element={<StaticSchemaDetailPage />} />
        <Route path="/validator" element={<RootDocumentValidatorPage />} />
        <Route path="/inference" element={<InferenceHistoryPage />} />
        <Route path="/inference/new" element={<InferenceStartPage />} />
        <Route path="/inference/samples" element={<InferenceReplayPage />} />
        <Route path="/inference-runs/:runId/monitor" element={<InferenceMonitorPage />} />
        <Route path="/inference-runs/:runId/review" element={<CandidateReviewPage />} />
        <Route path="/templates" element={<TemplateListPage />} />
        <Route path="/templates/new" element={<TemplateCreatePage />} />
        <Route path="/templates/:templateId" element={<TemplateEditorPage />} />
        <Route path="/prototype/schema-studio" element={<SchemaStudioPrototype />} />
        <Route path="*" element={<Navigate replace to="/schemas" />} />
      </Routes>
    </Suspense>
  );
}
