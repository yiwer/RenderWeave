import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';

const SchemaStudioPage = lazy(async () => ({
  default: (await import('../features/schema-studio/SchemaStudioPage')).SchemaStudioPage,
}));
const SchemaStudioPrototype = lazy(async () => ({
  default: (await import('../prototype/schema-studio/SchemaStudioPrototype')).SchemaStudioPrototype,
}));
const DraftListPage = lazy(async () => ({
  default: (await import('../features/resources/DraftListPage')).DraftListPage,
}));
const StaticSchemaListPage = lazy(async () => ({
  default: (await import('../features/resources/StaticSchemaPages')).StaticSchemaListPage,
}));
const StaticSchemaDetailPage = lazy(async () => ({
  default: (await import('../features/resources/StaticSchemaPages')).StaticSchemaDetailPage,
}));
const RootDocumentValidatorPage = lazy(async () => ({
  default: (await import('../features/resources/RootDocumentValidatorPage')).RootDocumentValidatorPage,
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
        <Route path="/prototype/schema-studio" element={<SchemaStudioPrototype />} />
        <Route path="*" element={<Navigate replace to="/schemas" />} />
      </Routes>
    </Suspense>
  );
}
